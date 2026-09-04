package dev.denza.apps.feature.trip

import kotlin.math.exp

/**
 * The temporal behaviour of the analyser: how a bar rises, how it falls back,
 * how the peak marker above it hangs before dropping, and how the bloom behind
 * the bars breathes.
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

    /**
     * A slow mean of the bars, 0..1: what the bloom behind them is drawn from.
     *
     * The bloom used to take the raw per-frame mean, which made the largest lit area on the
     * panel flicker at the frame rate. A wash of light should breathe with a phrase, not
     * blink with a hi-hat.
     */
    var energy: Float = 0f
        private set

    private val peakVelocity = FloatArray(bandCount)
    private val holdRemaining = FloatArray(bandCount)

    fun update(targets: FloatArray, dtSec: Double) {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        val attack = (1.0 - exp(-dt / ATTACK_TAU_SEC)).toFloat()
        val release = (1.0 - exp(-dt / RELEASE_TAU_SEC)).toFloat()
        var sum = 0f
        for (band in 0 until bandCount) {
            val target = targets[band]
            // A short attack rather than an instant one. Each FFT frame is a single
            // 21 ms window of the mix, and the frames arrive at 20 Hz; snapping a bar
            // to every one of them drew the frame-to-frame noise of the 8-bit FFT as
            // a twitch on top of the music. Sixty milliseconds is short enough that a
            // real transient still stands at four fifths of its height within three
            // frames, and long enough that a single noisy frame reads as a bump.
            val rate = if (target >= bars[band]) attack else release
            bars[band] += (target - bars[band]) * rate
            sum += bars[band]

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
        val bloom = (1.0 - exp(-dt / BLOOM_TAU_SEC)).toFloat()
        energy += (sum / bandCount - energy) * bloom
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
        energy -= energy * decay
    }

    private companion object {
        const val MIN_DT = 1.0 / 240.0
        const val MAX_DT = 1.0 / 10.0

        /** Rise time constant. It was zero - see [update] for why it is not. */
        const val ATTACK_TAU_SEC = 0.06

        /**
         * Fall time constant. It was 0.17 s, and with the attack instant the bars spent
         * their whole life either snapping up or racing down; a little more hang lets a
         * bar carry from one beat to the next instead of collapsing between them.
         */
        const val RELEASE_TAU_SEC = 0.24
        const val SETTLE_TAU_SEC = 0.5
        const val HOLD_SEC = 0.62f
        const val PEAK_GRAVITY = 1.15
        const val BLOOM_TAU_SEC = 0.4
    }
}
