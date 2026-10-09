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
 * The service says it was created as the system starts binding it, binds itself as it connects and
 * unbinds as it goes. Only that instance can unbind itself, so an old instance destroyed late does
 * not report a newer, bound one as gone.
 */
object AccessibilityHost {
    private class Arriving(val host: RiderHost, val sinceMs: Long)

    private val bound = AtomicReference<RiderHost?>(null)
    private val arriving = AtomicReference<Arriving?>(null)

    /** The system created [host] at [atMs] (elapsed realtime) and is binding it now. */
    fun created(host: RiderHost, atMs: Long) {
        arriving.set(Arriving(host, atMs))
    }

    fun bind(host: RiderHost) {
        bound.set(host)
        settle(host)
    }

    fun unbind(host: RiderHost) {
        bound.compareAndSet(host, null)
        settle(host)
    }

    /**
     * How long ago, at [nowMs], the system created an instance that has neither connected nor gone
     * since; null when none is on its way. A service the firmware left crashed has no instance at
     * all, so this is null for it.
     */
    fun bindingForMs(nowMs: Long): Long? = arriving.get()?.let { nowMs - it.sinceMs }

    private fun settle(host: RiderHost) {
        val current = arriving.get()
        if (current?.host === host) arriving.compareAndSet(current, null)
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
