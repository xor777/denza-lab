package dev.denza.apps.feature.hud

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * «HUD Подсказки»: [HudGuidanceAccessibilityMonitor] lives as long as the service is bound, reads
 * Yandex Navigator through it and hears every event (it keeps those with no package or
 * Navigator's).
 */
class HudGuidanceRider : AccessibilityRider {
    private var monitor: HudGuidanceAccessibilityMonitor? = null

    override val name: String = "hud-guidance"
    override val eventTypes: Int = AccessibilityEvent.TYPES_ALL_MASK

    override fun onConnected(service: AccessibilityService) {
        // Kept before it attaches, so a monitor whose attach throws is still detached on going.
        val monitor = HudGuidanceAccessibilityMonitor(service)
        this.monitor = monitor
        monitor.attach()
    }

    override fun onEvent(event: AccessibilityEvent) {
        monitor?.onAccessibilityEvent(event)
    }

    override fun onDisconnected(service: AccessibilityService) {
        val gone = monitor
        monitor = null
        gone?.detach()
    }

    companion object {
        /**
         * The switch moved. The monitor's state belongs to the main thread, and this is also called
         * from the access repair's executor (`DenzaAppRepository.setHudGuidanceEnabled` after a
         * repair), so it goes there through [AccessibilityHost.post].
         */
        @JvmStatic
        fun requestRefresh() {
            AccessibilityHost.post(HudGuidanceRider::class.java) { rider -> rider.monitor?.onSettingChanged() }
        }
    }
}
