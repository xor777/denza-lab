package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.disharebridge.LocalAdbClient
import java.security.GeneralSecurityException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SimulcastEnvironment(
    val desired: Boolean,
    val blockingProblem: String? = null,
    val overlayAllowed: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val active: Boolean,
) {
    val needsSetup: Boolean =
        !overlayAllowed || !accessibilityEnabled || !accessibilityConnected
}

sealed interface SimulcastReconcileEvent {
    data object Refresh : SimulcastReconcileEvent
    data class Blocked(
        val message: String,
        val selectedAppCount: Int,
    ) : SimulcastReconcileEvent
    data object Repairing : SimulcastReconcileEvent
    data object Repaired : SimulcastReconcileEvent
    data class RepairFailed(
        val message: String,
        val details: String?,
    ) : SimulcastReconcileEvent
}

/**
 * Owns Simulcast setup and recovery. UI state remains in [DenzaAppRepository];
 * this component reports bounded lifecycle events back to that facade.
 */
object SimulcastCoordinator {
    const val DISHARE_PACKAGE = "com.byd.dishare"
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private val executor = Executors.newSingleThreadExecutor()
    private val repairRunning = AtomicBoolean(false)

    fun inspect(context: Context): SimulcastEnvironment = SimulcastEnvironment(
        desired = SimulcastIntegration.isEnabled(context),
        blockingProblem = blockingProblem(context),
        overlayAllowed = hasOverlayPermission(context),
        accessibilityEnabled = isAccessibilityEnabled(context),
        accessibilityConnected = SimulcastAccessibilityService.isConnected(),
        active = SimulcastIntegration.getLastTargetPackage(context) != null,
    )

    fun evaluate(environment: SimulcastEnvironment): FeatureSnapshot {
        if (!environment.desired) {
            return FeatureReducer.disabled(FeatureId.SIMULCAST)
        }
        environment.blockingProblem?.let { problem ->
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                problem,
            )
        }
        if (!environment.overlayAllowed || !environment.accessibilityEnabled) {
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Нужно разрешить доступ",
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
        environment.blockingProblem?.let { blocking ->
            onEvent(
                SimulcastReconcileEvent.Blocked(
                    message = blocking,
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
        if (!repairRunning.compareAndSet(false, true)) {
            onEvent(SimulcastReconcileEvent.Repairing)
            return
        }

        onEvent(SimulcastReconcileEvent.Repairing)
        executor.execute {
            val failure = runCatching { repairAccessNow(context) }.exceptionOrNull()
            val latestEnvironment = inspect(context)
            val repaired = failure == null &&
                latestEnvironment.overlayAllowed &&
                latestEnvironment.accessibilityEnabled
            repairRunning.set(false)
            if (!latestEnvironment.desired) {
                onEvent(SimulcastReconcileEvent.Refresh)
            } else if (latestEnvironment.blockingProblem != null) {
                onEvent(
                    SimulcastReconcileEvent.Blocked(
                        message = latestEnvironment.blockingProblem,
                        selectedAppCount = SimulcastApps.selectedCount(context),
                    ),
                )
            } else if (repaired) {
                SimulcastOverlayService.startMonitor(context)
                onEvent(SimulcastReconcileEvent.Repaired)
            } else {
                onEvent(
                    SimulcastReconcileEvent.RepairFailed(
                        message = friendlySetupError(failure),
                        details = failure?.toString(),
                    ),
                )
            }
        }
    }

    fun repairAccess(context: Context, onComplete: (Throwable?) -> Unit) {
        executor.execute {
            onComplete(runCatching { repairAccessNow(context) }.exceptionOrNull())
        }
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return SimulcastAccessibilityAccess.isEnabled(setting)
    }

    private fun blockingProblem(context: Context): String? {
        if (!isInstalled(context.packageManager, DISHARE_PACKAGE)) {
            return "Simulcast не найден"
        }
        if (SimulcastApps.getSelected(context).isEmpty()) {
            return "Выберите приложения"
        }
        return null
    }

    private fun repairAccessNow(context: Context) {
        val adb = LocalAdbClient(context, ADB_KEY_COMMENT)
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
    }

    fun friendlySetupError(error: Throwable?): String {
        if (error == null) return "Нужно подтвердить доступ"
        val message = error.message.orEmpty()
        return when {
            message.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            message.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) -> "ADB пока не отвечает"
            error is GeneralSecurityException -> "Не удалось подготовить ключ доступа"
            else -> "Не удалось восстановить доступ"
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
