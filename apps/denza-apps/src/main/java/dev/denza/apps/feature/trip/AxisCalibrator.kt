package dev.denza.apps.feature.trip

import kotlin.math.sqrt

/**
 * Projects raw head-unit IMU samples onto the vehicle's vertical axis.
 *
 * docs/vehicle-data-findings.md: the head unit is fixed-mounted but its sensor
 * axes are NOT the vehicle axes. This class extracts exactly what a gravity
 * reference makes honest:
 *
 * - VERTICAL axis: from a slowly smoothed gravity vector; the linear-acceleration
 *   component along it is trustworthy once gravity settles (a few seconds).
 *   Sign is "up" = opposite gravity.
 * - HORIZONTAL magnitude: the residual horizontal acceleration magnitude,
 *   orientation-independent.
 * - YAW rate: angular velocity about the up axis (right-hand rule: positive =
 *   counter-clockwise seen from above = a left turn), mount-independent because
 *   the gyro is projected onto the gravity axis.
 *
 * The former statistical lateral/longitudinal split (regressing the horizontal
 * residual against yaw) is gone after the first live drive: its convergence
 * threshold was unreachable in normal driving, and the magnitude-times-yaw-sign
 * fallback flipped sign at 30 Hz during straight-line braking, so consumers
 * low-passed it to zero. Lateral/longitudinal now come from physics in
 * [TripEngine]: lateral = GNSS ground speed x yaw rate (v*w, centripetal) and
 * longitudinal = the derivative of smoothed GNSS speed ([SpeedDynamics]). The
 * horizontal IMU residual is deliberately not used for either channel, because
 * sustained braking also leaks into the re-smoothed gravity estimate and partly
 * vanishes from that residual.
 *
 * This class is pure: no Android sensor types, fully JVM-testable.
 */
class AxisCalibrator(
    /** Time constant of the gravity low-pass, seconds. */
    private val gravityTau: Double = 1.2,
) {
    // Smoothed gravity (up axis, before normalization). Seeded on first sample.
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var seeded = false

    /**
     * Feed one sample.
     *
     * @param ax raw accelerometer (includes gravity), m/s^2
     * @param gravX smoothed gravity vector from TYPE_GRAVITY, m/s^2
     * @param gyroX gyroscope, rad/s
     * @param dt seconds since the previous sample
     */
    fun update(
        ax: Double, ay: Double, az: Double,
        gravX: Double, gravY: Double, gravZ: Double,
        gyroX: Double, gyroY: Double, gyroZ: Double,
        dt: Double,
    ): AxisReading {
        val step = dt.coerceIn(0.0, 0.2)
        if (!seeded) {
            gx = gravX; gy = gravY; gz = gravZ; seeded = true
        } else {
            val a = 1.0 - Math.exp(-step / gravityTau)
            gx += (gravX - gx) * a
            gy += (gravY - gy) * a
            gz += (gravZ - gz) * a
        }
        val gMag = sqrt(gx * gx + gy * gy + gz * gz)
        if (gMag < 1e-3) {
            return AxisReading(0.0, 0.0, 0.0)
        }
        // Unit up axis.
        val ux = gx / gMag; val uy = gy / gMag; val uz = gz / gMag

        // Linear acceleration = raw accel - gravity.
        val lx = ax - gx; val ly = ay - gy; val lz = az - gz

        // Vertical component (positive = up).
        val vertical = lx * ux + ly * uy + lz * uz

        // Horizontal residual magnitude (diagnostic only; see class doc).
        val hx = lx - vertical * ux
        val hy = ly - vertical * uy
        val hz = lz - vertical * uz
        val horizontalMagnitude = sqrt(hx * hx + hy * hy + hz * hz)

        // Yaw rate = angular velocity about the up axis.
        val yawRate = gyroX * ux + gyroY * uy + gyroZ * uz

        return AxisReading(
            vertical = vertical,
            horizontalMagnitude = horizontalMagnitude,
            yawRate = yawRate,
        )
    }
}
