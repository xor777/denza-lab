package dev.denza.apps.feature.cloud

import java.util.concurrent.Executor

/** Bounded in-memory event buffer; only the shared report worker reads or writes the AtomicFile. */
internal class CloudCustomHistoryQueue(
    private val executor: Executor,
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var saved = CloudLinkTrace()
    private var pending = CloudLinkTrace()
    private var loaded = false
    private var draining = false

    fun addAll(lines: List<Pair<String, String>>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            lines.forEach { (at, message) -> pending.add(at, message) }
            if (!draining) {
                draining = true
                executor.execute(::drain)
            }
        }
    }

    /** Never waits for storage. Includes events accepted while the writer is blocked. */
    fun snapshot(): String = synchronized(lock) {
        CloudLinkTrace(saved.text() + "\n" + pending.text()).text()
    }

    private fun drain() {
        if (!loaded) {
            val old = runCatching(load).getOrElse { error ->
                synchronized(lock) { draining = false }
                runCatching { onFailure(error) }
                return
            }
            synchronized(lock) {
                saved = CloudLinkTrace(old)
                loaded = true
            }
        }
        val merged = synchronized(lock) {
            val batch = pending.text()
            if (batch.isEmpty()) {
                draining = false
                return
            }
            pending = CloudLinkTrace()
            saved = CloudLinkTrace(saved.text() + "\n" + batch)
            saved.text()
        }
        runCatching { save(merged) }.onFailure { error ->
            runCatching { onFailure(error) }
        }
        // Yield the shared writer after one bounded batch. A queued OFF commit must get a
        // turn even when status events keep arriving while each storage write is in flight.
        val more = synchronized(lock) {
            if (pending.text().isEmpty()) {
                draining = false
                false
            } else true
        }
        if (more) executor.execute(::drain)
    }
}
