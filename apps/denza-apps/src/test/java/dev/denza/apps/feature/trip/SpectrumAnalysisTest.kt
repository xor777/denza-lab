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
    fun `a single step of the converter reads as silence and a real reading survives`() {
        // At low volume the 8-bit FFT hands back a sprinkling of ±1 parts across the treble; with
        // the tilt on top they drew as a full-height comb over music nobody could hear. One step
        // in either or both parts is the converter, not the mix, and must read as nothing.
        val bandMap = map()
        val out = DoubleArray(bandMap.bandCount)
        val step = ByteArray(CAPTURE)
        for (bin in 1 until CAPTURE / 2) {
            step[2 * bin] = 1
            step[2 * bin + 1] = if (bin % 2 == 0) 1 else -1
        }
        bandMap.magnitudes(step, out)
        assertTrue("one step everywhere must read as silence: ${out.toList()}", out.all { it == 0.0 })

        // Ten steps in every bin, so the RMS bands and the interpolated ones read the same thing:
        // sqrt(100 - 2), which is 9.9 of the 10 that went in.
        val loud = ByteArray(CAPTURE)
        for (bin in 1 until CAPTURE / 2) loud[2 * bin] = 10
        bandMap.magnitudes(loud, out)
        assertTrue(
            "a ten-step reading must keep almost all of itself: ${out.toList()}",
            out.all { it in 9.85..9.95 },
        )
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
    fun `a loud steady band reports signal while retaining visible headroom`() {
        val (levels, _) = prepared()
        val magnitudes = DoubleArray(BANDS)
        magnitudes[10] = 90.0
        val out = FloatArray(BANDS)
        repeat(30) { levels.normalise(magnitudes, out, 1.0 / 30.0) }
        assertTrue(levels.hasSignal())
        // LIT_FOOT + (1 - LIT_FOOT) * (1 - CEILING_HEADROOM_DB / DYNAMIC_RANGE_DB) = 0.83, and the
        // bounds bracket it closely enough that moving any of the three has to come here and say
        // so. The range went from 40 to 36 and the headroom from 7 to 6.5 together, so the
        // loudest band stayed put while every band under it moved down the field.
        assertTrue("loud band should remain prominent, was ${out[10]}", out[10] > 0.80f)
        assertTrue("steady audio should retain headroom, was ${out[10]}", out[10] < 0.85f)
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
        // A third of the field, less the six per cent the lit foot takes off the scale: the
        // climb here is 0.285 with a 36 dB range and a -30 dB ceiling limit, and both of those
        // are tuned by eye at the car, so the bound is set to catch the gain going away, not to
        // pin the tuning.
        assertTrue(
            "quiet material should climb once the gain adapts ($immediate -> ${out[10]})",
            out[10] > immediate + 0.25f,
        )
        assertTrue(
            "quiet material should not be pumped almost to full height: ${out[10]}",
            out[10] < 0.85f,
        )
    }

    @Test
    fun `a band with anything in it keeps a foot and an empty one does not`() {
        val (levels, _) = prepared()
        val magnitudes = DoubleArray(BANDS)
        magnitudes[5] = 90.0
        // Forty decibels under the loudest band: well below the foot of the scale.
        magnitudes[20] = 0.9
        val out = FloatArray(BANDS)
        repeat(30) { levels.normalise(magnitudes, out, 1.0 / 30.0) }
        assertTrue("a faint band must still be drawn: ${out[20]}", out[20] > 0.04f)
        assertTrue("but drawn low: ${out[20]}", out[20] < 0.12f)
        assertEquals("an empty band draws nothing", 0f, out[21], 0f)
    }

    @Test
    fun `spectral tilt cannot promote sub-gate treble noise into a signal`() {
        val (levels, _) = prepared()
        val hiss = DoubleArray(BANDS) { 0.1 }
        val out = FloatArray(BANDS)

        levels.normalise(hiss, out, 1.0 / 30.0)

        assertTrue("fixture must exercise treble tilt", out[BANDS - 1] > out[0])
        assertFalse("visual tilt must not alter the raw signal gate", levels.hasSignal())
    }

    @Test
    fun `raw audio just above the gate remains a signal at every frequency`() {
        for (band in intArrayOf(0, BANDS / 2, BANDS - 1)) {
            val (levels, _) = prepared()
            val audio = DoubleArray(BANDS)
            audio[band] = 0.2

            levels.normalise(audio, FloatArray(BANDS), 1.0 / 30.0)

            assertTrue("band $band should pass the raw signal gate", levels.hasSignal())
        }
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

    private val frame = 1.0 / 30.0

    @Test
    fun `bars rise within a few frames and fall gradually`() {
        val dynamics = SpectrumDynamics(4)
        val loud = floatArrayOf(1f, 1f, 1f, 1f)
        dynamics.update(loud, frame)
        val afterOne = dynamics.bars[0]
        assertTrue("must answer on the first frame: $afterOne", afterOne > 0.35f)
        assertTrue("must not snap to full height: $afterOne", afterOne < 0.6f)
        repeat(4) { dynamics.update(loud, frame) }
        assertTrue("must be near full height within 170 ms: ${dynamics.bars[0]}", dynamics.bars[0] > 0.9f)

        val silent = FloatArray(4)
        val before = dynamics.bars[0]
        dynamics.update(silent, frame)
        assertTrue("must not snap to zero", dynamics.bars[0] > 0.7f)
        assertTrue("must start falling", dynamics.bars[0] < before)
    }

    @Test
    fun `a spike seen for two frames reads as a bump rather than a full bar`() {
        // The Visualizer delivers at 20 Hz and the panel draws at 30, so a single noisy FFT
        // frame is on screen for one or two draws. This is the twitch the attack exists to
        // soften; the previous instant attack drew every such frame at full height.
        val dynamics = SpectrumDynamics(1)
        val loud = floatArrayOf(1f)
        val silent = FloatArray(1)
        var highest = 0f
        repeat(2) {
            dynamics.update(loud, frame)
            highest = maxOf(highest, dynamics.bars[0])
        }
        repeat(10) {
            dynamics.update(silent, frame)
            highest = maxOf(highest, dynamics.bars[0])
        }
        assertTrue("a two-frame spike must stay well under full height: $highest", highest < 0.75f)
        assertTrue("but it must still be visible: $highest", highest > 0.4f)
    }

    @Test
    fun `the release is slower than the attack`() {
        val dynamics = SpectrumDynamics(1)
        dynamics.update(floatArrayOf(1f), frame)
        val rose = dynamics.bars[0]
        repeat(60) { dynamics.update(floatArrayOf(1f), frame) }
        val settled = dynamics.bars[0]
        dynamics.update(FloatArray(1), frame)
        val fell = settled - dynamics.bars[0]
        assertTrue("one frame of rise ($rose) must outrun one frame of fall ($fell)", rose > fell * 2f)
    }

    @Test
    fun `peaks hang before they fall and never sink below the bar`() {
        val dynamics = SpectrumDynamics(1)
        repeat(30) { dynamics.update(floatArrayOf(1f), frame) }
        assertTrue(dynamics.bars[0] > 0.99f)
        val held = dynamics.peaks[0]
        assertEquals(dynamics.bars[0], held, 1e-4f)

        val silent = FloatArray(1)
        // Still inside the hold window.
        repeat(10) { dynamics.update(silent, frame) }
        assertEquals("peak should still be held", held, dynamics.peaks[0], 1e-3f)

        // Well past it.
        repeat(60) { dynamics.update(silent, frame) }
        assertTrue("peak should have fallen", dynamics.peaks[0] < 0.9f)
        assertTrue("peak may not sink under the bar", dynamics.peaks[0] >= dynamics.bars[0])
    }

    @Test
    fun `motion is frame rate independent`() {
        val fast = SpectrumDynamics(1)
        val slow = SpectrumDynamics(1)
        val loud = floatArrayOf(1f)
        repeat(6) { fast.update(loud, 1.0 / 60.0) }
        repeat(3) { slow.update(loud, 1.0 / 30.0) }
        assertEquals(
            "a tenth of a second of rise must match at either frame rate",
            fast.bars[0].toDouble(), slow.bars[0].toDouble(), 0.02,
        )

        val silent = FloatArray(1)
        repeat(30) { fast.update(silent, 1.0 / 60.0) }
        repeat(15) { slow.update(silent, 1.0 / 30.0) }
        assertEquals(
            "half a second of decay must match at either frame rate",
            fast.bars[0].toDouble(), slow.bars[0].toDouble(), 0.02,
        )
    }

    @Test
    fun `the bloom energy breathes behind the bars instead of blinking with them`() {
        val dynamics = SpectrumDynamics(2)
        val loud = floatArrayOf(1f, 1f)
        repeat(4) { dynamics.update(loud, frame) }
        val mean = (dynamics.bars[0] + dynamics.bars[1]) / 2f
        assertTrue("bars are up: $mean", mean > 0.8f)
        assertTrue("the bloom is still on its way: ${dynamics.energy}", dynamics.energy < mean * 0.5f)

        repeat(60) { dynamics.update(loud, frame) }
        assertTrue("and it does arrive: ${dynamics.energy}", dynamics.energy > 0.9f)
    }

    @Test
    fun `settle collapses the display`() {
        val dynamics = SpectrumDynamics(2)
        repeat(60) { dynamics.update(floatArrayOf(1f, 1f), frame) }
        repeat(120) { dynamics.settle(frame) }
        assertTrue(dynamics.bars.all { it < 0.02f })
        assertTrue(dynamics.peaks.all { it < 0.02f })
        assertTrue(dynamics.energy < 0.02f)
    }
}
