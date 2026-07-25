package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private val g = 9.81

    /** Feed one GNSS fix with sane defaults so each test only names what it varies. */
    private fun TripEngine.fix(
        tMs: Long,
        speed: Double,
        lat: Double = 55.0,
        lon: Double = 37.0,
        alt: Double = 0.0,
        hasAlt: Boolean = false,
        vacc: Double = 5.0,
        hasVacc: Boolean = true,
        bearing: Double = 0.0,
        hasBearing: Boolean = false,
    ) = onLocation(
        nowElapsedMs = tMs,
        wallMs = tMs,
        tzOffsetMinutes = 180,
        latitude = lat,
        longitude = lon,
        altitude = alt,
        hasAltitude = hasAlt,
        verticalAccuracyMeters = vacc,
        hasVerticalAccuracy = hasVacc,
        bearing = bearing,
        hasBearing = hasBearing,
        speed = speed,
        accuracyMeters = 5.0,
    )

    /** A flat, yaw-only IMU sample (device mounted flat, gravity along +z). */
    private fun TripEngine.imu(tMs: Long, yaw: Double = 0.0, az: Double = g) =
        onImu(tMs, 0.0, 0.0, az, 0.0, 0.0, g, 0.0, 0.0, yaw)

    /** Sustained movement at [speed] so the trip clock starts. Returns the next tMs. */
    private fun TripEngine.startTrip(fromMs: Long = 0L, speed: Double = 10.0): Long {
        var t = fromMs
        repeat(4) {
            fix(t, speed)
            t += 1_000L
        }
        assertTrue(tripStarted)
        return t
    }

    // ---- trip clock ---------------------------------------------------------

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
        // The clock is credited from when the movement began (10 s), not from
        // when the sustain gate confirmed it (13 s).
        assertEquals(3.0, engine.elapsedSeconds, 1e-6)
        engine.onTick(20_000L)
        assertEquals(10.0, engine.elapsedSeconds, 1e-6)
    }

    @Test
    fun movementBlipDoesNotStartTrip() {
        val engine = TripEngine()
        engine.fix(0L, speed = 2.5)
        engine.fix(1_000L, speed = 2.5)
        engine.fix(2_000L, speed = 0.5) // gate resets
        engine.fix(3_500L, speed = 2.5)
        assertFalse(engine.tripStarted)
        engine.onTick(6_000L)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
    }

    @Test
    fun accumulationWaitsForTheTripClock() {
        val engine = TripEngine()
        // Creeping below the sustain gate: nothing may accumulate.
        engine.fix(0L, speed = 2.5, lon = 37.0)
        engine.fix(1_000L, speed = 2.5, lon = 37.0005)
        engine.fix(2_000L, speed = 2.5, lon = 37.001)
        engine.fix(2_500L, speed = 0.5, lon = 37.001)
        assertFalse(engine.tripStarted)
        assertEquals(0.0, engine.distanceMeters(), 1e-9)
        assertEquals(0, engine.routePointCount())
        assertEquals(0, engine.eventCount())
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

    // ---- physics channels ---------------------------------------------------

    @Test
    fun lateralIsGroundSpeedTimesYawRate() {
        val engine = TripEngine()
        engine.fix(0L, speed = 15.0)
        engine.imu(100L, yaw = 0.2)
        // v*w = 15 * 0.2, positive = left turn; available with zero calibration.
        assertEquals(3.0, engine.lateralAccel, 1e-6)
        // And it does not sign-flip when yaw jitters near zero at a standstill:
        val engine2 = TripEngine()
        engine2.imu(0L, yaw = 0.3)
        assertEquals(0.0, engine2.lateralAccel, 1e-9) // no GNSS speed -> no claim
    }

    @Test
    fun longitudinalTracksGnssBraking() {
        val engine = TripEngine()
        engine.fix(0L, speed = 15.0)
        engine.fix(1_000L, speed = 12.0)
        engine.fix(2_000L, speed = 9.0)
        engine.fix(3_000L, speed = 6.0)
        assertTrue("longitudinal=${engine.longitudinalAccel}", engine.longitudinalAccel < -1.5)
        // Straight-line braking must show up in agitation (the old IMU-statistics
        // pipeline averaged it to zero).
        engine.imu(3_100L)
        assertTrue("agitation=${engine.latestAgitation}", engine.latestAgitation > 1.5)
    }

    // ---- events -------------------------------------------------------------

    @Test
    fun turnFiresOnlyWithSpeedYawAndSustain() {
        val engine = TripEngine()
        var t = engine.startTrip(speed = 6.0)
        // 0.4 rad/s for ~3 s above 5 m/s -> fires.
        repeat(100) {
            engine.imu(t, yaw = 0.4)
            t += 33L
        }
        assertTrue(engine.events().any { it.kind == TripEventKind.TURN })
    }

    @Test
    fun intersectionYawBelowThresholdDoesNotFire() {
        val engine = TripEngine()
        var t = engine.startTrip(speed = 6.0)
        // The old 0.25 rad/s threshold fired here; 0.3 must stay silent now.
        repeat(150) {
            engine.imu(t, yaw = 0.3)
            t += 33L
        }
        assertTrue(engine.events().none { it.kind == TripEventKind.TURN })
    }

    @Test
    fun slowManeuveringYawDoesNotFire() {
        val engine = TripEngine()
        var t = engine.startTrip(speed = 6.0)
        engine.fix(t, speed = 4.0) // parking-lot speed
        t += 100L
        repeat(150) {
            engine.imu(t, yaw = 0.4)
            t += 33L
        }
        assertTrue(engine.events().none { it.kind == TripEventKind.TURN })
    }

    @Test
    fun calmNeverFiresAtAStandstill() {
        val engine = TripEngine()
        var t = engine.startTrip(speed = 10.0)
        engine.fix(t, speed = 0.0) // red light
        t += 100L
        repeat(250) { // 25 s of a perfectly still car
            engine.imu(t)
            t += 100L
        }
        assertTrue(engine.events().none { it.kind == TripEventKind.CALM })

        // Driving the same calm window does fire.
        engine.fix(t, speed = 4.0)
        t += 100L
        repeat(250) {
            engine.imu(t)
            t += 100L
        }
        assertTrue(engine.events().any { it.kind == TripEventKind.CALM })
    }

    @Test
    fun climbEventFiresPastFortyMeterWindow() {
        val engine = TripEngine()
        var t = 0L
        var alt = 100.0
        engine.fix(t, speed = 8.0, alt = alt, hasAlt = true)
        repeat(30) { i ->
            t += 1_000L
            alt += 25.0
            engine.fix(t, speed = 8.0, lon = 37.0 + i * 0.0002, alt = alt, hasAlt = true)
        }
        val fired = engine.events().any { it.kind == TripEventKind.CLIMB }
        assertTrue("events=${engine.events().map { it.kind }}", fired)
    }

    // ---- guidance -----------------------------------------------------------

    @Test
    fun guidanceIsFailClosed() {
        val engine = TripEngine()
        // No valid guidance -> null (row falls back to "В пути").
        engine.onGuidance(distanceMeters = null, timeSeconds = null, valid = false, nowElapsedMs = 100L)
        assertNull(engine.guidance())

        // Valid guidance -> exposed.
        engine.onGuidance(distanceMeters = 4200, timeSeconds = 600, valid = true, nowElapsedMs = 200L)
        val guidance = engine.guidance()
        assertNotNull(guidance)
        assertEquals(4200, guidance!!.distanceMeters)
        assertEquals(600, guidance.timeSeconds)

        // Guidance goes away again -> dropped immediately.
        engine.onGuidance(distanceMeters = null, timeSeconds = null, valid = false, nowElapsedMs = 300L)
        assertNull(engine.guidance())
    }
}
