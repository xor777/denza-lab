package dev.denza.apps.feature.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * The stock wireless-debugging network dialog, answered while ADB restoration is on: every window
 * that comes up is offered to [WifiDebuggingDialogAutoAllow], which clicks only SystemUI's exact
 * dialog.
 */
class WifiDebuggingDialogRider : AccessibilityRider {
    private var service: AccessibilityService? = null

    override val name: String = "wifi-debugging-dialog"
    override val eventTypes: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    override fun onConnected(service: AccessibilityService) {
        this.service = service
    }

    override fun onEvent(event: AccessibilityEvent) {
        service?.let { AdbRestore.onWifiDialog(it, event) }
    }
}
