package dev.denza.apps.feature.simulcast

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * «Трансляция»: the overlay over the stock DiShare dialog ([SimulcastDialogOverlay]) lives as long
 * as the service is bound, and every window appearing, going or changing - in any app - makes it
 * look at the dialog again.
 */
class SimulcastOverlayRider : AccessibilityRider {
    private var overlay: SimulcastDialogOverlay? = null

    override val name: String = "simulcast-overlay"
    override val eventTypes: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    override fun onConnected(service: AccessibilityService) {
        overlay = SimulcastDialogOverlay().also { overlay ->
            overlay.attach(service)
            overlay.scheduleRefresh()
        }
    }

    override fun onEvent(event: AccessibilityEvent) {
        overlay?.scheduleRefresh()
    }

    /**
     * Unbind and destroy each end the service; the overlay is detached on both, so a window the
     * first could not remove is tried again by the second.
     */
    override fun onDisconnected(service: AccessibilityService) {
        overlay?.detach()
    }
}
