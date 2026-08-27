package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionLogTest {

    /** 0.1 km every 6 s is 60 km/h, the cadence the hub samples at. */
    private fun ConsumptionLog.drive(steps: Int, powerKw: Double, fromKm: Double = 100.0) {
        sample(fromKm, powerKw, 0.0)
        repeat(steps) { index ->
            sample(fromKm + (index + 1) * 0.1, powerKw, 6.0)
        }
    }

    @Test
    fun firstSampleOnlyAnchorsTheOdometer() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 6.0)
        assertTrue(log.buckets.isEmpty())
    }

    @Test
    fun aBarClosesEveryHundredMetres() {
        val log = ConsumptionLog()
        // One odometer tick is the bar now: 20 kW for 6 s over 0.1 km.
        log.drive(steps = 2, powerKw = 20.0)
        assertEquals(2, log.buckets.size)
        assertEquals(33.33, log.buckets.first(), 0.01)
    }

    @Test
    fun regenerationMakesABarNegative() {
        val log = ConsumptionLog()
        log.drive(steps = 2, powerKw = -12.0)
        assertEquals(-20.0, log.buckets.first(), 0.01)
    }

    @Test
    fun standingStillChangesTheDashboardStateWithoutDroppingEnergy() {
        val log = ConsumptionLog()
        log.drive(steps = 4, powerKw = 20.0)
        // Parked on charge: the odometer holds while power goes negative.
        repeat(10) { log.sample(100.4, -2.0, 6.0) }
        assertTrue(log.stationary)
    }

    @Test
    fun energySpentStandingStillStaysInTheBar() {
        val log = ConsumptionLog()
        log.sample(100.0, 0.0, 0.0)
        log.sample(100.1, 0.0, 6.0)
        // Thirty seconds of idling at 6 kW, then the bar closes.
        repeat(5) { log.sample(100.1, 6.0, 6.0) }
        log.sample(100.2, 0.0, 6.0)
        // Two bars now: the first tick, then the one that carries the idling.
        assertEquals(2, log.buckets.size)
        assertEquals(0.05 / 0.1 * 100.0, log.buckets.last(), 0.01)
    }

    @Test
    fun aGapInSamplingDoesNotIntegrateEnergyItDidNotSee() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        // The dashboard was away for four minutes; the odometer moved, but the
        // energy for that road was never sampled.
        log.sample(100.4, 30.0, 240.0)
        assertEquals(0.0, log.buckets.single(), 1e-9)
    }

    @Test
    fun anOdometerJumpDropsTheOpenWorkInsteadOfInventingConsumption() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        log.sample(100.1, 30.0, 6.0)
        // Driven with the dashboard closed: the odometer is 40 km further on.
        log.sample(140.0, 30.0, 6.0)
        // Forty kilometres of road nobody watched must not become a bar. The one
        // bar that had honestly closed before the jump stays.
        assertEquals(1, log.buckets.size)
        // Accumulation resumes from the new anchor.
        log.drive(steps = 2, powerKw = 20.0, fromKm = 140.0)
        assertEquals(3, log.buckets.size)
    }

    @Test
    fun aMissingOdometerReadPausesTheBarWithoutBreakingIt() {
        val log = ConsumptionLog()
        log.sample(100.0, 20.0, 0.0)
        log.sample(null, 20.0, 6.0)
        log.sample(100.1, 20.0, 6.0)
        assertEquals(20.0 * 6.0 / 3600.0 / 0.1 * 100.0, log.buckets.single(), 0.01)
    }

    @Test
    fun theWindowKeepsOnlyTheMostRecentBars() {
        val log = ConsumptionLog(capacity = 3)
        var odometer = 100.0
        repeat(5) { index ->
            log.sample(odometer, (index + 1) * 10.0, 0.0)
            repeat(2) {
                odometer += 0.1
                log.sample(odometer, (index + 1) * 10.0, 6.0)
            }
        }
        assertEquals(3, log.buckets.size)
        // The oldest bars fell off; the newest is the last power level.
        assertEquals(50.0 * 6.0 / 3600.0 / 0.1 * 100.0, log.buckets.last(), 0.1)
    }

    @Test
    fun everyClosedBarIsOfferedToWhoeverIsKeepingThem() {
        val seen = mutableListOf<ConsumptionSample>()
        val log = ConsumptionLog(onBucketClosed = seen::add)
        log.drive(steps = 3, powerKw = 20.0)
        assertEquals(3, seen.size)
        // The odometer is carried with the value, which is what lets a journal
        // decide later whether a bar is still part of the last thirty kilometres.
        assertEquals(100.1, seen.first().odometerKm, 1e-6)
        assertEquals(log.buckets.last(), seen.last().value, 1e-9)
    }

    @Test
    fun aJournalIsSeededOnlyWithRoadTheCarHasJustCovered() {
        val log = ConsumptionLog()
        val restored = log.restore(
            samples = listOf(
                ConsumptionSample(900.0, 11.0),   // forty kilometres ago
                ConsumptionSample(939.5, 22.0),
                ConsumptionSample(939.9, 33.0),
            ),
            odometerKm = 940.0,
            windowKm = 30.0,
        )
        assertTrue(restored)
        assertEquals(listOf(22.0, 33.0), log.buckets)
    }

    @Test
    fun aJournalFromAheadOfTheCarIsRefusedRatherThanTrusted() {
        val log = ConsumptionLog()
        // An odometer that went backwards means this journal is not this car's.
        val restored = log.restore(
            samples = listOf(ConsumptionSample(5000.0, 18.0)),
            odometerKm = 940.0,
            windowKm = 30.0,
        )
        assertFalse(restored)
    }

    @Test
    fun resetForgetsEverything() {
        val log = ConsumptionLog()
        log.drive(steps = 2, powerKw = 20.0)
        log.reset()
        assertTrue(log.buckets.isEmpty())
    }
}
