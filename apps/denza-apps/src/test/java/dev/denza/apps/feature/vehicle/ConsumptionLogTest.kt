package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(log.current)
    }

    @Test
    fun aBarClosesEveryHalfKilometre() {
        val log = ConsumptionLog()
        // 20 kW held for 5 x 6 s covers 0.5 km and spends 0.1667 kWh.
        log.drive(steps = 5, powerKw = 20.0)
        assertEquals(1, log.buckets.size)
        assertEquals(33.33, log.buckets.single(), 0.01)
    }

    @Test
    fun regenerationMakesABarNegative() {
        val log = ConsumptionLog()
        log.drive(steps = 5, powerKw = -12.0)
        assertEquals(-20.0, log.buckets.single(), 0.01)
    }

    @Test
    fun theOpenBarReadsOnceItCoversEnoughRoad() {
        val log = ConsumptionLog()
        log.sample(100.0, 24.0, 0.0)
        log.sample(100.05, 24.0, 3.0)
        // 50 m is not enough to call it consumption yet.
        assertNull(log.current)
        log.sample(100.1, 24.0, 3.0)
        assertEquals(40.0, log.current!!, 0.01)
    }

    @Test
    fun aGapInSamplingDoesNotIntegrateEnergyItDidNotSee() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        // The panel was away for four minutes; the odometer moved, but the
        // energy for that road was never sampled.
        log.sample(100.4, 30.0, 240.0)
        log.sample(100.5, 30.0, 6.0)
        assertEquals(1, log.buckets.size)
        // Only the last 6 s of energy is in the bar, spread over 0.5 km.
        assertEquals(30.0 * 6.0 / 3600.0 / 0.5 * 100.0, log.buckets.single(), 0.01)
    }

    @Test
    fun anOdometerJumpDropsTheOpenBarInsteadOfInventingConsumption() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        log.sample(100.2, 30.0, 6.0)
        // Driven with the panel closed: the odometer is 40 km further on.
        log.sample(140.0, 30.0, 6.0)
        assertTrue(log.buckets.isEmpty())
        assertNull(log.current)
        // Accumulation resumes from the new anchor.
        log.drive(steps = 5, powerKw = 20.0, fromKm = 140.0)
        assertEquals(1, log.buckets.size)
    }

    @Test
    fun aMissingOdometerReadPausesTheBarWithoutBreakingIt() {
        val log = ConsumptionLog()
        log.sample(100.0, 20.0, 0.0)
        log.sample(null, 20.0, 6.0)
        log.sample(100.1, 20.0, 6.0)
        assertEquals(20.0 * 6.0 / 3600.0 / 0.1 * 100.0, log.current!!, 0.01)
    }

    @Test
    fun theWindowKeepsOnlyTheMostRecentBars() {
        val log = ConsumptionLog(capacity = 3)
        var odometer = 100.0
        repeat(5) { index ->
            log.sample(odometer, (index + 1) * 10.0, 0.0)
            repeat(5) {
                odometer += 0.1
                log.sample(odometer, (index + 1) * 10.0, 6.0)
            }
        }
        assertEquals(3, log.buckets.size)
        // The oldest bars fell off; the newest is the last power level.
        assertEquals(50.0 * 30.0 / 3600.0 / 0.5 * 100.0, log.buckets.last(), 0.1)
    }

    @Test
    fun resetForgetsEverything() {
        val log = ConsumptionLog()
        log.drive(steps = 5, powerKw = 20.0)
        log.reset()
        assertTrue(log.buckets.isEmpty())
        assertNull(log.current)
    }
}
