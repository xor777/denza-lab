package dev.denza.apps.feature.fse

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import dev.denza.apps.adb.AdbProblem
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.disharebridge.LocalAdbClient
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One application the passenger chooser offers. Its picture is drawn by package from
 * [dev.denza.apps.AppIcons], by the rule every chooser draws by: its launcher activity's icon, as
 * the home screen shows it.
 */
data class FseInstallApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkSizeBytes: Long,
    val installable: Boolean,
    val unavailableReason: String = "",
)

sealed interface FseInstallResult {
    data class Installed(val app: FseInstallApp) : FseInstallResult
    data class Failed(val failure: FseInstallFailure, val details: String? = null) : FseInstallResult {
        /** The tile's caption. */
        val message: String get() = failure.words
    }
}

/** The «Экран справа» tile while an install runs and once it has ended. */
object FseInstallStatus {
    /**
     * Whether an install is running. While it is, the tile's press opens nothing: the tile already
     * shows how far it has got, and the chooser it used to open again took every tap and did
     * nothing with it - one install runs at a time.
     */
    fun installing(snapshot: FeatureSnapshot): Boolean =
        snapshot.status == FeatureStatus.STARTING || snapshot.status == FeatureStatus.RECOVERING

    /** Running: the tile's caption is the install's own words, [FseInstallStep]'s. */
    fun progress(words: String): FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.FSE_INSTALLER,
        desiredEnabled = false,
        status = FeatureStatus.STARTING,
        message = words,
    )

    fun of(result: FseInstallResult): FeatureSnapshot = when (result) {
        is FseInstallResult.Installed -> FeatureSnapshot(
            id = FeatureId.FSE_INSTALLER,
            desiredEnabled = false,
            status = FeatureStatus.READY,
            message = result.app.label,
        )
        // The channel's failure waits on the press, which looks at the car's access before the
        // chooser opens again; any other ending is the install's own, and the press is the chooser.
        is FseInstallResult.Failed -> FeatureSnapshot(
            id = FeatureId.FSE_INSTALLER,
            desiredEnabled = false,
            status = if (result.failure == FseInstallFailure.NO_ACCESS) {
                FeatureStatus.NEEDS_ACTION
            } else {
                FeatureStatus.ERROR
            },
            message = result.message,
            details = result.details,
            resolution = FeatureResolution.CHECK_ACCESS.takeIf { result.failure == FseInstallFailure.NO_ACCESS },
        )
    }
}

/**
 * Where an install stopped, in the few words the «Экран справа» tile has room for.
 *
 * These are the tile's caption, and they used to be written as instructions to somebody standing at
 * a desk: «Откройте ADB Rescue в диагностике» named a screen that no longer exists under that name,
 * and «Подтвердите ADB-ключ на экране автомобиля» is a sentence and a half on a tile that elides at
 * one line. A tile says what state a thing is in; what to do about it is the press, which reopens
 * the chooser. Every failure an install can end in is one of these, so the list is the whole of
 * what the tile can say.
 */
enum class FseInstallFailure(val words: String) {
    /** Guards: the chooser draws what cannot go across as unpressable, so the car changed under it. */
    NOT_FOUND("Не найдено"),
    NOT_TRANSFERABLE("Не переносится"),
    UNREADABLE("Не прочиталось"),

    /** No answer from the passenger screen within its timeout; the staged files are kept. */
    NO_ANSWER("Экран не ответил"),

    /** The passenger screen answered with a code that is not a success. */
    DECLINED("Экран отклонил"),
    NOT_COPIED("Не скопировалось"),

    /** The channel's own failure, as on every tile ([AdbProblem]). */
    NO_ACCESS(AdbProblem.WORDS),
    NO_SCREEN("Экран не найден"),
    NOT_INSTALLED("Не установилось"),
    ;

    companion object {
        /**
         * What an exception the install threw is. The copy's own failure is named first, whatever
         * stopped it: a copy that died half-way is what the driver is looking at.
         */
        fun of(error: Exception): FseInstallFailure = when {
            error.message.orEmpty().contains("APK copy", ignoreCase = true) -> NOT_COPIED
            AdbProblem.of(error) != null -> NO_ACCESS
            error.message.orEmpty().contains("not mounted", ignoreCase = true) -> NO_SCREEN
            else -> NOT_INSTALLED
        }
    }
}

/**
 * What an install is doing, as the tile says it while it runs - the tile's caption is the
 * install's own words until it ends, so they are held to its 17 characters. «Подготавливаю
 * Яндекс Навигатор» was 30.
 */
enum class FseInstallStep(val words: String) {
    CHECKING("Проверка экрана"),
    PREPARING("Подготовка"),
    INSTALLING("Установка"),
    ;

    companion object {
        /** The copy, the one step that can say how far it has got. */
        fun copying(percent: Int): String = "Копирование: $percent%"
    }
}

private const val FSE_INSTALL_RESULT_SUCCESS = 1
// FSE 42.1.8.2605219.1 returned -7 after a fresh RUTUBE install became visible.
// The package is present even though the OEM wallpaper provider reports a warning.
private const val FSE_INSTALL_RESULT_PACKAGE_PRESENT_WITH_PROVIDER_WARNING = -7

object FseAppInstaller {
    private const val TAG = "DenzaApps.FseInstaller"
    private const val CROSS_ID_CHANGE_THEME = -13_631_467
    private const val IVI_DEVICE_ID = 1
    private const val FSE_DEVICE_ID = 2
    private const val RESPONSE_TIMEOUT_MS = 90_000L
    private const val COPY_BLOCK_BYTES = 4L * 1024L * 1024L
    private const val COPY_READ_TIMEOUT_MS = 30_000

