package dev.denza.apps.feature.trip

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Raw, un-tilted output-mix level shared by drawing and background automation. */
object SpectrumSignal {
    fun loudestDb(magnitudes: DoubleArray): Double {
        var loudest = SpectrumLevels.SILENCE_DB
        for (magnitude in magnitudes) {
            val db = if (magnitude <= 0.0) {
                SpectrumLevels.SILENCE_DB
            } else {
                20.0 * log10(magnitude / SpectrumLevels.FULL_SCALE)
            }
            if (db > loudest) loudest = db
        }
        return loudest
    }

    fun hasSignal(magnitudes: DoubleArray): Boolean =
        loudestDb(magnitudes) > SpectrumLevels.SIGNAL_GATE_DB
}

/**
 * Log-spaced band layout over the Visualizer's FFT output.
 *
 * The byte array the Visualizer hands back is laid out as: `fft[0]` the DC real
 * part, `fft[1]` the Nyquist real part, then interleaved real/imaginary pairs for
 * bins `1 .. captureSize/2 - 1`.
 *
 * The sample rate must be the *calibrated* one. `Visualizer.getSamplingRate()`
 * reports 44100 on this head unit while the mix genuinely runs at 48 kHz, which
 * would slide every band edge by about 9% — see docs/audio-capture-findings.md.
 */
class SpectrumBandMap(
    val bandCount: Int,
    private val captureSize: Int,
    sampleRateHz: Int,
    minHz: Double,
    maxHz: Double,
) {
    private val lowBin = IntArray(bandCount)
    private val highBin = IntArray(bandCount)
    private val centreBin = DoubleArray(bandCount)
    private val topBin = captureSize / 2 - 1

    /** Band centres, used for the frequency axis and the spectral tilt. */
    val centreHz = DoubleArray(bandCount)

    init {
        val binWidthHz = sampleRateHz.toDouble() / captureSize
        val ratio = maxHz / minHz
        for (band in 0 until bandCount) {
            val lowHz = minHz * ratio.pow(band.toDouble() / bandCount)
            val highHz = minHz * ratio.pow((band + 1).toDouble() / bandCount)
            centreHz[band] = sqrt(lowHz * highHz)
            centreBin[band] = (centreHz[band] / binWidthHz).coerceIn(1.0, topBin.toDouble())
            val low = (lowHz / binWidthHz).toInt().coerceIn(1, topBin)
            val high = (highHz / binWidthHz).toInt().coerceIn(low, topBin)
            lowBin[band] = low
            highBin[band] = high
        }
    }

    /**
     * Fills [out] with the magnitude of each band.
     *
     * Two regimes, because the FFT's resolution is coarser than the band layout
     * across the whole bass. At 48 kHz over a 1024-point capture a bin is
     * 46.875 Hz wide, while a log-spaced band down at 50 Hz is only a few Hz
     * wide — so a naive `lowBin..highBin` loop hands the eight lowest bands the
     * exact same single bin, and they draw as eight identical bars.
     *
     * A band narrower than a bin is therefore read by interpolating the spectrum
     * at its centre, which is a faithful reading of the measured data at a
     * frequency between two bins rather than a repeat of one of them. Wider
     * bands take the RMS across the bins they cover — RMS rather than the peak
     * bin, because the upper bands span many bins and a maximum there would
     * latch onto the 8-bit FFT's noise floor and leave the treble permanently
     * lit regardless of the music.
     */
    fun magnitudes(fft: ByteArray, out: DoubleArray) {
        for (band in 0 until bandCount) {
            out[band] = if (highBin[band] > lowBin[band]) {
                var sumSquares = 0.0
                var counted = 0
                for (bin in lowBin[band]..highBin[band]) {
                    val magnitude = binMagnitude(fft, bin)
                    sumSquares += magnitude * magnitude
                    counted++
                }
                if (counted == 0) 0.0 else sqrt(sumSquares / counted)
            } else {
                interpolate(fft, centreBin[band])
            }
        }
    }

    private fun interpolate(fft: ByteArray, position: Double): Double {
        val lower = position.toInt().coerceIn(1, topBin)
        val upper = (lower + 1).coerceAtMost(topBin)
        val fraction = (position - lower).coerceIn(0.0, 1.0)
        return binMagnitude(fft, lower) * (1.0 - fraction) + binMagnitude(fft, upper) * fraction
    }

    private fun binMagnitude(fft: ByteArray, bin: Int): Double =
        hypot(fft[2 * bin].toDouble(), fft[2 * bin + 1].toDouble())
}

