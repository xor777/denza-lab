package dev.denza.apps.feature.split

/**
 * Execution frame of one operation: fenced commands, a mutation journal, exact rollback and a
 * single atomic commit (contract section 7, invariants 9 and 10).
 *
 * A live snapshot assembled from several shell reads is approximate by nature. Consistency comes
 * not from pretending otherwise but from the token check around every single command and from the
 * final read-back, which is exactly what this file mechanises.
 */

/**
 * One reversible thing an operation did. Task and root ids live here and nowhere durable.
 *
 * The operations record all of these but [TaskMoved]: no recipe reports which task it moved out of
 * which root, and a journal entry the operation would have to guess at is worse than none
 * (invariant 3). The entry stays because [SplitRollback] speaks the whole vocabulary of section
 * 7.6, so an operation that one day can observe a move gets an exact inverse rather than a new
 * special case.
 */
internal sealed interface SplitJournalEntry {
    /** We opened the firmware-global gate; [prevOpen] is what it was before. */
    data class GateOpened(val prevOpen: Boolean) : SplitJournalEntry

    /** We took a global lease (resizeability, observer); [prevValue] is the value we displaced. */
    data class LeaseEnabled(val kind: String, val prevValue: String?) : SplitJournalEntry

    data class TaskCreated(val taskId: Int, val component: String) : SplitJournalEntry

    data class TaskMoved(val taskId: Int, val fromRootId: Int, val toRootId: Int) : SplitJournalEntry

    /** Irreversible by nature: a removed task cannot be recreated, only reported. */
    data class TaskRemoved(val taskId: Int, val component: String) : SplitJournalEntry

    /**
     * Everything recorded before this marker stays as it is: past this line the operation may only
     * go forward to a fully verified scene (contract 7.10, invariant 9).
     */
    data class PointOfNoReturn(val reason: String) : SplitJournalEntry
}

/** Append-only record of what one operation changed, in the order it changed it. */
internal class SplitMutationJournal {
    private val recorded = mutableListOf<SplitJournalEntry>()

    fun record(entry: SplitJournalEntry) {
        synchronized(recorded) { recorded += entry }
    }

    fun entries(): List<SplitJournalEntry> = synchronized(recorded) { recorded.toList() }

    fun isEmpty(): Boolean = synchronized(recorded) { recorded.isEmpty() }
}

/**
 * What an operation is given: its token, the clock and its own journal.
 *
 * Every side effect an operation performs goes through one of the fenced wrappers, so "after a
 * cancel not a single command reaches the device" is a property of this class rather than of the
 * discipline of each operation.
 */
internal class SplitOperationContext(
    val token: SplitOperationToken,
    val clock: SplitClock,
    val journal: SplitMutationJournal = SplitMutationJournal(),
) {
    val operationId: Long get() = token.operationId

    /** Throws [SplitOperationCancelled] the moment this operation no longer owns its mutations. */
    fun fence() {
        token.ensureAlive(clock.nowMs())
    }

    /** Wraps a raw shell so that the fence is checked before every single command. */
    fun fencedShell(raw: (String) -> String): (String) -> String = { command ->
        fence()
        raw(command)
    }

    /**
     * Wraps a settle pause. The live recipes keep their exact totals, but a long wait is served in
     * slices with a fence between them, so a cancelled operation stops within one slice instead of
     * sleeping out the rest of a multi-second settle.
     */
    fun fencedPause(sleep: (Long) -> Unit): (Long) -> Unit = { millis ->
        fence()
        var remaining = millis
        while (remaining > 0L) {
            val slice = minOf(remaining, MAX_PAUSE_SLICE_MS)
            sleep(slice)
            remaining -= slice
            fence()
        }
    }

    private companion object {
        const val MAX_PAUSE_SLICE_MS = 500L
    }
}

/** The inverse of every journal entry, addressed by exact identity (invariant 3). */
internal interface RollbackExecutor {
    fun closeGate()

    fun openGate()

    fun restoreLease(kind: String, prevValue: String?)

    fun removeTask(taskId: Int, component: String)

    fun moveTask(taskId: Int, toRootId: Int)
}

internal data class SplitRollbackFailure(
    val entry: SplitJournalEntry,
    val message: String,
)

/**
 * What a rollback managed to undo.
 *
 * @param undone entries reverted, in the order they were reverted (newest first).
 * @param irreversible entries with no inverse; they are reported, never silently dropped.
 * @param failures inverses the executor rejected. Collected, so one dead command cannot abort the
 * rest of the unwind.
 * @param stoppedAt reason of the point of no return that ended the walk, or `null` if there was none.
 */
internal data class SplitRollbackReport(
    val undone: List<SplitJournalEntry> = emptyList(),
    val irreversible: List<SplitJournalEntry> = emptyList(),
    val failures: List<SplitRollbackFailure> = emptyList(),
    val stoppedAt: String? = null,
) {
    val isClean: Boolean get() = failures.isEmpty() && irreversible.isEmpty()
}

