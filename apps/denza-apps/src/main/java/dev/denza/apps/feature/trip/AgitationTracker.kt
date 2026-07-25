package dev.denza.apps.feature.trip

import kotlin.math.exp

/**
 * Turns the per-sample agitation magnitude (m/s^2, blended in [TripEngine] from
 * the physics lateral/longitudinal channels plus weighted vertical) into the
 * comfort figures shown in mode 2 and the halo energy used in mode 1.
 *
 * Tuning is against realistic physics magnitudes (comfortable corner ~2-3 m/s^2,
 * normal brake ~1.5-3 m/s^2, bump spikes ~2-6 m/s^2 vertical): smooth city
 * driving should score ~80-90, aggressive driving ~50-70.
 *
 * Pure and JVM-testable.
 */
class AgitationTracker(
    /** Smoothing time constant for the comfort EMA, seconds. */
    private val emaTau: Double = 2.5,
    /** Agitation above this (m/s^2) counts as a "splash" and resets the calm timer. */
    private val impulseThreshold: Double = 3.5,
    /** Maps the smoothed agitation onto the 0..100 smoothness score. */
    private val scoreGain: Double = 13.0,
) {
    private var seeded = false

    /** Exponentially smoothed agitation, m/s^2. */
    var smoothedAgitation: Double = 0.0
        private set

    /** Seconds since the last significant impulse ("Без всплесков"). */
    var calmSeconds: Double = 0.0
        private set

    /** Fast EMA of vertical energy for the elevation-head halo (mode 1). */
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
        if (agitation > impulseThreshold) {
            calmSeconds = 0.0
        } else {
            calmSeconds += step
        }
    }

    /** 0..100 comfort score; higher = smoother. */
    val smoothnessScore: Int
        get() = (100.0 - smoothedAgitation * scoreGain).coerceIn(0.0, 100.0).toInt()
}
