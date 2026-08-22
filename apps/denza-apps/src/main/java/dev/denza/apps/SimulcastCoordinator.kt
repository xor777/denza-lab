package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.split.SplitScreenCoordinator
import java.security.GeneralSecurityException
import java.util.concurrent.Executors

data class SimulcastEnvironment(
    val desired: Boolean,
    val blocker: SimulcastBlocker? = null,
    val overlayAllowed: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val active: Boolean,
) {
    val needsSetup: Boolean =
        !overlayAllowed || !accessibilityEnabled || !accessibilityConnected
}

enum class SimulcastBlocker {
    DISHARE_UNAVAILABLE,
    APPS_NOT_SELECTED,
}

data class SimulcastSetupProblem(
    val message: String,
    val resolution: FeatureResolution,
)

sealed interface SimulcastReconcileEvent {
    val setupRunning: Boolean

    data object Refresh : SimulcastReconcileEvent {
        override val setupRunning: Boolean = false
    }

    data class Blocked(
        val blocker: SimulcastBlocker,
        val selectedAppCount: Int,
    ) : SimulcastReconcileEvent {
        override val setupRunning: Boolean = false
    }

    data object Repairing : SimulcastReconcileEvent {
        override val setupRunning: Boolean = true
    }

    data object Repaired : SimulcastReconcileEvent {
        override val setupRunning: Boolean = false
    }

    data class RepairFailed(
        val message: String,
        val details: String?,
        val resolution: FeatureResolution = FeatureResolution.RETRY,
    ) : SimulcastReconcileEvent {
        override val setupRunning: Boolean = false
    }
}

/** Requires a quiet AccessibilityManager lifecycle, not merely an enabled component entry. */
internal class AccessibilityBindingSettleTracker(
    private val requiredStableSamples: Int,
) {
    private var stableSamples = 0

    fun observe(connected: Boolean, accessibilityDump: String): Boolean {
        val settled = connected &&
            accessibilityDump.contains("Binding services:{}") &&
            accessibilityDump.contains("Crashed services:{}")
        stableSamples = if (settled) stableSamples + 1 else 0
        return stableSamples >= requiredStableSamples
    }
}

/**
 * Owns Simulcast setup and recovery. UI state remains in [DenzaAppRepository];
 * this component reports bounded lifecycle events back to that facade.
 */
object SimulcastCoordinator {
    const val DISHARE_PACKAGE = "com.byd.dishare"
    private val executor = Executors.newSingleThreadExecutor()
    private val accessibilityRepair = AccessibilityRepairSingleFlight()

    fun inspect(context: Context): SimulcastEnvironment = SimulcastEnvironment(
        desired = SimulcastIntegration.isEnabled(context),
        blocker = blocker(context),
        overlayAllowed = hasOverlayPermission(context),
        accessibilityEnabled = isAccessibilityEnabled(context),
        accessibilityConnected = SimulcastAccessibilityService.isConnected(),
        active = SimulcastIntegration.getLastTargetPackage(context) != null,
    )