    private data class InstalledPackageCandidate(
        val packageName: String,
        val label: String,
        val packageInfo: PackageInfo,
        val applicationInfo: ApplicationInfo,
    )

    fun installedApps(context: Context): List<FseInstallApp> {
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

    fun install(
        context: Context,
        packageName: String,
        onProgress: (String) -> Unit,
    ): FseInstallResult {
        // These four are guards rather than answers to a gesture: the picker draws an application
        // it cannot send across as unpressable, so getting here means the car changed under the
        // list. They still land on the tile's one line, so they are states - the reason in full
        // (a split package, an unreadable source) stays in [FseInstallApp.unavailableReason] and
        // in the log.
        val app = installedApps(context).firstOrNull { it.packageName == packageName }
            ?: return FseInstallResult.Failed(FseInstallFailure.NOT_FOUND)
        if (!app.installable) {
            Log.w(TAG, "FSE install refused for $packageName: ${app.unavailableReason}")
            return FseInstallResult.Failed(FseInstallFailure.NOT_TRANSFERABLE)
        }

        val manager = context.packageManager
        val packageInfo = runCatching { manager.getPackageInfo(packageName, 0) }.getOrNull()
            ?: return FseInstallResult.Failed(FseInstallFailure.UNREADABLE)
        val sourcePath = packageInfo.applicationInfo?.sourceDir
            ?: return FseInstallResult.Failed(FseInstallFailure.NOT_TRANSFERABLE)
        if (!packageInfo.applicationInfo?.splitSourceDirs.isNullOrEmpty()) {
            return FseInstallResult.Failed(FseInstallFailure.NOT_TRANSFERABLE)
        }

        val requestId = requestId()
        val resourceName = "denza-apps-install-$requestId"
        val iviRoot = "/storage/FFFF-FFFC/$resourceName"
        val fseRoot = "/storage/emulated/0/$resourceName"
        val adb = DenzaLocalAdb.client(context).openPersistentShell()
        var installSent = false

        return try {
            onProgress(FseInstallStep.CHECKING.words)
            requireFseStorage(adb)
            cleanupAbandonedStages(adb)

            onProgress(FseInstallStep.PREPARING.words)
            val config = installConfig(packageInfo, requestId)
            val encodedConfig = Base64.encodeToString(
                config.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            adb.shell(stageConfigCommand(iviRoot, encodedConfig))

            copyApk(
                adb = adb,
                sourcePath = sourcePath,
                targetPath = "$iviRoot/wallpaper/Application.apk",
                expectedBytes = app.apkSizeBytes,
                onProgress = onProgress,
            )

            onProgress(FseInstallStep.INSTALLING.words)
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
                // Both of these end up as the tile's one-line caption, so they are states and not
                // sentences; the staging path and the vendor's result code go to `details`, which
                // only the service panel reads.
                null -> FseInstallResult.Failed(
                    FseInstallFailure.NO_ANSWER,
                    "staged=$iviRoot; requestId=$requestId",
                )
                else -> {
                    cleanup(adb, iviRoot)
                    FseInstallResult.Failed(
                        FseInstallFailure.DECLINED,
                        "result=$installResult; requestId=$requestId",
                    )
                }
            }
        } catch (error: Exception) {
            if (!installSent) cleanup(adb, iviRoot)
            Log.w(TAG, "FSE install of $packageName failed requestId=$requestId", error)
            FseInstallResult.Failed(FseInstallFailure.of(error), error.toString())
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
            adb.shell(truncateCommand(targetPath))
            onProgress(FseInstallStep.copying(0))
            val blockCount = (expectedBytes + COPY_BLOCK_BYTES - 1L) / COPY_BLOCK_BYTES
            repeat(blockCount.toInt()) { block ->
                val result = adb.shell(
                    copyBlockCommand(sourcePath, targetPath, block),
                    COPY_READ_TIMEOUT_MS,
                ).trim()
                if (result.lineSequence().lastOrNull() != "0") {
                    throw IllegalStateException("dd exit=$result block=$block")
                }
                val copiedBytes = minOf((block + 1L) * COPY_BLOCK_BYTES, expectedBytes)
                val percent = (copiedBytes * 100L / expectedBytes).toInt()
                onProgress(FseInstallStep.copying(percent))
            }
            val actualBytes = adb.shell(sizeCommand(targetPath)).trim().toLongOrNull()
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
            adb.shell(removeStageCommand(iviRoot))
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
                    packageInfo = packageInfo,
                    applicationInfo = applicationInfo,
                )
            }
    }

    private fun requestId(): Int =
        1_000_000_000 + ((System.currentTimeMillis() / 1_000L) % 900_000_000L).toInt()

    internal fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    /** The request's staging folder and its `config.json`, written in one shell trip. */
    internal fun stageConfigCommand(iviRoot: String, encodedConfig: String): String =
        "mkdir -p ${quote("$iviRoot/wallpaper")} && " +
            "echo ${quote(encodedConfig)} | base64 -d > ${quote("$iviRoot/config.json")}"

    internal fun truncateCommand(targetPath: String): String =
        "rm -f ${quote(targetPath)}; : > ${quote(targetPath)}"

    /** One [COPY_BLOCK_BYTES] block of the APK, written in place; it answers `dd`'s exit status. */
    internal fun copyBlockCommand(sourcePath: String, targetPath: String, block: Int): String =
        "dd if=${quote(sourcePath)} of=${quote(targetPath)} " +
            "bs=$COPY_BLOCK_BYTES skip=$block seek=$block count=1 conv=notrunc " +
            ">/dev/null 2>&1; echo \$?"

    internal fun sizeCommand(targetPath: String): String = "stat -c %s ${quote(targetPath)}"

    internal fun removeStageCommand(iviRoot: String): String = "rm -rf ${quote(iviRoot)}"

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
