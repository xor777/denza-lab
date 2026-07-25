package dev.denza.apps.feature.trip

import kotlin.math.exp

/**
 * Smoothed GNSS ground speed and its derivative — the honest longitudinal
 * acceleration channel.
 *
 * The head-unit IMU cannot separate sustained braking/acceleration from its own
 * slowly re-smoothed gravity estimate (the live-drive finding that motivated this
 * class), but GNSS ground speed at ~1 Hz differentiates cleanly: low-pass with
 * tau ~= 1.2 s and take the per-fix derivative of the smoothed value. Between
 * fixes the last figures are held (the ~30 Hz consumers low-pass them anyway);
 * when fixes stop arriving (tunnel, cold start, revoked permission) both figures
 * decay to zero instead of freezing on a stale value.
 *
 * [smoothedSpeed] is also the "v" of the engine's centripetal lateral channel
 * (v * yawRate), which is why the stale decay matters: no fix, no claimed speed.
 *
 * Pure and JVM-testable.
 */
class SpeedDynamics(
    /** Ground-speed low-pass time constant, seconds (spec: 1.0-1.5 s). */
    private val speedTau: Double = 1.2,
    /** Fix age beyond which the held values start decaying, seconds. */
    private val staleAfterSeconds: Double = 3.0,
    /** Decay time constant once stale, seconds. */
    private val staleDecayTau: Double = 1.0,
) {
    /** Low-passed GNSS ground speed, m/s. */
    var smoothedSpeed: Double = 0.0
        private set

    /** d(smoothed speed)/dt, m/s^2. Positive = accelerating, negative = braking. */
    var longitudinalAccel: Double = 0.0
        private set

    private var seeded = false
    private var sinceFixSeconds = 0.0

    /** Feed one GNSS fix's ground speed ([dt] seconds since the previous fix). */
    fun onFix(speed: Double, dt: Double) {
        val step = dt.coerceIn(0.0, 5.0)
        sinceFixSeconds = 0.0
        val v = speed.coerceAtLeast(0.0)
        if (!seeded) {
            // Seeding must not spike the derivative.
            smoothedSpeed = v
            longitudinalAccel = 0.0
            seeded = true
            return
        }
        if (step > 0.0) {
            val prev = smoothedSpeed
            val a = 1.0 - exp(-step / speedTau)
            smoothedSpeed += (v - smoothedSpeed) * a
            longitudinalAccel = (smoothedSpeed - prev) / step
        }
    }

    /** Age the estimate between fixes (called at IMU rate); decay when stale. */
    fun onIdle(dt: Double) {
        val step = dt.coerceIn(0.0, 0.2)
        sinceFixSeconds += step
        if (sinceFixSeconds > staleAfterSeconds) {
            val k = exp(-step / staleDecayTau)
            longitudinalAccel *= k
            smoothedSpeed *= k
        }
    }
}
