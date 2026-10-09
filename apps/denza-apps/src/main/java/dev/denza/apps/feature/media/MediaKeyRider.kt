package dev.denza.apps.feature.media

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * The steering wheel's Play/Pause, first of the key riders: [MediaResumeController] answers the
 * press when the policy accepts it and owns its repeats and UP, else the key goes on - to the ★
 * rider, which takes only `321`, and on to the firmware.
 *
 * Whether a new press may be taken at all ([MediaButtonEnvironment]: a call, a mute) is asked only
 * for a new media DOWN, as the service always asked it; the guard notes its answer for «Сервис».
 */
class MediaKeyRider : AccessibilityRider {
    // Published here and read by the support report, which is built off the main looper.
    @Volatile
    private var controller: MediaResumeController? = null
    private var guard: (() -> Boolean)? = null

    override val name: String = "media-key"
    override val takesKeys: Boolean = true

    override fun onConnected(service: AccessibilityService) {
        // On in a normal build. The switch exists so a build without the key filter can be made
        // without touching anything else; see MediaKeyExperiment.
        if (MediaKeyExperiment.INTERCEPT_KEYS) {
            val environment = MediaButtonEnvironment(service)
            attach(MediaResumeController(service), environment::allowsNewPress)
        }
    }

    /**
     * The key's two parts without the Android around them: [controller] answers a press, [guard]
     * says whether a new one may be taken at all. The controller is kept before it starts, so one
     * whose start throws is still stopped by [onDisconnected].
     */
    internal fun attach(controller: MediaResumeController, guard: () -> Boolean) {
        this.guard = guard
        this.controller = controller
        controller.start()
    }

    override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val controller = controller ?: return false
        val allowNewPress = mayTakeNewPress(keyCode, action, repeatCount) { guard?.invoke() == true }
        return controller.onKeyEvent(keyCode, action, repeatCount, allowNewPress)
    }

    override fun onDisconnected(service: AccessibilityService) {
        controller?.stop()
        controller = null
        guard = null
    }

    companion object {
        /**
         * What the controller is told about a key: anything but a new media press may go on as it
         * is (repeats and UP of a press already owned, keys it does not take), and a new media press
         * only when [guard] allows it - asked for that press alone.
         */
        internal inline fun mayTakeNewPress(
            keyCode: Int,
            action: Int,
            repeatCount: Int,
            guard: () -> Boolean,
        ): Boolean = !isNewMediaPress(keyCode, action, repeatCount) || guard()

        /** A first DOWN of Play/Pause, Play, Pause or the vendor toggle: the only key the guard is asked about. */
        internal fun isNewMediaPress(keyCode: Int, action: Int, repeatCount: Int): Boolean =
            action == KeyEvent.ACTION_DOWN &&
                repeatCount == 0 &&
                (
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        keyCode == MediaResumeKeyInterceptor.KEYCODE_BYD_MEDIA_TOGGLE
                    )

        /**
         * The media key's own state, taken from the live controller rather than from a second copy
         * of it. No bound service, or a service without a controller, is reported as absent.
         */
        @JvmStatic
        fun snapshot(): MediaKeySnapshot {
            val controller = AccessibilityHost.rider(MediaKeyRider::class.java)?.controller
            return MediaKeyDiagnostics.snapshot(controller?.isListening(), controller?.rememberedPackage())
        }

        /** Access may just have been granted: the controller listens again, on the main thread. */
        @JvmStatic
        fun requestRefresh() {
            AccessibilityHost.post(MediaKeyRider::class.java) { rider -> rider.controller?.start() }
        }
    }
}
