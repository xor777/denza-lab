package dev.denza.apps.feature.trip

import kotlin.math.exp

/**
 * The temporal behaviour of the analyser: how a bar rises, how it falls back,
 * and how the peak marker above it hangs before dropping.
 *
 * Kept apart from both the audio maths and the drawing so it can be stepped with
 * an arbitrary `dt` in tests. Every rate is per-second and integrated against the
 * real frame delta, so the motion is identical whether the panel is running at
 * its 30 FPS cap or dropping frames.
 */
class SpectrumDynamics(private val bandCount: Int) {

    /** Current bar heights, 0..1. */
    val bars = FloatArray(bandCount)

    /** Peak-hold markers, 0..1. */
    val peaks = FloatArray(bandCount)

    private val peakVelocity = FloatArray(bandCount)
    private val holdRemaining = FloatArray(bandCount)

    fun update(targets: FloatArray, dtSec: Double) {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        val decay = (1.0 - exp(-dt / DECAY_TAU_SEC)).toFloat()
        for (band in 0 until bandCount) {
            val target = targets[band]
            // Instant attack, damped release: a transient should reach full height
            // on the frame it happens, which is what makes the display feel
            // connected to the music rather than lagging behind it.
            bars[band] = if (target >= bars[band]) target else bars[band] + (target - bars[band]) * decay

            if (bars[band] >= peaks[band]) {
                peaks[band] = bars[band]
                peakVelocity[band] = 0f
                holdRemaining[band] = HOLD_SEC
            } else if (holdRemaining[band] > 0f) {
                holdRemaining[band] -= dt.toFloat()
            } else {
                peakVelocity[band] += (PEAK_GRAVITY * dt).toFloat()
                peaks[band] = (peaks[band] - peakVelocity[band]).coerceAtLeast(bars[band])
            }
        }
    }

    /** Collapses everything to rest, for when playback stops. */
    fun settle(dtSec: Double) {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        val decay = (1.0 - exp(-dt / SETTLE_TAU_SEC)).toFloat()
        for (band in 0 until bandCount) {
            bars[band] -= bars[band] * decay
            peaks[band] -= peaks[band] * decay
            peakVelocity[band] = 0f
            holdRemaining[band] = 0f
        }
    }

    private companion object {
        const val MIN_DT = 1.0 / 240.0
        const val MAX_DT = 1.0 / 10.0
        const val DECAY_TAU_SEC = 0.17
        const val SETTLE_TAU_SEC = 0.5
        const val HOLD_SEC = 0.62f
        const val PEAK_GRAVITY = 1.15
    }
}
