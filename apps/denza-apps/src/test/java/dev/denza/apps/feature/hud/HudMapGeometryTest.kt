package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cut in Yandex's dp, placed around the cursor and kept inside Yandex's window. */
class HudMapGeometryTest {
    private val mainScreen = PxRect(0, 0, 2560, 1600)

    @Test
    fun theCutIsTheSameDpAtEveryDensity() {
        // 490×294 dp since 2026-10-11 (576×346 before). The main screen is 320 dpi, density 2.
        val parked = HudMapGeometry.crop(PxRect(0, 0, 960, 576), 1f, 480, 400)
        val main = HudMapGeometry.crop(mainScreen, 2f, 1280, 1136)

        assertEquals(490, parked.width)
        assertEquals(294, parked.height)
        assertEquals(980, main.width)
        assertEquals(588, main.height)
    }

    @Test
    fun theCursorIsCentredAtThreeQuartersDown() {
        val crop = HudMapGeometry.crop(mainScreen, 2f, 1280, 1136)

        assertEquals(1280, crop.left + crop.width / 2)
        // 0.77 of 588 is 453: the road ahead takes the 453 pixels above the cursor.
        assertEquals(1136 - 453, crop.top)
    }

    @Test
    fun aCursorNearTheEdgeMovesTheCutInsideTheWindow() {
        val window = PxRect(100, 50, 2660, 1650)
        val crop = HudMapGeometry.crop(window, 2f, 150, 1640)

        assertEquals(window.left, crop.left)
        assertEquals(window.bottom, crop.bottom)
        assertEquals(980, crop.width)
    }

    @Test
    fun aOneThirdPaneShrinksTheCutAndKeepsFiveToThree() {
        // A one-third split pane: about 425 dp wide at density 2.
        val pane = PxRect(1700, 0, 2550, 1600)
        val crop = HudMapGeometry.crop(pane, 2f, 2125, 1136)

        assertEquals(pane.width, crop.width)
        assertEquals(510, crop.height)
        assertEquals(pane.left, crop.left)
        assertEquals(pane.right, crop.right)
    }

    @Test
    fun aWindowLowerThanTheCutShrinksItByHeight() {
        val strip = PxRect(0, 0, 2560, 400)
        val crop = HudMapGeometry.crop(strip, 2f, 1280, 300)

        assertEquals(400, crop.height)
        assertEquals(666, crop.width)
        assertTrue(crop.top >= strip.top && crop.bottom <= strip.bottom)
    }

    @Test
    fun everyInstrumentsPlacementTakesTheSameDpCutAsTheMainScreen() {
        // The navigation display on the 2560×720 instruments: pixels and dpi of each placement
        // (ClusterMapLayout); the cut is 490 dp in all of them, cursor in the middle.
        val placements = listOf(
            Triple("FULL", PxRect(0, 0, 2560, 720), 272),
            Triple("LEFT", PxRect(0, 0, 1023, 609), 272),
            Triple("RIGHT", PxRect(0, 0, 1023, 524), 272),
            Triple("CENTER", PxRect(0, 0, 1023, 720), 320),
        )
        for ((name, window, dpi) in placements) {
            val density = HudMapClusterSource.density(dpi)
            val crop = HudMapGeometry.crop(window, density, window.width / 2, window.height * 3 / 4)

            assertEquals(name, Math.round(HudMapGeometry.CROP_WIDTH_DP * density), crop.width)
            assertTrue(name, crop.left >= 0 && crop.top >= 0 && crop.right <= window.right && crop.bottom <= window.bottom)
        }
    }

    @Test
    fun theAnalysisImageIsThreeHundredTwentyWideInTheWindowsProportions() {
        assertEquals(320, HudMapGeometry.analysisWidth(mainScreen))
        assertEquals(200, HudMapGeometry.analysisHeight(mainScreen))
        val narrow = PxRect(0, 0, 200, 300)
        assertEquals(200, HudMapGeometry.analysisWidth(narrow))
        assertEquals(300, HudMapGeometry.analysisHeight(narrow))
    }

    @Test
    fun overlapsNeedsDepthOnBothAxes() {
        val crop = PxRect(100, 100, 300, 300)

        assertEquals(false, crop.overlaps(PxRect(300, 100, 400, 300)))
        assertEquals(false, crop.overlaps(PxRect(290, 100, 400, 300), depth = 11))
        assertEquals(true, crop.overlaps(PxRect(290, 100, 400, 300), depth = 10))
    }
}
