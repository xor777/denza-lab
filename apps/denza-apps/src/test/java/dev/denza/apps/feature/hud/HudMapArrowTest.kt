package dev.denza.apps.feature.hud

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor search on drawn images: a 320×200 analysis image of the main screen, where one dp of
 * Yandex's is a quarter of a pixel (density 2, ×0.125), on Yandex's night ground.
 */
class HudMapArrowTest {
    private val width = 320
    private val height = 200
    private val pxPerDp = 0.25f

    @Test
    fun theCursorsColoursAreAmberAndTheDayRoadIsNot() {
        assertTrue(HudMapArrow.isCursorColour(0xFFF9C21B.toInt()))
        assertTrue(HudMapArrow.isCursorColour(0xFFE8A317.toInt()))
        // A day-theme major road: pale yellow with a lot of blue in it.
        assertFalse(HudMapArrow.isCursorColour(0xFFFFE08A.toInt()))
        assertFalse(HudMapArrow.isCursorColour(NIGHT))
        assertFalse(HudMapArrow.isCursorColour(0xFFFFFFFF.toInt()))
    }

    @Test
    fun anUpwardTriangleIsFound() {
        val image = ground()
        // About 100 dp tall: 25 pixels here.
        triangle(image, centreX = 160, tipY = 120, height = 25, colour = CURSOR)

        val found = HudMapArrowSearch().find(image, width, height, pxPerDp, 160f, 142f)

        assertNotNull(found)
        assertEquals(160f, found!!.x, 1f)
        assertTrue("centroid below the tip", found.y > 120f && found.y < 145f)
        assertEquals(25, found.height)
    }

    @Test
    fun aLongYellowRoadIsNotTheCursor() {
        val image = ground()
        // A horizontal stretch of yellow traffic, 6 px thick and 200 long.
        rectangle(image, 40, 60, 240, 66, TRAFFIC)
        // And a diagonal one, 168 dp across: its box is square and of the cursor's size, but it
        // fills a sliver of it.
        for (step in 0 until 40) {
            rectangle(image, 200 + step, 100 + step, 203 + step, 103 + step, TRAFFIC)
        }

        assertEquals(emptyList<HudMapBlob>(), HudMapArrowSearch().candidates(image, width, height, pxPerDp))
    }

    @Test
    fun aFilledYellowButtonIsNotTheCursor() {
        val image = ground()
        rectangle(image, 20, 150, 50, 175, CURSOR)

        assertNull(HudMapArrowSearch().find(image, width, height, pxPerDp, 35f, 160f))
    }

    @Test
    fun tooSmallAndTooLargeTrianglesAreNotTheCursor() {
        val image = ground()
        // 5 px is 20 dp; 70 px is 280 dp.
        triangle(image, centreX = 40, tipY = 20, height = 5, colour = CURSOR)
        triangle(image, centreX = 200, tipY = 10, height = 70, colour = CURSOR)

        assertEquals(emptyList<HudMapBlob>(), HudMapArrowSearch().candidates(image, width, height, pxPerDp))
    }

    @Test
    fun ofTwoCursorsTheOneNearestTheExpectedPlaceWins() {
        val image = ground()
        triangle(image, centreX = 80, tipY = 40, height = 25, colour = CURSOR)
        triangle(image, centreX = 240, tipY = 130, height = 25, colour = CURSOR)
        val search = HudMapArrowSearch()

        assertEquals(240f, search.find(image, width, height, pxPerDp, 230f, 150f)!!.x, 1f)
        assertEquals(80f, search.find(image, width, height, pxPerDp, 100f, 50f)!!.x, 1f)
    }

    @Test
    fun scatteredNoiseInTheCursorsColourProposesNothing() {
        val image = ground()
        val random = Random(7)
        repeat(400) { image[random.nextInt(image.size)] = CURSOR }

        assertEquals(emptyList<HudMapBlob>(), HudMapArrowSearch().candidates(image, width, height, pxPerDp))
    }

    @Test
    fun aSearchReusedOnAnotherImageForgetsTheFirst() {
        val search = HudMapArrowSearch()
        val first = ground()
        triangle(first, centreX = 160, tipY = 120, height = 25, colour = CURSOR)
        assertNotNull(search.find(first, width, height, pxPerDp, 160f, 140f))

        assertNull(search.find(ground(), width, height, pxPerDp, 160f, 140f))
    }

    private fun ground() = IntArray(width * height) { NIGHT }

    /** An isosceles triangle pointing up, as wide at its base as it is tall. */
    private fun triangle(image: IntArray, centreX: Int, tipY: Int, height: Int, colour: Int) {
        for (row in 0 until height) {
            val half = row / 2
            for (x in centreX - half..centreX + half) {
                val y = tipY + row
                if (x in 0 until width && y in 0 until this.height) image[y * width + x] = colour
            }
        }
    }

    private fun rectangle(image: IntArray, left: Int, top: Int, right: Int, bottom: Int, colour: Int) {
        for (y in top until bottom) for (x in left until right) image[y * width + x] = colour
    }

    private companion object {
        val NIGHT = 0xFF1C2333.toInt()
        val CURSOR = 0xFFF9C21B.toInt()
        val TRAFFIC = 0xFFFFC800.toInt()
    }
}
