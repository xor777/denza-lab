package dev.denza.apps.feature.cloud

import java.util.concurrent.Executor

/** One bounded pending snapshot. A settings change revokes it without waiting for file I/O. */
internal class CloudReportQueue(
    private val executor: Executor,
    private val onWriteFailure: (Throwable) -> Unit = {},
    private val onWriteComplete: () -> Unit = {},
) {
    private data class Work(val generation: Long, val write: () -> Unit)
    private val lock = Any()
    private var generation = 0L
    private var pending: Work? = null
    private var draining = false
    private var changing = false

    fun ticket(): Long = synchronized(lock) { generation }

    /** Export eligibility depends only on the independent report setting, never cloud state. */
    fun capture(ticket: Long, enabled: Boolean, changing: Boolean,
                snapshot: () -> (() -> Unit)): Boolean {
        if (!enabled || changing) return false
        return submit(ticket, snapshot())
    }

    fun submit(ticket: Long, write: () -> Unit): Boolean = synchronized(lock) {
        if (ticket != generation || changing) return false
        pending = Work(ticket, write)
        if (!draining) {
            draining = true
            executor.execute(::drain)
        }
        true
    }

    /** Returns immediately; settled runs after any already-started write and the durable commit. */
    fun change(commit: () -> Boolean, settled: (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (changing) return false
        changing = true
        generation++
        pending = null
        executor.execute {
            val saved = runCatching(commit).getOrDefault(false)
            synchronized(lock) { changing = false }
            settled(saved)
        }
        true
    }

    private fun drain() {
        while (true) {
            // Taking a work item under the lock is its start point. A later OFF waits for this
            // one to finish; queued items are revoked before they can reach this point.
            val work = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) {
                    draining = false
                    return
                }
                next.takeIf { it.generation == generation && !changing }
            } ?: continue
            try {
                runCatching(work.write).onFailure { error -> runCatching { onWriteFailure(error) } }
            } finally {
                runCatching(onWriteComplete)
            }
        }
    }
}
