package dev.denza.apps.feature.split

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The actor: the single owner of the mutation queue (contract section 7).
 *
 * It is deliberately not a FIFO executor. The queue is ordered by [SplitInputPriority] (contract
 * section 4), `DISABLE` and `HOME` cancel and overtake the work they outrank, a repeated request
 * joins the live operation instead of starting a second one, and passive hints collapse to the last
 * one. Everything here is plain JVM code: no ADB, no Android, no semantic state.
 *
 * Time enters only through [SplitClock], so a test drives deadlines by hand.
 */

/** Cancels a scheduled action. Cancelling an already fired or already cancelled action is inert. */
internal fun interface SplitCancellable {
    fun cancel()
}

/** The only source of time and timers in the split core. */
internal interface SplitClock {
    fun nowMs(): Long

    fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable
}

/** Why an operation was fenced off. The strings are part of the outcome a caller may report. */
internal object SplitCancelReason {
    const val DISABLE = "disable"
    const val HOME = "home"

    /** An explicit user tap (`OPEN`, `SELECT`) displaced the passive work (contract §4). */
    const val USER_INPUT = "user-input"
    const val DEADLINE = "deadline"
    const val COALESCED = "coalesced"
    const val SHUTDOWN = "shutdown"
}

/**
 * Thrown by [SplitOperationToken.ensureAlive] at the first fence after the operation lost its
 * right to mutate anything (invariant 10).
 */
internal class SplitOperationCancelled(
    val operationId: Long,
    val reason: String,
) : RuntimeException("split operation $operationId cancelled: $reason")

/**
 * The right of one operation to keep mutating, revoked either by a preemption or by its deadline.
 *
 * Thread safe by construction: the worker thread reads it around every command while the actor
 * thread may revoke it at any moment. The first revocation wins, so the reason never changes under
 * an unwinding operation.
 */
internal class SplitOperationToken(
    val operationId: Long,
    val deadlineAtMs: Long,
) {
    private val cancellation = AtomicReference<String?>(null)

    /** The reason of the first revocation, or `null` while the operation still owns its mutations. */
    val cancelReason: String? get() = cancellation.get()

    fun cancel(reason: String) {
        cancellation.compareAndSet(null, reason)
    }

    fun isAlive(nowMs: Long): Boolean = cancellation.get() == null && nowMs <= deadlineAtMs

    /** The fence. Every command and every pause slice of an operation passes through it. */
    fun ensureAlive(nowMs: Long) {
        if (nowMs > deadlineAtMs) cancel(SplitCancelReason.DEADLINE)
        val reason = cancellation.get() ?: return
        throw SplitOperationCancelled(operationId, reason)
    }
}

/** Descending urgency of the inputs that may ask for a mutation (contract section 4). */
internal enum class SplitInputPriority {
    /** Toggle off: cancels every unfinished operation. */
    DISABLE,

    /**
     * A confirmed Home: cancels the scene work the user just walked away from.
     *
     * Never the explicit `OPEN` below it (contract 4.2): that one is the action the user asked
     * for while looking at Home, and only its own deadline may end it.
     */
    HOME,

    /** The navigation lease owns the navigator task while it moves. */
    NAV,

    /** An explicit tap in a picker. Two of them in different panes do not conflict (1.5.5). */
    SELECT,

    /** An explicit tap on the launcher button. */
    OPEN,

    /** A confirmed edge commit or collapse. */
    EDGE,

    /** Passive lifecycle, accessibility and resize hints. */
    HINT,
}

/** Terminal result of one operation (contract 2.4). */
internal sealed interface SplitOutcome {
    /** The read-back passed and the durable snapshot was written by exactly one commit. */
    data object Committed : SplitOutcome

    /** The operation failed and the journal was replayed backwards (contract 7.10). */
    data class RolledBack(val reason: String) : SplitOutcome

    /** The operation lost its token: preempted, shut down or expired. */
    data class Cancelled(val reason: String) : SplitOutcome

