package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.min

/**
 * The elastic membrane behind the comfort field — the mode-1 instrument that
 * replaced the artificial horizon (a horizon says nothing in a car; what
 * passengers actually feel is this).
 *
 * Everything here is driven by the engine's honest physics: lateral is v*yawRate,
 * longitudinal is the GNSS speed derivative, vertical comes from the calibrated
 * IMU axis. Steering angle itself is behind BYD permissions
 * (docs/vehicle-data-findings.md), so the slight rotation leans on yaw rate —
 * the car's real rate of turn, which is what a turned wheel produces.
 *
 * Offsets are normalised (-1..1) and ripple radii/amplitudes are fractions of the
 * field radius, so the renderer can size the field however it likes. Pure Kotlin,
 * JVM-testable.
 */
class ComfortFieldPhysics(
    /** Acceleration that pushes the membrane to its limit, m/s^2. */
    private val accelFullScale: Double = 1.7,
    private val stiffness: Double = 95.0,
    private val damping: Double = 9.5,
    private val rotationStiffness: Double = 70.0,
    private val rotationDamping: Double = 9.0,
    /** Yaw rate mapped to the rotation limit, rad/s. */
    private val yawFullScale: Double = 0.14,
    /** Hard cap on the tilt, radians (~6 degrees): a hint, not a carousel. */
    private val maxRotationRad: Double = 0.105,
    /** Vertical acceleration that starts throwing ripples, m/s^2. */
    private val rippleThreshold: Double = 0.6,
    private val rippleMinIntervalSeconds: Double = 0.22,
    /** Ripple travel speed, in field radii per second. */
    private val rippleSpeed: Double = 1.15,
    private val rippleDecayPerSecond: Double = 0.85,
) {
    /** Membrane centre offset, -1..1. Positive x = right, positive y = down. */
    var offsetX: Double = 0.0
        private set
    var offsetY: Double = 0.0
        private set
    var rotationRad: Double = 0.0
        private set

    private var velocityX = 0.0
    private var velocityY = 0.0
    private var rotationVelocity = 0.0

    // Starts "ready" so the very first impact ripples instead of being swallowed
    // by the minimum interval between fronts.
    private var sinceRipple = rippleMinIntervalSeconds

    val rippleRadius = DoubleArray(MAX_RIPPLES)
    val rippleLife = DoubleArray(MAX_RIPPLES)
    val rippleAmplitude = DoubleArray(MAX_RIPPLES)
    var rippleCount = 0
        private set

    fun update(
        dtSeconds: Double,
        lateral: Double,
        longitudinal: Double,
        vertical: Double,
        yawRate: Double,
    ) {
        val dt = dtSeconds.coerceIn(0.0, 0.08)
        sinceRipple += dt

        // Passengers lurch the way the cabin's contents do: braking throws them
        // forward (up the field), a left turn pushes them outward to the right.
        val targetX = (lateral / accelFullScale).coerceIn(-1.0, 1.0)
        val targetY = (longitudinal / accelFullScale).coerceIn(-1.0, 1.0)
        velocityX += (-stiffness * (offsetX - targetX) - damping * velocityX) * dt
        velocityY += (-stiffness * (offsetY - targetY) - damping * velocityY) * dt
        offsetX += velocityX * dt
        offsetY += velocityY * dt

        val targetRotation = ((yawRate / yawFullScale) * maxRotationRad)
            .coerceIn(-maxRotationRad, maxRotationRad)
        rotationVelocity +=
            (-rotationStiffness * (rotationRad - targetRotation) - rotationDamping * rotationVelocity) * dt
        rotationRad += rotationVelocity * dt

        if (abs(vertical) >= rippleThreshold && sinceRipple >= rippleMinIntervalSeconds) {
            spawnRipple(abs(vertical))
            sinceRipple = 0.0
        }
        advanceRipples(dt)
    }

    private fun spawnRipple(strength: Double) {
        if (rippleCount >= MAX_RIPPLES) return
        rippleRadius[rippleCount] = 0.0
        rippleLife[rippleCount] = 1.0
        rippleAmplitude[rippleCount] = min(RIPPLE_MAX_AMPLITUDE, strength * RIPPLE_AMPLITUDE_GAIN)
        rippleCount++
    }

    private fun advanceRipples(dt: Double) {
        var i = 0
        while (i < rippleCount) {
            rippleRadius[i] += rippleSpeed * dt
            rippleLife[i] -= rippleDecayPerSecond * dt
            if (rippleLife[i] <= 0.0 || rippleRadius[i] > RIPPLE_MAX_RADIUS) {
                rippleCount--
                rippleRadius[i] = rippleRadius[rippleCount]
                rippleLife[i] = rippleLife[rippleCount]
                rippleAmplitude[i] = rippleAmplitude[rippleCount]
            } else {
                i++
            }
        }
    }

    /**
     * Displacement (in field radii) a ripple front adds at distance [distance]
     * from the centre, both given as fractions of the field radius.
     */
    fun rippleDisplacementAt(distance: Double): Double {
        var sum = 0.0
        for (i in 0 until rippleCount) {
            val offset = distance - rippleRadius[i]
            if (abs(offset) >= RIPPLE_WIDTH) continue
            val falloff = kotlin.math.cos(offset / RIPPLE_WIDTH * Math.PI / 2.0)
            sum += falloff * rippleAmplitude[i] * rippleLife[i]
        }
        return sum
    }

    companion object {
        const val MAX_RIPPLES = 8
        /** Half-width of a ripple front, in field radii. */
        const val RIPPLE_WIDTH = 0.30
        const val RIPPLE_MAX_RADIUS = 1.6
        const val RIPPLE_MAX_AMPLITUDE = 0.09
        const val RIPPLE_AMPLITUDE_GAIN = 0.055
    }
}
