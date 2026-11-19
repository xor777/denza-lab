package dev.denza.apps.feature.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * The stock wireless-debugging network dialog, answered while ADB restoration is on: every window
 * that comes up is offered to [WifiDebuggingDialogAutoAllow], which clicks only SystemUI's exact
 * dialog; on connecting, so is every SystemUI window already up (since 2026-11-19).
 */
class WifiDebuggingDialogRider : AccessibilityRider {
    private var service: AccessibilityService? = null

    override val name: String = "wifi-debugging-dialog"
    override val eventTypes: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    override fun onConnected(service: AccessibilityService) {
        this.service = service
        // A dialog raised before the service bound sent its event to nobody: look at what is up.
        AdbRestore.onWifiDialogServiceConnected(service)
    }

    override fun onEvent(event: AccessibilityEvent) {
        service?.let { AdbRestore.onWifiDialog(it, event) }
    }
}