    /** The operation refused its preconditions or died in a way it could not describe. */
    data class Failed(val message: String) : SplitOutcome
}

/**
 * One unit of work for the actor.
 *
 * @param joinKey a repeated request with a live twin joins it and shares its ticket (1.3.7, K4).
 * @param coalesceKey at most one queued operation may carry it; a newer one replaces the older.
 */
internal interface SplitOperationSpec {
    val label: String
    val priority: SplitInputPriority

    /** Budget of the operation. The absolute deadline is fixed at submit time. */
    val durationMs: Long
    val joinKey: Any?
    val coalesceKey: Any?

    fun run(op: SplitOperationContext): SplitOutcome
}

/**
 * Handle on a submitted operation. Several callers may hold the very same ticket when their
 * requests joined (K4); all of them then observe one result.
 */
internal class SplitTicket internal constructor(
    val operationId: Long,
    val label: String,
) {
    private val lock = ReentrantLock()
    private val completed = CountDownLatch(1)
    private val listeners = mutableListOf<(SplitOutcome) -> Unit>()
    private var result: SplitOutcome? = null

    val outcome: SplitOutcome? get() = lock.withLock { result }

    val isComplete: Boolean get() = completed.count == 0L

    /** Blocks up to [timeoutMs]; `null` means the operation is still running. */
    fun await(timeoutMs: Long): SplitOutcome? {
        completed.await(timeoutMs, TimeUnit.MILLISECONDS)
        return outcome
    }

    /** Registers a listener. One added after the fact is called immediately, on the caller's thread. */
    fun onComplete(listener: (SplitOutcome) -> Unit) {
        val settled = lock.withLock {
            val known = result
            if (known == null) listeners += listener
            known
        }
        if (settled != null) listener(settled)
    }

    /**
     * Finalises the ticket exactly once.
     *
     * @return `false` when the ticket was already settled - the late result of an operation that a
     * deadline or a preemption already cancelled must not overwrite it (invariant 10, K13).
     */
    internal fun finish(outcome: SplitOutcome): Boolean {
        val waiting = lock.withLock {
            if (result != null) return false
            result = outcome
            val copy = listeners.toList()
            listeners.clear()
            copy
        }
        completed.countDown()
        waiting.forEach { it(outcome) }
        return true
    }
}

/**
 * Priority queue with preemption over a single worker thread.
 *
 * The worker is the only thread that ever runs an operation, so mutations are serialised by
 * construction; preemption cancels a token rather than killing a thread, and the victim dies at its
 * next fence.
 */
