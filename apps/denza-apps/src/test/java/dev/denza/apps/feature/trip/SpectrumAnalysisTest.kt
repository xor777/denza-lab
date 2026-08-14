package dev.denza.apps.feature.trip

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumBandMapTest {

    private fun map(bandCount: Int = 48) = SpectrumBandMap(
        bandCount = bandCount,
        captureSize = CAPTURE,
        sampleRateHz = RATE,
        minHz = SpectrumSource.MIN_HZ,
        maxHz = SpectrumSource.MAX_HZ,
    )

    @Test
    fun `band centres rise monotonically across the range`() {
        val centres = map().centreHz
        for (band in 1 until centres.size) {
            assertTrue("band $band must sit above ${band - 1}", centres[band] > centres[band - 1])
        }
        assertTrue(centres.first() >= SpectrumSource.MIN_HZ)
        assertTrue(centres.last() <= SpectrumSource.MAX_HZ)
    }

    @Test
    fun `a tone lands in the band covering its frequency`() {
        // 1 kHz at the calibrated 48 kHz rate is bin 21 — the value measured on
        // the car, and the reason the map must not use the reported 44100.
        val bin = (1000.0 / (RATE.toDouble() / CAPTURE)).roundToInt()
        assertEquals(21, bin)

        val fft = ByteArray(CAPTURE)
        fft[2 * bin] = 100
        fft[2 * bin + 1] = 0

        val bandMap = map()
        val out = DoubleArray(bandMap.bandCount)
        bandMap.magnitudes(fft, out)

        val loudest = out.indices.maxByOrNull { out[it] }!!
        val centre = bandMap.centreHz[loudest]
        assertTrue("expected a band near 1 kHz, got $centre Hz", centre in 700.0..1400.0)
    }

    @Test
    fun `silence maps to zero everywhere`() {
        val bandMap = map()
        val out = DoubleArray(bandMap.bandCount)
        bandMap.magnitudes(ByteArray(CAPTURE), out)
        assertTrue(out.all { it == 0.0 })
    }

    @Test
    fun `neighbouring bass bands do not repeat the same bin`() {
        // The bug this replaces: a log-spaced band down in the bass is narrower
        // than the 46.875 Hz bin it sits in, so a plain lowBin..highBin read gave
        // the eight lowest bands one identical value and they drew as eight
        // identical bars. A spectrum that differs between bins must come out
        // differing between those bands.
        val bandMap = map()
        val fft = ByteArray(CAPTURE)
        fft[2 * 1] = 20
        fft[2 * 2] = 90
        fft[2 * 3] = 40
        val out = DoubleArray(bandMap.bandCount)
        bandMap.magnitudes(fft, out)

        val bass = out.take(8)
        assertEquals("this test only means something over the bass", 8, bass.size)
        assertTrue(
            "the lowest bands must not all read the same bin: $bass",
            bass.toSet().size >= 6,
        )
        for (band in 1 until 8) {
            assertTrue(
                "band $band must differ from ${band - 1} (${out[band]} vs ${out[band - 1]})",
                out[band] != out[band - 1],
            )
        }
    }

    @Test
    fun `bass bands track the spectrum between two bins`() {
        // Interpolation must follow the data, not invent it: with bin 2 far
        // louder than bin 1, bands climbing towards bin 2 must rise.
        val bandMap = map()
        val fft = ByteArray(CAPTURE)
        fft[2 * 1] = 10
        fft[2 * 2] = 100
        val out = DoubleArray(bandMap.bandCount)
        bandMap.magnitudes(fft, out)
        for (band in 1 until 6) {
            assertTrue(
                "band $band should climb towards the loud bin 2",
                out[band] > out[band - 1],
            )
        }
    }

    private companion object {
        const val CAPTURE = 1024
        const val RATE = SpectrumSource.CALIBRATED_RATE_HZ
    }
}

class SpectrumLevelsTest {

    private fun prepared(): Pair<SpectrumLevels, SpectrumBandMap> {
        val map = SpectrumBandMap(
            BANDS, 1024, SpectrumSource.CALIBRATED_RATE_HZ, SpectrumSource.MIN_HZ, SpectrumSource.MAX_HZ,
        )
        val levels = SpectrumLevels(BANDS)
        levels.prepare(map)
        return levels to map
    }

