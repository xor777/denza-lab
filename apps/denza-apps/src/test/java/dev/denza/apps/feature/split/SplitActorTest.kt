package dev.denza.apps.feature.split

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The actor under adversarial ordering: preemption, joining, coalescing and deadlines.
 *
 * Every test is deterministic by construction. Time only moves when the test moves it, timers only
 * fire from the test thread, and the test thread never sleeps: it waits on latches that the worker
 * thread opens, so a slow machine makes the test slower, never flaky. The single worker also gives
 * a free barrier - submitting an operation and awaiting it proves everything queued before it has
 * already finished.
 */
class SplitActorTest {

    private val clock = FakeSplitClock()
    private val actor = SplitActor(clock)
    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun releaseEverything() {
        gates.forEach(CountDownLatch::countDown)
        actor.shutdown()
    }

    @Test
    fun higherPriorityWorkOvertakesQueuedWork() {
        // контракт §4: очередь приоритетная, а не FIFO
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)

        val open = actor.submit(spec("open", SplitInputPriority.OPEN))
        val nav = actor.submit(spec("nav", SplitInputPriority.NAV))
        release.countDown()

        assertEquals(SplitOutcome.Committed, open.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, nav.await(AWAIT_MS))
        assertEquals(listOf("blocker", "nav", "open"), executed.toList())
    }

    @Test
    fun samePriorityWorkKeepsItsSubmissionOrder() {
        // контракт 1.5.5, сценарий 29: два SELECT в разные панели выполняются оба
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)

        val first = actor.submit(spec("select-primary", SplitInputPriority.SELECT, joinKey = SplitPane.PRIMARY))
        val second = actor.submit(spec("select-secondary", SplitInputPriority.SELECT, joinKey = SplitPane.SECONDARY))
        release.countDown()

        assertEquals(SplitOutcome.Committed, first.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, second.await(AWAIT_MS))
        assertEquals(listOf("blocker", "select-primary", "select-secondary"), executed.toList())
    }

    @Test
    fun homeOvertakesAndCancelsAQueuedOpen() {
        // контракт §4.2, 1.3.9: Home не может стоять в очереди за работой, которую он отменяет
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)
        val open = actor.submit(spec("open", SplitInputPriority.OPEN))

        val home = actor.submit(spec("home", SplitInputPriority.HOME))

        assertEquals(
            "the queued open is settled by the submit itself",
            SplitOutcome.Cancelled(SplitCancelReason.HOME),
            open.outcome,
        )
        release.countDown()
        assertEquals(SplitOutcome.Committed, home.await(AWAIT_MS))
        assertEquals(listOf("blocker", "home"), executed.toList())
    }

    @Test
    fun homeCancelsAnOpenInFlightAtItsNextFence() {
        // K1, контракт 1.3.9, инвариант 10: после Home ни одной команды старой операции
        val started = CountDownLatch(1)
        val release = gate()
        val open = actor.submit(
            spec("open", SplitInputPriority.OPEN) { op ->
                val shell = op.fencedShell(::shell)
                shell("am stack list")
                started.countDown()
                release.await()
                shell("am task move-to-back 42")
                executed += "open"
                SplitOutcome.Committed
            },
        )
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))

        val home = actor.submit(spec("home", SplitInputPriority.HOME))
        release.countDown()

        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.HOME), open.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, home.await(AWAIT_MS))
        assertEquals("no command of the cancelled operation reached the shell", listOf("am stack list"), commands.toList())
        assertEquals(listOf("home"), executed.toList())
    }

    @Test
    fun disableCancelsEverythingQueuedAndInFlight() {
        // K2, контракт 1.2.6, §4.1
        val started = CountDownLatch(1)
        val release = gate()
        val open = actor.submit(
            spec("open", SplitInputPriority.OPEN) { op ->
                val shell = op.fencedShell(::shell)
                shell("am stack list")
                started.countDown()
                release.await()
                shell("am start picker")
                executed += "open"
                SplitOutcome.Committed
            },
        )
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        val select = actor.submit(spec("select", SplitInputPriority.SELECT, joinKey = SplitPane.PRIMARY))
        val hint = actor.submit(spec("hint", SplitInputPriority.HINT, coalesceKey = "reconcile"))
        val nav = actor.submit(spec("nav", SplitInputPriority.NAV))

        val disable = actor.submit(spec("disable", SplitInputPriority.DISABLE))

        listOf(select, hint, nav).forEach { ticket ->
            assertEquals(
                "${ticket.label} survived the disable",
                SplitOutcome.Cancelled(SplitCancelReason.DISABLE),
                ticket.outcome,
            )
        }
        release.countDown()
        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.DISABLE), open.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, disable.await(AWAIT_MS))
        assertEquals(listOf("am stack list"), commands.toList())
        assertEquals(listOf("disable"), executed.toList())
    }

    @Test
    fun theDeadlineCancelsTheOperationWithoutWaitingForItToFinish() {
        // K3, контракт 1.3.8, обязательство к 1.3: дедлайн отменяет операцию, а не только окно
        val started = CountDownLatch(1)
        val release = gate()
        val finished = CountDownLatch(1)
        val open = actor.submit(
            spec("open", SplitInputPriority.OPEN, durationMs = 4_000L) { op ->
                val shell = op.fencedShell(::shell)
                shell("am stack list")
                started.countDown()
                release.await()
                shell("am start picker")
                finished.countDown()
                SplitOutcome.Committed
            },
        )
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))

        clock.advance(4_001L)

        assertEquals(
            "the ticket is settled by the deadline itself",
            SplitOutcome.Cancelled(SplitCancelReason.DEADLINE),
            open.outcome,
        )
        assertEquals("the run is still inside its own step", 1L, finished.count)

        release.countDown()
        val after = actor.submit(spec("after", SplitInputPriority.OPEN))
        assertEquals(SplitOutcome.Committed, after.await(AWAIT_MS))
        assertEquals("nothing of the expired operation reached the shell", listOf("am stack list"), commands.toList())
        assertEquals(listOf("after"), executed.toList())
        assertEquals("a settled operation leaves no armed timer behind", 0, clock.pendingTimers())
    }

    @Test
    fun aLateResultNeverOverwritesACancelledTicket() {
        // K13, инвариант 10: опоздавший итог отменённой операции не финализирует ticket повторно
        val observed = Collections.synchronizedList(mutableListOf<SplitOutcome>())
        val settled = CountDownLatch(1)
        val started = CountDownLatch(1)
        val release = gate()
        // An operation that never checks its fence: the actor, not the operation, is the guard.
        val open = actor.submit(
            spec("open", SplitInputPriority.OPEN, durationMs = 2_000L) {
                started.countDown()
                release.await()
                executed += "open"
                SplitOutcome.Committed
            },
        )
        open.onComplete {
            observed += it
            settled.countDown()
        }
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))

        clock.advance(2_001L)
        assertTrue(settled.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        release.countDown()
        val after = actor.submit(spec("after", SplitInputPriority.OPEN))
        assertEquals(SplitOutcome.Committed, after.await(AWAIT_MS))

        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.DEADLINE), open.outcome)
        assertEquals("the ticket was settled exactly once", listOf(SplitOutcome.Cancelled(SplitCancelReason.DEADLINE)), observed.toList())
        assertEquals("the run itself did finish, its result was simply refused", listOf("open", "after"), executed.toList())
    }

    @Test
    fun aRepeatedRequestJoinsTheLiveOperationAndSharesItsResult() {
        // K4, контракт 1.3.7: повторный тап получает итог общей операции, а не второй запуск
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = gate()
        val body: (SplitOperationContext) -> SplitOutcome = {
            runs.incrementAndGet()
            started.countDown()
            release.await()
            executed += "open"
            SplitOutcome.Committed
        }

        val first = actor.submit(spec("open", SplitInputPriority.OPEN, joinKey = "open", body = body))
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        val second = actor.submit(spec("open", SplitInputPriority.OPEN, joinKey = "open", body = body))

        assertSame("the second tap must hold the very same ticket", first, second)
        release.countDown()
        assertEquals(SplitOutcome.Committed, first.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, second.outcome)
        assertEquals(1, runs.get())
        assertEquals(listOf("open"), executed.toList())
    }

    @Test
    fun aQueuedRequestIsJoinedTooAndRunsOnlyOnce() {
        // K4, контракт 1.3.7: присоединяется и к ещё не начатой операции
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)

        val first = actor.submit(spec("open", SplitInputPriority.OPEN, joinKey = "open"))
        val second = actor.submit(spec("open", SplitInputPriority.OPEN, joinKey = "open"))
        release.countDown()

        assertSame(first, second)
        assertEquals(SplitOutcome.Committed, first.await(AWAIT_MS))
        assertEquals(listOf("blocker", "open"), executed.toList())
    }

    @Test
    fun passiveHintsCoalesceToTheLastOne() {
        // K5, контракт §4.7, §7: пассивные hints схлопываются до одного признака
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)

        val hints = (1..10).map { index ->
            actor.submit(spec("hint-$index", SplitInputPriority.HINT, coalesceKey = "reconcile"))
        }
        release.countDown()

        assertEquals(SplitOutcome.Committed, hints.last().await(AWAIT_MS))
        hints.dropLast(1).forEach { ticket ->
            assertEquals(
                "${ticket.label} should have been replaced",
                SplitOutcome.Cancelled(SplitCancelReason.COALESCED),
                ticket.outcome,
            )
        }
        assertEquals(listOf("blocker", "hint-10"), executed.toList())
    }

    @Test
    fun anExplicitTapOvertakesAStormOfPassiveHints() {
        // K5, сценарий 22: SELECT не стоит в очереди из ста reconcile и не ждёт их
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)

        val hints = (0 until 100).map { index ->
            actor.submit(spec("hint-$index", SplitInputPriority.HINT, coalesceKey = "reconcile"))
        }
        val select = actor.submit(spec("select", SplitInputPriority.SELECT, joinKey = SplitPane.PRIMARY))
        release.countDown()

        assertEquals(SplitOutcome.Committed, select.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, hints.last().await(AWAIT_MS))
        assertEquals(
            "one reconcile survives the storm, and the tap runs before it",
            listOf("blocker", "select", "hint-99"),
            executed.toList(),
        )
    }

    @Test
    fun aCoalescedKeyNeverReplacesTheOperationInFlight() {
        // контракт §7: коалесцирование живёт в очереди; начатая работа не подменяется
        val started = CountDownLatch(1)
        val release = gate()
        val running = actor.submit(
            spec("hint-running", SplitInputPriority.HINT, coalesceKey = "reconcile") {
                started.countDown()
                release.await()
                executed += "hint-running"
                SplitOutcome.Committed
            },
        )
        assertTrue(started.await(AWAIT_MS, TimeUnit.MILLISECONDS))

        val queuedHint = actor.submit(spec("hint-queued", SplitInputPriority.HINT, coalesceKey = "reconcile"))
        release.countDown()

        assertEquals(SplitOutcome.Committed, running.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, queuedHint.await(AWAIT_MS))
        assertEquals(listOf("hint-running", "hint-queued"), executed.toList())
    }

    @Test
    fun aCancelledOperationIsNeverReplayed() {
        // контракт §4: проигравшее событие не воспроизводится из очереди
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)
        val open = actor.submit(spec("open", SplitInputPriority.OPEN))
        actor.submit(spec("disable", SplitInputPriority.DISABLE))
        release.countDown()

        val drain = actor.submit(spec("drain", SplitInputPriority.HINT))
        assertEquals(SplitOutcome.Committed, drain.await(AWAIT_MS))
        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.DISABLE), open.outcome)
        assertEquals(listOf("blocker", "disable", "drain"), executed.toList())
    }

    @Test
    fun aThrowingOperationFailsItsTicketAndTheWorkerSurvives() {
        // U5: сбой видим и ограничен одной операцией
        val boom = actor.submit(
            spec("boom", SplitInputPriority.OPEN) { throw IllegalStateException("adb is gone") },
        )
        val after = actor.submit(spec("after", SplitInputPriority.OPEN))

        assertEquals(SplitOutcome.Failed("adb is gone"), boom.await(AWAIT_MS))
        assertEquals(SplitOutcome.Committed, after.await(AWAIT_MS))
        assertEquals(listOf("after"), executed.toList())
    }

    @Test
    fun anOperationThatCancelsItselfReportsItsOwnReason() {
        // инвариант 10: SplitOperationCancelled из run - это Cancelled, а не Failed
        val ticket = actor.submit(
            spec("nav", SplitInputPriority.NAV) { op ->
                op.token.cancel("navigation lease lost")
                op.fence()
                SplitOutcome.Committed
            },
        )

        assertEquals(SplitOutcome.Cancelled("navigation lease lost"), ticket.await(AWAIT_MS))
    }

    @Test
    fun awaitReportsNothingWhileTheOperationIsStillRunning() {
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)
        val queuedTicket = actor.submit(spec("open", SplitInputPriority.OPEN))

        assertNull(queuedTicket.await(1L))

        release.countDown()
        assertEquals(SplitOutcome.Committed, queuedTicket.await(AWAIT_MS))
    }

    @Test
    fun shutdownCancelsEverythingStillOwed() {
        val release = blockedInFlight("blocker", SplitInputPriority.NAV)
        val queuedTicket = actor.submit(spec("open", SplitInputPriority.OPEN))

        release.countDown()
        actor.shutdown()

        assertEquals(SplitOutcome.Cancelled(SplitCancelReason.SHUTDOWN), queuedTicket.outcome)
        assertEquals(
            SplitOutcome.Cancelled(SplitCancelReason.SHUTDOWN),
            actor.submit(spec("late", SplitInputPriority.OPEN)).outcome,
        )
    }

    /** Occupies the single worker until the returned latch is opened. */
    private fun blockedInFlight(label: String, priority: SplitInputPriority): CountDownLatch {
        val started = CountDownLatch(1)
        val release = gate()
        actor.submit(
            spec(label, priority) {
                started.countDown()
                release.await()
                executed += label
                SplitOutcome.Committed
            },
        )
        assertTrue("$label never reached the worker", started.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        return release
    }

    private fun gate(): CountDownLatch = CountDownLatch(1).also { gates += it }

    private fun shell(command: String): String {
        commands += command
        return ""
    }

    private fun spec(
        name: String,
        priority: SplitInputPriority,
        durationMs: Long = 60_000L,
        joinKey: Any? = null,
        coalesceKey: Any? = null,
        body: (SplitOperationContext) -> SplitOutcome = {
            executed += name
            SplitOutcome.Committed
        },
    ): SplitOperationSpec = TestSpec(name, priority, durationMs, joinKey, coalesceKey, body)

    private class TestSpec(
        override val label: String,
        override val priority: SplitInputPriority,
        override val durationMs: Long,
        override val joinKey: Any?,
        override val coalesceKey: Any?,
        private val body: (SplitOperationContext) -> SplitOutcome,
    ) : SplitOperationSpec {
        override fun run(op: SplitOperationContext): SplitOutcome = body(op)
    }

    private companion object {
        const val AWAIT_MS = 5_000L
    }
}

/**
 * A clock that only moves when a test moves it, and whose timers fire on the test thread.
 *
 * [nowMs] is read by the actor worker while the test advances time, hence the volatile field and
 * the guarded timer list; the determinism comes from the fact that nothing here is time-driven.
 */
internal class FakeSplitClock(start: Long = 0L) : SplitClock {

    private class Timer(val atMs: Long, val action: () -> Unit)

    @Volatile
    private var now: Long = start
    private val timers = mutableListOf<Timer>()

    override fun nowMs(): Long = now

    override fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable {
        val timer = Timer(now + delayMs, action)
        synchronized(timers) { timers += timer }
        return SplitCancellable { synchronized(timers) { timers.remove(timer) } }
    }

    fun advance(byMs: Long) {
        now += byMs
        while (true) {
            val due = synchronized(timers) {
                timers.filter { it.atMs <= now }.minByOrNull(Timer::atMs)?.also(timers::remove)
            } ?: return
            due.action()
        }
    }

    fun pendingTimers(): Int = synchronized(timers) { timers.size }
}
