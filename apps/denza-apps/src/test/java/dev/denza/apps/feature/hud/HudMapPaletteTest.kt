package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each role of a night map in its own colour on the glass, Yandex's shapes kept: scenes drawn at
 * the palette's doubled size in Yandex's keyed night colours (measured on the drive of 2026-10-11),
 * read back at the map window's 300×180.
 */
class HudMapPaletteTest {

    @Test
    fun trafficColoursComeOutInTheProjectorsOwn() {
        val scene = canvas()
        scene.rect(100, 20, 112, 220, FREE)
        scene.rect(200, 20, 212, 220, SLOW)
        scene.rect(420, 20, 432, 220, JAM)

        val out = paint(scene)

        assertColour(HudMapPalette.GREEN, out.at(53, 60))
        assertColour(HudMapPalette.YELLOW, out.at(103, 60))
        assertColour(HudMapPalette.RED, out.at(213, 60))
    }

    @Test
    fun aGreyRouteIsCyanAndItsManeuverArrowWhite() {
        val scene = canvas()
        scene.rect(150, 60, 162, 260, GREY)
        scene.rect(146, 40, 166, 60, WHITE_INK)

        val out = paint(scene)

        assertColour(HudMapPalette.CYAN, out.at(78, 80))
        assertColour(HudMapPalette.WHITE, out.at(78, 25))
    }

    @Test
    fun aBlueRouteIsCyanToo() {
        val scene = canvas()
        scene.rect(100, 20, 112, 270, BLUE)

        assertColour(HudMapPalette.CYAN, paint(scene).at(53, 70))
    }

    @Test
    fun aHouseNumberGoesWhole() {
        val scene = canvas()
        // An "L" of 3-pixel strokes: thinner than the grey route, smaller than a road.
        scene.rect(100, 100, 103, 118, NUMBER)
        scene.rect(100, 115, 112, 118, NUMBER)

        val out = paint(scene)

        assertEquals(BLACK, out.at(50, 54))
        assertEquals(BLACK, out.at(53, 58))
    }

    @Test
    fun everyRoadIsFlatDimWhiteAWideOneToo() {
        val scene = canvas()
        scene.rect(0, 40, 600, 44, ROAD)
        scene.rect(0, 100, 600, 160, ROAD)

        val out = paint(scene)

        val road = HudMapPalette.ROAD.map { it * HudMapPalette.ROAD_LEVEL }.toFloatArray()
        assertColour(road, out.at(100, 21))
        // No outline: the wide road's edge and its middle alike.
        assertColour(road, out.at(100, 50))
        assertColour(road, out.at(100, 65))
    }

    @Test
    fun theCursorInTheBottomMiddleIsOrange() {
        val scene = canvas()
        scene.rect(285, 262, 315, 292, AMBER)

        assertColour(HudMapPalette.CURSOR, paint(scene).at(150, 138))
    }

    @Test
    fun amberAwayFromTheCursorsPlaceIsSlowTraffic() {
        val scene = canvas()
        scene.rect(60, 40, 90, 70, AMBER)

        assertColour(HudMapPalette.YELLOW, paint(scene).at(37, 27))
    }

    @Test
    fun aRoadSignStaysAsYandexDrewItWithItsPlateDimmed() {
        val scene = canvas()
        scene.rect(98, 98, 128, 128, WHITE_INK)
        scene.rect(100, 100, 126, 126, BLUE)
        scene.rect(109, 109, 117, 117, WHITE_INK)

        val out = paint(scene)

        assertColour(dimmed(BLUE), out.at(51, 51))
        assertColour(HudMapPalette.WHITE, out.at(56, 56))
    }

    @Test
    fun theLaneBalloonStaysWithItsPlateDimmedAndEveryArrowWhole() {
        val scene = canvas()
        scene.rect(100, 40, 210, 116, BLUE)
        scene.rect(150, 50, 156, 106, WHITE_INK)
        // A lane that does not go the route's way: a light-blue arrow, thick enough to be outlined
        // if it were taken for road.
        scene.rect(120, 50, 132, 106, LANE_OFF)

        val out = paint(scene)

        assertColour(dimmed(BLUE), out.at(55, 30))
        assertColour(HudMapPalette.WHITE, out.at(76, 39))
        assertColour(floatArrayOf(115 / 255f, 163 / 255f, 254 / 255f), out.at(63, 39))
    }

