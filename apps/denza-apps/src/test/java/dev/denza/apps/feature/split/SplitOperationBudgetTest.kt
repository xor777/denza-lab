package dev.denza.apps.feature.split

import dev.denza.apps.TaskMoveOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф5 волны 15: what one operation cost the car, counted by the funnel rather than estimated.
 *
 * The instrument is the acceptance criterion of every speed wave after this one, so it is tested
 * where it is counted: a command that failed still cost its round trip, a settle pause still cost
 * its wait, and an operation that touched nothing writes no line at all.
 */
class SplitOperationBudgetTest {

    private val diagnostics = mutableListOf<String>()
    private val clock = AdvancingClock()

    /**
     * Контракт §1.13: пользовательская операция укладывается в 10 секунд до результата или честной
     * ошибки. Ни одного теста на это не было, и все бюджеты, кроме HOME, потолок превышали -
     * TOGGLE 30 с, OPEN 15 с, SELECT 20 с, - причём под этими числами не лежало ни одного замера.
     *
     * Дедлайн отсчитывается от submit, то есть в бюджет входит и ожидание в очереди; поэтому
     * проверяется само число, а не время исполнения рецепта.
     *
     * Сюда входят только те операции, к которым потолок применим. `EDGE` ждёт палец пользователя,
     * `RECONCILE` фоновая и её никто не ждёт - у обеих в коде написано, почему они снаружи.
     */
    @Test
    fun everyUserVisibleOperationFitsTheContractCeiling() {
        USER_VISIBLE_BUDGETS_MS.forEach { (label, budget) ->
            assertTrue(
                "$label: бюджет $budget мс выше потолка $USER_VISIBLE_CEILING_MS мс (§1.13)",
                budget <= USER_VISIBLE_CEILING_MS,
            )
        }
    }

    @Test
    fun theCeilingCoversEveryOperationAUserWaitsFor() {
        // Инвариант против тихого сужения: убрать операцию из набора - такой же способ «пройти»
        // проверку выше, как и поднять потолок.
        assertEquals(
            setOf("toggle", "open", "select", "home"),
            USER_VISIBLE_BUDGETS_MS.keys,
        )
    }

    @Test
    fun budgetCountsEveryCommandAndEverySettleOfTheOperation() {
        val workspace = workspace(commandMs = 120L)

        workspace.shell("am stack list")
        workspace.shell("service call activity_task 30")
        workspace.pause(300L)
        workspace.pause(300L)
        workspace.reportBudget("open")

        assertEquals(
            listOf("open: обращений 2, в shell 0.2 с, транспорт (очередь 0.0, отправка 0.0, "
                + "ответ 0.0), разбор 0.0 с, в паузах 0.6 с"),
            diagnostics,
        )
    }

    /** A round trip that ended in a failure is a round trip the user waited for all the same. */
    @Test
    fun budgetCountsAFailedCommandToo() {
        val workspace = workspace(commandMs = 900L, failing = true)

        runCatching { workspace.shell("am stack list") }
        workspace.reportBudget("reconcile")

        assertEquals(
            listOf("reconcile: обращений 1, в shell 0.9 с, транспорт (очередь 0.0, отправка 0.0, "
                + "ответ 0.0), разбор 0.0 с, в паузах 0.0 с"),
            diagnostics,
        )
    }

    /** K6/K7: an operation that decided to do nothing has nothing to report about the car. */
    @Test
    fun anOperationThatTouchedNothingWritesNoBudgetLine() {
        workspace(commandMs = 0L).reportBudget("disable")

        assertTrue(diagnostics.toString(), diagnostics.isEmpty())
    }

    /** Seconds are rendered by the product, in tenths, without a locale of their own. */
    @Test
    fun budgetRendersTenthsOfASecond() {
        val workspace = workspace(commandMs = 3_970L)

        workspace.shell("am stack list")
        workspace.reportBudget("open")

        assertEquals(
            listOf("open: обращений 1, в shell 3.9 с, транспорт (очередь 0.0, отправка 0.0, "
                + "ответ 0.0), разбор 0.0 с, в паузах 0.0 с"),
            diagnostics,
        )
    }