    @Test
    fun `silence reports no signal and flat bars`() {
        val (levels, _) = prepared()
        val out = FloatArray(BANDS)
        levels.normalise(DoubleArray(BANDS), out, 1.0 / 30.0)
        assertFalse(levels.hasSignal())
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `a loud band reports signal and reaches the top of the scale`() {
        val (levels, _) = prepared()
        val magnitudes = DoubleArray(BANDS)
        magnitudes[10] = 90.0
        val out = FloatArray(BANDS)
        repeat(30) { levels.normalise(magnitudes, out, 1.0 / 30.0) }
        assertTrue(levels.hasSignal())
        assertTrue("loud band should be near full height, was ${out[10]}", out[10] > 0.9f)
    }

    @Test
    fun `automatic gain lifts a quiet mix back up the scale`() {
        val (levels, _) = prepared()
        val quiet = DoubleArray(BANDS)
        // Far enough below the scale's initial ceiling that the gain has real
        // work to do; 4.0 sat close enough to it that adaptation was invisible.
        quiet[10] = 1.0
        val out = FloatArray(BANDS)
        // One frame: the ceiling still sits at its loud initial value.
        levels.normalise(quiet, out, 1.0 / 30.0)
        val immediate = out[10]
        // After a few seconds of the same quiet material the gain has adapted.
        repeat(300) { levels.normalise(quiet, out, 1.0 / 30.0) }
        assertTrue(
            "quiet material should climb once the gain adapts ($immediate -> ${out[10]})",
            out[10] > immediate + 0.3f,
        )
    }

    @Test
    fun `the spectral tilt lifts treble above bass for equal magnitudes`() {
        val (levels, _) = prepared()
        // Quiet enough that neither end clips: at 30.0 the tilt pushed both the
        // bass and the treble past the top of the scale and the comparison was
        // between two clamped 1.0s.
        val flat = DoubleArray(BANDS) { 2.0 }
        val out = FloatArray(BANDS)
        levels.normalise(flat, out, 1.0 / 30.0)
        assertTrue(
            "treble must be tilted up against equal-magnitude bass",
            out[BANDS - 1] > out[0],
        )
    }

    private companion object {
        const val BANDS = 48
    }
}

class SpectrumDynamicsTest {

    @Test
    fun `bars rise instantly and fall gradually`() {
        val dynamics = SpectrumDynamics(4)
        val loud = floatArrayOf(1f, 1f, 1f, 1f)
        dynamics.update(loud, 1.0 / 30.0)
        assertEquals(1f, dynamics.bars[0], 1e-4f)

        val silent = FloatArray(4)
        dynamics.update(silent, 1.0 / 30.0)
        assertTrue("must not snap to zero", dynamics.bars[0] > 0.5f)
        assertTrue("must start falling", dynamics.bars[0] < 1f)
    }

    @Test
    fun `peaks hang before they fall and never sink below the bar`() {
        val dynamics = SpectrumDynamics(1)
        dynamics.update(floatArrayOf(1f), 1.0 / 30.0)
        assertEquals(1f, dynamics.peaks[0], 1e-4f)

        val silent = FloatArray(1)
        // Still inside the hold window.
        repeat(10) { dynamics.update(silent, 1.0 / 30.0) }
        assertEquals("peak should still be held", 1f, dynamics.peaks[0], 1e-3f)

        // Well past it.
        repeat(60) { dynamics.update(silent, 1.0 / 30.0) }
        assertTrue("peak should have fallen", dynamics.peaks[0] < 0.9f)
        assertTrue("peak may not sink under the bar", dynamics.peaks[0] >= dynamics.bars[0])
    }

    @Test
    fun `motion is frame rate independent`() {
        val fast = SpectrumDynamics(1)
        val slow = SpectrumDynamics(1)
        fast.update(floatArrayOf(1f), 1.0 / 60.0)
        slow.update(floatArrayOf(1f), 1.0 / 60.0)

        val silent = FloatArray(1)
        repeat(30) { fast.update(silent, 1.0 / 60.0) }
        repeat(15) { slow.update(silent, 1.0 / 30.0) }

        assertEquals(
            "half a second of decay must match at either frame rate",
            fast.bars[0].toDouble(), slow.bars[0].toDouble(), 0.02,
        )
    }

    @Test
    fun `settle collapses the display`() {
        val dynamics = SpectrumDynamics(2)
        dynamics.update(floatArrayOf(1f, 1f), 1.0 / 30.0)
        repeat(120) { dynamics.settle(1.0 / 30.0) }
        assertTrue(dynamics.bars.all { it < 0.02f })
        assertTrue(dynamics.peaks.all { it < 0.02f })
    }
}
