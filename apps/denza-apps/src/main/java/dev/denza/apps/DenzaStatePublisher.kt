package dev.denza.apps

import java.util.EnumSet
import java.util.concurrent.Executor

/**
 * The one writer of the dashboard's feature state: reads and commits, one at a time, in the order
 * they were asked for.
 *
 * Every feature used to call `DenzaAppRepository.refresh()`, which read about thirty sources on the
 * caller's thread - the main one included - and then wrote all of them in one compare-and-set. The
 * compare-and-set kept the write whole, but not the reads in order: two refreshes on two threads
 * could finish in the wrong order, and the slower one put an older reading back over a newer one -
 * or over the «starting» a switch had just shown.
 *
 * Here a feature marks its slice dirty ([invalidate]) and returns at once. Marks that arrive before
 * the slice is read are folded into one read ([invalidateAll] marks every slice). Reads happen on
 * [executor] alone, one after another, and each is committed before the next begins, so no read
 * can land after a newer one. A state that is not a reading - a switch showing «starting» before
 * its feature has answered - is [publish]ed through the same queue: it lands after every read
 * asked for before it, and every read asked for after it reads the car after it.
 *
 * [read] turns a set of slices into a transform of the state, or null when there is nothing to
 * publish yet (no context). It runs on [executor] and nowhere else.
 */
internal class DenzaStatePublisher(
    private val store: DenzaUiStateStore,
    private val executor: Executor,
    private val read: (Set<StateSlice>) -> ((DenzaUiState) -> DenzaUiState)?,
    private val log: RecomputeLog? = null,
    private val elapsedMs: () -> Long = { 0L },
    private val nanoTime: () -> Long = System::nanoTime,
    private val onError: (String, RuntimeException) -> Unit = { _, _ -> },
) {
    private sealed interface Op

    /** A read still waiting: later marks join it until it starts. */
    private class Read(val slices: EnumSet<StateSlice>, val causes: LinkedHashSet<String>) : Op

    private class Write(val cause: String, val transform: (DenzaUiState) -> DenzaUiState) : Op

    private val lock = Any()
    private val queue = ArrayDeque<Op>()
    private var draining = false

    /** [slice] changed because of [cause]; it is read again, soon, off the caller's thread. */
    fun invalidate(slice: StateSlice, cause: String) {
        enqueueRead(EnumSet.of(slice), cause)
    }

    fun invalidate(slices: Set<StateSlice>, cause: String) {
        if (slices.isEmpty()) return
        enqueueRead(EnumSet.copyOf(slices), cause)
    }

    /** Everything is read again: for the rare paths that cannot say what changed. */
    fun invalidateAll(cause: String) {
        enqueueRead(EnumSet.allOf(StateSlice::class.java), cause)
    }

    /**
     * A state that is not a reading, laid on in its turn. [transform] may run more than once if
     * another writer races it, so it must be pure.
     */
    fun publish(cause: String, transform: (DenzaUiState) -> DenzaUiState) {
        synchronized(lock) {
            queue.addLast(Write(cause, transform))
            scheduleLocked()
        }
    }

    private fun enqueueRead(slices: EnumSet<StateSlice>, cause: String) {
        synchronized(lock) {
            val waiting = queue.lastOrNull() as? Read
            if (waiting != null) {
                waiting.slices.addAll(slices)
                waiting.causes += cause
            } else {
                queue.addLast(Read(slices, linkedSetOf(cause)))
            }
            scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        if (draining) return
        draining = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val op = synchronized(lock) {
                queue.removeFirstOrNull() ?: run {
                    draining = false
                    return
                }
            }
            try {
                when (op) {
                    is Read -> recompute(op)
                    is Write -> store.update(op.transform)
                }
            } catch (error: RuntimeException) {
                onError(if (op is Write) op.cause else (op as Read).causes.joinToString(), error)
            }
        }
    }

    private fun recompute(op: Read) {
        val started = nanoTime()
        try {
            read(op.slices)?.let(store::update)
        } finally {
            log?.record(
                atMs = elapsedMs(),
                trigger = op.causes.joinToString(", "),
                thread = Thread.currentThread().name,
                durationUs = (nanoTime() - started) / 1_000L,
            )
        }
    }
}
