package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pixel of a night map in the projector's colour by its own colour, nothing removed, and no
 * jump in between: scenes in Yandex's keyed night colours (measured on the drive of 2026-10-11).
 */
class HudMapPaletteTest {

    @Test
    fun trafficColoursComeOutInTheProjectorsOwn() {
        assertColour(HudMapPalette.GREEN, paintBlock(FREE))
        assertColour(HudMapPalette.YELLOW, paintBlock(SLOW))
        assertColour(HudMapPalette.RED, paintBlock(JAM))
        // A heavy jam's dark red is red too, at its own shade: a dark colour is no light on the glass.
        assertColour(HudMapPalette.RED.map { it * 0.804f }.toFloatArray(), paintBlock(HEAVY))
    }

    @Test
    fun theCursorsGoldIsOrangeAndKeepsItsShape() {
        val scene = canvas()
        // A triangle, its tip one pixel wide: nothing about it is cut.
        for (row in 0 until 16) scene.rect(150 - row / 2, 120 + row, 151 + row / 2, 121 + row, CURSOR)

        val out = paint(scene)

        assertColour(HudMapPalette.ORANGE, out.at(150, 120))
        assertColour(HudMapPalette.ORANGE, out.at(143, 135))
    }

    @Test
    fun lanePlatesAreDarkBlueTheirActiveArrowWhiteTheOthersMuted() {
        val scene = canvas()
        scene.rect(100, 40, 160, 80, PLATE)
        scene.rect(140, 45, 143, 75, WHITE_INK)
        scene.rect(115, 45, 118, 75, LANE_OFF)

        val out = paint(scene)

        assertColour(HudMapPalette.BLUE, out.at(105, 60))
        assertColour(HudMapPalette.COOL, out.at(141, 60))
        // Between the plate and the white arrow, nearer neither.
        val off = out.at(116, 60)
        assertTrue(Integer.toHexString(off), red(off) in 60..200 && blue(off) > red(off) + 40)
    }

    @Test
    fun roadsAreACoolWhiteLiftedFromTheirDimGrey() {
        // Road blue-grey 0.50 of full: 0.50^0.65 = 0.64 of the cool white.
        assertColour(HudMapPalette.COOL.map { it * 0.639f }.toFloatArray(), paintBlock(ROAD))
        assertColour(HudMapPalette.COOL, paintBlock(WHITE_INK))
    }

    @Test
    fun theGreyRouteIsCyanButNotBesideWhite() {
        val scene = canvas()
        scene.rect(60, 20, 70, 160, GREY)
        scene.rect(200, 20, 210, 160, GREY)
        scene.rect(210, 20, 214, 160, WHITE_INK)

        val out = paint(scene)

        // 0.63 of full: cyan at 0.63 / 0.65.
        assertColour(HudMapPalette.CYAN.map { it * 0.965f }.toFloatArray(), out.at(65, 90))
        // Within a pixel of white the grey stays a grey: white text keeps no cyan rim.
        val rim = out.at(209, 90)
        assertTrue(Integer.toHexString(rim), red(rim) > 150)
    }

    @Test
    fun nothingIsRemoved() {
        val scene = canvas()
        // A house number's stroke, three pixels by five, in the labels' blue-grey.
        scene.rect(100, 100, 103, 105, LABEL)

        val out = paint(scene)

        assertTrue(red(out.at(101, 102)) > 100)
        assertEquals(BLACK, out.at(110, 102))
    }

    @Test
    fun aColourShadingIntoAnotherNeverJumps() {
        // The edge of one colour against another, as antialiasing and a moving map make it: the
        // output follows the input without a step, whichever two colours meet.
        val pairs = listOf(
            FREE to ROAD, PLATE to WHITE_INK, LANE_OFF to PLATE, GREY to BLACK, GREY to WHITE_INK,
            CURSOR to SLOW, JAM to ROAD, FREE to BLACK, ROAD to BLACK,
        )
        val steps = 400
        for ((a, b) in pairs) {
            var last = -1
            for (k in 0..steps) {
                val out = paintBlock(mix(a, b, k / steps.toFloat()))
                if (last != -1) {
                    for (shift in intArrayOf(16, 8, 0)) {
                        val jump = kotlin.math.abs((out shr shift and 0xFF) - (last shr shift and 0xFF))
                        assertTrue("${Integer.toHexString(a)} to ${Integer.toHexString(b)} at $k: $jump", jump <= 20)
                    }
                }
                last = out
            }
        }
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

    private fun canvas() = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT) { BLACK }

    /** Fills [x0, x1) × [y0, y1). */
    private fun IntArray.rect(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * HudMapGeometry.MAP_WIDTH + x] = colour
    }

    private fun paint(scene: IntArray): IntArray {
        val out = IntArray(scene.size)
        HudMapPalette().apply(scene, out)
        return out
    }

    /** The middle of a 20-pixel block of [colour] on black. */
    private fun paintBlock(colour: Int): Int {
        val scene = canvas()
        scene.rect(50, 50, 70, 70, colour)
        return paint(scene).at(60, 60)
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * HudMapGeometry.MAP_WIDTH + x]

    private fun red(c: Int) = c shr 16 and 0xFF

    private fun blue(c: Int) = c and 0xFF

    private fun mix(a: Int, b: Int, t: Float): Int {
        var out = BLACK
        for (shift in intArrayOf(16, 8, 0)) {
            val v = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t + 0.5f).toInt()
            out = out or (v shl shift)
        }
        return out
    }

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
        const val HEAVY = 0xFFA51923.toInt()
        const val CURSOR = 0xFFE9B135.toInt()
        const val PLATE = 0xFF1666FE.toInt()
        const val LANE_OFF = 0xFF73A3FE.toInt()
        const val GREY = 0xFFA0A0A0.toInt()
        const val WHITE_INK = 0xFFFFFFFF.toInt()
        const val ROAD = 0xFF465880.toInt()
        const val LABEL = 0xFF78829A.toInt()
    }
}
