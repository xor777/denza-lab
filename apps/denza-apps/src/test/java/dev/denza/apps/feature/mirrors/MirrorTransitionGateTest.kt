package dev.denza.apps.feature.mirrors

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTransitionGateTest {
    @Test
    fun stopWaitsForInFlightShowAndMakesHideTheFinalCommand() {
        val gate = MirrorTransitionGate()
        val showEntered = CountDownLatch(1)
        val allowShow = CountDownLatch(1)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val run = checkNotNull(gate.start(0L))

        val poll = thread {
            gate.runIf(run) {
                showEntered.countDown()
                allowShow.await(1, TimeUnit.SECONDS)
                commands += "show"
            }
        }
        assertTrue(showEntered.await(1, TimeUnit.SECONDS))
        val stop = thread { gate.stop { commands += "hide" } }

        allowShow.countDown()
        poll.join(1_000L)
        stop.join(1_000L)

        assertEquals(listOf("show", "hide"), commands.toList())
        assertFalse(gate.isRunning)
        assertFalse(gate.runIf(run) { commands += "late-show" })
        assertEquals(listOf("show", "hide"), commands.toList())
    }

    @Test
    fun duplicateStartDoesNotCreateASecondLifecycle() {
        val gate = MirrorTransitionGate()

        assertNotNull(gate.start(0L))
        assertNull(gate.start(0L))
    }

    /**
     * Round-2 finding 5. The poll's shell read comes back at 3 990 ms, just inside the deadline,
     * and the poll applies what it saw; the watchdog looks at 4 000 ms. Before 2026-11-19 the poll
     * released the gate after applying and recorded the read on the next line, so a watchdog that
     * took the gate in between read the old timestamp, closed the camera the poll had just kept
     * and published «Нет доступа». The watchdog is paused here exactly there: it has to wait for
     * the poll's critical section, and when it gets the lock the read is recorded.
     */
    @Test
    fun theWatchdogNeverActsOnTheReadBeforeAPollWhoseTransitionIsApplied() {
        val gate = MirrorTransitionGate()
        val run = checkNotNull(gate.start(0L))
        val clock = AtomicLong(3_990L)
        val applying = CountDownLatch(1)
        val finishApply = CountDownLatch(1)
        val teardowns = AtomicInteger()

        val poll = thread(name = "poll") {
            val now = clock.get()
            gate.observe(run, now) {
                applying.countDown()
                finishApply.await(1, TimeUnit.SECONDS)
            }
        }
        assertTrue(applying.await(1, TimeUnit.SECONDS))
        clock.set(4_000L)
        val watchdog = thread(name = "watchdog") {
            gate.checkDeadline(run, clock::get) { teardowns.incrementAndGet() }
        }
        awaitBlocked(watchdog)
        finishApply.countDown()
        poll.join(1_000L)
        watchdog.join(1_000L)

        assertEquals("the read was recorded with its transition", 0, teardowns.get())
        // The deadline still exists: four seconds after that read, nothing more read, it fires.
        clock.set(3_990L + MirrorObservationWatch.DEADLINE_MS)
        assertTrue(gate.checkDeadline(run, clock::get) { teardowns.incrementAndGet() })
        assertEquals(1, teardowns.get())
    }

    @Test
    fun theWatchdogThatTookTheLockFirstActsAndTheLateReadThenApplies() {
        // The other order is a real deadline: nothing was applied when it looked.
        val gate = MirrorTransitionGate()
        val run = checkNotNull(gate.start(0L))
        val events = mutableListOf<String>()
        assertTrue(gate.checkDeadline(run, { 4_000L }) { events += "deadline" })
        assertTrue(gate.observe(run, 3_990L) { events += "apply" })
        assertEquals(listOf("deadline", "apply"), events)
        assertFalse(gate.checkDeadline(run, { 4_000L }) { events += "deadline again" })
    }

    @Test
    fun aStoppedRunCannotRefreshItsSuccessorsDeadline() {
        val gate = MirrorTransitionGate()
        val first = checkNotNull(gate.start(0L))
        gate.stop { }
        val second = checkNotNull(gate.start(1_000L))
        val applied = mutableListOf<String>()

        // The first run's poll had a shell read in flight across the stop and the new start.
        assertFalse(gate.observe(first, 4_900L) { applied += "stale" })
        assertFalse(gate.readFailed(first, "IOException", 4_900L) { applied += "stale status" })
        assertFalse(first.isCurrent())

        assertEquals(emptyList<String>(), applied)
        var lost: String? = null
        assertTrue(gate.checkDeadline(second, { 5_000L }) { lost = it ?: "no answer" })
        assertEquals("no answer", lost)
    }

    @Test
    fun aFailedReadWithinTheDeadlineIsTheStatusAndPastItTheDeadlineIs() {
        val gate = MirrorTransitionGate()
        val run = checkNotNull(gate.start(0L))
        val statuses = mutableListOf<String>()
        gate.readFailed(run, "SocketTimeoutException", 2_500L) { statuses += "error" }
        gate.readFailed(run, "SocketTimeoutException", 4_100L) { statuses += "error late" }
        assertEquals(listOf("error"), statuses)
        var lastError: String? = null
        assertTrue(gate.checkDeadline(run, { 4_100L }) { lastError = it })
        assertEquals("SocketTimeoutException", lastError)
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < deadline) { "${thread.name} never waited for the gate" }
            Thread.onSpinWait()
        }
    }
}
