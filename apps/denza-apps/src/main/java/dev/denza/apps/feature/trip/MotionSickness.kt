package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.max

/**
 * Motion sickness for the mirror toy, counted in manoeuvres, not in averages:
 * one firm brake, corner or launch is one dose, and a sensitive passenger is
 * queasy by the third. A refractory gap keeps a single long corner from
 * counting as five. Rough road adds a slow trickle; a calm road washes it out
 * about twice as fast as it builds.
 *
 * A direct port of the approved prototype — the constants are tuned against it.
 * Pure Kotlin, no Android imports; JVM-testable.
 */
class MotionSickness {

    /** 0..1; 0 = grinning, 1 = about to lose it. */
    var nausea: Double = 0.0
        private set

    /** How many doses (manoeuvres) have been charged so far. */
    var manoeuvreCount: Int = 0
        private set

    private var armed = true
    private var refractorySeconds = 0.0
    private var peak = 0.0

    /**
     * Feed one frame of the engine's physics channels (m/s^2). A dose arms when
     * the horizontal magnitude crosses [DOSE_THRESHOLD], tracks its peak, and is
     * charged — scaled by how firm the manoeuvre was — only once the magnitude
     * falls back below [RELEASE_FRACTION] of the threshold.
     */
    fun update(dtSec: Double, lateral: Double, longitudinal: Double, vertical: Double) {
        val dt = dtSec.coerceIn(0.0, 0.1)
        val mag = max(abs(lateral), abs(longitudinal))
        refractorySeconds -= dt
        if (mag >= DOSE_THRESHOLD && armed && refractorySeconds <= 0.0) {
            armed = false
            peak = mag
        }
        if (!armed) {
            peak = max(peak, mag)
            if (mag < DOSE_THRESHOLD * RELEASE_FRACTION) {
                // The manoeuvre is over: charge for it, scaled by how firm it was.
                val dose = DOSE_VALUE * (peak / DOSE_THRESHOLD).coerceIn(1.0, PEAK_SCALE_MAX)
                nausea = (nausea + dose).coerceIn(0.0, 1.0)
                manoeuvreCount++
                armed = true
                refractorySeconds = REFRACTORY_SECONDS
                peak = 0.0
            }
        }
        // Rough road adds a slow trickle, calm road washes it out.
        if (abs(vertical) > ROUGH_VERTICAL) {
            nausea = (nausea + dt * ROUGH_RATE).coerceIn(0.0, 1.0)
        }
        nausea = (nausea - dt / RECOVERY_SECONDS).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Horizontal magnitude (m/s^2) at which a manoeuvre starts counting. */
        const val DOSE_THRESHOLD = 1.5

        /** Nausea charged per manoeuvre before the firmness scale. */
        const val DOSE_VALUE = 0.265

        /** The manoeuvre ends once the magnitude falls below this fraction. */
        const val RELEASE_FRACTION = 0.6

        /** A very firm manoeuvre charges up to this multiple of [DOSE_VALUE]. */
        const val PEAK_SCALE_MAX = 1.4

        /** Gap after a charge during which no new dose can arm, seconds. */
        const val REFRACTORY_SECONDS = 1.4

        /** Full recovery time — deliberately about twice as fast as it builds. */
        const val RECOVERY_SECONDS = 45.0

        /** |vertical| (m/s^2) above which the rough-road trickle runs. */
        const val ROUGH_VERTICAL = 1.6

        /** Rough-road trickle, nausea per second. */
        const val ROUGH_RATE = 0.024
    }
}
