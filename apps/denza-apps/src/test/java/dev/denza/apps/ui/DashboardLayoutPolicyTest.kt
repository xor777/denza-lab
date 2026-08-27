package dev.denza.apps.ui

import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.trip.TripPanelLayout
import org.junit.Assert.assertEquals
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
    fun `each width gets its own margin, columns and strip`() {
        // One table, because these three answers are one decision: the margin buys the width, the
        // width sets the column count, and what the columns leave is the strip. The pane numbers
        // are the boards' - TwoThirds.dc.html and OneThird.dc.html.
        assertEquals(DenzaMetrics.Space.XXL, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.WIDE))
        assertEquals(DenzaMetrics.Space.L, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.MEDIUM))
        assertEquals(DenzaMetrics.Space.M, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW))

        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.WIDE))
        assertEquals(4, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM))
        assertEquals(2, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW))

        assertEquals(TripPanelLayout.WIDE, DashboardLayoutPolicy.panel(DashboardLayoutMode.WIDE))
        assertEquals(TripPanelLayout.MEDIUM, DashboardLayoutPolicy.panel(DashboardLayoutMode.MEDIUM))
        assertEquals(TripPanelLayout.NARROW, DashboardLayoutPolicy.panel(DashboardLayoutMode.NARROW))
    }

    @Test
    fun `only the full screen takes its strip height from its width`() {
        // The wide panel is a fixed shape - 1184 by 296 - so a caller asks for a box of that shape
        // and the drawing arrives unstretched. A pane is laid out in the screen's own dp, so its
        // height is a number and does not move when the window does. This is the difference that
        // was missing: the two-thirds pane used to be handed the wide ratio, which at 788 dp of
        // content is 197, and the whole composition was scaled to two thirds of its own type.
        assertEquals(296f, DashboardLayoutPolicy.panelHeight(DashboardLayoutMode.WIDE, 1_184f).value, 1e-3f)
        assertEquals(148f, DashboardLayoutPolicy.panelHeight(DashboardLayoutMode.WIDE, 592f).value, 1e-3f)

        val medium = DenzaMetrics.Component.PANEL_HEIGHT_MEDIUM
        assertEquals(medium, DashboardLayoutPolicy.panelHeight(DashboardLayoutMode.MEDIUM, 788f))
        assertEquals(medium, DashboardLayoutPolicy.panelHeight(DashboardLayoutMode.MEDIUM, 1f))

        val narrow = DenzaMetrics.Component.PANEL_HEIGHT_NARROW
        assertEquals(narrow, DashboardLayoutPolicy.panelHeight(DashboardLayoutMode.NARROW, 392f))
    }
}
