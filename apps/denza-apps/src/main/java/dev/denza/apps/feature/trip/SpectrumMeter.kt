package dev.denza.apps.feature.trip

/**
 * The analyser's motion, apart from its drawing: capture in, bar heights and crowns out.
 *
 * This is what `SpectrumRenderer` used to do before it drew anything - read the newest capture,
 * turn the magnitudes into heights through the tilt and the automatic gain ([SpectrumLevels]), and
 * integrate them through the attack, the release and the peak-hold ([SpectrumDynamics]). It is its
 * own class now because the drawing no longer reads a source at all: the strip draws a
 * [StripModel], and a model can come from here or from a board's frozen scene.
 *
 * [bars] and [peaks] are the dynamics' own arrays, handed to the model by reference: the heights
 * are the board's `levels` and the peaks are its `crowns`.
 */
class SpectrumMeter {

    private val magnitudes = DoubleArray(SpectrumSource.BAND_COUNT)
    private val targets = FloatArray(SpectrumSource.BAND_COUNT)
    private val dynamics = SpectrumDynamics(SpectrumSource.BAND_COUNT)
    private val levels = SpectrumLevels(SpectrumSource.BAND_COUNT)
    private var prepared = false

    val bars: FloatArray get() = dynamics.bars
    val peaks: FloatArray get() = dynamics.peaks

    /**
     * One frame: a fresh capture moves the bars toward it, a stale or silent one settles them.
     *
     * The spectral tilt is prepared once, on the first frame the capture is attached, from the same
     * band layout the capture uses - its centres are what the tilt is a function of.
     */
    fun advance(source: SpectrumSource, dtSec: Double) {
        if (!prepared && source.centreHz != null) {
            levels.prepare(
                SpectrumBandMap(
                    bandCount = SpectrumSource.BAND_COUNT,
                    captureSize = FFT_CAPTURE_SIZE,
                    sampleRateHz = SpectrumSource.CALIBRATED_RATE_HZ,
                    minHz = SpectrumSource.MIN_HZ,
                    maxHz = SpectrumSource.MAX_HZ,
                ),
            )
            prepared = true
        }
        val playing = if (source.snapshot(magnitudes)) {
            levels.normalise(magnitudes, targets, dtSec)
            levels.hasSignal()
        } else {
            false
        }
        if (playing) dynamics.update(targets, dtSec) else dynamics.settle(dtSec)
    }

    private companion object {
        /** The Visualizer's maximum capture size, which the tilt's band centres are laid out on. */
        const val FFT_CAPTURE_SIZE = 1024
    }
}
