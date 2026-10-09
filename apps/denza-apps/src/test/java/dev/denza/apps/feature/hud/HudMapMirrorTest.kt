package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The half-size mirror: its size, the window list brought into its pixels, and its pace. */
class HudMapMirrorTest {

    @Test
    fun theMirrorIsHalfTheScreen() {
        assertEquals(1280, HudMapMirror.width(2560))
        assertEquals(800, HudMapMirror.height(1600))
        assertEquals(1, HudMapMirror.width(1))
    }

    @Test
    fun windowsAreScaledIntoTheMirrorsPixels() {
        val placement = YandexMapPlacement.MainScreen(
            window = PxRect(1700, 0, 2550, 1600),
            above = listOf(HudMapWindow("x", 2, 9, PxRect(1601, 701, 2401, 1301))),
        )

        val mirrored = HudMapMirror.scale(placement, 0.5f)

        assertEquals(PxRect(850, 0, 1275, 800), mirrored.window)
        // Rounded edges, the rest of the window as it was.
        assertEquals(PxRect(801, 351, 1201, 651), mirrored.above.single().bounds)
        assertEquals("x", mirrored.above.single().packageName)
    }

    @Test
    fun theCutInTheMirrorIsTheSameDpAsOnTheScreen() {
        // Density 2 halved is 1: 576 dp is 576 mirror pixels, as on the 160 dpi display of 2026-09-23.
        val crop = HudMapGeometry.crop(HudMapMirror.scale(PxRect(0, 0, 2560, 1600), 0.5f), 2f * 0.5f, 640, 568)

        assertEquals(576, crop.width)
        assertEquals(346, crop.height)
    }

    @Test
    fun aFrameIsDueOncePerIntervalFromWhenTheLastBegan() {
        val pacer = HudMapPacer(83L)

        assertTrue(pacer.due(1_000))
        pacer.started(1_000)
        assertFalse(pacer.due(1_016))
        assertFalse(pacer.due(1_082))
        assertTrue(pacer.due(1_083))
    }

    @Test
    fun aSlowFrameIsFollowedByOneFrameNotByTheBacklog() {
        val pacer = HudMapPacer(83L)
        pacer.started(1_000)
        // The frame took 250 ms; three pictures arrived meanwhile and are seen now, newest first.
        assertTrue(pacer.due(1_250))
        pacer.started(1_250)

        assertFalse(pacer.due(1_251))
        assertFalse(pacer.due(1_266))
    }
}
