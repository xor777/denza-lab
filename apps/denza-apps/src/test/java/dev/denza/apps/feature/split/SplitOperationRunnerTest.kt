package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The execution frame of one operation: fencing, journalling, rollback and the single commit.
 *
 * Nothing here is time-driven or thread-driven: the clock is a field a test writes, and the
 * cancellation arrives from inside a fake shell or a fake sleep, exactly where a real one arrives.
 * The oracle is always what reached the raw shell and what reached the store - never a restatement
 * of the code under test.
 */
class SplitOperationRunnerTest {

    private val clock = StepClock()
    private val commands = mutableListOf<String>()
    private val store = InMemoryStore()
    private val rollback = RecordingRollback()
    private var failingCommand: String? = null

    @Test
    fun theFenceIsCheckedBeforeEveryCommand() {
        // инвариант 10: fencing тотален
        val op = context(deadlineAtMs = 10_000L)
        val shell = op.fencedShell(::shell)

        shell("am stack list")
        shell("dumpsys input")
        op.token.cancel(SplitCancelReason.HOME)
        val cancelled = assertThrows(SplitOperationCancelled::class.java) { shell("am task remove 11") }

        assertEquals(SplitCancelReason.HOME, cancelled.reason)
        assertEquals(listOf("am stack list", "dumpsys input"), commands)
    }

    @Test
    fun aCancelledOperationSendsNoCommandAtAll() {
        // K2/K7-механика: после отмены ноль команд доходит до raw shell
        val op = context(deadlineAtMs = 10_000L)
        val shell = op.fencedShell(::shell)
        op.token.cancel(SplitCancelReason.DISABLE)

        repeat(5) {
            assertThrows(SplitOperationCancelled::class.java) { shell("am start picker") }
        }

        assertEquals(emptyList<String>(), commands)
    }

    @Test
    fun anExpiredDeadlineFencesTheShellWithoutAnyExplicitCancel() {
        // K3: истёкший дедлайн - это отмена операции, а не только снятие окна
        val op = context(deadlineAtMs = 1_000L)
        val shell = op.fencedShell(::shell)
        shell("am stack list")

        clock.now = 1_001L
        val cancelled = assertThrows(SplitOperationCancelled::class.java) { shell("am start picker") }

        assertEquals(SplitCancelReason.DEADLINE, cancelled.reason)
        assertEquals(listOf("am stack list"), commands)
    }

    @Test
    fun aLongPauseIsServedInSlicesWithAFenceBetweenThem() {
        // §7.5: token проверяется вокруг каждого шага, включая settle-паузы рецептов
        val slept = mutableListOf<Long>()
        val op = context(deadlineAtMs = 10_000L)

        op.fencedPause { slept += it }(1_200L)

        assertEquals("the recipe keeps its exact total", 1_200L, slept.sum())
        assertEquals(listOf(500L, 500L, 200L), slept)
        assertEquals("one fence before the pause and one after every slice", 4, clock.reads)
    }

    @Test
    fun aShortPauseIsPassedThroughUnsliced() {
        val slept = mutableListOf<Long>()
        val op = context(deadlineAtMs = 10_000L)

        op.fencedPause { slept += it }(120L)

        assertEquals(listOf(120L), slept)
    }

    @Test
    fun aPauseDiesWithinOneSliceOfTheCancel() {
        // 1.3.9: Home должен побеждать мгновенно, а не через многосекундный settle
        val slept = mutableListOf<Long>()
        val op = context(deadlineAtMs = 10_000L)
        val pause = op.fencedPause { millis ->
            slept += millis
            if (slept.size == 2) op.token.cancel(SplitCancelReason.HOME)
        }

        val cancelled = assertThrows(SplitOperationCancelled::class.java) { pause(5_000L) }

        assertEquals(SplitCancelReason.HOME, cancelled.reason)
        assertEquals("the remaining 4 seconds were never slept", listOf(500L, 500L), slept)
    }

    @Test
    fun theJournalKeepsEveryEntryInRecordOrder() {
        val journal = SplitMutationJournal()
        assertTrue(journal.isEmpty())

        val written = listOf(
            SplitJournalEntry.GateOpened(prevOpen = false),
            SplitJournalEntry.LeaseEnabled("resizeability", "0"),
            SplitJournalEntry.TaskCreated(11, PICKER),
            SplitJournalEntry.TaskMoved(7, fromRootId = 1, toRootId = 2),
            SplitJournalEntry.PointOfNoReturn("stock picker removed"),
            SplitJournalEntry.TaskRemoved(9, PICKER),
        )
        written.forEach(journal::record)

        assertEquals(written, journal.entries())
    }

