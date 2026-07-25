package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDynamicsTest {

    @Test
    fun seedingDoesNotSpikeTheDerivative() {
        val s = SpeedDynamics()
        s.onFix(speed = 20.0, dt = 1.0)
        assertEquals(20.0, s.smoothedSpeed, 1e-9)
        assertEquals(0.0, s.longitudinalAccel, 1e-9)
    }

    @Test
    fun sustainedBrakingConvergesToTheRampRate() {
        val s = SpeedDynamics()
        var v = 20.0
        s.onFix(v, 1.0)
        repeat(8) {
            v -= 2.0
            s.onFix(v.coerceAtLeast(0.0), 1.0)
        }
        // A -2 m/s^2 GNSS speed ramp must read back as ~-2 m/s^2.
        assertEquals(-2.0, s.longitudinalAccel, 0.2)
    }

    @Test
    fun sustainedAccelerationConvergesPositive() {
        val s = SpeedDynamics()
        var v = 0.0
        s.onFix(v, 1.0)
        repeat(8) {
            v += 1.5
            s.onFix(v, 1.0)
        }
        assertEquals(1.5, s.longitudinalAccel, 0.2)
    }

    @Test
    fun holdsBrieflyBetweenFixes() {
        val s = SpeedDynamics()
        s.onFix(20.0, 1.0)
        s.onFix(15.0, 1.0)
        val held = s.longitudinalAccel
        assertTrue("accel=$held", held < -1.0)
        // 1 s of IMU-rate idling is fresher than the 3 s stale limit: hold.
        repeat(30) { s.onIdle(1.0 / 30.0) }
        assertEquals(held, s.longitudinalAccel, 1e-9)
        assertEquals(s.smoothedSpeed, s.smoothedSpeed, 1e-9)
    }

    @Test
    fun decaysToZeroWhenFixesStop() {
        val s = SpeedDynamics()
        s.onFix(20.0, 1.0)
        s.onFix(15.0, 1.0)
        assertTrue(s.longitudinalAccel < -1.0)
        // 20 s without a fix: no claimed dynamics, no claimed speed.
        repeat(600) { s.onIdle(1.0 / 30.0) }
        assertEquals(0.0, s.longitudinalAccel, 0.05)
        assertEquals(0.0, s.smoothedSpeed, 0.2)
    }
}
