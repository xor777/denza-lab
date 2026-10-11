package dev.denza.apps.feature.alice

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityKeyEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/** Always available while the shared service is bound; a visible Navigator window is its only selector. */
class AliceVoiceKeyRider : AccessibilityRider {
    override val name = "alice-voice-key"
    override val takesKeys = true
    override val eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    private var controller: AliceVoiceKeyController? = null
    private var windows: NavigatorVoiceWindow? = null
    private var handler: Handler? = null

    override fun onConnected(service: AccessibilityService) {
        detach()
        val handler = Handler(service.mainLooper)
        this.handler = handler
        val windows = NavigatorVoiceWindow(service, handler) { Log.i("DenzaAliceKey", it) }
        this.windows = windows
        controller = AliceVoiceKeyController(
            clock = SystemClock::uptimeMillis,
            visibleNavigator = windows::visibleNavigator,
            post = { work -> check(handler.post { work() }) { "service looper stopped" } },
            clickIfVisible = windows::clickIfVisible,
            report = { Log.i("DenzaAliceKey", it) },
            postDelayed = { delay, work -> check(handler.postDelayed({ work() }, delay)) },
            revealIfVisible = windows::revealIfVisible,
        )
        windows.refresh()
    }

    internal fun attach(controller: AliceVoiceKeyController) {
        detach()
        this.controller = controller
    }

    override fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.packageName?.toString() == NavigatorVoiceWindow.PACKAGE) windows?.onNavigatorContent()
        } else {
            controller?.onWindowChanged()
            windows?.refresh()
        }
    }

    override fun onKeyEvent(event: AccessibilityKeyEvent): Boolean = controller?.onKeyEvent(event) ?: false

    override fun onDisconnected(service: AccessibilityService) = detach()

    private fun detach() {
        controller?.close()
        controller = null
        windows?.close()
        windows = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
    }
}
