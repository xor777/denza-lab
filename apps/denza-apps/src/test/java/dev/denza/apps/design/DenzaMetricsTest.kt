package dev.denza.apps.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladders hold themselves apart.
 *
 * This is the whole point of declaring them. The failure they replace was not one wrong number but
 * fourteen type sizes where seven were meant, including 12 beside 13 and 19 beside 20. A rung a few
 * per cent from its neighbour cannot be chosen deliberately - whoever reaches for it is guessing -
 * so the thing worth testing is the gap, not the value.
 */
class DenzaMetricsTest {

    @Test
    fun everySpacingRungIsFarEnoughFromTheOneBelowToBeAChoice() {
        assertLadder(DenzaMetrics.Space.RUNGS.map { it.value })
    }

    @Test
    fun everyCornerRungIsFarEnoughFromTheOneBelow() {
        assertLadder(DenzaMetrics.Radius.RUNGS.map { it.value })
    }

    @Test
    fun everyTypeRungIsFarEnoughFromTheOneBelow() {
        // Declared largest first, so it is compared the other way up; the rule is the same rule.
        assertLadder(DenzaMetrics.Type.RUNGS.map { it.value }.reversed())
    }

    @Test
    fun theLaddersAreTheOnesTheDesignBoardsRestate() {
        // tools/design-canvas/README.md declares the head unit's ramps and audit.py holds the
        // boards to them. Two records of one ladder only stay one ladder if something compares
        // them, and until the boards can be read from here, that something is this list.
        assertEquals(
            listOf(62f, 46f, 34f, 24f, 19f, 15f),
            DenzaMetrics.Type.RUNGS.map { it.value },
        )
        assertEquals(
            listOf(2f, 6f, 12f, 22f),
            DenzaMetrics.Radius.RUNGS.map { it.value },
        )
    }

    @Test
    fun nothingReadableIsSmallerThanTheBottomRung() {
        // 15 sp is the floor and there is deliberately nothing under it: this screen is read at
        // arm's length from a driver's seat, and the 11, 12 and 13 sp captions the old screen was
        // full of were legible on a desk and not in a car.
        assertEquals(15f, DenzaMetrics.Type.RUNGS.last().value, 1e-4f)
    }

    @Test
    fun aTileIsTallEnoughToHoldItsOwnTwoLinesWithRoomToSpare() {
        // Name, state line and the padding either side, measured off Main.dc.html. If a rung moves
        // and this stops holding, the tile has to be re-measured on the board first.
        val content = DenzaMetrics.Type.LABEL.value * LINE_HEIGHT +
            DenzaMetrics.Type.BODY.value * LINE_HEIGHT +
            DenzaMetrics.Space.L.value * 2
        assertTrue(
            "a tile of ${DenzaMetrics.Component.TILE_HEIGHT} cannot hold $content",
            DenzaMetrics.Component.TILE_HEIGHT.value > content,
        )
    }

    @Test
    fun aRowIsBigEnoughForAFinger() {
        // 48 dp is the platform's own floor for a touch target; a car is worse than a desk, not
        // better, so nothing here goes under it.
        assertTrue(DenzaMetrics.Component.ROW_HEIGHT.value >= 48f)
        assertTrue(DenzaMetrics.Component.SEGMENT_HEIGHT.value >= 42f)
    }

    private fun assertLadder(rungs: List<Float>) {
        assertTrue("a ladder needs rungs", rungs.size >= 4)
        rungs.zipWithNext().forEach { (below, above) ->
            assertTrue("$above is not above $below", above > below)
            assertTrue(
                "$above and $below are a ${above / below} step apart, which is a difference you " +
                    "can measure and cannot see",
                above / below >= MIN_STEP,
            )
        }
    }

    private companion object {
        const val MIN_STEP = 1.2f
        const val LINE_HEIGHT = 1.3f
    }
}
