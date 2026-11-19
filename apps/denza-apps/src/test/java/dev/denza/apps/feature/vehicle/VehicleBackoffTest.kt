package dev.denza.apps.feature.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll loop's wait after a failed read, on virtual time.
 *
 * The scenario behind the wake: the hub has failed for a while - adbd not up yet at boot, or the key
 * not confirmed - and sleeps a long backoff; the car comes back and the driver brings the Contour
 * up. [VehicleWatcher.LEDGER] keeps that loop alive, so the screen's claim cannot start a fresh one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleBackoffTest {

    @Test
    fun theWaitDoublesUpToAMinute() = runTest {
        val backoff = VehicleBackoff()
        val waits = List(6) {
            val before = currentTime
            assertFalse(backoff.await())
            currentTime - before
        }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun aScreenThatStartsWatchingEndsTheWaitAndStartsItOver() = runTest {
        val backoff = VehicleBackoff()
        repeat(4) { backoff.await() }
        assertEquals(VehicleBackoff.MAX_MS, backoff.nextMs)

        val started = currentTime
        val waiting = async { backoff.await() }
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse("still asleep before anyone looks", waiting.isCompleted)

        backoff.wake()
        runCurrent()

        assertTrue("woken", waiting.await())
        assertEquals("not the minute it was sleeping", 1_500L, currentTime - started)
        assertEquals("and the next failure waits the first wait again", VehicleBackoff.FIRST_MS, backoff.nextMs)
    }

    @Test
    fun aScreenThatAppearsWhileTheReadIsFailingIsNotLost() = runTest {
        // The claim lands while the shell call is still timing out, before the wait has begun.
        val backoff = VehicleBackoff()
        backoff.wake()

        val started = currentTime
        assertTrue(backoff.await())
        assertEquals("the retry is at once", 0L, currentTime - started)
    }

    @Test
    fun aWakeFromAGoodStretchDoesNotCutALaterBackoff() = runTest {
        val backoff = VehicleBackoff()
        backoff.wake()
        // The read in flight answered: the screen got its sweep and owes nothing.
        backoff.reset()

        val started = currentTime
        assertFalse(backoff.await())
        assertEquals(VehicleBackoff.FIRST_MS, currentTime - started)
    }

    /**
     * The hub's sentinel-only branch. A shell that answers every id with a sentinel answered, and
     * the loop used to reset the backoff on the reply and ask again at its cadence for as long as
     * the bus slept. The wait follows what the sweep decoded: a quiet bus is polled at the cadence
     * for the link's horizon, and backed off from after it.
     */
    @Test
    fun anAnsweringShellOverASilentBusIsBackedOffFrom() {
        val batch = VehicleSignal.HOT + VehicleSignal.EVENT
        // Every hot id `-10011`, and the cluster's warning slot answering from its cache.
        val sentinels = batch.indices.joinToString("\n") { index ->
            val word = if (batch[index] == VehicleSignal.TEXT_WARNING) "00000000" else "ffffd8e5"
            "@@$index\nResult: Parcel(00000000 $word)"
        }
        val parsed = AutoserviceShell.parse(sentinels, batch)
        assertTrue("the slot answered", VehicleSignal.TEXT_WARNING in parsed)
        assertFalse("but nothing about the car did", VehicleQuietBus.fresh(parsed))

        val quiet = VehicleQuietBus()
        var now = 0L
        var backedOff = false
        while (now < 60_000L && !backedOff) {
            backedOff = quiet.backOff(VehicleQuietBus.fresh(parsed), now)
            if (!backedOff) now += VehicleSweepCadence.HOT_INTERVAL_MS + VehicleSweepCadence.SHELL_BATCH_MS
        }
        assertTrue("a sleeping bus is backed off from", backedOff)
        assertTrue("after the link's horizon, not before: $now", now >= VehicleLink.CLOSE_AFTER_MS)
        assertTrue("and every quiet sweep after it", quiet.backOff(false, now + 4_000L))

        // A reading of the car ends it.
        assertTrue(VehicleQuietBus.fresh(mapOf(VehicleSignal.POWER_KW to 12.0)))
        assertFalse(quiet.backOff(true, now + 8_000L))
        assertFalse("and the next quiet sweep is a blip again", quiet.backOff(false, now + 8_250L))
    }

    @Test
    fun aQuietBusWaitsNoLongerThanTheGapTheIntegralsAccept() = runTest {
        val backoff = VehicleBackoff()
        val waits = List(4) {
            val before = currentTime
            backoff.await(VehicleQuietBus.MAX_WAIT_MS)
            currentTime - before
        }
        assertEquals(listOf(4_000L, 8_000L, 8_000L, 8_000L), waits)
    }

    @Test
    fun anAnswerStartsTheBackoffOver() = runTest {
        val backoff = VehicleBackoff()
        repeat(3) { backoff.await() }
        backoff.reset()
        assertEquals(VehicleBackoff.FIRST_MS, backoff.nextMs)
    }
}
