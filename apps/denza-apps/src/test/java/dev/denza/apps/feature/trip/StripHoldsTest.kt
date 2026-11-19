package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the live strip holds on each page.
 *
 * The audio capture is a system effect and an FFT on every callback, and only the sound page draws
 * the analyser it feeds. The car page used to keep it running for as long as it was up.
 */
class StripHoldsTest {

    @Test
    fun theCarPageHoldsTheCarAndNotTheAudio() {
        assertEquals(StripHolds(spectrum = false, vehicle = true), StripHolds.of(live = true, page = StripPage.VEHICLE))
    }

    @Test
    fun theSoundPageHoldsTheAudioAndNotTheCar() {
        assertEquals(StripHolds(spectrum = true, vehicle = false), StripHolds.of(live = true, page = StripPage.SOUND))
    }

    @Test
    fun aStripThatIsNotLiveHoldsNothing() {
        StripPage.entries.forEach { page ->
            assertEquals(page.name, StripHolds(spectrum = false, vehicle = false), StripHolds.of(live = false, page = page))
        }
    }
}