internal class SplitActor(
    private val clock: SplitClock,
    private val threadFactory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "split-actor").apply { isDaemon = true }
    },
) {
    private class Entry(
        val spec: SplitOperationSpec,
        val ticket: SplitTicket,
        val token: SplitOperationToken,
        val sequence: Long,
    ) {
        @Volatile
        var deadline: SplitCancellable? = null

        fun disarm() {
            deadline?.cancel()
        }
    }

    private val lock = ReentrantLock()
    private val pending = lock.newCondition()
    private val queued = mutableListOf<Entry>()
    private var inFlight: Entry? = null
    private var worker: Thread? = null
    private var nextOperationId = 1L
    private var nextSequence = 0L
    private var stopped = false

    /**
     * Queues [spec], cancelling whatever it outranks and joining whatever it duplicates.
     *
     * Tickets of the victims are settled outside the actor lock, so a listener may submit new work
     * without deadlocking the queue it is being notified from.
     */
    fun submit(spec: SplitOperationSpec): SplitTicket {
        val cancelled = mutableListOf<Pair<SplitTicket, String>>()
        var entry: Entry? = null
        val ticket = lock.withLock {
            if (stopped) return refused(spec)
            val joined = joinable(spec)
            if (joined != null) return joined.ticket
            cancelled += preempt(spec.priority)
            cancelled += coalesce(spec.coalesceKey)
            val id = nextOperationId++
            val queuedEntry = Entry(
                spec = spec,
                ticket = SplitTicket(id, spec.label),
                token = SplitOperationToken(id, clock.nowMs() + spec.durationMs),
                sequence = nextSequence++,
            )
            queued += queuedEntry
            entry = queuedEntry
            startWorker()
            pending.signalAll()
            queuedEntry.ticket
        }
        cancelled.forEach { (victim, reason) -> victim.finish(SplitOutcome.Cancelled(reason)) }
        entry?.let { armed -> armed.deadline = clock.schedule(armed.spec.durationMs) { expire(armed) } }
        return ticket
    }

    /** Stops the worker and cancels everything still owed. For tests and for process teardown. */
    fun shutdown() {
        var abandoned: List<Entry> = emptyList()
        var thread: Thread? = null
        lock.withLock {
            if (stopped) return
            stopped = true
            abandoned = queued.toList()
            queued.clear()
            inFlight?.token?.cancel(SplitCancelReason.SHUTDOWN)
            thread = worker
            pending.signalAll()
        }
        abandoned.forEach { entry ->
            entry.token.cancel(SplitCancelReason.SHUTDOWN)
            entry.disarm()
            entry.ticket.finish(SplitOutcome.Cancelled(SplitCancelReason.SHUTDOWN))
        }
        thread?.join(SHUTDOWN_JOIN_MS)
    }

    private fun refused(spec: SplitOperationSpec): SplitTicket =
        SplitTicket(NO_OPERATION_ID, spec.label).apply {
            finish(SplitOutcome.Cancelled(SplitCancelReason.SHUTDOWN))
        }

    /**
     * Contract 1.3.7: a second tap must not start a second operation. The live twin is found by
     * [SplitOperationSpec.joinKey] both in the queue and in flight.
     */
    private fun joinable(spec: SplitOperationSpec): Entry? {
        val key = spec.joinKey ?: return null
        val now = clock.nowMs()
        val live = inFlight?.let { listOf(it) + queued } ?: queued
        return live.firstOrNull { candidate ->
            candidate.spec.joinKey == key && !candidate.ticket.isComplete && candidate.token.isAlive(now)
        }
    }

    /**
     * Contract section 4. `DISABLE` cancels everything unfinished; `HOME` cancels the work the user
     * walked away from; an explicit `OPEN` or `SELECT` cancels the passive hint work it must not
     * wait behind (§4, ред. 2026-08-24). An in-flight victim only loses its token here: it settles
     * its own ticket when it unwinds at its next fence, which is what makes "no late mutation"
     * observable.
     */
    private fun preempt(priority: SplitInputPriority): List<Pair<SplitTicket, String>> {
        val victims: Set<SplitInputPriority>
        val reason: String
        when (priority) {
            SplitInputPriority.DISABLE -> {
                victims = SplitInputPriority.entries.toSet()
                reason = SplitCancelReason.DISABLE
            }
            SplitInputPriority.HOME -> {
                victims = HOME_VICTIMS
                reason = SplitCancelReason.HOME
            }
            SplitInputPriority.SELECT, SplitInputPriority.OPEN -> {
                victims = USER_TAP_VICTIMS
                reason = SplitCancelReason.USER_INPUT
            }
            else -> return emptyList()
        }
        inFlight?.takeIf { it.spec.priority in victims }?.token?.cancel(reason)
        return drop(reason) { it.spec.priority in victims }
    }

    /** Passive hints collapse: the queue keeps only the newest one of a key (contract section 7). */
    private fun coalesce(key: Any?): List<Pair<SplitTicket, String>> {
        if (key == null) return emptyList()
        return drop(SplitCancelReason.COALESCED) { it.spec.coalesceKey == key }
    }

    private fun drop(
        reason: String,
        predicate: (Entry) -> Boolean,
    ): List<Pair<SplitTicket, String>> {
        val dropped = queued.filter(predicate)
        if (dropped.isEmpty()) return emptyList()
        queued.removeAll(dropped.toSet())
        dropped.forEach { entry ->
            entry.token.cancel(reason)
            entry.disarm()
        }
        return dropped.map { it.ticket to reason }
    }

    /**
     * Deadline of one operation (contract 5, "to 1.3"): it cancels the operation, not just a window.
     * The ticket settles immediately - nothing waits for the run to notice, it dies at its next
     * fence and its late result is refused by [SplitTicket.finish].
     */
    private fun expire(entry: Entry) {
        entry.token.cancel(SplitCancelReason.DEADLINE)
        lock.withLock { queued.remove(entry) }
        entry.ticket.finish(SplitOutcome.Cancelled(SplitCancelReason.DEADLINE))
    }

    private fun startWorker() {
        if (worker != null) return
        worker = threadFactory.newThread { workerLoop() }.also(Thread::start)
    }

    private fun workerLoop() {
        while (true) {
            val entry = lock.withLock {
                while (queued.isEmpty() && !stopped) {
                    try {
                        pending.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (stopped) return
                val next = queued.minWith(ORDER)
                queued.remove(next)
                inFlight = next
                next
            }
            try {
                execute(entry)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (escaped: Throwable) {
                // The queue outlives any single operation, including a listener that throws while
                // its ticket is being settled. Losing the worker would freeze the whole product.
                entry.ticket.finish(SplitOutcome.Failed(escaped.message ?: escaped.toString()))
            }
        }
    }

    private fun execute(entry: Entry) {
        val outcome = try {
            entry.spec.run(SplitOperationContext(entry.token, clock, SplitMutationJournal()))
        } catch (cancelled: SplitOperationCancelled) {
            SplitOutcome.Cancelled(cancelled.reason)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            SplitOutcome.Failed(SplitCancelReason.SHUTDOWN)
        } catch (failure: Throwable) {
            SplitOutcome.Failed(failure.message ?: failure.toString())
        }
        lock.withLock {
            if (inFlight === entry) inFlight = null
        }
        entry.disarm()
        entry.ticket.finish(fenced(entry, outcome))
    }

    /**
     * Invariant 10: an operation that lost its token may not report success, however far its own
     * code got before it noticed.
     */
    private fun fenced(entry: Entry, outcome: SplitOutcome): SplitOutcome {
        if (outcome !is SplitOutcome.Committed) return outcome
        if (entry.token.isAlive(clock.nowMs())) return outcome
        return SplitOutcome.Cancelled(entry.token.cancelReason ?: SplitCancelReason.DEADLINE)
    }

    private companion object {
        const val NO_OPERATION_ID = -1L
        const val SHUTDOWN_JOIN_MS = 1_000L

        /**
         * Contract 4.2. Home cancels the scene work the user walked away from - a selection, an
         * edge commit, a passive hint - and never the `OPEN` the user just asked for: an open
         * starts *from* Home, so Home on the screen is not news about it (1.3.9, invariant 8).
         * An `OPEN` past its budget still dies, but of its own deadline, and `DISABLE` still
         * cancels everything.
         */
        val HOME_VICTIMS = setOf(
            SplitInputPriority.SELECT,
            SplitInputPriority.EDGE,
            SplitInputPriority.HINT,
        )

        /**
         * Contract §4 (ред. 2026-08-24): явное действие пользователя не ждёт фоновый шум. Сабмит
         * `OPEN` или `SELECT` отменяет пассивный hint/reconcile не только в очереди, но и в
         * полёте - тем же механизмом (токен, fence, откат по журналу), которым Home уже отменяет
         * их; фоновая сверка перечитает мир после пользовательской операции, а проигравшее
         * событие из очереди не воспроизводится. Live v20 D1: сверка со слепым settle стоила
         * пользователю ~2 c очереди перед каждым open. Подтверждённый EDGE-commit шумом не
         * является и не отменяется.
         */
        val USER_TAP_VICTIMS = setOf(SplitInputPriority.HINT)

        /** Priority first, submission order inside one priority. */
        val ORDER: Comparator<Entry> = compareBy({ it.spec.priority.ordinal }, { it.sequence })
    }
}
