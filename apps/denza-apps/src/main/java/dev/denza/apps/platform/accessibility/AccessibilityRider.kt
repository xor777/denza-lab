package dev.denza.apps.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * One feature riding on the app's shared accessibility service, over types a test can stand in
 * for: [S] the service, [E] an event. A key is its three numbers, read once by the host, so the
 * key riders run in a test as they run in the car. Production riders are [AccessibilityRider].
 *
 * Everything is called on the service's main thread, in the order the host was given its riders
 * ([RiderDispatch]).
 */
interface Rider<in S, in E> {
    /** What the log and the order test call it. */
    val name: String

    /**
     * The event types [onEvent] is handed, as a mask of `AccessibilityEvent.TYPE_*` bits:
     * `AccessibilityEvent.TYPES_ALL_MASK` for every type, 0 for none.
     */
    val eventTypes: Int get() = 0

    /**
     * The only packages whose events [onEvent] is handed, or null for every event - an event
     * with no package included.
     */
    val eventPackages: Set<String>? get() = null

    /** Whether [onKeyEvent] is offered the keys at all. */
    val takesKeys: Boolean get() = false

    fun onConnected(service: S) {}

    fun onEvent(event: E) {}

    /**
     * A key: its code, `KeyEvent.ACTION_DOWN` or `ACTION_UP`, and its repeat count. True consumes
     * it: no later rider sees it, and it does not reach the firmware.
     *
     * The dispatcher counts a rider that throws as not consuming, so a rider that has decided must
     * return that decision whatever its telling about it - a log line, the support report's ring -
     * does after: a DOWN it took and then threw on would reach the firmware too, and its UP not.
     */
    fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean = false

    /**
     * The service is going. It is called as the service is unbound and again as it is destroyed,
     * as the service always ran its teardown on both, so a rider's going must be safe to repeat.
     */
    fun onDisconnected(service: S) {}
}

/** A feature on the real service. */
interface AccessibilityRider : Rider<AccessibilityService, AccessibilityEvent>

/** Where the host reports a rider that threw; the rider is passed over, the rest still run. */
fun interface RiderFailure {
    fun failed(rider: String, call: String, error: Throwable)
}
