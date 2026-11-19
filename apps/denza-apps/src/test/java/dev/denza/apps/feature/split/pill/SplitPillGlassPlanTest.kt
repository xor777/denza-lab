package dev.denza.apps.feature.split.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which prepared glass a capsule is drawn with ([PillGlassPlan]). What the glass looks like cannot
 * be checked here - `android.graphics` is only stubs in this module's unit tests and there is no
 * Robolectric - so the picture is held by the fixture check written in the KDoc of the debug
 * `SplitPillFixtureActivity`. This holds the part that is arithmetic: every height the pill can
 * take has a size class, at most half a pixel from its own height, and none past the prepared ones.
 */
class SplitPillGlassPlanTest {

    private val scale = 2.0

    @Test
    fun theDiscAndTheOpenPillHaveTheirOwnHeights() {
        assertEquals(64, PillGlassPlan.classOf(SplitPillSpec.Handle.H, scale))
        assertEquals(176, PillGlassPlan.classOf(SplitPillSpec.Pill.OPEN_H, scale))
        assertEquals(SplitPillSpec.Handle.H, PillGlassPlan.heightOf(64, scale), 0.0)
        assertEquals(SplitPillSpec.Pill.OPEN_H, PillGlassPlan.heightOf(176, scale), 0.0)
    }

    @Test
    fun everyHeightThePillTakesIsWithinHalfAPixelOfItsClass() {
        val tallest = SplitPillSpec.Pill.OPEN_H + SplitPillSpec.Pull.BAND_CAP + SplitPillSpec.Handle.SWELL
        assertTrue(PillGlassPlan.TALLEST >= tallest)
        var h = SplitPillSpec.Pill.MIN_H
        while (h <= tallest) {
            val cls = PillGlassPlan.classOf(h, scale)
            assertTrue("$h dp", cls in 1..PillGlassPlan.classes(scale))
            assertEquals("$h dp", h * scale, cls.toDouble(), 0.5)
            h += 0.01
        }
    }

    @Test
    fun aHeightPastThePreparedOnesTakesTheLargestClass() {
        assertEquals(PillGlassPlan.classes(scale), PillGlassPlan.classOf(10_000.0, scale))
        assertEquals(1, PillGlassPlan.classOf(0.0, scale))
    }
}
