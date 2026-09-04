package dev.denza.apps.feature.mirrors

/** Serializes lifecycle closure with every transition that can issue a camera command. */
internal class MirrorTransitionGate {
    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(): Boolean = synchronized(lock) {
        if (isRunning) return@synchronized false
        isRunning = true
        true
    }

    fun stop(finalAction: () -> Unit) = synchronized(lock) {
        isRunning = false
        finalAction()
    }

    fun runIfRunning(action: () -> Unit): Boolean = synchronized(lock) {
        if (!isRunning) return@synchronized false
        action()
        true
    }

    fun <T> read(action: () -> T): T = synchronized(lock, action)
}
