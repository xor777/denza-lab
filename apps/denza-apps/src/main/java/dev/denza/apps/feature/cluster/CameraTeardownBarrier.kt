package dev.denza.apps.feature.cluster

/** Process-wide fence that survives a ClusterSceneService instance being destroyed mid-teardown. */
internal class CameraTeardownBarrier {
    private val lock = Any()
    private var nextToken = 0L
    private var activeToken: Long? = null
    private val waiters = mutableListOf<() -> Unit>()

    val isClear: Boolean
        get() = synchronized(lock) { activeToken == null }

    fun begin(): Long = synchronized(lock) {
        check(activeToken == null) { "an AVC presentation teardown is already active" }
        (++nextToken).also { activeToken = it }
    }

    fun whenClear(callback: () -> Unit) {
        val runNow = synchronized(lock) {
            if (activeToken == null) true else false.also { waiters += callback }
        }
        if (runNow) runCatching(callback)
    }

    fun complete(token: Long): Boolean {
        val callbacks = synchronized(lock) {
            if (activeToken != token) return false
            activeToken = null
            waiters.toList().also { waiters.clear() }
        }
        callbacks.forEach { runCatching(it) }
        return true
    }
}