    fun evaluate(environment: SimulcastEnvironment): FeatureSnapshot {
        if (!environment.desired) {
            return FeatureReducer.disabled(FeatureId.SIMULCAST)
        }
        environment.blocker?.let { blocker ->
            return blockedSnapshot(blocker)
        }
        if (!environment.overlayAllowed || !environment.accessibilityEnabled) {
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Повторите настройку доступа",
                resolution = FeatureResolution.RETRY,
            )
        }
        if (!environment.accessibilityConnected) {
            return FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Восстанавливаю трансляцию",
            )
        }
        return FeatureReducer.ready(FeatureId.SIMULCAST, active = environment.active)
    }

    fun blockedSnapshot(blocker: SimulcastBlocker): FeatureSnapshot = when (blocker) {
        SimulcastBlocker.DISHARE_UNAVAILABLE -> FeatureSnapshot(
            id = FeatureId.SIMULCAST,
            desiredEnabled = true,
            status = FeatureStatus.UNAVAILABLE,
            message = "Трансляция недоступна на этой системе",
        )
        SimulcastBlocker.APPS_NOT_SELECTED -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.SIMULCAST),
            "Выберите приложения для трансляции",
            resolution = FeatureResolution.SELECT_APPS,
        )
    }

    fun reconcile(
        context: Context,
        repairMissingSetup: Boolean,
        forceRepair: Boolean = false,
        onEvent: (SimulcastReconcileEvent) -> Unit,
    ) {
        val environment = inspect(context)
        if (!environment.desired) {
            onEvent(SimulcastReconcileEvent.Refresh)
            return
        }
        environment.blocker?.let { blocker ->
            onEvent(
                SimulcastReconcileEvent.Blocked(
                    blocker = blocker,
                    selectedAppCount = SimulcastApps.selectedCount(context),
                ),
            )
            return
        }
        if (!environment.needsSetup && !forceRepair) {
            SimulcastOverlayService.startMonitor(context)
            onEvent(SimulcastReconcileEvent.Refresh)
            return
        }
        if (!repairMissingSetup && !forceRepair) {
            onEvent(SimulcastReconcileEvent.Refresh)
            return
        }
        onEvent(SimulcastReconcileEvent.Repairing)
        repairAccess(context) { failure ->
            val latestEnvironment = inspect(context)
            val repaired = failure == null &&
                latestEnvironment.overlayAllowed &&
                latestEnvironment.accessibilityEnabled
            if (!latestEnvironment.desired) {
                onEvent(SimulcastReconcileEvent.Refresh)
            } else if (latestEnvironment.blocker != null) {
                onEvent(
                    SimulcastReconcileEvent.Blocked(
                        blocker = latestEnvironment.blocker,
                        selectedAppCount = SimulcastApps.selectedCount(context),
                    ),
                )
            } else if (repaired) {
                SimulcastOverlayService.startMonitor(context)
                onEvent(SimulcastReconcileEvent.Repaired)
            } else {
                val problem = setupProblem(failure)
                onEvent(
                    SimulcastReconcileEvent.RepairFailed(
                        message = problem.message,
                        details = failure?.toString(),
                        resolution = problem.resolution,
                    ),
                )
            }
        }
    }

    fun repairAccess(context: Context, onComplete: (Throwable?) -> Unit) {
        if (!accessibilityRepair.join(onComplete)) return
        try {
            executor.execute {
                val failure = runCatching { repairAccessNow(context) }.exceptionOrNull()
                accessibilityRepair.complete(failure)
            }
        } catch (error: RuntimeException) {
            accessibilityRepair.complete(error)
        }
    }

    fun isAccessibilityRepairRunning(): Boolean = accessibilityRepair.isRunning()

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return SimulcastAccessibilityAccess.isEnabled(setting)
    }

    fun isAccessibilityConnected(): Boolean = SimulcastAccessibilityService.isConnected()

    private fun blocker(context: Context): SimulcastBlocker? {
        if (!isInstalled(context.packageManager, DISHARE_PACKAGE)) {
            return SimulcastBlocker.DISHARE_UNAVAILABLE
        }
        if (SimulcastApps.getSelected(context).isEmpty()) {
            return SimulcastBlocker.APPS_NOT_SELECTED
        }
        return null
    }

    private fun repairAccessNow(context: Context) {
        AccessibilitySettingsMutationLock.withLock {
            val adb = DenzaLocalAdb.client(context).openPersistentShell()
            try {
                val packageName = shellQuote(context.packageName)
                adb.shell("cmd appops set $packageName SYSTEM_ALERT_WINDOW allow")

                val current = adb.shell(
                    "settings get secure enabled_accessibility_services",
                ).trim()
                adb.shell(
                    "settings put secure enabled_accessibility_services " +
                        shellQuote(SimulcastAccessibilityAccess.withoutService(current)),
                )
                Thread.sleep(250L)

                // Preserve accessibility services changed by another actor during rebind.
                val refreshed = adb.shell(
                    "settings get secure enabled_accessibility_services",
                ).trim()
                adb.shell(
                    "settings put secure enabled_accessibility_services " +
                        shellQuote(SimulcastAccessibilityAccess.withService(refreshed)) +
                        "; settings put secure accessibility_enabled 1",
                )

                // The BYD AccessibilityManager can report the newly added service as connected
                // while it is still rebuilding its event-routing table. Rebinding Split during
                // that overlap leaves both services formally bound, but the Split service never
                // receives Home/window events. Require a sustained quiet lifecycle before Split
                // performs the final, separately owned rebind.
                val settleTracker = AccessibilityBindingSettleTracker(
                    requiredStableSamples = ACCESSIBILITY_STABLE_SAMPLES,
                )
                val settled = (0 until ACCESSIBILITY_SETTLE_ATTEMPTS).any { attempt ->
                    val ready = settleTracker.observe(
                        connected = SimulcastAccessibilityService.isConnected(),
                        accessibilityDump = adb.shell("dumpsys accessibility"),
                    )
                    if (!ready && attempt + 1 < ACCESSIBILITY_SETTLE_ATTEMPTS) {
                        Thread.sleep(ACCESSIBILITY_SETTLE_INTERVAL_MS)
                    }
                    ready
                }
                check(settled) {
                    "AccessibilityManager не завершил подключение Denza Apps"
                }
            } finally {
                adb.close()
            }
        }
        SplitScreenCoordinator.rebindNativePickerAccessAfterGlobalMutation(context)
    }

    private const val ACCESSIBILITY_STABLE_SAMPLES = 8
    private const val ACCESSIBILITY_SETTLE_ATTEMPTS = 40
    private const val ACCESSIBILITY_SETTLE_INTERVAL_MS = 250L

    fun setupProblem(error: Throwable?): SimulcastSetupProblem {
        if (error == null) {
            return SimulcastSetupProblem(
                message = "Подтвердите запрос на экране автомобиля",
                resolution = FeatureResolution.CONFIRM_ON_CAR,
            )
        }
        val message = error.message.orEmpty()
        return when {
            message.contains("authorization required", ignoreCase = true) ->
                SimulcastSetupProblem(
                    message = "Откройте ADB Rescue в диагностике",
                    resolution = FeatureResolution.CONFIRM_ON_CAR,
                )
            message.contains("authorization pending", ignoreCase = true) ->
                SimulcastSetupProblem(
                    message = "Подтвердите запрос на экране автомобиля",
                    resolution = FeatureResolution.CONFIRM_ON_CAR,
                )
            message.contains("refused", ignoreCase = true) ->
                SimulcastSetupProblem(
                    message = "Включите отладку USB в настройках автомобиля",
                    resolution = FeatureResolution.ENABLE_CAR_DEBUGGING,
                )
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                SimulcastSetupProblem(
                    message = "Система автомобиля не отвечает",
                    resolution = FeatureResolution.RETRY,
                )
            error is GeneralSecurityException ->
                SimulcastSetupProblem(
                    message = "Не удалось подготовить безопасный доступ",
                    resolution = FeatureResolution.RETRY,
                )
            else ->
                SimulcastSetupProblem(
                    message = "Не удалось восстановить доступ",
                    resolution = FeatureResolution.RETRY,
                )
        }
    }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
