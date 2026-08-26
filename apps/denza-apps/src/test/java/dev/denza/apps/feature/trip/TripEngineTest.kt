package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private fun TripEngine.fix(
        tMs: Long,
        speed: Double,
        lat: Double = 55.0,
        lon: Double = 37.0,
        alt: Double = 0.0,
        hasAlt: Boolean = false,
        verticalAccuracy: Double = 5.0,
        hasVerticalAccuracy: Boolean = true,
    ) = onLocation(
        nowElapsedMs = tMs,
        wallMs = tMs,
        tzOffsetMinutes = 180,
        latitude = lat,
        longitude = lon,
        altitude = alt,
        hasAltitude = hasAlt,
        verticalAccuracyMeters = verticalAccuracy,
        hasVerticalAccuracy = hasVerticalAccuracy,
        speed = speed,
    )

    @Test
    fun tripClockWaitsForSustainedMovement() {
        val engine = TripEngine()
        engine.fix(0L, speed = 0.0)
        engine.fix(5_000L, speed = 0.0)
        assertFalse(engine.tripStarted)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)

        engine.fix(10_000L, speed = 2.5)
        engine.fix(11_000L, speed = 2.5)
        engine.fix(12_000L, speed = 2.5)
        assertFalse(engine.tripStarted)
        engine.fix(13_000L, speed = 2.5)
        assertTrue(engine.tripStarted)
        assertEquals(3.0, engine.elapsedSeconds, 1e-6)
        engine.onTick(20_000L)
        assertEquals(10.0, engine.elapsedSeconds, 1e-6)
    }

    @Test
    fun movementBlipDoesNotStartTrip() {
        val engine = TripEngine()
        engine.fix(0L, speed = 2.5)
        engine.fix(1_000L, speed = 2.5)
        engine.fix(2_000L, speed = 0.5)
        engine.fix(3_500L, speed = 2.5)
        assertFalse(engine.tripStarted)
        engine.onTick(6_000L)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
    }

    @Test
    fun distanceWaitsForTripStart() {
        val engine = TripEngine()
        engine.fix(0L, speed = 2.5, lon = 37.0)
        engine.fix(1_000L, speed = 2.5, lon = 37.0005)
        engine.fix(2_000L, speed = 2.5, lon = 37.001)
        engine.fix(2_500L, speed = 0.5, lon = 37.001)
        assertFalse(engine.tripStarted)
        assertEquals(0.0, engine.distanceMeters(), 1e-9)
    }

    @Test
    fun distanceGrowsFromMovingFixesOnceStarted() {
        val engine = TripEngine()
        engine.fix(0L, speed = 10.0, lon = 37.000)
        engine.fix(1_000L, speed = 10.0, lon = 37.002)
        engine.fix(2_000L, speed = 10.0, lon = 37.004)
        engine.fix(3_000L, speed = 10.0, lon = 37.006)
        assertTrue(engine.tripStarted)
        engine.fix(4_000L, speed = 10.0, lon = 37.008)
        assertTrue("distance=${engine.distanceMeters()}", engine.distanceMeters() > 100.0)
    }

    @Test
    fun altitudeSurfaceReflectsAcceptedGnssFixes() {
        val engine = TripEngine()
        engine.fix(0L, speed = 10.0, alt = 100.0, hasAlt = true)
        assertTrue(engine.hasAltitude())
        assertEquals(100.0, engine.smoothedAltitude(), 1e-9)
    }

    @Test
    fun guidanceIsFailClosed() {
        val engine = TripEngine()
        engine.onGuidance(distanceMeters = null, timeSeconds = null, valid = false, nowElapsedMs = 100L)
        assertNull(engine.guidance())

        engine.onGuidance(distanceMeters = 4200, timeSeconds = 600, valid = true, nowElapsedMs = 200L)
        val guidance = engine.guidance()
        assertNotNull(guidance)
        assertEquals(4200, guidance!!.distanceMeters)
        assertEquals(600, guidance.timeSeconds)

        engine.onGuidance(distanceMeters = null, timeSeconds = null, valid = false, nowElapsedMs = 300L)
        assertNull(engine.guidance())
    }
}
