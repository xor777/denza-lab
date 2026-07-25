package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Longitudinal acceleration that reacts the instant the pedal moves.
 *
 * GNSS speed alone is honest but slow: fixes arrive at ~1 Hz and the smoother
 * adds another second, so the panel used to answer a brake about a second late
 * with the peak already flattened. The IMU sees the same brake immediately, but
 * cannot say by itself which way "forward" is, and loses sustained acceleration
 * into its own re-smoothed gravity estimate.
 *
 * So each source does what it is good at:
 *
 *  - Direction: learned. Whenever the car changes speed in a straight line, the
 *    horizontal acceleration vector IS the longitudinal axis, and GNSS says which
 *    end of it is forward. Straight-line speed changes happen at every stop and
 *    start, so this converges in ordinary driving (unlike a cornering-based fit).
 *  - Transient: the IMU projection onto that axis, high-passed — the onset.
 *  - Steady state: the GNSS derivative, which never drifts.
 *
 * Until the axis is learned the output is simply the GNSS figure: late, but never
 * pointing the wrong way. Pure Kotlin, JVM-testable.
 */
class LongitudinalFusion(
    /** Corner of the transient high-pass, seconds. */
    private val transientTau: Double = 1.3,
    /** GNSS acceleration worth learning from, m/s^2. */
    private val learnMinAccel: Double = 0.35,
    /** Above this lateral acceleration the sample is a corner, not a straight line. */
    private val learnMaxLateral: Double = 1.2,
    /** Learning rate per qualifying sample. */
    private val learnRate: Double = 0.05,
    /** Agreement (0..1) the learnt axis must reach before its transient is used. */
    private val convergenceLevel: Double = 0.75,
) {
    /**
     * Fused longitudinal acceleration, m/s^2. Negative = braking.
     *
     * GNSS carries the steady state and the IMU adds the part GNSS has not caught
     * up with yet, so with no IMU samples at all this degrades to the plain (late
     * but correct) GNSS figure rather than freezing.
     */
    val value: Double get() = gnssBaseline + transient

    private var gnssBaseline = 0.0
    private var transient = 0.0

    /** True once the forward axis is trustworthy. */
    var axisLearned: Boolean = false
        private set

    // Learnt forward direction in the horizontal basis, and how consistent the
    // evidence for it has been.
    private var forward1 = 0.0
    private var forward2 = 0.0
    private var agreement = 0.0
    private var slowImu = 0.0

    /** Feed one IMU sample: learns the forward axis and refreshes the transient. */
    fun update(
        dtSeconds: Double,
        gnssAccel: Double,
        horizontal1: Double,
        horizontal2: Double,
        lateral: Double,
    ) {
        val dt = dtSeconds.coerceIn(0.0, 0.2)
        gnssBaseline = gnssAccel
        learn(gnssAccel, horizontal1, horizontal2, lateral)

        if (!axisLearned) {
            slowImu = 0.0
            transient = 0.0
            return
        }
        val imu = horizontal1 * forward1 + horizontal2 * forward2
        slowImu += (imu - slowImu) * (1.0 - exp(-dt / transientTau))
        transient = imu - slowImu
    }

    /** Feed the GNSS figure directly, for the stretch before any IMU sample. */
    fun onGnss(gnssAccel: Double) {
        gnssBaseline = gnssAccel
    }

    private fun learn(gnssAccel: Double, h1: Double, h2: Double, lateral: Double) {
        val magnitude = hypot(h1, h2)
        if (abs(gnssAccel) < learnMinAccel || abs(lateral) > learnMaxLateral || magnitude < 1e-3) {
            return
        }
        // Point the sample the way GNSS says the car is accelerating.
        val s = sign(gnssAccel)
        val n1 = h1 / magnitude * s
        val n2 = h2 / magnitude * s
        if (forward1 == 0.0 && forward2 == 0.0) {
            forward1 = n1
            forward2 = n2
            return
        }
        val alignment = n1 * forward1 + n2 * forward2
        forward1 += (n1 - forward1) * learnRate
        forward2 += (n2 - forward2) * learnRate
        val norm = hypot(forward1, forward2)
        if (norm > 1e-6) {
            forward1 /= norm
            forward2 /= norm
        }
        // Consistent evidence builds confidence; contradictory evidence removes it.
        agreement += (alignment.coerceIn(-1.0, 1.0) - agreement) * learnRate
        axisLearned = agreement >= convergenceLevel
    }
}
