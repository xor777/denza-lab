package dev.denza.apps.ui

import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.trip.TripPanelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test
    fun `measured one-third pane uses vertical layout`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(416),
        )
    }

    @Test
    fun `measured two-thirds pane fits the dashboard into its own width`() {
        // Правка W8: холст 1280 dp в горизонтальном скролле прятал ~904 px за краем панели 828 dp.
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(828),
        )
    }

    @Test
    fun `measured fullscreen width keeps the existing layout`() {
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(1_280),
        )
    }

    @Test
    fun `thresholds have no ambiguous width`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP + 1),
        )
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP + 1),
        )
    }

    @Test
    fun `each width gets its own margin, its own row and its own kind of feature`() {
        // One table, because these answers are one decision: the margin buys the width, the width
        // decides whether a feature can afford its name, and what is left over is the strip. The
        // pane numbers are the boards' - TwoThirds.dc.html and OneThird.dc.html.
        assertEquals(DenzaMetrics.Space.XXL, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.WIDE))
        assertEquals(DenzaMetrics.Space.L, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.MEDIUM))
        assertEquals(DenzaMetrics.Space.M, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW))

        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.WIDE))
        assertEquals(10, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM))
        assertEquals(5, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW))

        // A feature is written out on the full screen and compressed to a chip in a pane.
        assertEquals(false, DashboardLayoutPolicy.chips(DashboardLayoutMode.WIDE))
        assertEquals(true, DashboardLayoutPolicy.chips(DashboardLayoutMode.MEDIUM))
        assertEquals(true, DashboardLayoutPolicy.chips(DashboardLayoutMode.NARROW))

        assertEquals(TripPanelLayout.WIDE, DashboardLayoutPolicy.panel(DashboardLayoutMode.WIDE))
        assertEquals(TripPanelLayout.MEDIUM, DashboardLayoutPolicy.panel(DashboardLayoutMode.MEDIUM))
        assertEquals(TripPanelLayout.NARROW, DashboardLayoutPolicy.panel(DashboardLayoutMode.NARROW))
    }

    @Test
    fun `a chip row costs a pane a fraction of what a tile row costs`() {
        // The reason a pane has chips at all, as arithmetic rather than as taste. Ten tiles at the
        // width their names need are four rows at 828 dp and five at 416 - 692 and 868 dp of a
        // window that has 656 once the car has taken its caption bar - so the page scrolled and
        // the analyser was below the fold. Ten chips are one row and two.
        for ((window, mode) in listOf(
            828 to DashboardLayoutMode.MEDIUM,
            416 to DashboardLayoutMode.NARROW,
        )) {
            val columns = DashboardLayoutPolicy.columns(mode)
            val content = window - DashboardLayoutPolicy.sideMargin(mode).value * 2
            val chip = (content - (columns - 1) * DenzaMetrics.Space.M.value) / columns
            val rows = (10 + columns - 1) / columns
            val band = rows * chip + (rows - 1) * DenzaMetrics.Space.M.value

            assertTrue("a chip at $window dp is $chip, which is not chip-sized", chip in 60f..76f)
            assertTrue(
                "ten chips at $window dp take $band dp, which is no better than tiles",
                band < 3 * DenzaMetrics.Component.TILE_HEIGHT.value,
            )
        }
    }

    @Test
    fun `only the full screen takes its strip height from its width`() {
        // The wide panel is a fixed shape - 1184 by 296 - so a caller asks for a box of that shape
        // and the drawing arrives unstretched. A pane's strip is handed the remainder and lays
        // itself out in whatever it gets, which is the one arrangement that could not have had the
        // bug the first cut of these panes shipped with: the height was worked out by hand against
        // 680, the car keeps 24 of that for its caption bar, and the foot of the strip was drawn
        // past the bottom edge of the window.
        assertEquals(296f, DashboardLayoutPolicy.wholeScreenPanelHeight(1_184f).value, 1e-3f)
        assertEquals(148f, DashboardLayoutPolicy.wholeScreenPanelHeight(592f).value, 1e-3f)
    }
}
