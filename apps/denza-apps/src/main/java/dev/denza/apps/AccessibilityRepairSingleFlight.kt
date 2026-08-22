package dev.denza.apps

/** Joins all current accessibility owners to one serialized system rebind. */
internal class AccessibilityRepairSingleFlight {
    private val lock = Any()
    private val callbacks = mutableListOf<(Throwable?) -> Unit>()

    @Volatile
    private var running = false

    fun join(callback: (Throwable?) -> Unit): Boolean = synchronized(lock) {
        callbacks += callback
        if (running) {
            false
        } else {
            running = true
            true
        }
    }

    fun complete(failure: Throwable?) {
        val waiting = synchronized(lock) {
            running = false
            callbacks.toList().also { callbacks.clear() }
        }
        waiting.forEach { callback ->
            runCatching { callback(failure) }
        }
    }

    fun isRunning(): Boolean = running
}
