package dev.denza.apps.feature.fse

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import dev.denza.disharebridge.LocalAdbClient
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

internal data class FseSplitFileDiagnostic(
    val declaredName: String,
    val fileName: String,
    val isFile: Boolean,
    val readable: Boolean,
    val sizeBytes: Long,
)

internal data class FseApkLayoutDiagnostic(
    val packageName: String,
    val label: String,
    val versionName: String,
    val launcherSplitName: String?,
    val baseFileName: String,
    val baseIsFile: Boolean,
    val baseReadable: Boolean,
    val baseSizeBytes: Long,
    val declaredSplitNames: List<String>,
    val splitFiles: List<FseSplitFileDiagnostic>,
)

internal object FseApkLayoutDiagnostics {
    fun render(
        candidateCount: Int,
        layouts: List<FseApkLayoutDiagnostic>,
    ): List<String> {
        val splitLayouts = layouts
            .filter { it.splitFiles.isNotEmpty() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return buildList {
            add(
                "FSE APK layouts=" +
                    "candidates=$candidateCount; " +
                    "split=${splitLayouts.size}; " +
                    "monolithic=${(candidateCount - splitLayouts.size).coerceAtLeast(0)}",
            )
            if (splitLayouts.isEmpty()) {
                add("FSE split packages=none")
                return@buildList
            }
            splitLayouts.forEach { layout ->
                add(
                    "FSE split ${layout.packageName}=" +
                        "label=${layout.label}; " +
                        "version=${layout.versionName.ifBlank { "—" }}; " +
                        "launcher=${layout.launcherSplitName ?: "base"}; " +
                        "base=${layout.baseFileName.ifBlank { "—" }}:" +
                        fileState(
                            layout.baseIsFile,
                            layout.baseReadable,
                            layout.baseSizeBytes,
                        ) + "; " +
                        "files=${layout.splitFiles.size}; " +
                        "names=${layout.declaredSplitNames.size}",
                )
                add(
                    "FSE split names ${layout.packageName}=" +
                        layout.declaredSplitNames.ifEmpty { listOf("—") }.joinToString(" | "),
                )
                add(
                    "FSE split files ${layout.packageName}=" +
                        layout.splitFiles.mapIndexed { index, file ->
                            "${index + 1}:${file.declaredName}:" +
                                "${file.fileName.ifBlank { "—" }}:" +
                                fileState(file.isFile, file.readable, file.sizeBytes)
                        }.joinToString(" | "),
                )
            }
        }
    }

    private fun fileState(isFile: Boolean, readable: Boolean, sizeBytes: Long): String = when {
        !isFile -> "missing"
        !readable -> "not-readable"
        else -> "${sizeBytes}B"
    }
}

private const val FSE_INSTALL_RESULT_SUCCESS = 1
// FSE 42.1.8.2605219.1 returned -7 after a fresh RUTUBE install became visible.
// The package is present even though the OEM wallpaper provider reports a warning.
private const val FSE_INSTALL_RESULT_PACKAGE_PRESENT_WITH_PROVIDER_WARNING = -7

object FseAppInstaller {
    private const val TAG = "DenzaApps.FseInstaller"
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private const val CROSS_ID_CHANGE_THEME = -13_631_467
    private const val IVI_DEVICE_ID = 1
    private const val FSE_DEVICE_ID = 2
    private const val RESPONSE_TIMEOUT_MS = 90_000L
    private const val COPY_BLOCK_BYTES = 4L * 1024L * 1024L
    private const val COPY_READ_TIMEOUT_MS = 30_000

    private data class InstalledPackageCandidate(
        val packageName: String,
        val label: String,
        val resolveInfo: ResolveInfo,
        val packageInfo: PackageInfo,
        val applicationInfo: ApplicationInfo,
    )

