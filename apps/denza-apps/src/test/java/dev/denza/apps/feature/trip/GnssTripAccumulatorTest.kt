package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssTripAccumulatorTest {

    private fun GnssTripAccumulator.fix(
        speed: Double,
        lat: Double = 55.0,
        lon: Double = 37.0,
        alt: Double = 0.0,
        hasAlt: Boolean = false,
        verticalAccuracy: Double = 5.0,
        hasVerticalAccuracy: Boolean = true,
        dt: Double = 1.0,
        accumulate: Boolean = true,
    ) = onFix(
        latitude = lat,
        longitude = lon,
        altitude = alt,
        hasAltitudeFix = hasAlt,
        verticalAccuracyMeters = verticalAccuracy,
        hasVerticalAccuracy = hasVerticalAccuracy,
        speed = speed,
        dt = dt,
        accumulate = accumulate,
    )

    @Test
    fun haversineMatchesOneDegreeAtEquator() {
        val distance = GnssTripAccumulator.haversine(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, distance, 500.0)
    }

    @Test
    fun accumulatesDistanceOnlyWhileMoving() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, lon = 37.0)
        accumulator.fix(speed = 10.0, lon = 37.001)
        val movingDistance = accumulator.distanceMeters
        assertTrue("distance=$movingDistance", movingDistance in 55.0..75.0)

        accumulator.fix(speed = 0.0, lon = 37.010)
        assertEquals(movingDistance, accumulator.distanceMeters, 1e-6)
    }

    @Test
    fun distanceWaitsForTheTrip() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, lon = 37.0, accumulate = false)
        accumulator.fix(speed = 10.0, lon = 37.001, accumulate = false)
        assertEquals(0.0, accumulator.distanceMeters, 1e-9)
    }

    @Test
    fun accumulatesClimbOnARealHillAndSmoothsAltitude() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        var previous = accumulator.smoothedAltitude
        repeat(30) { index ->
            accumulator.fix(
                speed = 10.0,
                lon = 37.0 + index * 0.0001,
                alt = 100.0 + (index + 1) * 10.0,
                hasAlt = true,
            )
            assertTrue(accumulator.smoothedAltitude >= previous)
            previous = accumulator.smoothedAltitude
        }
        assertTrue("climb=${accumulator.tripClimbMeters}", accumulator.tripClimbMeters > 100.0)
        assertTrue("vario=${accumulator.variometer}", accumulator.variometer > 0.0)
    }

    @Test
    fun variometerGoesNegativeOnDescent() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, alt = 500.0, hasAlt = true)
        repeat(20) { index ->
            accumulator.fix(speed = 10.0, alt = 500.0 - (index + 1) * 8.0, hasAlt = true)
        }
        assertTrue("vario=${accumulator.variometer}", accumulator.variometer < 0.0)
        assertEquals(0.0, accumulator.tripClimbMeters, 1e-9)
    }

    @Test
    fun climbStairStepIgnoresRandomWalkButKeepsRealHills() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        repeat(40) { index ->
            val altitude = 100.0 + if (index % 2 == 0) 2.0 else -2.0
            accumulator.fix(speed = 10.0, alt = altitude, hasAlt = true)
        }
        assertEquals(0.0, accumulator.tripClimbMeters, 1e-9)

        repeat(10) { index ->
            accumulator.fix(speed = 10.0, alt = 100.0 + (index + 1) * 5.0, hasAlt = true)
        }
        repeat(10) { accumulator.fix(speed = 10.0, alt = 150.0, hasAlt = true) }
        assertTrue("climb=${accumulator.tripClimbMeters}", accumulator.tripClimbMeters in 40.0..51.0)
    }

    @Test
    fun seedRejectsPoorVerticalAccuracy() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 0.0, alt = 4800.0, hasAlt = true, verticalAccuracy = 150.0)
        assertFalse(accumulator.hasAltitude)
        accumulator.fix(speed = 0.0, alt = 120.0, hasAlt = true, verticalAccuracy = 8.0)
        assertTrue(accumulator.hasAltitude)
        assertEquals(120.0, accumulator.smoothedAltitude, 1e-9)
    }

    @Test
    fun seedWithoutReportedAccuracyNeedsConsistentFixes() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 0.0, alt = 101.0, hasAlt = true, hasVerticalAccuracy = false)
        accumulator.fix(speed = 0.0, alt = 99.0, hasAlt = true, hasVerticalAccuracy = false)
        assertFalse(accumulator.hasAltitude)
        accumulator.fix(speed = 0.0, alt = 350.0, hasAlt = true, hasVerticalAccuracy = false)
        accumulator.fix(speed = 0.0, alt = 100.0, hasAlt = true, hasVerticalAccuracy = false)
        accumulator.fix(speed = 0.0, alt = 101.0, hasAlt = true, hasVerticalAccuracy = false)
        assertFalse(accumulator.hasAltitude)
        accumulator.fix(speed = 0.0, alt = 100.0, hasAlt = true, hasVerticalAccuracy = false)
        assertTrue(accumulator.hasAltitude)
        assertEquals(100.0, accumulator.smoothedAltitude, 1e-9)
    }

    @Test
    fun parkedAltitudeWanderFreezesTheChannel() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        repeat(5) { index ->
            accumulator.fix(speed = 10.0, alt = 100.0 + (index + 1) * 2.0, hasAlt = true)
        }
        val altitudeBeforeStop = accumulator.smoothedAltitude
        val climbBeforeStop = accumulator.tripClimbMeters
        repeat(60) { index ->
            val wander = 110.0 + if (index % 2 == 0) 12.0 else -12.0
            accumulator.fix(speed = 0.2, alt = wander, hasAlt = true)
        }
        assertEquals(altitudeBeforeStop, accumulator.smoothedAltitude, 1e-9)
        assertEquals(climbBeforeStop, accumulator.tripClimbMeters, 1e-9)
        assertEquals(0.0, accumulator.variometer, 0.01)
    }

    @Test
    fun resumeAfterStopReanchorsWithoutPhantomClimb() {
        val accumulator = GnssTripAccumulator()
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        accumulator.fix(speed = 10.0, alt = 100.0, hasAlt = true)
        val climbBeforeStop = accumulator.tripClimbMeters
        repeat(30) { accumulator.fix(speed = 0.0, alt = 112.0, hasAlt = true) }
        accumulator.fix(speed = 8.0, alt = 112.0, hasAlt = true)
        assertEquals(112.0, accumulator.smoothedAltitude, 1e-9)
        assertEquals(climbBeforeStop, accumulator.tripClimbMeters, 1e-9)
        repeat(10) { accumulator.fix(speed = 8.0, alt = 112.0, hasAlt = true) }
        assertEquals(climbBeforeStop, accumulator.tripClimbMeters, 1e-9)
    }
}
