package dev.denza.apps.feature.simulcast

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.denza.apps.SimulcastAccessibilityService
import dev.denza.apps.adb.AdbProblem
import dev.denza.apps.adb.OverlayGrant
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.platform.accessibility.AccessibilityRepair
import dev.denza.apps.platform.accessibility.SharedAccessibilityAccess

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

/**
 * Owns Simulcast setup and recovery. UI state remains in [dev.denza.apps.DenzaAppRepository];
 * this component reports bounded lifecycle events back to that facade.
 */
object SimulcastCoordinator {
    const val DISHARE_PACKAGE = "com.byd.dishare"

    fun inspect(context: Context): SimulcastEnvironment = SimulcastEnvironment(
        desired = SimulcastIntegration.isEnabled(context),
        blocker = blocker(context),
        overlayAllowed = hasOverlayPermission(context),
        accessibilityEnabled = isAccessibilityEnabled(context),
        accessibilityConnected = SimulcastAccessibilityService.isConnected(),
        active = SimulcastIntegration.getLastTargetPackage() != null,
    )

    fun evaluate(environment: SimulcastEnvironment): FeatureSnapshot {
        if (!environment.desired) {
            return FeatureReducer.disabled(FeatureId.SIMULCAST)
        }
        environment.blocker?.let { blocker ->
            return blockedSnapshot(blocker)
        }
        if (!environment.overlayAllowed || !environment.accessibilityEnabled) {
            // The press repairs it: [AccessibilityRepair.repair] grants the overlay and enables the service.
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                FeatureWords.NO_ACCESS,
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
        // No words of its own: the tile's own «Недоступно» says it, and the panel the press opens
        // says what the projection is. «Трансляция недоступна на этой системе» was 37 characters
        // on a line that holds 17.
        SimulcastBlocker.DISHARE_UNAVAILABLE -> FeatureSnapshot(
            id = FeatureId.SIMULCAST,
            desiredEnabled = true,
            status = FeatureStatus.UNAVAILABLE,
        )
        // A state, and the press opens the choice it is waiting on.
        SimulcastBlocker.APPS_NOT_SELECTED -> FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.SIMULCAST),
            FeatureWords.NOT_CHOSEN,
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
            SimulcastOverlayService.showActiveExit(context)
            onEvent(SimulcastReconcileEvent.Refresh)
            return
        }
        if (!repairMissingSetup && !forceRepair) {
            onEvent(SimulcastReconcileEvent.Refresh)
            return
        }
        onEvent(SimulcastReconcileEvent.Repairing)
        AccessibilityRepair.repair(context) { failure ->
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
                SimulcastOverlayService.showActiveExit(context)
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

    fun hasOverlayPermission(context: Context): Boolean = OverlayGrant.held(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return SharedAccessibilityAccess.isEnabled(setting)
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

    /**
     * What a repair that did not take says on the tile - the projection's and the HUD's, which
     * borrows this repair.
     *
     * Whatever stopped it, the app has no access to what the feature needs, and the tile says that.
     * The channel's own failures used to be found by words in the message, two of them sending the
     * driver to «ADB Rescue» and to the car's USB settings; a repair that finished and still left the
     * service off asked the driver to confirm a prompt that did not exist. What differs is the
     * press: the channel's failures go and look at the channel ([AdbProblem]); the rest repair again.
     */
    fun setupProblem(error: Throwable?): SimulcastSetupProblem = SimulcastSetupProblem(
        message = FeatureWords.NO_ACCESS,
        resolution = AdbProblem.of(error)?.resolution ?: FeatureResolution.RETRY,
    )

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