    fun installedApps(context: Context): List<FseInstallApp> {
        val manager = context.packageManager
        return installedPackageCandidates(context)
            .map { candidate ->
                val source = File(candidate.applicationInfo.sourceDir.orEmpty())
                val splitCount = candidate.applicationInfo.splitSourceDirs?.size ?: 0
                val reason = when {
                    splitCount > 0 -> "Split APK пока не поддерживается"
                    candidate.applicationInfo.sourceDir.isNullOrBlank() -> "APK не найден"
                    !source.isFile -> "APK недоступен"
                    else -> ""
                }
                FseInstallApp(
                    packageName = candidate.packageName,
                    label = candidate.label,
                    icon = runCatching { candidate.resolveInfo.loadIcon(manager) }.getOrNull(),
                    versionName = candidate.packageInfo.versionName.orEmpty(),
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

    /**
     * Passive package-layout evidence for the hidden support screen.
     *
     * This uses only PackageManager metadata and file stat calls. It never opens
     * ADB, copies an APK, or contacts the passenger screen.
     */
    fun diagnosticLines(context: Context): List<String> {
        val candidates = installedPackageCandidates(context)
        val layouts = candidates.map { candidate ->
            val base = File(candidate.applicationInfo.sourceDir.orEmpty())
            val rawDeclaredNames: Array<out String>? = candidate.packageInfo.splitNames
            val declaredNames = rawDeclaredNames.orEmpty().map { it.trim() }
            val splitFiles = candidate.applicationInfo.splitSourceDirs
                ?.mapIndexed { index, path ->
                    val file = File(path.orEmpty())
                    FseSplitFileDiagnostic(
                        declaredName = declaredNames.getOrNull(index)
                            ?.takeIf { it.isNotBlank() }
                            ?: "index-${index + 1}",
                        fileName = file.name,
                        isFile = file.isFile,
                        readable = file.canRead(),
                        sizeBytes = file.length(),
                    )
                }
                .orEmpty()
            FseApkLayoutDiagnostic(
                packageName = candidate.packageName,
                label = candidate.label,
                versionName = candidate.packageInfo.versionName.orEmpty(),
                launcherSplitName = candidate.resolveInfo.activityInfo?.splitName
                    ?.takeIf { it.isNotBlank() },
                baseFileName = base.name,
                baseIsFile = base.isFile,
                baseReadable = base.canRead(),
                baseSizeBytes = base.length(),
                declaredSplitNames = declaredNames,
                splitFiles = splitFiles,
            )
        }
        return FseApkLayoutDiagnostics.render(candidates.size, layouts)
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
        val adb = LocalAdbClient(context, ADB_KEY_COMMENT).openPersistentShell()
        var installSent = false

        return try {
            onProgress("Проверяю пассажирский экран")
            requireFseStorage(adb)
            cleanupAbandonedStages(adb)

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
            val installResult = FseCrossResponseSession.open(context, requestId).use { cross ->
                cross.send(message.toString())
                installSent = true
                cross.await(RESPONSE_TIMEOUT_MS)
            }
            Log.i(TAG, "FSE install result=$installResult requestId=$requestId")

            when (installResult) {
                FSE_INSTALL_RESULT_SUCCESS,
                FSE_INSTALL_RESULT_PACKAGE_PRESENT_WITH_PROVIDER_WARNING,
                -> {
                    cleanup(adb, iviRoot)
                    FseInstallResult.Installed(app)
                }
                null -> FseInstallResult.Failed(
                    "Нет подтверждения от экрана",
                    "staged=$iviRoot; requestId=$requestId",
                )
                else -> {
                    cleanup(adb, iviRoot)
                    FseInstallResult.Failed(
                        "Пассажирский экран отклонил установку",
                        "result=$installResult; requestId=$requestId",
                    )
                }
            }
        } catch (error: Exception) {
            if (!installSent) cleanup(adb, iviRoot)
            FseInstallResult.Failed(friendlyError(error), error.toString())
        } finally {
            adb.close()
        }
    }

    private fun requireFseStorage(adb: LocalAdbClient.PersistentShellSession) {
        val result = adb.shell(
            "if [ -d /storage/FFFF-FFFC ]; then echo ready; else echo missing; fi",
        ).trim()
        if (result != "ready") throw IllegalStateException("FSE storage is not mounted")
    }

    private fun cleanupAbandonedStages(adb: LocalAdbClient.PersistentShellSession) {
        val result = adb.shell(abandonedStageCleanupCommand()).trim()
        if (result != "cleaned") {
            throw IllegalStateException("FSE staging cleanup failed: $result")
        }
    }

    private fun copyApk(
        adb: LocalAdbClient.PersistentShellSession,
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

    private fun installConfig(packageInfo: PackageInfo, requestId: Int) = JSONObject()
        .put("wallpaper_type", 14)
        .put("theme_id", requestId)
        .put("wallpaper_service", "${packageInfo.packageName}/.NoSuchWallpaperService")
        .put("app_version_name", packageInfo.versionName.orEmpty())
        .put("app_version_code", packageInfo.longVersionCode)

    private fun cleanup(
        adb: LocalAdbClient.PersistentShellSession,
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

    private fun installedPackageCandidates(context: Context): List<InstalledPackageCandidate> {
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
                InstalledPackageCandidate(
                    packageName = packageName,
                    label = label,
                    resolveInfo = resolveInfo,
                    packageInfo = packageInfo,
                    applicationInfo = applicationInfo,
                )
            }
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

    internal fun abandonedStageCleanupCommand(): String =
        "for path in /storage/FFFF-FFFC/denza-apps-install-*; do " +
            "[ -d \"\$path\" ] || continue; " +
            "rm -rf -- \"\$path\" || exit 1; " +
            "done; echo cleaned"

    private class FseCrossResponseSession private constructor(
        private val device: Any,
        private val deviceClass: Class<*>,
        private val valueClass: Class<*>,
        private val listenerClass: Class<*>,
        private val listener: Any,
        private val waiter: FseInstallResponseWaiter,
    ) : AutoCloseable {
        fun send(message: String) {
            val value = valueClass.getConstructor(ByteArray::class.java)
                .newInstance(message.toByteArray(StandardCharsets.UTF_8))
            val result = deviceClass.getMethod("set", IntArray::class.java, valueClass)
                .invoke(device, intArrayOf(CROSS_ID_CHANGE_THEME), value) as Number
            if (result.toInt() != 0) {
                throw IllegalStateException("Cross-device send failed: $result")
            }
        }

        fun await(timeoutMs: Long): Int? = waiter.await(timeoutMs)

        override fun close() {
            runCatching {
                deviceClass.getMethod("unregisterListener", listenerClass)
                    .invoke(device, listener)
            }
        }

        companion object {
            // BYD's cross-device transport is vendor-only and has no public SDK equivalent.
            @SuppressLint("PrivateApi")
            fun open(context: Context, requestId: Int): FseCrossResponseSession {
                val deviceClass = Class.forName("android.cross.device.BYDCrossDevice")
                val device = requireNotNull(
                    deviceClass.getMethod("getInstance", Context::class.java)
                        .invoke(null, context),
                ) { "BYDCrossDevice is unavailable" }
                val listenerClass = Class.forName("android.cross.IBYDCrossListener")
                val eventClass = Class.forName("android.cross.IBYDCrossEvent")
                val valueClass = Class.forName("android.cross.BYDCrossEventValue")
                val bufferField = valueClass.getField("bufferDataValue")
                val waiter = FseInstallResponseWaiter(requestId)
                val listener = Proxy.newProxyInstance(
                    context.javaClass.classLoader,
                    arrayOf(listenerClass),
                ) { proxy, method, arguments ->
                    when (method.name) {
                        "onDataEventChanged" -> {
                            val eventType = arguments?.getOrNull(0) as? Number
                            val eventValue = arguments?.getOrNull(1)
                            if (eventType?.toInt() == CROSS_ID_CHANGE_THEME && eventValue != null) {
                                val payload = bufferField.get(eventValue) as? ByteArray
                                Log.i(
                                    TAG,
                                    "Received FSE install response bytes=${payload?.size ?: 0}",
                                )
                                waiter.onPayload(payload)
                            }
                            null
                        }
                        "onDataChanged" -> {
                            val event = arguments?.getOrNull(0)
                            if (event != null) {
                                val eventType = eventClass.getMethod("getEventType")
                                    .invoke(event) as? Number
                                if (eventType?.toInt() == CROSS_ID_CHANGE_THEME) {
                                    val payload = eventClass.getMethod("getBufferData")
                                        .invoke(event) as? ByteArray
                                    Log.i(
                                        TAG,
                                        "Received legacy FSE response bytes=${payload?.size ?: 0}",
                                    )
                                    waiter.onPayload(payload)
                                }
                            }
                            null
                        }
                        "onError" -> {
                            Log.w(
                                TAG,
                                "FSE cross listener error code=${arguments?.getOrNull(0)} " +
                                    "message=${arguments?.getOrNull(1)}",
                            )
                            null
                        }
                        "toString" -> "FseInstallResponseListener"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.getOrNull(0)
                        else -> null
                    }
                }
                deviceClass.getMethod(
                    "registerListener",
                    listenerClass,
                    IntArray::class.java,
                ).invoke(device, listener, intArrayOf(CROSS_ID_CHANGE_THEME))
                return FseCrossResponseSession(
                    device = device,
                    deviceClass = deviceClass,
                    valueClass = valueClass,
                    listenerClass = listenerClass,
                    listener = listener,
                    waiter = waiter,
                )
            }
        }
    }
}

internal class FseInstallResponseWaiter(private val requestId: Int) {
    private val completed = AtomicBoolean(false)
    private val latch = CountDownLatch(1)
    private val result = AtomicReference<Int?>()

    fun onPayload(payload: ByteArray?) {
        if (payload == null) return
        val response = payload.toString(StandardCharsets.UTF_8)
        val parsed = FseInstallResponse.code(response, requestId) ?: return
        if (!completed.compareAndSet(false, true)) return
        result.set(parsed)
        latch.countDown()
    }

    fun await(timeoutMs: Long): Int? {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result.get()
    }

    fun isComplete(): Boolean = completed.get()
}

internal object FseInstallResponse {
    private val requestPattern = Regex("\"res_id\"\\s*:\\s*(-?\\d+)")
    private val resultPattern = Regex("\"result\"\\s*:\\s*(-?\\d+)")

    fun code(log: String, requestId: Int): Int? {
        return log.lineSequence()
            .filter { "using_wallpaper_result" in it }
            .mapNotNull { line ->
                val responseId = requestPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (responseId != requestId) return@mapNotNull null
                resultPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            .lastOrNull()
    }

    fun result(log: String, requestId: Int): Boolean? =
        code(log, requestId)?.let {
            it == FSE_INSTALL_RESULT_SUCCESS ||
                it == FSE_INSTALL_RESULT_PACKAGE_PRESENT_WITH_PROVIDER_WARNING
        }
}