/**
 * Turns raw band magnitudes into the 0..1 heights the bars are drawn at.
 *
 * Two corrections make the difference between a display that reacts and one that
 * just sits there. Music's energy falls off steeply with frequency, so without a
 * spectral tilt the right-hand half of the analyser barely lifts. And cabin
 * volume varies enormously — the car was at 5/39 during the live probe — so a
 * fixed floor and ceiling would leave the bars either flat or clipped. A slow
 * automatic gain rides the recent loudest band instead.
 */
class SpectrumLevels(
    private val bandCount: Int,
    private val tiltDbPerOctave: Double = TILT_DB_PER_OCTAVE,
    private val dynamicRangeDb: Double = DYNAMIC_RANGE_DB,
) {
    private val tiltDb = DoubleArray(bandCount)

    /** The adaptive top of the scale, in dB. */
    private var ceilingDb = INITIAL_CEILING_DB

    /** Loudest band of the last processed frame; the panel's "is anything playing". */
    var signalDb: Double = SILENCE_DB
        private set

    fun prepare(map: SpectrumBandMap) {
        val baseHz = map.centreHz[0]
        for (band in 0 until bandCount) {
            tiltDb[band] = tiltDbPerOctave * log2(map.centreHz[band] / baseHz)
        }
    }

    /**
     * Maps [magnitudes] into [out] as 0..1 bar heights and advances the automatic
     * gain by [dtSec].
     */
    fun normalise(magnitudes: DoubleArray, out: FloatArray, dtSec: Double) {
        var loudest = SILENCE_DB
        var rawLoudest = SILENCE_DB
        for (band in 0 until bandCount) {
            val rawDb = toDecibels(magnitudes[band])
            val db = rawDb + tiltDb[band]
            if (rawDb > rawLoudest) rawLoudest = rawDb
            if (db > loudest) loudest = db
            out[band] = db.toFloat()
        }
        // Spectral tilt is a drawing correction, not real signal gain. Using
        // the tilted maximum here let quiet high-frequency hiss cross the gate
        // by up to ~18 dB and made the whole display look over-sensitive.
        signalDb = rawLoudest

        // The scale's top sits a little above the loudest band rather than on it:
        // pinned exactly, every band within a decibel of the loudest clamped to
        // full height together and drew as a row of flat-topped bars across the
        // bass. It rises quickly so a loud passage does not clip on the way in,
        // and falls back slowly so quiet material keeps its shape instead of
        // being pumped back up to full height.
        val target = (loudest + CEILING_HEADROOM_DB).coerceAtLeast(MIN_CEILING_DB)
        val rate = if (target > ceilingDb) CEILING_ATTACK_PER_SEC else CEILING_RELEASE_PER_SEC
        ceilingDb += (target - ceilingDb) * (1.0 - exp(-dtSec * rate))

        val floorDb = ceilingDb - dynamicRangeDb
        val span = (ceilingDb - floorDb).coerceAtLeast(1.0)
        for (band in 0 until bandCount) {
            out[band] = (((out[band] - floorDb) / span).coerceIn(0.0, 1.0)).toFloat()
        }
    }

    /** True while the mix is loud enough to be worth drawing as music. */
    fun hasSignal(): Boolean = signalDb > SIGNAL_GATE_DB

    private fun toDecibels(magnitude: Double): Double =
        if (magnitude <= 0.0) SILENCE_DB else 20.0 * log10(magnitude / FULL_SCALE)

    companion object {
        /** 8-bit FFT parts, so a full-scale component sits near 128. */
        const val FULL_SCALE = 128.0
        const val SILENCE_DB = -90.0
        const val TILT_DB_PER_OCTAVE = 2.2
        const val DYNAMIC_RANGE_DB = 40.0
        const val INITIAL_CEILING_DB = -18.0
        const val MIN_CEILING_DB = -46.0
        const val SIGNAL_GATE_DB = -58.0
        // Five decibels keeps a steady loudest band at about 87.5% of the
        // 40 dB scale. The previous 3 dB value held it at 92.5% at any cabin
        // volume, so even quiet playback looked almost full-scale.
        const val CEILING_HEADROOM_DB = 5.0
        const val CEILING_ATTACK_PER_SEC = 8.0
        const val CEILING_RELEASE_PER_SEC = 0.25
    }
}
