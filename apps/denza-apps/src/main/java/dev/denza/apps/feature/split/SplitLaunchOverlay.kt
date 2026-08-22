package dev.denza.apps.feature.split

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.denza.apps.R
import dev.denza.apps.ui.VehicleProgressOverlay
import dev.denza.apps.ui.VehicleProgressOverlayInputMode

internal data class SplitLaunchOverlayState(
    val activeLeaseIds: Set<Long> = emptySet(),
) {
    val shouldShow: Boolean
        get() = activeLeaseIds.isNotEmpty()

    fun begin(id: Long): SplitLaunchOverlayState = copy(activeLeaseIds = activeLeaseIds + id)

    fun finish(id: Long): SplitLaunchOverlayState = copy(activeLeaseIds = activeLeaseIds - id)
}

/**
 * Blocks interaction only during an explicit launcher-driven split restore.
 * Native edge-drag discovery never calls this owner and remains fully firmware-controlled.
 */
internal object SplitLaunchOverlay {
    private const val MAX_VISIBLE_MS = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val overlay = VehicleProgressOverlay(
        textRes = R.string.split_launch_overlay_text,
        windowTitle = "Denza split launch",
        inputMode = VehicleProgressOverlayInputMode.BLOCK_SCREEN,
    )

    private var nextLeaseId = 0L
    private var state = SplitLaunchOverlayState()
    private var appContext: Context? = null

    fun begin(context: Context): Lease {
        val app = context.applicationContext
        val leaseId: Long
        val latest: SplitLaunchOverlayState
        synchronized(stateLock) {
            appContext = app
            nextLeaseId += 1
            leaseId = nextLeaseId
            state = state.begin(leaseId)
            latest = state
        }
        overlay.setVisible(app, latest.shouldShow)
        mainHandler.postDelayed({ finish(leaseId) }, MAX_VISIBLE_MS)
        return Lease(leaseId)
    }

    private fun finish(leaseId: Long) {
        val app: Context
        val latest: SplitLaunchOverlayState
        synchronized(stateLock) {
            if (leaseId !in state.activeLeaseIds) return
            state = state.finish(leaseId)
            latest = state
            app = appContext ?: return
        }
        overlay.setVisible(app, latest.shouldShow)
    }

    class Lease internal constructor(
        private val leaseId: Long,
    ) : AutoCloseable {
        override fun close() {
            finish(leaseId)
        }
    }
}
