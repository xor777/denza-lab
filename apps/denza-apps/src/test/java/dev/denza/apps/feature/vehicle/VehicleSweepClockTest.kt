package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The interval two consumers integrate over, and the one thing that must never be inside it.
 *
 * The backoff after a failed sweep was booked as observed time: nothing reset the clock in the
 * error path, so the next successful read carried the whole outage - about seven and a half seconds
 * on the first failure, which is under the eight-second gap both the consumption log and the trip
 * ledger refuse. One 200 kW sample then wrote 0.4 kWh of unwatched time into both.
 */
class VehicleSweepClockTest {

    @Test
    fun theFirstSweepOfALoopHasNoIntervalBehindIt() {
        val clock = VehicleSweepClock()
        assertEquals(0.0, clock.tick(10_000L), 1e-9)
        assertEquals(0.25, clock.tick(10_250L), 1e-9)
    }

    @Test
    fun anInterruptedSweepIsNotTimeAnybodyWatched() {
        val clock = VehicleSweepClock()
        clock.tick(10_000L)
        clock.tick(10_250L)

        // The shell threw, the loop closed it, published unavailable and waited four seconds.
        clock.interrupted()

        assertEquals("the outage is not an interval", 0.0, clock.tick(17_600L), 1e-9)
        assertEquals("and the one after it is", 0.25, clock.tick(17_850L), 1e-9)
    }

    @Test
    fun aClockThatWentBackwardsIsNotAnIntervalEither() {
        val clock = VehicleSweepClock()
        clock.tick(10_000L)
        assertEquals(0.0, clock.tick(9_000L), 1e-9)
    }
}
