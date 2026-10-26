package ru.adbgw.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay loop's pacing in virtual time: the same shape as `GatewaySupervisor.relayLoop` (mark,
 * attempt, pause after a failure), driven by scripted outcomes. Times are virtual milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRetryPacerTest {
    private enum class Outcome { Failed, PortBusy, Connected }

    @Test
    fun `a relay that keeps failing backs off from one second to the fifteen-second cap`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        val loop = launch { relayLoop(pacer, attempts) { Outcome.Failed } }

        advanceTimeBy(200_000)
        loop.cancelAndJoin()

        assertEquals(
            listOf(0L, 1_000, 3_000, 7_000, 15_000) + (30_000L..195_000L step 15_000).toList(),
            attempts,
        )
    }

    @Test
    fun `a new network ends the backoff wait at once and restarts the backoff`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        val loop = launch { relayLoop(pacer, attempts) { Outcome.Failed } }

        // Wi-Fi off: by 70 s the loop sleeps the capped 15 s from 60 s to 75 s.
        advanceTimeBy(70_000)
        assertEquals(60_000L, attempts.last())

        // Wi-Fi back: onAvailable -> networkChanged() -> wake().
        pacer.wake()
        runCurrent()
        assertEquals("the attempt follows the network at once", 70_000L, attempts.last())

        advanceTimeBy(3_001)
        loop.cancelAndJoin()
        assertEquals("the backoff starts again from one second", listOf(70_000L, 71_000, 73_000), attempts.takeLast(3))
    }

    @Test
    fun `a wake during an attempt makes the next attempt immediate`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        // Each attempt takes 5 s (a connect that times out on a dying network).
        val loop = launch { relayLoop(pacer, attempts) { delay(5_000); Outcome.Failed } }

        advanceTimeBy(2_000)
        pacer.wake() // the network changed while the attempt was in flight
        advanceTimeBy(3_001)
        loop.cancelAndJoin()

        assertEquals(listOf(0L, 5_000), attempts)
    }

    @Test
    fun `a validated network wakes the loop the same way`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        val loop = launch { relayLoop(pacer, attempts) { Outcome.Failed } }

        advanceTimeBy(20_000) // attempts at 0, 1, 3, 7, 15 s; asleep until 31 s
        pacer.wake() // onCapabilitiesChanged(VALIDATED) -> networkValidated()
        runCurrent()
        loop.cancelAndJoin()

        assertEquals(listOf(15_000L, 20_000), attempts.takeLast(2))
    }

    @Test
    fun `the waiting-for-network poll still runs every second and a wake cuts it short`() = runTest {
        val pacer = pacer()
        val polls = mutableListOf<Long>()
        val loop = launch {
            while (true) {
                val mark = pacer.mark()
                polls += currentTime
                pacer.pause(1_000, mark) // WaitingForNetwork: no endpoint or inner server yet
            }
        }

        advanceTimeBy(2_500)
        pacer.wake()
        runCurrent()
        advanceTimeBy(1_001)
        loop.cancelAndJoin()

        assertEquals(listOf(0L, 1_000, 2_000, 2_500, 3_500), polls)
    }

    @Test
    fun `a pause reports whether a wake or the clock ended it`() = runTest {
        val pacer = pacer()
        assertFalse(pacer.pause(1_000, pacer.mark()))
        assertEquals(1_000L, currentTime)

        val mark = pacer.mark()
        pacer.wake()
        assertTrue("a wake before the pause still ends it", pacer.pause(60_000, mark))
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `a relay that still holds the old port is retried every 2500 ms for 30 s, then backs off`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        val loop = launch { relayLoop(pacer, attempts) { Outcome.PortBusy } }

        advanceTimeBy(37_001)
        loop.cancelAndJoin()

        val shortRetries = (0..12).map { it * 2_500L } // 0 .. 30 s
        assertEquals(shortRetries + listOf(31_000L, 33_000, 37_000), attempts)
    }

    @Test
    fun `the car rebinds within one short retry of the relay dropping the stale session`() = runTest {
        val pacer = pacer()
        val attempts = mutableListOf<Long>()
        // The relay keeps the old session for 15 s (ClientAlive 5 s x 3), then frees the port.
        val loop = launch {
            relayLoop(pacer, attempts) { if (currentTime < 15_000) Outcome.PortBusy else Outcome.Connected }
        }

        advanceTimeBy(60_000)
        loop.join()

        assertEquals(15_000L, attempts.last())
        assertEquals(7, attempts.size)
    }

    @Test
    fun `the short window starts afresh after a working tunnel`() = runTest {
        var now = 0L
        val pacer = RelayRetryPacer(RetryBackoff(jitterRatio = 0.0), clock = { now })

        assertEquals(2_500L, pacer.nextDelayMillis(portBusy = true))
        now = 30_000
        assertEquals("the window ran out", 1_000L, pacer.nextDelayMillis(portBusy = true))
        pacer.connected()
        now = 40_000
        assertEquals(2_500L, pacer.nextDelayMillis(portBusy = true))
        assertEquals("other failures keep the backoff", 1_000L, pacer.nextDelayMillis(portBusy = false))
    }

    private fun TestScope.pacer() =
        RelayRetryPacer(RetryBackoff(jitterRatio = 0.0), clock = { testScheduler.currentTime })

    /** The supervisor's loop with the network and the relay replaced by [attempt]. */
    private suspend fun TestScope.relayLoop(
        pacer: RelayRetryPacer,
        attempts: MutableList<Long>,
        attempt: suspend () -> Outcome,
    ) {
        while (true) {
            val mark = pacer.mark()
            attempts += currentTime
            when (attempt()) {
                Outcome.Connected -> {
                    pacer.connected()
                    return
                }
                Outcome.PortBusy -> pacer.pause(pacer.nextDelayMillis(portBusy = true), mark)
                Outcome.Failed -> pacer.pause(pacer.nextDelayMillis(portBusy = false), mark)
            }
        }
    }
}
