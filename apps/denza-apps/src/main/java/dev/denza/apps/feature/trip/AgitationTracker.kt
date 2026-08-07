package dev.denza.apps.feature.trip

import kotlin.math.exp

/**
 * Turns the per-sample agitation magnitude (m/s^2, blended in [TripEngine] from
 * the physics lateral/longitudinal channels plus weighted vertical) into the
 * smoothed figures the panel reads: the calmness widening the journey thread's
 * halo and the vertical energy swelling its head.
 *
 * Tuning is against realistic physics magnitudes (comfortable corner ~2-3 m/s^2,
 * normal brake ~1.5-3 m/s^2, bump spikes ~2-6 m/s^2 vertical).
 *
 * Pure and JVM-testable.
 */
class AgitationTracker(
    /** Smoothing time constant for the agitation EMA, seconds. */
    private val emaTau: Double = 2.5,
) {
    private var seeded = false

    /** Exponentially smoothed agitation, m/s^2. */
    var smoothedAgitation: Double = 0.0
        private set

    /** Fast EMA of vertical energy for the journey thread's head halo. */
    var verticalEnergy: Double = 0.0
        private set

    fun update(agitation: Double, verticalAbs: Double, dt: Double) {
        val step = dt.coerceIn(0.0, 0.2)
        if (!seeded) {
            smoothedAgitation = agitation
            verticalEnergy = verticalAbs
            seeded = true
        } else {
            val a = 1.0 - exp(-step / emaTau)
            smoothedAgitation += (agitation - smoothedAgitation) * a
            val av = 1.0 - exp(-step / 0.6)
            verticalEnergy += (verticalAbs - verticalEnergy) * av
        }
    }
}
