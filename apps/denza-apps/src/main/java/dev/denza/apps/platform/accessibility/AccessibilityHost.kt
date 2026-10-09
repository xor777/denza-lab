package dev.denza.apps.platform.accessibility

import java.util.concurrent.atomic.AtomicReference

/** The bound shared service, as [AccessibilityHost] reaches it. */
interface RiderHost {
    /** This service's rider of [type], if it has one. */
    fun <R : Any> rider(type: Class<R>): R?

    /** Runs [call] on the service's main thread. */
    fun post(call: Runnable)
}

/**
 * The app's shared accessibility service as the rest of the process sees it: bound or not, and a
 * way to its riders from any thread.
 *
 * The service binds itself as it connects and unbinds as it goes. Only that instance can unbind
 * itself, so an old instance destroyed late does not report a newer, bound one as gone.
 */
object AccessibilityHost {
    private val bound = AtomicReference<RiderHost?>(null)

    fun bind(host: RiderHost) {
        bound.set(host)
    }

    fun unbind(host: RiderHost) {
        bound.compareAndSet(host, null)
    }

    /** Whether the service is bound to this process right now. */
    fun isConnected(): Boolean = bound.get() != null

    /** The bound service's rider of [type]; read from any thread, so its fields must be safe to read so. */
    fun <R : Any> rider(type: Class<R>): R? = bound.get()?.rider(type)

    /**
     * Carries [call] from any thread to the main thread, and runs it with [type]'s rider only if the
     * service bound now is still the bound one when it gets there. False when none is bound, and
     * nothing is queued.
     */
    fun <R : Any> post(type: Class<R>, call: (R) -> Unit): Boolean = ServiceInstanceHop.post<RiderHost>(
        { bound.get() },
        { host, runnable -> host.post(runnable) },
        { host -> host.rider(type)?.let(call) },
    )
}
