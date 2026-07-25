package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssTripAccumulatorTest {

    /** Feed one fix with sane defaults so each test only names what it varies. */
    private fun GnssTripAccumulator.fix(
        elapsed: Double,
        speed: Double,
        lat: Double = 55.0,
        lon: Double = 37.0,
        alt: Double = 0.0,
        hasAlt: Boolean = false,
        vacc: Double = 5.0,
        hasVacc: Boolean = true,
        dt: Double = 1.0,
        accumulate: Boolean = true,
    ): Boolean = onFix(lat, lon, alt, hasAlt, vacc, hasVacc, speed, elapsed, dt, accumulate)

    @Test
    fun haversineMatchesOneDegreeAtEquator() {
        val d = GnssTripAccumulator.haversine(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, d, 500.0)
    }

    @Test
    fun accumulatesDistanceOnlyWhileMoving() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, lon = 37.0)
        acc.fix(elapsed = 1.0, speed = 10.0, lon = 37.001)
        val moving = acc.distanceMeters
        assertTrue("distance=$moving", moving in 55.0..75.0)

        // A stationary jump (speed below threshold) must not add distance.
        acc.fix(elapsed = 2.0, speed = 0.0, lon = 37.010)
        assertEquals(moving, acc.distanceMeters, 1e-6)
    }

    @Test
    fun distanceWaitsForTheTrip() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, lon = 37.0, accumulate = false)
        acc.fix(elapsed = 1.0, speed = 10.0, lon = 37.001, accumulate = false)
        assertEquals(0.0, acc.distanceMeters, 1e-9)
    }

    @Test
    fun accumulatesClimbOnARealHillAndSmoothsAltitude() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, alt = 100.0, hasAlt = true)
        var prev = acc.smoothedAltitude
        repeat(30) { i ->
            acc.fix(
                elapsed = (i + 1).toDouble(), speed = 10.0,
                lon = 37.0 + i * 0.0001,
                alt = 100.0 + (i + 1) * 10.0, hasAlt = true,
            )
            assertTrue(acc.smoothedAltitude >= prev)
            prev = acc.smoothedAltitude
        }
        assertTrue("climb=${acc.tripClimbMeters}", acc.tripClimbMeters > 100.0)
        assertTrue("vario=${acc.variometer}", acc.variometer > 0.0)
    }

    @Test
    fun variometerGoesNegativeOnDescent() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, alt = 500.0, hasAlt = true)
        repeat(20) { i ->
            acc.fix(
                elapsed = (i + 1).toDouble(), speed = 10.0,
                alt = 500.0 - (i + 1) * 8.0, hasAlt = true,
            )
        }
        assertTrue("vario=${acc.variometer}", acc.variometer < 0.0)
        assertEquals(0.0, acc.tripClimbMeters, 1e-9)
    }

    @Test
    fun climbStairStepIgnoresRandomWalkButKeepsRealHills() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, alt = 100.0, hasAlt = true)
        // +-2 m raw wander while driving: below the 3 m step, no climb.
        repeat(40) { i ->
            val alt = 100.0 + if (i % 2 == 0) 2.0 else -2.0
            acc.fix(elapsed = (i + 1).toDouble(), speed = 10.0, alt = alt, hasAlt = true)
        }
        assertEquals(0.0, acc.tripClimbMeters, 1e-9)

        // A real +50 m hill counts (minus at most one sub-step residual).
        repeat(10) { i ->
            acc.fix(elapsed = 41.0 + i, speed = 10.0, alt = 100.0 + (i + 1) * 5.0, hasAlt = true)
        }
        repeat(10) { i ->
            acc.fix(elapsed = 51.0 + i, speed = 10.0, alt = 150.0, hasAlt = true)
        }
        assertTrue("climb=${acc.tripClimbMeters}", acc.tripClimbMeters in 40.0..51.0)
    }

    @Test
    fun seedRejectsPoorVerticalAccuracy() {
        val acc = GnssTripAccumulator()
        // Cold-fix garbage with terrible vertical accuracy must not seed.
        acc.fix(elapsed = 0.0, speed = 0.0, alt = 4800.0, hasAlt = true, vacc = 150.0)
        assertFalse(acc.hasAltitude)
        // A decent fix seeds directly.
        acc.fix(elapsed = 1.0, speed = 0.0, alt = 120.0, hasAlt = true, vacc = 8.0)
        assertTrue(acc.hasAltitude)
        assertEquals(120.0, acc.smoothedAltitude, 1e-9)
    }

    @Test
    fun seedWithoutReportedAccuracyNeedsConsistentFixes() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 0.0, alt = 101.0, hasAlt = true, hasVacc = false)
        acc.fix(elapsed = 1.0, speed = 0.0, alt = 99.0, hasAlt = true, hasVacc = false)
        assertFalse(acc.hasAltitude)
        // An outlier resets the consistency run.
        acc.fix(elapsed = 2.0, speed = 0.0, alt = 350.0, hasAlt = true, hasVacc = false)
        acc.fix(elapsed = 3.0, speed = 0.0, alt = 100.0, hasAlt = true, hasVacc = false)
        acc.fix(elapsed = 4.0, speed = 0.0, alt = 101.0, hasAlt = true, hasVacc = false)
        assertFalse(acc.hasAltitude)
        acc.fix(elapsed = 5.0, speed = 0.0, alt = 100.0, hasAlt = true, hasVacc = false)
        assertTrue(acc.hasAltitude)
        assertEquals(100.0, acc.smoothedAltitude, 1e-9)
    }

    @Test
    fun parkedAltitudeWanderFreezesTheChannel() {
        val acc = GnssTripAccumulator()
        // Establish the channel while driving.
        acc.fix(elapsed = 0.0, speed = 10.0, alt = 100.0, hasAlt = true)
        acc.fix(elapsed = 1.0, speed = 10.0, alt = 100.0, hasAlt = true)
        // Build some variometer, then stop.
        repeat(5) { i ->
            acc.fix(elapsed = 2.0 + i, speed = 10.0, alt = 100.0 + (i + 1) * 2.0, hasAlt = true)
        }
        val altBeforeStop = acc.smoothedAltitude
        val climbBeforeStop = acc.tripClimbMeters
        // Parked: the observed +-12 m wander must not move altitude or climb,
        // and the variometer must decay to zero.
        repeat(60) { i ->
            val wander = 110.0 + if (i % 2 == 0) 12.0 else -12.0
            acc.fix(elapsed = 7.0 + i, speed = 0.2, alt = wander, hasAlt = true)
        }
        assertEquals(altBeforeStop, acc.smoothedAltitude, 1e-9)
        assertEquals(climbBeforeStop, acc.tripClimbMeters, 1e-9)
        assertEquals(0.0, acc.variometer, 0.01)
    }

    @Test
    fun resumeAfterStopReanchorsWithoutPhantomClimb() {
        val acc = GnssTripAccumulator()
        acc.fix(elapsed = 0.0, speed = 10.0, alt = 100.0, hasAlt = true)
        acc.fix(elapsed = 1.0, speed = 10.0, alt = 100.0, hasAlt = true)
        val climbBefore = acc.tripClimbMeters
        // Long stop during which the GPS altitude baseline drifts +12 m.
        repeat(30) { i ->
            acc.fix(elapsed = 2.0 + i, speed = 0.0, alt = 112.0, hasAlt = true)
        }
        // Resume: the smoother snaps to the current baseline, no climb counted.
        acc.fix(elapsed = 32.0, speed = 8.0, alt = 112.0, hasAlt = true)
        assertEquals(112.0, acc.smoothedAltitude, 1e-9)
        assertEquals(climbBefore, acc.tripClimbMeters, 1e-9)
        repeat(10) { i ->
            acc.fix(elapsed = 33.0 + i, speed = 8.0, alt = 112.0, hasAlt = true)
        }
        assertEquals(climbBefore, acc.tripClimbMeters, 1e-9)
    }

    @Test
    fun elevationSeriesSpansTheWholeTripDecimated() {
        val acc = GnssTripAccumulator(elevationCapacity = 100)
        repeat(1000) { i ->
            acc.fix(elapsed = i.toDouble(), speed = 10.0, alt = 100.0 + i * 0.1, hasAlt = true)
        }
        val samples = acc.elevationSamples()
        assertTrue("count=${samples.size}", samples.size in 2..100)
        // Still spans start to now — never a rolling window.
        assertTrue("first=${samples.first().elapsedSeconds}", samples.first().elapsedSeconds <= 1.0)
        assertTrue("last=${samples.last().elapsedSeconds}", samples.last().elapsedSeconds >= 950.0)
    }

    @Test
    fun copyElevationIntoDecimatesAcrossTheWholeSeries() {
        val acc = GnssTripAccumulator()
        repeat(600) { i ->
            acc.fix(elapsed = i.toDouble(), speed = 10.0, alt = 100.0 + i * 1.0, hasAlt = true)
        }
        val out = FloatArray(100)
        val n = acc.copyElevationInto(out)
        assertEquals(100, n)
        val samples = acc.elevationSamples()
        assertEquals(samples.first().altitudeMeters.toFloat(), out[0], 1e-3f)
        assertEquals(samples.last().altitudeMeters.toFloat(), out[n - 1], 1e-3f)
        // Monotonic input stays monotonic through decimation.
        for (i in 1 until n) assertTrue(out[i] >= out[i - 1])
    }

    @Test
    fun stopLongerThanFifteenMinutesCrossesExactlyOnce() {
        val acc = GnssTripAccumulator()
        var crossings = 0
        var elapsed = 0.0
        // Feed ~16 minutes of stationary fixes in 5 s steps.
        repeat(200) {
            elapsed += 5.0
            if (acc.fix(elapsed = elapsed, speed = 0.0, dt = 5.0)) crossings++
        }
        assertEquals(1, crossings)
        assertTrue(acc.currentStopSeconds >= GnssTripAccumulator.STOP_THRESHOLD_SECONDS)
    }
}