    @Test
    fun rollbackUndoesEveryMutationInReverseOrder() {
        // §7.10: exact rollback по журналу
        val journal = journalOf(
            SplitJournalEntry.GateOpened(prevOpen = false),
            SplitJournalEntry.LeaseEnabled("resizeability", "0"),
            SplitJournalEntry.TaskCreated(11, PICKER),
            SplitJournalEntry.TaskMoved(7, fromRootId = 1, toRootId = 2),
        )

        val report = SplitRollback.run(journal, rollback)

        assertEquals(
            listOf(
                "moveTask(7 -> 1)",
                "removeTask(11, $PICKER)",
                "restoreLease(resizeability, 0)",
                "closeGate",
            ),
            rollback.calls,
        )
        assertEquals(journal.entries().asReversed(), report.undone)
        assertTrue(report.isClean)
        assertNull(report.stoppedAt)
    }

    @Test
    fun rollbackRestoresAGateThatWasAlreadyOpen() {
        // К 1.12: gate закрывается только по собственному lease, чужое состояние возвращается как было
        val report = SplitRollback.run(journalOf(SplitJournalEntry.GateOpened(prevOpen = true)), rollback)

        assertEquals(listOf("openGate"), rollback.calls)
        assertTrue(report.isClean)
    }

    @Test
    fun rollbackStopsAtThePointOfNoReturn() {
        // §7.10, инвариант 9: за точкой невозврата откатывать нечего
        val journal = journalOf(
            SplitJournalEntry.GateOpened(prevOpen = false),
            SplitJournalEntry.LeaseEnabled("resizeability", "0"),
            SplitJournalEntry.PointOfNoReturn("stock picker removed"),
            SplitJournalEntry.TaskMoved(7, fromRootId = 1, toRootId = 2),
        )

        val report = SplitRollback.run(journal, rollback)

        assertEquals(listOf("moveTask(7 -> 1)"), rollback.calls)
        assertEquals(listOf(SplitJournalEntry.TaskMoved(7, 1, 2)), report.undone)
        assertEquals("stock picker removed", report.stoppedAt)
    }

    @Test
    fun rollbackCollectsExecutorFailuresInsteadOfCascading() {
        // U5: сбой отката видим, но не отменяет остальную часть отката
        rollback.failOn = "removeTask(11, $PICKER)"
        val journal = journalOf(
            SplitJournalEntry.GateOpened(prevOpen = false),
            SplitJournalEntry.TaskCreated(11, PICKER),
            SplitJournalEntry.TaskMoved(7, fromRootId = 1, toRootId = 2),
        )

        val report = SplitRollback.run(journal, rollback)

        assertEquals(
            listOf("moveTask(7 -> 1)", "removeTask(11, $PICKER)", "closeGate"),
            rollback.calls,
        )
        assertEquals(
            listOf(
                SplitJournalEntry.TaskMoved(7, 1, 2),
                SplitJournalEntry.GateOpened(prevOpen = false),
            ),
            report.undone,
        )
        assertEquals(1, report.failures.size)
        assertEquals(SplitJournalEntry.TaskCreated(11, PICKER), report.failures.single().entry)
    }

    @Test
    fun rollbackReportsARemovedTaskAsIrreversible() {
        val journal = journalOf(
            SplitJournalEntry.GateOpened(prevOpen = false),
            SplitJournalEntry.TaskRemoved(9, PICKER),
        )

        val report = SplitRollback.run(journal, rollback)

        assertEquals("a removed task is reported, never faked back into existence", listOf("closeGate"), rollback.calls)
        assertEquals(listOf(SplitJournalEntry.TaskRemoved(9, PICKER)), report.irreversible)
    }

    @Test
    fun aSuccessfulOperationCommitsExactlyOnce() {
        // K9: один снапшот - одна запись
        val outcome = operation().run(context(deadlineAtMs = 10_000L))

        assertEquals(SplitOutcome.Committed, outcome)
        assertEquals(1, store.commits)
        assertEquals(COMMITTED_SNAPSHOT, store.load())
        assertEquals(PLANNED_COMMANDS, commands)
        assertEquals(emptyList<String>(), rollback.calls)
    }

