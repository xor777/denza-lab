package dev.denza.apps.feature.diagnostics.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cabin's plates stacked as `sysCabin()` stacks them (board `scanner-sys-cabin-parked`): the
 * tops and the bends `scanner.js` draws for the parked board's plates, worked out by the board's own
 * code, and the rules that keep leaders from crossing.
 */
class CabinStackTest {
    private val c = ScannerSpec.CabinScheme
    private val o = ScannerSpec.Callout
    private val type = ScannerSpec.Type

    /** A plate of a label and [rows] rows in the cell styles. */
    private fun h(rows: Int) = 2 * o.PAD_Y + type.COUNTER_LABEL.lh + rows * type.CELL.lh + rows * o.GAP

    /** The car of the rail's area: its top-left, its sides' outer edges. */
    private val cx = (ScannerSpec.W - ScannerSpec.Rail.W) / 2f
    private val x0 = cx - ScannerSpec.Car.W / 2f
    private val y0 = ScannerSpec.Car.TOP

    @Test
    fun theLeftColumnOfTheParkedBoard() {
        // the cluster over the wheel, the wheel's rim, the driver, the rear left, the centre
        val targets = listOf(
            y0 + c.WHEEL.second - c.CLUSTER_LIFT, y0 + c.WHEEL.second, y0 + c.FRONT_Y + c.FRONT_H / 2f,
            y0 + c.BENCH_Y + c.BENCH_H / 2f, y0 + c.CENTRE_LEADER,
        )
        val heights = listOf(h(2), h(1), h(3), h(3), h(1))
        assertEquals(listOf(74f, 53f, 95f, 95f, 53f), heights)
        val tops = CabinStack.tops(targets, heights, c.TOP, c.BOTTOM, c.GAP)
        assertEquals(listOf(208f, 292f, 355f, 460f, 565f), tops)
        val edge = x0 - ScannerSpec.Car.WHEEL_W / 2f - c.BEND_IN
        assertEquals(423f, edge)
        assertEquals(listOf(405f, 351f, 369f, 387f, 423f), CabinStack.bends(targets, tops, heights, edge, c.BEND_STEP, -1f))
    }

    @Test
    fun theRightColumnOfTheParkedBoard() {
        // the pads, the passenger, the fridges, the rear right
        val targets = listOf(
            y0 + c.PAD_Y - c.PADS_LIFT, y0 + c.FRONT_Y + c.FRONT_H / 2f, y0 + c.FRIDGE_Y + c.FRIDGE_H - c.FRIDGE_LIFT,
            y0 + c.BENCH_Y + c.BENCH_H / 2f,
        )
        val heights = listOf(h(2), h(3), h(2), h(3))
        val tops = CabinStack.tops(targets, heights, c.TOP, c.BOTTOM, c.GAP)
        assertEquals(listOf(250f, 334f, 439f, 523f), tops)
        val edge = x0 + ScannerSpec.Car.W + ScannerSpec.Car.WHEEL_W / 2f + c.BEND_IN
        assertEquals(listOf(677f, 695f, 659f, 641f), CabinStack.bends(targets, tops, heights, edge, c.BEND_STEP, 1f))
    }

    @Test
    fun aColumnWithRoomStandsLevelWithItsPlacesAndNeverOverlaps() {
        val tops = CabinStack.tops(listOf(250f, 400f), listOf(53f, 53f), c.TOP, c.BOTTOM, c.GAP)
        assertEquals(listOf(250f - 26.5f, 400f - 26.5f), tops)
        // crowded: pushed down, then lifted to the bottom, never above the top
        val crowded = CabinStack.tops(List(6) { 600f }, List(6) { 95f }, c.TOP, c.BOTTOM, c.GAP)
        crowded.zipWithNext().forEach { (a, b) -> assertTrue(b >= a + 95f + c.GAP) }
        assertTrue(crowded.first() >= c.TOP)
    }

    @Test
    fun theLeaderThatTravelsFurthestBendsNearestTheCar() {
        // a leader dropping 100 bends at the edge, one level with its plate furthest out
        val bends = CabinStack.bends(listOf(300f, 500f), listOf(273.5f, 373.5f), listOf(53f, 53f), 100f, 18f, -1f)
        assertEquals(listOf(82f, 100f), bends)
    }
}
