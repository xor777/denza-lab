package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisCalibratorTest {

    private val g = 9.81

    @Test
    fun extractsVerticalAccelerationAlongGravity() {
        val axis = AxisCalibrator()
        // Gravity points down -z (device flat): up = +z. Add +1 m/s^2 upward.
        val r = axis.update(
            ax = 0.0, ay = 0.0, az = g + 1.0,
            gravX = 0.0, gravY = 0.0, gravZ = g,
            gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
            dt = 1.0 / 30.0,
        )
        assertEquals(1.0, r.vertical, 0.05)
        assertEquals(0.0, r.horizontalMagnitude, 0.05)
    }

    @Test
    fun extractsHorizontalMagnitudeRegardlessOfDirection() {
        val axis = AxisCalibrator()
        val r = axis.update(
            ax = 2.0, ay = 0.0, az = g,
            gravX = 0.0, gravY = 0.0, gravZ = g,
            gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
            dt = 1.0 / 30.0,
        )
        assertEquals(0.0, r.vertical, 0.05)
        assertEquals(2.0, r.horizontalMagnitude, 0.05)
    }

    @Test
    fun yawRateIsMeasuredAboutTheGravityAxisNotADeviceAxis() {
        val axis = AxisCalibrator()
        // Head unit mounted so gravity runs along +y: up = +y, so yaw = gyroY
        // regardless of what the other gyro axes report.
        val r = axis.update(
            ax = 0.0, ay = g, az = 0.0,
            gravX = 0.0, gravY = g, gravZ = 0.0,
            gyroX = 0.3, gyroY = 0.5, gyroZ = 0.1,
            dt = 1.0 / 30.0,
        )
        assertEquals(0.5, r.yawRate, 1e-6)
    }
}
