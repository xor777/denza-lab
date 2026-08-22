package dev.denza.apps.feature.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.denza.apps.R
import dev.denza.apps.ui.VehicleProgressOverlay
import dev.denza.apps.ui.VehicleProgressOverlayInputMode

internal data class NavigationTransferOverlayState(
    val transferActive: Boolean = false,
    val mainActivityResumed: Boolean = false,
) {
    val shouldShow: Boolean
        get() = transferActive && !mainActivityResumed
}

/**
 * Shows a small, non-interactive status window on the main IVI display while a
 * navigation task is moving. The Denza Apps screen already renders the same
 * transition in its Navigation card, so the overlay stays hidden while that
 * Activity is active.
 */
object NavigationTransferOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val overlay = VehicleProgressOverlay(
        textRes = R.string.navigation_transfer_overlay_text,
        windowTitle = "Denza navigation transfer",
        inputMode = VehicleProgressOverlayInputMode.PASS_THROUGH,
    )

    @Volatile
    private var state = NavigationTransferOverlayState()

    @Volatile
    private var appContext: Context? = null

    fun setTransferActive(context: Context, active: Boolean) {
        updateState(context) { copy(transferActive = active) }
    }

    fun setMainActivityResumed(context: Context, resumed: Boolean) {
        updateState(context) { copy(mainActivityResumed = resumed) }
    }

    /** Retry rendering after the shell has repaired the overlay app-op. */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        mainHandler.post(::renderLatestState)
    }

    private fun updateState(
        context: Context,
        transform: NavigationTransferOverlayState.() -> NavigationTransferOverlayState,
    ) {
        appContext = context.applicationContext
        synchronized(stateLock) {
            state = state.transform()
        }
        mainHandler.post(::renderLatestState)
    }

    private fun renderLatestState() {
        val context = appContext ?: return
        overlay.setVisible(context, state.shouldShow)
    }
}
