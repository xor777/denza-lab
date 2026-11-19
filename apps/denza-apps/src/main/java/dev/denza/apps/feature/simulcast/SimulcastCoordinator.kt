package dev.denza.apps.feature.simulcast

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.denza.apps.R
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice
import dev.denza.apps.adb.AdbProblem
import dev.denza.apps.adb.OverlayGrant
import dev.denza.apps.core.AppText
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.platform.accessibility.AccessibilityHealth
import dev.denza.apps.platform.accessibility.AccessibilityRepair

data class SimulcastEnvironment(
    val desired: Boolean,
    val blocker: SimulcastBlocker? = null,
    val overlayAllowed: Boolean,
    val accessibility: AccessibilityHealth,
    val active: Boolean,
    /**
     * How long the shared service has been listed in the setting without binding to this process
     * ([SimulcastBindWatch]); 0 when it is bound, switched off, or was only just seen unbound.
     */
    val unboundForMs: Long = 0L,
) {
    /** The shared service's one check, with the projection's own extra need: the overlay grant. */
    val needsSetup: Boolean = !accessibility.ready(extra = overlayAllowed)
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

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Reads the slice again when a bind's deadline runs out, so the tile does not wait for a resume. */
    private val bindWatch = SimulcastBindWatch { delayMs ->
        mainHandler.postDelayed(
            { StateMarks.mark(StateSlice.SIMULCAST, "simulcast bind deadline") },
            delayMs,
        )
    }

    fun inspect(context: Context): SimulcastEnvironment {
        val accessibility = AccessibilityHealth.read(context)
        return SimulcastEnvironment(
            desired = SimulcastIntegration.isEnabled(context),
            blocker = blocker(context),
            overlayAllowed = hasOverlayPermission(context),
            accessibility = accessibility,
            active = SimulcastIntegration.getLastTargetPackage() != null,
            unboundForMs = bindWatch.unboundForMs(
                accessibility.enabled,
                accessibility.connected,
                SystemClock.elapsedRealtime(),
            ),
        )
    }

    fun evaluate(environment: SimulcastEnvironment): FeatureSnapshot {
        if (!environment.desired) {
            return FeatureReducer.disabled(FeatureId.SIMULCAST)
        }
        environment.blocker?.let { blocker ->
            return blockedSnapshot(blocker)
        }
        if (!environment.overlayAllowed || !environment.accessibility.enabled) {
            // The press repairs it: [AccessibilityRepair.repair] grants the overlay and enables the service.
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                FeatureWords.NO_ACCESS,
                resolution = FeatureResolution.RETRY,
            )
        }
        if (!environment.accessibility.connected) {
            // A service the setting lists and the system never binds - after an update, or one
            // the firmware left crashed - used to read «Восстанавливаю трансляцию» for good, with
            // the panel's switch held disabled under it. Past the deadline it is what it is: no
            // access, and the press repairs it again.
            if (environment.unboundForMs >= SimulcastBindWatch.BIND_DEADLINE_MS) {
                return FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.SIMULCAST),
                    FeatureWords.NO_ACCESS,
                    resolution = FeatureResolution.RETRY,
                )
            }
            return FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                AppText.s(R.string.simulcast_recovering),
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
                latestEnvironment.accessibility.enabled
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
                // The repair rewrote the setting: the system binds anew, with a full deadline.
                bindWatch.restart()
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

/**
 * How long the shared accessibility service has stood in the setting without binding, as the
 * projection's tile counts it, and one re-read of the slice when that reaches [BIND_DEADLINE_MS].
 *
 * The clock starts the first time a read finds the service listed and unbound, and stops when it
 * binds or leaves the setting; a finished repair starts it again, because the repair rewrote the
 * setting and the system binds anew.
 */
internal class SimulcastBindWatch(private val schedule: (delayMs: Long) -> Unit) {
    private var unboundSinceMs: Long? = null

    @Synchronized
    fun unboundForMs(enabled: Boolean, connected: Boolean, nowMs: Long): Long {
        if (!enabled || connected) {
            unboundSinceMs = null
            return 0L
        }
        val since = unboundSinceMs ?: nowMs.also {
            unboundSinceMs = it
            schedule(BIND_DEADLINE_MS)
        }
        return (nowMs - since).coerceAtLeast(0L)
    }

    @Synchronized
    fun restart() {
        unboundSinceMs = null
    }

    companion object {
        /**
         * Ten seconds. A bind takes milliseconds and a repair already waits two seconds for it
         * ([AccessibilityHealth.BIND_GRACE_MS]); a service still unbound after ten is not coming.
         */
        const val BIND_DEADLINE_MS = 10_000L
    }
}
