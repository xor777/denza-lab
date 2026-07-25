package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongitudinalFusionTest {

    private val dt = 1.0 / 30.0

    /**
     * Drive straight while changing speed. The head unit's forward axis is at an
     * arbitrary angle in its own horizontal basis — here 40 degrees — which is
     * exactly what the fusion has to discover.
     */
    private fun LongitudinalFusion.driveStraight(seconds: Double, accel: Double) {
        val fx = Math.cos(Math.toRadians(40.0))
        val fy = Math.sin(Math.toRadians(40.0))
        var t = 0.0
        while (t < seconds) {
            update(dt, gnssAccel = accel, horizontal1 = accel * fx, horizontal2 = accel * fy, lateral = 0.0)
            t += dt
        }
    }

    @Test
    fun beforeTheAxisIsKnownItReportsThePlainGnssFigure() {
        val fusion = LongitudinalFusion()
        fusion.update(dt, gnssAccel = -1.4, horizontal1 = 0.9, horizontal2 = 0.6, lateral = 0.0)
        assertFalse(fusion.axisLearned)
        assertEquals(-1.4, fusion.value, 1e-9)
    }

    @Test
    fun ordinaryStopsAndStartsTeachItWhichWayIsForward() {
        val fusion = LongitudinalFusion()
        fusion.driveStraight(seconds = 4.0, accel = 1.2)
        fusion.driveStraight(seconds = 4.0, accel = -1.5)
        assertTrue(fusion.axisLearned)
    }

    @Test
    fun onceLearnedTheImuAnswersBrakingBeforeGnssDoes() {
        val fusion = LongitudinalFusion()
        fusion.driveStraight(seconds = 6.0, accel = 1.2)
        fusion.driveStraight(seconds = 6.0, accel = -1.2)
        assertTrue(fusion.axisLearned)

        // The pedal goes down: the IMU sees -3 m/s^2 immediately while GNSS, a
        // second behind, still reports the old gentle figure.
        val fx = Math.cos(Math.toRadians(40.0))
        val fy = Math.sin(Math.toRadians(40.0))
        fusion.update(dt, gnssAccel = -1.2, horizontal1 = -3.0 * fx, horizontal2 = -3.0 * fy, lateral = 0.0)
        assertTrue("value=${fusion.value}", fusion.value < -1.6)
    }

    @Test
    fun aSteadyStateFallsBackToGnssSoItCannotDriftAway() {
        val fusion = LongitudinalFusion()
        fusion.driveStraight(seconds = 6.0, accel = 1.2)
        fusion.driveStraight(seconds = 6.0, accel = -1.5)
        // Held long enough for the transient to wash out, the answer is GNSS's.
        fusion.driveStraight(seconds = 8.0, accel = -1.5)
        assertEquals(-1.5, fusion.value, 0.25)
    }

    @Test
    fun corneringSamplesAreNotUsedForLearning() {
        val fusion = LongitudinalFusion()
        var t = 0.0
        while (t < 10.0) {
            // Hard cornering: the horizontal vector is dominated by lateral force,
            // so it says nothing about where forward is.
            fusion.update(dt, gnssAccel = -1.5, horizontal1 = 0.2, horizontal2 = 3.0, lateral = 3.0)
            t += dt
        }
        assertFalse(fusion.axisLearned)
        assertEquals(-1.5, fusion.value, 1e-9)
    }

    @Test
    fun withNoImuAtAllItStillFollowsGnss() {
        val fusion = LongitudinalFusion()
        fusion.onGnss(-2.4)
        assertEquals(-2.4, fusion.value, 1e-9)
    }
}