    @Test
    fun aPinGoesAndTheRouteRunsOnThroughIt() {
        val scene = canvas()
        scene.rect(100, 20, 112, 300, FREE)
        // A drop, taller than wide and with no white frame: a pin, not a sign.
        scene.rect(96, 138, 116, 162, BLUE)

        val out = paint(scene)

        assertColour(HudMapPalette.GREEN, out.at(53, 75))
        assertEquals(BLACK, out.at(48, 75))
    }

    @Test
    fun aStreetNameWrittenOnTheRouteIsClosedOver() {
        val scene = canvas()
        scene.rect(100, 20, 112, 300, FREE)
        scene.rect(100, 148, 112, 152, BLACK)

        assertColour(HudMapPalette.GREEN, paint(scene).at(53, 75))
    }

    @Test
    fun theOutputIsTheMapWindowsOpaqueFrame() {
        val out = paint(canvas())

        assertEquals(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT, out.size)
        assertTrue(out.all { it == BLACK })
    }

    @Test
    fun onlyANightGroundIsRepainted() {
        // Yandex's night ground on the drive of 2026-10-11, and a day map's light grey.
        assertTrue(HudMapPalette.isNight(0xFF232C3F.toInt()))
        assertFalse(HudMapPalette.isNight(0xFFE8E8E8.toInt()))
    }

    @Test
    fun aDayMapIsOnlyReducedTwoByTwo() {
        val scene = canvas()
        scene[0] = WHITE_INK
        val out = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT)

        HudMapPalette.reduce(scene, out)

        assertEquals(0xFF404040.toInt(), out[0])
        assertEquals(BLACK, out[1])
    }

    @Test
    fun maskStepsCrossWordBoundariesAndLeaveTheEdgeAlone() {
        val width = 130
        val height = 3
        val bits = HudMapBits(width, height)
        val mask = BooleanArray(width * height)
        mask[width + 63] = true

        bits.load(mask)
        bits.dilate(1)
        bits.store(mask)

        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals("($x, $y)", x in 62..64, mask[y * width + x])
            }
        }

        // Past the edge counts as set to an erosion: a full mask stays full, a lone pixel goes.
        val full = BooleanArray(width * height) { true }
        bits.load(full)
        bits.erode(1)
        bits.store(full)
        assertTrue(full.all { it })
        bits.load(BooleanArray(width * height).also { it[width + 64] = true })
        bits.erode(1)
        bits.store(full)
        assertTrue(full.none { it })
    }

    private fun canvas() = IntArray(HudMapPalette.WIDTH * HudMapPalette.HEIGHT) { BLACK }

    /** Fills [x0, x1) × [y0, y1) of a doubled-size scene. */
    private fun IntArray.rect(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * HudMapPalette.WIDTH + x] = colour
    }

    private fun paint(scene: IntArray): IntArray {
        val out = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT)
        HudMapPalette().apply(scene, out)
        return out
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * HudMapGeometry.MAP_WIDTH + x]

    private fun dimmed(colour: Int): FloatArray = floatArrayOf(
        (colour shr 16 and 0xFF) / 255f * HudMapPalette.PLATE_LEVEL,
        (colour shr 8 and 0xFF) / 255f * HudMapPalette.PLATE_LEVEL,
        (colour and 0xFF) / 255f * HudMapPalette.PLATE_LEVEL,
    )

    private fun assertColour(expected: FloatArray, actual: Int) {
        val channels = intArrayOf(actual shr 16 and 0xFF, actual shr 8 and 0xFF, actual and 0xFF)
        for (c in 0..2) {
            val want = (expected[c] * 255f + 0.5f).toInt()
            assertTrue(
                "channel $c: want $want in ${Integer.toHexString(actual)}",
                kotlin.math.abs(channels[c] - want) <= 2,
            )
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val FREE = 0xFF82E90D.toInt()
        const val SLOW = 0xFFFFFF41.toInt()
        const val JAM = 0xFFF03C3C.toInt()
        const val GREY = 0xFFA0A0A0.toInt()
        const val WHITE_INK = 0xFFFFFFFF.toInt()
        const val BLUE = 0xFF1A6DFF.toInt()
        const val NUMBER = 0xFF969696.toInt()
        const val ROAD = 0xFF465880.toInt()
        const val AMBER = 0xFFF0B428.toInt()
        const val LANE_OFF = 0xFF73A3FE.toInt()
    }
}
