package dev.denza.apps.feature.cluster

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * Finds the instruments' right card in map mode ([ClusterMapCard]): a window appearing, going or
 * changing makes it look at the overlay display's windows - only while the panel's switch is on -
 * and hand [ClusterMapCardRuntime] the stock small map's bounds, or null.
 */
class ClusterMapCardRider : AccessibilityRider {
    private var service: AccessibilityService? = null

    override val name: String = "cluster-map-card"
    override val eventTypes: Int =
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    override fun onConnected(service: AccessibilityService) {
        this.service = service
        ClusterMapCardRuntime.attach(service)
        look()
    }

    override fun onEvent(event: AccessibilityEvent) {
        look()
    }

    override fun onDisconnected(service: AccessibilityService) {
        this.service = null
        ClusterMapCardRuntime.onCard(null)
    }

    private fun look() {
        val service = service ?: return
        if (!ClusterMapCardSettings.isEnabled(service)) {
            ClusterMapCardRuntime.onCard(null)
            return
        }
        val selection = ClusterDisplayResolver.resolveCameraOverlay(service)
        val display = (selection as? ClusterDisplaySelection.Selected)?.display?.id
        val byDisplay = service.windowsOnAllDisplays
        val groups = (0 until byDisplay.size()).map { byDisplay.valueAt(it) }
        val windows = display?.let { id ->
            ClusterMapCard.windowsOnDisplay(id, groups) { it.displayId }
        }.orEmpty()
        val seen = mutableListOf<ClusterMapCard.Seen>()
        val bounds = Rect()
        windows.forEach { window ->
            val root = window.root ?: return@forEach
            window.getBoundsInScreen(bounds)
            val pkg = root.packageName?.toString()
            seen += ClusterMapCard.Seen(pkg, null, box(bounds))
            if (pkg == ClusterMapCard.STOCK_PACKAGE) {
                root.findAccessibilityNodeInfosByViewId(ClusterMapCard.MAP_VIEW_ID).forEach { node: AccessibilityNodeInfo ->
                    node.getBoundsInScreen(bounds)
                    seen += ClusterMapCard.Seen(pkg, ClusterMapCard.MAP_VIEW_ID, box(bounds))
                }
            }
        }
        ClusterMapCardRuntime.onCard(ClusterMapCard.find(seen))
    }

    private fun box(rect: Rect) = ClusterMapCard.Box(rect.left, rect.top, rect.right, rect.bottom)

    companion object {
        /** The switch moved: look at once, on the main thread, from any caller. */
        @JvmStatic
        fun requestRefresh() {
            AccessibilityHost.post(ClusterMapCardRider::class.java) { rider -> rider.look() }
        }
    }
}
