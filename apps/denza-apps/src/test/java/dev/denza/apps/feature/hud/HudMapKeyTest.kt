package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/** The ground goes black, what stands off it stays, and the shuffles around it. */
class HudMapKeyTest {

    @Test
    fun theGroundIsTheCommonestColourNotTheMeanOne() {
        // Mostly night blue with a little texture, a third of it bright roads.
        val pixels = IntArray(900) { index ->
            when {
                index % 3 == 0 -> WHITE
                index % 2 == 0 -> 0xFF1C2333.toInt()
                else -> 0xFF1D2434.toInt()
            }
        }

        val ground = HudMapKey().ground(pixels)

        assertEquals(0x1C, ground shr 16 and 0xFF)
        assertEquals(0x23, ground shr 8 and 0xFF)
        assertEquals(0x33, ground and 0xFF)
    }

    @Test
    fun theGroundGoesBlackAndARoadStaysWhole() {
        val ground = 0xFF1C2333.toInt()
        val pixels = intArrayOf(ground, 0xFF202838.toInt(), WHITE, ROUTE)

        HudMapKey().apply(pixels, ground)

        assertEquals(BLACK, pixels[0])
        // About 8 away from the ground: texture, under NEAR.
        assertEquals(BLACK, pixels[1])
        assertEquals(WHITE, pixels[2])
        assertEquals(ROUTE, pixels[3])
    }

    @Test
    fun betweenNearAndFarAPixelKeepsPartOfItself() {
        val ground = 0xFF000000.toInt()
        // 56 away from black in red alone: (56 - 22) / (90 - 22) = 0.5.
        val pixels = intArrayOf(0xFF380000.toInt())

        HudMapKey().apply(pixels, ground)

        assertEquals(0xFF1C0000.toInt(), pixels[0])
    }

    @Test
    fun aKeyReusedOnAnotherFrameReadsOnlyThatFrame() {
        val key = HudMapKey()
        key.ground(IntArray(100) { WHITE })

        assertEquals(BLACK, key.ground(IntArray(100) { BLACK }))
    }

    @Test
    fun halvingTakesTheMeanOfEachTwoByTwo() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF040404.toInt(), WHITE, WHITE,
            0xFF080808.toInt(), 0xFF0C0C0C.toInt(), WHITE, BLACK,
        )
        val out = IntArray(2)

        HudMapPixels.halve(pixels, 4, 2, out)

        assertEquals(0xFF060606.toInt(), out[0])
        // (3 × 255 + 0 + 2) / 4, rounded down.
        assertEquals(0xFFBFBFBF.toInt(), out[1])
    }

    private companion object {
        val BLACK = 0xFF000000.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val ROUTE = 0xFF3D8BFF.toInt()
    }
}
