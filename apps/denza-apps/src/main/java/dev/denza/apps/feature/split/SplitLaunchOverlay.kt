package dev.denza.apps.feature.split

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.denza.apps.R
import dev.denza.apps.ui.VehicleProgressOverlay
import dev.denza.apps.ui.VehicleProgressOverlayInputMode
import java.util.concurrent.atomic.AtomicBoolean

internal data class SplitLaunchOverlayState(
    val activeLeaseIds: Set<Long> = emptySet(),
) {
    val shouldShow: Boolean
        get() = activeLeaseIds.isNotEmpty()

    fun begin(id: Long): SplitLaunchOverlayState = copy(activeLeaseIds = activeLeaseIds + id)

    fun finish(id: Long): SplitLaunchOverlayState = copy(activeLeaseIds = activeLeaseIds - id)
}

/** Deterministic lease/timer owner; it can only show or release the window. */
internal class SplitLaunchOverlayController(
    private val nowMs: () -> Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val render: (visible: Boolean) -> Unit,
    private val minimumVisibleMs: Long,
    private val maximumVisibleMs: Long,
) {
    init {
        require(minimumVisibleMs >= 0)
        require(maximumVisibleMs >= minimumVisibleMs)
    }

    private val stateLock = Any()
    private var nextLeaseId = 0L
    private var state = SplitLaunchOverlayState()

    fun begin(): Lease {
        val leaseId: Long
        val earliestFinishMs = nowMs() + minimumVisibleMs
        synchronized(stateLock) {
            nextLeaseId += 1
            leaseId = nextLeaseId
            state = state.begin(leaseId)
        }
        render(true)
        schedule(maximumVisibleMs) { finishNow(leaseId) }
        return Lease(leaseId, earliestFinishMs)
    }

    private fun finishWhenReady(leaseId: Long, earliestFinishMs: Long) {
        val remainingMs = earliestFinishMs - nowMs()
        if (remainingMs > 0) {
            schedule(remainingMs) { finishNow(leaseId) }
        } else {
            finishNow(leaseId)
        }
    }

    private fun finishNow(leaseId: Long) {
        val latest: SplitLaunchOverlayState
        synchronized(stateLock) {
            if (leaseId !in state.activeLeaseIds) return
            state = state.finish(leaseId)
            latest = state
        }
        render(latest.shouldShow)
    }

    inner class Lease internal constructor(
        private val leaseId: Long,
        private val earliestFinishMs: Long,
    ) : AutoCloseable {
        private val closeRequested = AtomicBoolean(false)

        override fun close() {
            if (closeRequested.compareAndSet(false, true)) {
                finishWhenReady(leaseId, earliestFinishMs)
            }
        }

        fun closeImmediately() {
            if (closeRequested.compareAndSet(false, true)) {
                finishNow(leaseId)
            }
        }
    }
}

/**
 * Blocks interaction only during an explicit launcher-driven split restore.
 * Native edge-drag discovery never calls this owner and remains fully firmware-controlled.
 */
internal object SplitLaunchOverlay {
    internal const val MIN_VISIBLE_MS = 700L
    internal const val MAX_VISIBLE_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlay = VehicleProgressOverlay(
        textRes = R.string.split_launch_overlay_text,
        windowTitle = "Denza split launch",
        inputMode = VehicleProgressOverlayInputMode.BLOCK_SCREEN,
    )

    @Volatile
    private var appContext: Context? = null

    private val controller = SplitLaunchOverlayController(
        nowMs = SystemClock::elapsedRealtime,
        schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
        render = { visible ->
            appContext?.let { context -> overlay.setVisible(context, visible) }
        },
        minimumVisibleMs = MIN_VISIBLE_MS,
        maximumVisibleMs = MAX_VISIBLE_MS,
    )

    fun begin(context: Context): SplitLaunchOverlayController.Lease {
        appContext = context.applicationContext
        return controller.begin()
    }
}
