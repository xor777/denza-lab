package dev.denza.apps.feature.split

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

    @Test
    fun budgetCountsEveryCommandAndEverySettleOfTheOperation() {
        val workspace = workspace(commandMs = 120L)

        workspace.shell("am stack list")
        workspace.shell("service call activity_task 30")
        workspace.pause(300L)
        workspace.pause(300L)
        workspace.reportBudget("open")

        assertEquals(
            listOf("open: обращений 2, в shell 0.2 с, в паузах 0.6 с"),
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
            listOf("reconcile: обращений 1, в shell 0.9 с, в паузах 0.0 с"),
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
            listOf("open: обращений 1, в shell 3.9 с, в паузах 0.0 с"),
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
                listOf("open: обращений $reached, в shell 0.0 с, в паузах 0.0 с"),
                car.diagnostics.filter { line -> line.startsWith("open: обращений ") },
            )
        } finally {
            car.close()
        }
    }

    private fun workspace(commandMs: Long, failing: Boolean = false) = SplitOperationWorkspace(
        shellFactory = { StubShellHandle(clock, commandMs, failing) },
        store = CountingStore(),
        catalog = FakeCatalog,
        leases = emptyList(),
        gateLeaseStore = FakeGateLease(),
        apkPath = SPLIT_APK_PATH,
        clock = clock,
        sleeper = clock::advance,
        diagnostics = { line -> diagnostics += line },
        readState = { SplitState() },
        readLive = { emptyMap() },
        externalMoveInFlight = { false },
        publisher = { _, _ -> },
    )

    private class StubShellHandle(
        private val clock: AdvancingClock,
        private val commandMs: Long,
        private val failing: Boolean,
    ) : SplitShellHandle {
        override fun shell(command: String): String {
            clock.advance(commandMs)
            check(!failing) { "link is gone" }
            return ""
        }

        override fun close() = Unit
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