    /**
     * The count is not an estimate of the recipe: it is exactly what reached the car.
     *
     * The oracle is the fake car's own journal of the operation's session, so a recipe that starts
     * sending its commands somewhere else would fail this rather than quietly stop being counted.
     */
    @Test
    fun theBudgetOfARealOperationCountsWhatReachedTheCar() {
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
            core.initialize {}
            car.barrier()
            car.clearCommands()
            car.diagnostics.clear()

            core.openPickerSession()
            car.barrier()

            val reached = car.sessions().sumOf { session -> session.size }
            assertTrue("the open sent nothing at all", reached > 0)
            assertEquals(
                listOf(
                    "open: обращений $reached, в shell 0.0 с, транспорт (очередь 0.0, " +
                        "отправка 0.0, ответ 0.0), разбор 0.0 с, в паузах 0.0 с",
                ),
                car.diagnostics.filter { line -> line.startsWith("open: обращений ") },
            )
        } finally {
            car.close()
        }
    }

    /**
     * Ф1 волны 16: the transport says which part of its own time was whose.
     *
     * A round trip that queued behind another speaker, one that was slow to hand its bytes over
     * and one the car simply took its time answering are three different defects, and the ring
     * used to print a single number for all three.
     */
    @Test
    fun theRingSaysWhichPartOfATripWasQueuingAndWhichWasTheCar() {
        val workspace = workspace(
            commandMs = 0L,
            spend = SplitShellSpend(calls = 3, queuedMs = 900L, sentMs = 100L, answeredMs = 1_600L),
        )

        workspace.shell("am stack list")
        workspace.close()
        workspace.reportBudget("open")

        assertEquals(
            listOf(
                "open: обращений 1, в shell 0.0 с, транспорт (очередь 0.9, отправка 0.1, " +
                    "ответ 1.6), разбор 0.0 с, в паузах 0.0 с",
            ),
            diagnostics,
        )
    }

    /** And what the session reports for its parses reaches the line, added up. */
    @Test
    fun theRingSaysHowLongTurningTheAnswersIntoTheModelTook() {
        val workspace = workspace(commandMs = 0L)

        workspace.shell("am stack list")
        workspace.recordParse(400L)
        workspace.recordParse(300L)
        workspace.close()
        workspace.reportBudget("open")

        assertEquals(
            listOf(
                "open: обращений 1, в shell 0.0 с, транспорт (очередь 0.0, отправка 0.0, " +
                    "ответ 0.0), разбор 0.7 с, в паузах 0.0 с",
            ),
            diagnostics,
        )
    }

    /**
     * Ф1 волны 16: every answer turned into the model is counted, and by the session that turns it.
     *
     * The world read is 8 KB of text and three regular expressions per line of it; a transaction is
     * two. Both used to be invisible - neither the car's time nor the transport's - so a slow
     * operation could be slow for a reason the ring had no word for.
     */
    @Test
    fun everyParseOfAnAnswerIsReportedToTheBudget() {
        val parses = mutableListOf<Long>()
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "am stack list" -> ONE_ROOT_WORLD
                    "service call activity_task 30" -> "Result: Parcel(00000000 00000003 '....')"
                    else -> ""
                }
            },
            apkPath = SPLIT_APK_PATH,
            settle = {},
            parsed = { elapsed -> parses += elapsed },
        )

        session.livingTaskIds()
        session.sceneCovered()

        assertEquals("one world parse and one parcel parse", 2, parses.size)
    }

    private fun workspace(
        commandMs: Long,
        failing: Boolean = false,
        spend: SplitShellSpend = SplitShellSpend(),
    ) = SplitOperationWorkspace(
        shellFactory = { StubShellHandle(clock, commandMs, failing, spend) },
        store = CountingStore(),
        catalog = FakeCatalog,
        leases = emptyList(),
        gateLeaseStore = FakeGateLease(),
        apkPath = SPLIT_APK_PATH,
        clock = clock,
        sleeper = clock::advance,
        diagnostics = { line, _ -> diagnostics += line },
        readState = { SplitState() },
        readLive = { emptyMap() },
        externalMoveInFlight = { false },
        ownership = TaskMoveOwnership(clock::nowMs),
        publisher = { _, _ -> },
    )

    private class StubShellHandle(
        private val clock: AdvancingClock,
        private val commandMs: Long,
        private val failing: Boolean,
        private val spend: SplitShellSpend,
    ) : SplitShellHandle {
        override fun shell(command: String): String {
            clock.advance(commandMs)
            check(!failing) { "link is gone" }
            return ""
        }

        override fun drainSpend(): SplitShellSpend = spend

        override fun close() = Unit
    }

    private companion object {
        val ONE_ROOT_WORLD = """
            RootTask id=1 bounds=[0,0][2560,1600] displayId=0 userId=0
             configuration={mWindowingMode=fullscreen mActivityType=standard}
              taskId=63: com.byd.mycar/com.byd.mycar.CarMainActivity bounds=[0,0][2560,1600] userId=0 visible=true
        """.trimIndent()
    }

    /** Time passes only where it really passes: inside the transport and inside a settle. */
    private class AdvancingClock : SplitClock {
        private var now = 0L

        override fun nowMs(): Long = now

        override fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable =
            SplitCancellable {}

        fun advance(byMs: Long) {
            now += byMs
        }
    }
}