/** Exact rollback by journal: reverse order, stopping at the first point of no return. */
internal object SplitRollback {

    fun run(journal: SplitMutationJournal, executor: RollbackExecutor): SplitRollbackReport {
        val newestFirst = journal.entries().asReversed()
        val reversible = newestFirst.takeWhile { it !is SplitJournalEntry.PointOfNoReturn }
        val barrier = newestFirst.getOrNull(reversible.size) as? SplitJournalEntry.PointOfNoReturn

        val undone = mutableListOf<SplitJournalEntry>()
        val irreversible = mutableListOf<SplitJournalEntry>()
        val failures = mutableListOf<SplitRollbackFailure>()
        reversible.forEach { entry ->
            if (entry is SplitJournalEntry.TaskRemoved) {
                irreversible += entry
                return@forEach
            }
            try {
                undo(entry, executor)
                undone += entry
            } catch (failure: Throwable) {
                failures += SplitRollbackFailure(entry, failure.message ?: failure.toString())
            }
        }
        return SplitRollbackReport(
            undone = undone,
            irreversible = irreversible,
            failures = failures,
            stoppedAt = barrier?.reason,
        )
    }

    private fun undo(entry: SplitJournalEntry, executor: RollbackExecutor) {
        when (entry) {
            is SplitJournalEntry.GateOpened ->
                if (entry.prevOpen) executor.openGate() else executor.closeGate()
            is SplitJournalEntry.LeaseEnabled -> executor.restoreLease(entry.kind, entry.prevValue)
            is SplitJournalEntry.TaskCreated -> executor.removeTask(entry.taskId, entry.component)
            is SplitJournalEntry.TaskMoved -> executor.moveTask(entry.taskId, entry.fromRootId)
            // Filtered out before the call; listed so the compiler keeps this exhaustive.
            is SplitJournalEntry.TaskRemoved -> Unit
            is SplitJournalEntry.PointOfNoReturn -> Unit
        }
    }
}

/**
 * Skeleton of the ten steps of contract section 7, so that no operation has to reinvent the order
 * in which fencing, journalling, read-back, commit and rollback happen.
 *
 * The rollback executor is deliberately raw rather than fenced: an operation rolls back precisely
 * because its own token is already dead.
 *
 * @param P whatever the read-only planning phase produced; it never leaves the operation.
 */
internal abstract class GuardedOperation<P>(
    override val label: String,
    override val priority: SplitInputPriority,
    override val durationMs: Long,
    private val shell: (String) -> String,
    private val store: SplitStateStore,
    private val rollbackExecutor: RollbackExecutor,
    override val joinKey: Any? = null,
    override val coalesceKey: Any? = null,
) : SplitOperationSpec {

    /** Steps 2-4: read-only snapshot, preconditions, full plan. `null` refuses before any mutation. */
    protected abstract fun plan(op: SplitOperationContext, shell: (String) -> String): P?

    /** Steps 5-6: exact mutations one at a time, each recorded in `op.journal`. */
    protected abstract fun mutate(op: SplitOperationContext, shell: (String) -> String, plan: P)

    /** Step 7: the product postcondition, measured on a fresh read-back. */
    protected open fun readBack(
        op: SplitOperationContext,
        shell: (String) -> String,
        plan: P,
    ): Boolean = true

    /** Step 8: the single durable snapshot to write, or `null` when nothing durable changed. */
    protected abstract fun durable(plan: P, current: SplitDurable): SplitDurable?

    protected open val refusal: String get() = "$label: preconditions refused"

    final override fun run(op: SplitOperationContext): SplitOutcome {
        val fenced = op.fencedShell(shell)
        return try {
            op.fence()
            val plan = plan(op, fenced) ?: return SplitOutcome.Failed(refusal)
            mutate(op, fenced, plan)
            op.fence()
            when {
                !readBack(op, fenced, plan) -> unwind(op, READ_BACK_FAILED)
                else -> commit(op, plan)
            }
        } catch (cancelled: SplitOperationCancelled) {
            SplitRollback.run(op.journal, rollbackExecutor)
            SplitOutcome.Cancelled(cancelled.reason)
        } catch (failure: Throwable) {
            SplitRollback.run(op.journal, rollbackExecutor)
            SplitOutcome.RolledBack(failure.message ?: failure.toString())
        }
    }

    /** Step 8-9. The fence sits immediately before the write: a dead operation persists nothing. */
    private fun commit(op: SplitOperationContext, plan: P): SplitOutcome {
        val next = durable(plan, store.load())
        op.fence()
        if (next != null && !store.commit(next)) return unwind(op, COMMIT_REJECTED)
        return SplitOutcome.Committed
    }

    private fun unwind(op: SplitOperationContext, reason: String): SplitOutcome {
        SplitRollback.run(op.journal, rollbackExecutor)
        return SplitOutcome.RolledBack(reason)
    }

    private companion object {
        const val READ_BACK_FAILED = "read-back failed"
        const val COMMIT_REJECTED = "commit rejected"
    }
}
