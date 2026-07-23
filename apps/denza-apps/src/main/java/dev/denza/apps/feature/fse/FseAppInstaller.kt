package dev.denza.apps.feature.fse

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Base64
import dev.denza.disharebridge.LocalAdbClient
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

data class FseInstallApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val versionName: String,
    val apkSizeBytes: Long,
    val installable: Boolean,
    val unavailableReason: String = "",
)

sealed interface FseInstallResult {
    data class Installed(val app: FseInstallApp) : FseInstallResult
    data class Failed(val message: String, val details: String? = null) : FseInstallResult
}

object FseAppInstaller {
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private const val CROSS_ID_CHANGE_THEME = -13_631_467
    private const val IVI_DEVICE_ID = 1
    private const val FSE_DEVICE_ID = 2
    private const val RESPONSE_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 750L
    private const val COPY_BLOCK_BYTES = 4L * 1024L * 1024L
    private const val COPY_READ_TIMEOUT_MS = 30_000

    fun installedApps(context: Context): List<FseInstallApp> {
        val manager = context.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return manager.queryIntentActivities(launcher, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (!seen.add(packageName)) return@mapNotNull null
                val packageInfo = runCatching { manager.getPackageInfo(packageName, 0) }.getOrNull()
                    ?: return@mapNotNull null
                val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(manager).toString().ifBlank { packageName }
                if (!isPassengerAppCandidate(packageName, applicationInfo, label)) {
                    return@mapNotNull null
                }
                val source = File(applicationInfo.sourceDir.orEmpty())
                val splitCount = applicationInfo.splitSourceDirs?.size ?: 0
                val reason = when {
                    splitCount > 0 -> "Split APK пока не поддерживается"
                    applicationInfo.sourceDir.isNullOrBlank() -> "APK не найден"
                    !source.isFile -> "APK недоступен"
                    else -> ""
                }
                FseInstallApp(
                    packageName = packageName,
                    label = label,
                    icon = runCatching { resolveInfo.loadIcon(manager) }.getOrNull(),
                    versionName = packageInfo.versionName.orEmpty(),
                    apkSizeBytes = source.length(),
                    installable = reason.isEmpty(),
                    unavailableReason = reason,
                )
            }
            .sortedWith(
                compareByDescending<FseInstallApp> { it.installable }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
    }

    fun install(
        context: Context,
        packageName: String,
        onProgress: (String) -> Unit,
    ): FseInstallResult {
        val app = installedApps(context).firstOrNull { it.packageName == packageName }
            ?: return FseInstallResult.Failed("Приложение больше не найдено")
        if (!app.installable) {
            return FseInstallResult.Failed(app.unavailableReason.ifBlank { "APK недоступен" })
        }

        val manager = context.packageManager
        val packageInfo = runCatching { manager.getPackageInfo(packageName, 0) }.getOrNull()
            ?: return FseInstallResult.Failed("Не удалось прочитать приложение")
        val sourcePath = packageInfo.applicationInfo?.sourceDir
            ?: return FseInstallResult.Failed("APK не найден")
        if (!packageInfo.applicationInfo?.splitSourceDirs.isNullOrEmpty()) {
            return FseInstallResult.Failed("Split APK пока не поддерживается")
        }

        val requestId = requestId()
        val resourceName = "denza-apps-install-$requestId"
        val iviRoot = "/storage/FFFF-FFFC/$resourceName"
        val fseRoot = "/storage/emulated/0/$resourceName"
        val adb = LocalAdbClient(context, ADB_KEY_COMMENT)
        var installSent = false

        return try {
            onProgress("Проверяю пассажирский экран")
            requireFseStorage(adb)

            onProgress("Подготавливаю ${app.label}")
            val config = installConfig(packageInfo, requestId)
            val encodedConfig = Base64.encodeToString(
                config.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            adb.shell(
                "mkdir -p ${quote("$iviRoot/wallpaper")} && " +
                    "echo ${quote(encodedConfig)} | base64 -d > ${quote("$iviRoot/config.json")}",
            )

            copyApk(
                adb = adb,
                sourcePath = sourcePath,
                targetPath = "$iviRoot/wallpaper/Application.apk",
                expectedBytes = app.apkSizeBytes,
                onProgress = onProgress,
            )

            onProgress("Устанавливаю ${app.label}")
            val message = JSONObject()
                .put("fromDevice", IVI_DEVICE_ID)
                .put("toDevice", FSE_DEVICE_ID)
                .put("function", "wallpaper")
                .put("provider_method", "set_wallpaper_path")
                .put("wallpaper_path", fseRoot)
                .put("wallpaper_type", 14)
                .put("theme_id", requestId)
                .put("res_id", requestId)
                .put("wallpaper_service", "$packageName/.NoSuchWallpaperService")
                .put("app_version_name", packageInfo.versionName.orEmpty())
                .put("app_version_code", packageInfo.longVersionCode)
            sendCrossMessage(context, message.toString())
            installSent = true

            when (awaitInstallResponse(adb, requestId)) {
                true -> {
                    cleanup(adb, iviRoot)
                    FseInstallResult.Installed(app)
                }
                false -> {
                    cleanup(adb, iviRoot)
                    FseInstallResult.Failed("Пассажирский экран отклонил установку")
                }
                null -> FseInstallResult.Failed(
                    "Нет подтверждения от экрана",
                    "staged=$iviRoot; requestId=$requestId",
                )
            }
        } catch (error: Exception) {
            if (!installSent) cleanup(adb, iviRoot)
            FseInstallResult.Failed(friendlyError(error), error.toString())
        }
    }

    private fun requireFseStorage(adb: LocalAdbClient) {
        val result = adb.shell(
            "if [ -d /storage/FFFF-FFFC ]; then echo ready; else echo missing; fi",
        ).trim()
        if (result != "ready") throw IllegalStateException("FSE storage is not mounted")
    }

    private fun copyApk(
        adb: LocalAdbClient,
        sourcePath: String,
        targetPath: String,
        expectedBytes: Long,
        onProgress: (String) -> Unit,
    ) {
        if (expectedBytes <= 0L) throw IllegalStateException("APK copy size is unknown")
        try {
            adb.shell("rm -f ${quote(targetPath)}; : > ${quote(targetPath)}")
            onProgress("Копирование: 0%")
            val blockCount = (expectedBytes + COPY_BLOCK_BYTES - 1L) / COPY_BLOCK_BYTES
            repeat(blockCount.toInt()) { block ->
                val result = adb.shell(
                    "dd if=${quote(sourcePath)} of=${quote(targetPath)} " +
                        "bs=$COPY_BLOCK_BYTES skip=$block seek=$block count=1 conv=notrunc " +
                        ">/dev/null 2>&1; echo \$?",
                    COPY_READ_TIMEOUT_MS,
                ).trim()
                if (result.lineSequence().lastOrNull() != "0") {
                    throw IllegalStateException("dd exit=$result block=$block")
                }
                val copiedBytes = minOf((block + 1L) * COPY_BLOCK_BYTES, expectedBytes)
                val percent = (copiedBytes * 100L / expectedBytes).toInt()
                onProgress("Копирование: $percent%")
            }
            val actualBytes = adb.shell("stat -c %s ${quote(targetPath)}").trim().toLongOrNull()
            if (actualBytes != expectedBytes) {
                throw IllegalStateException("size expected=$expectedBytes actual=$actualBytes")
            }
        } catch (error: Exception) {
            throw IllegalStateException("APK copy failed: ${error.message}", error)
        }
    }

    private fun awaitInstallResponse(
        adb: LocalAdbClient,
        requestId: Int,
    ): Boolean? {
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val log = adb.shell(
                "logcat -d -v raw -s Launcher.CrossUtil:I '*:S' | tail -n 120",
            )
            FseInstallResponse.result(log, requestId)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    // BYD's cross-device transport is vendor-only and has no public SDK equivalent.
    @SuppressLint("PrivateApi")
    private fun sendCrossMessage(context: Context, message: String) {
        val deviceClass = Class.forName("android.cross.device.BYDCrossDevice")
        val device = deviceClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context)
        val valueClass = Class.forName("android.cross.BYDCrossEventValue")
        val value = valueClass.getConstructor(ByteArray::class.java)
            .newInstance(message.toByteArray(StandardCharsets.UTF_8))
        val result = deviceClass.getMethod("set", IntArray::class.java, valueClass)
            .invoke(device, intArrayOf(CROSS_ID_CHANGE_THEME), value) as Number
        if (result.toInt() != 0) throw IllegalStateException("Cross-device send failed: $result")
    }

    private fun installConfig(packageInfo: PackageInfo, requestId: Int) = JSONObject()
        .put("wallpaper_type", 14)
        .put("theme_id", requestId)
        .put("wallpaper_service", "${packageInfo.packageName}/.NoSuchWallpaperService")
        .put("app_version_name", packageInfo.versionName.orEmpty())
        .put("app_version_code", packageInfo.longVersionCode)

    private fun cleanup(
        adb: LocalAdbClient,
        iviRoot: String,
    ) {
        runCatching {
            adb.shell("rm -rf ${quote(iviRoot)}")
        }
    }

    private fun isPassengerAppCandidate(
        packageName: String,
        applicationInfo: ApplicationInfo,
        label: String,
    ): Boolean {
        val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val isBydPackage = packageName.startsWith("com.byd.") ||
            packageName.startsWith("android.byd.") ||
            packageName.startsWith("com.dilink.")
        val hasChineseLabel = label.any { character ->
            Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
        }
        return !isSystemApp && !isBydPackage && !hasChineseLabel
    }

    private fun friendlyError(error: Exception): String = when {
        error.message.orEmpty().contains("APK copy", ignoreCase = true) ->
            "Не удалось скопировать APK"
        error.message.orEmpty().contains("authorization pending", ignoreCase = true) ->
            "Подтвердите ADB-ключ на экране автомобиля"
        error.message.orEmpty().contains("refused", ignoreCase = true) ->
            "ADB на машине недоступен"
        error.message.orEmpty().contains("not mounted", ignoreCase = true) ->
            "Пассажирский экран не подключен"
        error.message.orEmpty().contains("timed out", ignoreCase = true) ->
            "Пассажирский экран не ответил"
        else -> "Не удалось установить приложение"
    }

    private fun requestId(): Int =
        1_000_000_000 + ((System.currentTimeMillis() / 1_000L) % 900_000_000L).toInt()

    internal fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}

internal object FseInstallResponse {
    private val requestPattern = Regex("\"res_id\"\\s*:\\s*(-?\\d+)")
    private val resultPattern = Regex("\"result\"\\s*:\\s*([01])")

    fun result(log: String, requestId: Int): Boolean? {
        return log.lineSequence()
            .filter { "using_wallpaper_result" in it }
            .mapNotNull { line ->
                val responseId = requestPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (responseId != requestId) return@mapNotNull null
                resultPattern.find(line)?.groupValues?.get(1)?.let { it == "1" }
            }
            .lastOrNull()
    }
}
