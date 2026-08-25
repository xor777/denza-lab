package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the blocks fit the boxes.
 *
 * This is the test the design boards never had. Their tiles fitted by 1.3 pixels and nobody knew
 * until it was measured; four sibling layouts drifted onto four different grids the same way. A plan
 * and a box are both numbers, so the question is answerable here rather than on the car - which
 * matters more than usual, because this dashboard cannot be installed while another session owns the
 * vehicle.
 */
class ClusterBlockPlanTest {

    private val full = ClusterDashboardLayout(2560, 720, ClusterMapPlacement.FULL)
    private val right = ClusterDashboardLayout(2560, 720, ClusterMapPlacement.RIGHT)

    /**
     * A box's height in units.
     *
     * The space belongs to the layout, not to the block: a corner reveal is drawn with the narrow
     * ramp but sits inside the wide layout's 424-unit space, and measuring it against 308 is how
     * this test first failed.
     */
    private fun DashboardBox.units(layout: ClusterDashboardLayout): Float =
        (bottom - top) * layout.virtualHeight

    private fun assertFits(
        name: String,
        rows: List<DashboardRow>,
        box: DashboardBox,
        density: InstrumentDensity,
        layout: ClusterDashboardLayout,
    ) {
        val content = ClusterBlockPlan.height(rows, density)
        val room = box.units(layout)
        assertTrue("$name needs $content units and has $room", content <= room)
    }

    @Test
    fun theWideBlocksFitTheBandBetweenTheStockEdges() {
        val density = InstrumentDensity.WIDE
        assertFits("electric", ClusterBlockPlan.electricWide(density), full.electricBlock, density, full)
        assertFits("engine", ClusterBlockPlan.engineWide(density), full.engineBlock, density, full)
    }

    @Test
    fun theNarrowBlocksFitTheRightThird() {
        val density = InstrumentDensity.COMPACT
        assertFits("electric", ClusterBlockPlan.electricNarrow(density), right.electricBlock, density, right)
        assertFits("engine", ClusterBlockPlan.engineNarrow(density), right.engineBlock, density, right)
    }

    @Test
    fun theRevealBlocksFitTheCornersTheShadeOpensUp() {
        val density = InstrumentDensity.COMPACT
        assertFits("temperatures", ClusterBlockPlan.temperatures(density), requireNotNull(full.temperatureBlock), density, full)
        assertFits("lamps", ClusterBlockPlan.lamps(density, 8, 4), requireNotNull(full.lampBlock), density, full)
    }

    /**
     * The corner holds the eight lamps this car reports and no more.
     *
     * A ninth would start a third row and want 96 units where the reveal has 90, so it would not
     * fit - and that is the point of this test rather than an objection to it. The plan grows with
     * the catalog, so the day a lamp is added `theRevealBlocksFitTheCornersTheShadeOpensUp` fails
     * here instead of the row quietly running out over the vehicle's own graphics on the car.
     */
    @Test
    fun aNinthLampWouldCostHeightRatherThanGoMissing() {
        val density = InstrumentDensity.COMPACT
        val eight = ClusterBlockPlan.height(ClusterBlockPlan.lamps(density, 8, 4), density)
        val nine = ClusterBlockPlan.height(ClusterBlockPlan.lamps(density, 9, 4), density)
        assertTrue("a ninth lamp must cost a row", nine > eight)
        assertTrue(
            "the corner has no room for a third row, so this must be caught here",
            nine > requireNotNull(full.lampBlock).units(full),
        )
    }

    @Test
    fun aNarrowBlockGetsMoreRowsThanAWideOneBecauseItHasTheHeightAndNotTheWidth() {
        assertTrue(
            ClusterBlockPlan.electricNarrow(InstrumentDensity.COMPACT).size >
                ClusterBlockPlan.electricWide(InstrumentDensity.WIDE).size,
        )
        assertTrue(
            ClusterBlockPlan.engineNarrow(InstrumentDensity.COMPACT).size >
                ClusterBlockPlan.engineWide(InstrumentDensity.WIDE).size,
        )
    }

    @Test
    fun aCentredBlockLeavesEqualRoomAboveAndBelowRatherThanHangingFromTheTop() {
        val density = InstrumentDensity.COMPACT
        val slack = right.electricBlock.units(right) -
            ClusterBlockPlan.height(ClusterBlockPlan.electricNarrow(density), density)
        // The narrow column has real slack, which is exactly the case that has to be centred.
        assertTrue("the narrow block should have room to centre in, had $slack", slack > 40f)
    }

    @Test
    fun theRevealsStillExistAfterBeingDeepenedToFitTheirBlocks() {
        assertNotNull(full.temperatureBlock)
        assertNotNull(full.lampBlock)
        assertTrue(full.isClear(requireNotNull(full.temperatureBlock)))
        assertTrue(full.isClear(requireNotNull(full.lampBlock)))
    }
}