    @Test
    fun aFailureOnTheThirdCommandRollsBackAndNeverCommits() {
        // K8, сценарий 10: падение посреди операции откатывается по журналу, хранилище не тронуто
        val before = store.load()
        failingCommand = "am task move 7"

        val outcome = operation().run(context(deadlineAtMs = 10_000L))

        assertEquals(SplitOutcome.RolledBack("adb link dropped"), outcome)
        assertEquals(
            "only the two journalled mutations are undone, newest first",
            listOf("removeTask(11, $PICKER)", "closeGate"),
            rollback.calls,
        )
        assertEquals(0, store.commits)
        assertEquals(before, store.load())
    }

    @Test
    fun aCancelMidOperationRollsBackAndReportsCancelled() {
        // K1/K2-механика на уровне операции: отмена = откат + Cancelled, без записи
        val op = context(deadlineAtMs = 10_000L)
        val outcome = operation(onCommand = { command ->
            if (command == "am start picker") op.token.cancel(SplitCancelReason.HOME)
        }).run(op)

        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.HOME), outcome)
        assertEquals(
            "the command the cancel raced is the last one; nothing after it ran",
            listOf("am stack list", "settings put global gate 1", "am start picker"),
            commands,
        )
        assertEquals(
            "and the mutation it did land is undone by identity",
            listOf("removeTask(11, $PICKER)", "closeGate"),
            rollback.calls,
        )
        assertEquals(0, store.commits)
    }

    @Test
    fun aFailedReadBackRollsBackAndLeavesTheStoreAlone() {
        // §7.7: успех объявляется только после финального read-back
        val outcome = operation(readBackOk = false).run(context(deadlineAtMs = 10_000L))

        assertEquals(SplitOutcome.RolledBack("read-back failed"), outcome)
        assertEquals(
            listOf("moveTask(7 -> 1)", "removeTask(11, $PICKER)", "closeGate"),
            rollback.calls,
        )
        assertEquals(0, store.commits)
    }

    @Test
    fun aCancelDuringTheReadBackKeepsTheOperationOutOfTheStore() {
        // K13-механика: последний fence стоит перед записью, поздняя операция не персистит ничего
        val op = context(deadlineAtMs = 10_000L)
        val outcome = operation(onReadBack = { op.token.cancel(SplitCancelReason.DEADLINE) }).run(op)

        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.DEADLINE), outcome)
        assertEquals(0, store.commits)
        assertEquals(SplitDurable(), store.load())
    }

    @Test
    fun aRefusedPreconditionMutatesNothing() {
        // §7.3: предусловия проверяются до первой мутации
        val outcome = operation(planned = false).run(context(deadlineAtMs = 10_000L))

        assertEquals(SplitOutcome.Failed("open: preconditions refused"), outcome)
        assertEquals(emptyList<String>(), commands)
        assertEquals(emptyList<String>(), rollback.calls)
        assertEquals(0, store.commits)
    }

    @Test
    fun aRejectedCommitRollsBackTheWholeOperation() {
        // K9: неудавшаяся запись не оставляет частичного состояния
        store.accept = false

        val outcome = operation().run(context(deadlineAtMs = 10_000L))

        assertEquals(SplitOutcome.RolledBack("commit rejected"), outcome)
        assertEquals(1, store.commits)
        assertEquals(SplitDurable(), store.load())
        assertEquals(
            listOf("moveTask(7 -> 1)", "removeTask(11, $PICKER)", "closeGate"),
            rollback.calls,
        )
    }

    @Test
    fun theDurableSnapshotCannotCarryATaskId() {
        // инвариант 4: слот - это package, и другого способа его записать нет
        operation().run(context(deadlineAtMs = 10_000L))

        val stored = store.load()
        assertEquals(SplitSlot.App(MUSIC), stored.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.Picker, stored.slot(SplitPane.SECONDARY))
        assertEquals(mapOf("gate" to "0"), stored.leases)
    }

    private fun operation(
        planned: Boolean = true,
        readBackOk: Boolean = true,
        onCommand: (String) -> Unit = {},
        onReadBack: () -> Unit = {},
    ) = TestOperation(
        shell = { command ->
            onCommand(command)
            shell(command)
        },
        store = store,
        rollbackExecutor = rollback,
        planned = planned,
        readBackOk = readBackOk,
        onReadBack = onReadBack,
    )

    private fun context(deadlineAtMs: Long) =
        SplitOperationContext(SplitOperationToken(operationId = 1L, deadlineAtMs = deadlineAtMs), clock)

    private fun shell(command: String): String {
        commands += command
        if (command == failingCommand) throw IllegalStateException("adb link dropped")
        return ""
    }

    private fun journalOf(vararg entries: SplitJournalEntry) =
        SplitMutationJournal().apply { entries.forEach { entry -> record(entry) } }

    /** A clock a test writes by hand; [reads] counts the fences that consulted it. */
    private class StepClock(var now: Long = 0L) : SplitClock {
        var reads: Int = 0

        override fun nowMs(): Long {
            reads += 1
            return now
        }

        override fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable =
            throw UnsupportedOperationException("an operation never arms a timer of its own")
    }

    private class InMemoryStore(private var current: SplitDurable = SplitDurable()) : SplitStateStore {
        var commits: Int = 0
        var accept: Boolean = true

        override fun load(): SplitDurable = current

        override fun commit(next: SplitDurable): Boolean {
            commits += 1
            if (!accept) return false
            current = next
            return true
        }
    }

    private class RecordingRollback : RollbackExecutor {
        val calls = mutableListOf<String>()
        var failOn: String? = null

        override fun closeGate() = call("closeGate")

        override fun openGate() = call("openGate")

        override fun restoreLease(kind: String, prevValue: String?) = call("restoreLease($kind, $prevValue)")

        override fun removeTask(taskId: Int, component: String) = call("removeTask($taskId, $component)")

        override fun moveTask(taskId: Int, toRootId: Int) = call("moveTask($taskId -> $toRootId)")

        private fun call(name: String) {
            calls += name
            if (name == failOn) throw IllegalStateException("$name refused")
        }
    }

    /**
     * A stand-in for a real operation: three journalled mutations around one read-only planning
     * read, exactly the shape section 7 prescribes.
     */
    private class TestOperation(
        shell: (String) -> String,
        store: SplitStateStore,
        rollbackExecutor: RollbackExecutor,
        private val planned: Boolean,
        private val readBackOk: Boolean,
        private val onReadBack: () -> Unit,
    ) : GuardedOperation<String>(
        label = "open",
        priority = SplitInputPriority.OPEN,
        durationMs = 10_000L,
        shell = shell,
        store = store,
        rollbackExecutor = rollbackExecutor,
        joinKey = "open",
    ) {
        override fun plan(op: SplitOperationContext, shell: (String) -> String): String? {
            if (!planned) return null
            shell("am stack list")
            return MUSIC
        }

        override fun mutate(op: SplitOperationContext, shell: (String) -> String, plan: String) {
            shell("settings put global gate 1")
            op.journal.record(SplitJournalEntry.GateOpened(prevOpen = false))
            shell("am start picker")
            op.journal.record(SplitJournalEntry.TaskCreated(11, PICKER))
            shell("am task move 7")
            op.journal.record(SplitJournalEntry.TaskMoved(7, fromRootId = 1, toRootId = 2))
        }

        override fun readBack(
            op: SplitOperationContext,
            shell: (String) -> String,
            plan: String,
        ): Boolean {
            onReadBack()
            shell("am stack list")
            return readBackOk
        }

        override fun durable(plan: String, current: SplitDurable): SplitDurable = current.copy(
            enabled = true,
            slots = mapOf(
                SplitPane.PRIMARY to SplitSlot.App(plan),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            revision = current.revision + 1,
            leases = mapOf("gate" to "0"),
        )
    }

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val PICKER = "dev.denza.apps/.feature.split.SplitPickerActivity"

        val PLANNED_COMMANDS = listOf(
            "am stack list",
            "settings put global gate 1",
            "am start picker",
            "am task move 7",
            "am stack list",
        )

        val COMMITTED_SNAPSHOT = SplitDurable(
            enabled = true,
            slots = mapOf(
                SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            revision = 1L,
            leases = mapOf("gate" to "0"),
        )
    }
}
