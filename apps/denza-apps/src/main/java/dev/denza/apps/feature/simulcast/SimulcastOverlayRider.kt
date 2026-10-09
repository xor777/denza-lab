package dev.denza.apps.feature.simulcast

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * «Трансляция»: the overlay over the stock DiShare dialog ([SimulcastDialogOverlay]) lives as long
 * as the service is bound, and a window appearing, going or changing - in any app - makes it look at
 * the dialog again.
 *
 * Looking means walking every window on the screen, on the main thread the wheel keys share. With
 * the projection switched off and nothing of ours to finish, an event does not start that walk
 * ([looksAtWindows]); the switch moving does start one at once ([requestRefresh]), so a dialog
 * already open when the projection is switched on is picked up without waiting for an event.
 */
class SimulcastOverlayRider : AccessibilityRider {
    private var overlay: DialogOverlay? = null
    private var switchedOn: (() -> Boolean)? = null

    override val name: String = "simulcast-overlay"
    override val eventTypes: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    override fun onConnected(service: AccessibilityService) {
        val overlay = SimulcastDialogOverlay()
        // Kept before it attaches, so an overlay whose attach throws is still detached on going.
        attach(overlay) { SimulcastIntegration.isEnabled(service) }
        overlay.attach(service)
        overlay.scheduleRefresh()
    }

    /** The overlay and the projection's switch, without the Android around them. */
    internal fun attach(overlay: DialogOverlay, switchedOn: () -> Boolean) {
        this.overlay = overlay
        this.switchedOn = switchedOn
    }

    override fun onEvent(event: AccessibilityEvent) {
        onWindowEvent()
    }

    /** A window appeared, went or changed: the overlay looks, unless it is idle and the projection off. */
    internal fun onWindowEvent() {
        val overlay = overlay ?: return
        if (looksAtWindows(overlay.isIdle()) { switchedOn?.invoke() == true }) {
            overlay.scheduleRefresh()
        }
    }

    /**
     * Unbind and destroy each end the service; the overlay is detached on both, so a window the
     * first could not remove is tried again by the second.
     */
    override fun onDisconnected(service: AccessibilityService) {
        overlay?.detach()
    }

    companion object {
        /**
         * Whether an event makes the overlay look at the windows: always while it has anything on
         * screen or to finish - a projection switched off must still take its row down and give the
         * exit control back - and otherwise only while the projection is switched on.
         */
        internal inline fun looksAtWindows(overlayIdle: Boolean, switchedOn: () -> Boolean): Boolean =
            !overlayIdle || switchedOn()

        /** The switch moved: the overlay looks at once, on the main thread, from any caller. */
        @JvmStatic
        fun requestRefresh() {
            AccessibilityHost.post(SimulcastOverlayRider::class.java) { rider -> rider.overlay?.scheduleRefresh() }
        }
    }
}
