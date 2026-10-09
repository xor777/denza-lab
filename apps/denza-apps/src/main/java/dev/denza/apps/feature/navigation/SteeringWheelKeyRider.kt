package dev.denza.apps.feature.navigation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * The wheel's ★ key (`321`), second of the key riders: while its switch is on, each first DOWN
 * asks navigation for one action ([navigate]), and [SteeringWheelKeyInterceptor] consumes the whole
 * press only when navigation took it.
 */
class SteeringWheelKeyRider(
    private val navigate: (Context) -> Boolean,
) : AccessibilityRider {
    private val interceptor = SteeringWheelKeyInterceptor()
    private var switchedOn: (() -> Boolean)? = null
    private var perform: SteeringWheelNavigationAction? = null
    private var log: (String) -> Unit = { message -> Log.i(TAG, message) }

    override val name: String = "steering-wheel-key"
    override val takesKeys: Boolean = true

    override fun onConnected(service: AccessibilityService) {
        attach(
            switchedOn = { NavigationSettings.steeringWheelButtonEnabled(service) },
            perform = { navigate(service) },
        )
    }

    /**
     * The key's parts without the Android around them: whether the switch is on, read for every
     * key as the service always read it, the navigation action a press asks for, and the log line.
     */
    internal fun attach(
        switchedOn: () -> Boolean,
        perform: SteeringWheelNavigationAction,
        log: (String) -> Unit = this.log,
    ) {
        this.switchedOn = switchedOn
        this.perform = perform
        this.log = log
    }

    override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val switchedOn = switchedOn ?: return false
        val perform = perform ?: return false
        val consumed = interceptor.onKeyEvent(switchedOn(), keyCode, action, repeatCount, perform)
        if (consumed && action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            // Decided, and navigation has acted: the line only tells about it, and a throw in it
            // must not hand the press to the firmware as well.
            try {
                log("steering-wheel navigation action accepted")
            } catch (_: Exception) {
            }
        }
        return consumed
    }

    override fun onDisconnected(service: AccessibilityService) {
        interceptor.reset()
    }

    private companion object {
        // The service's tag: this line has always been logged under it.
        const val TAG = "DenzaSimulcastA11y"
    }
}
