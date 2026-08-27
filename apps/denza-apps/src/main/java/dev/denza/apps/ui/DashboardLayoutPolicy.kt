package dev.denza.apps.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.trip.BaseTripRenderer
import dev.denza.apps.feature.trip.TripPanelLayout

/**
 * Which of the three dashboards the window gets, and everything that follows from it.
 *
 * The live DiLink 5.1 windows are 416 dp (narrow pane), 828 dp (wide pane), and
 * 1280 dp (fullscreen). Keeping the thresholds between those measured sizes
 * makes the decision independent of native root ids and divider side.
 *
 * The three answers below belong together and used to be spread across the screen as three
 * separate `when` blocks over the same enum. They are one decision: the margin buys the width, the
 * width sets the column count, and the column count sets how much height is left for the strip.
 * Boards: `Main.dc.html`, `TwoThirds.dc.html`, `OneThird.dc.html`.
 */
internal object DashboardLayoutPolicy {
    const val NARROW_MAX_WIDTH_DP = 599
    const val MEDIUM_MAX_WIDTH_DP = 1_099

    fun resolve(widthDp: Int): DashboardLayoutMode = when {
        widthDp <= NARROW_MAX_WIDTH_DP -> DashboardLayoutMode.NARROW
        widthDp <= MEDIUM_MAX_WIDTH_DP -> DashboardLayoutMode.MEDIUM
        else -> DashboardLayoutMode.WIDE
    }

    /**
     * The page's own side margin.
     *
     * 48 is the *screen's* margin and a pane is not the screen, so it steps down the ladder as the
     * window narrows. This is not decoration: it is what buys the tile the width its longest name
     * needs. At 48 and 32 the two panes come out at 182 dp a tile and the cluster tile loses a
     * word to an ellipsis; at 20 and 12 they come out at 188 and 190.
     */
    fun sideMargin(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> DenzaMetrics.Space.XXL
        DashboardLayoutMode.MEDIUM -> DenzaMetrics.Space.L
        DashboardLayoutMode.NARROW -> DenzaMetrics.Space.M
    }

    /** How many tiles stand in one row; see [DenzaMetrics.Component.TILE_COLUMNS_WIDE]. */
    fun columns(mode: DashboardLayoutMode): Int = when (mode) {
        DashboardLayoutMode.WIDE -> DenzaMetrics.Component.TILE_COLUMNS_WIDE
        DashboardLayoutMode.MEDIUM -> DenzaMetrics.Component.TILE_COLUMNS_MEDIUM
        DashboardLayoutMode.NARROW -> DenzaMetrics.Component.TILE_COLUMNS_NARROW
    }

    /** Which of the strip's three compositions this window gets. */
    fun panel(mode: DashboardLayoutMode): TripPanelLayout = when (mode) {
        DashboardLayoutMode.WIDE -> TripPanelLayout.WIDE
        DashboardLayoutMode.MEDIUM -> TripPanelLayout.MEDIUM
        DashboardLayoutMode.NARROW -> TripPanelLayout.NARROW
    }

    /**
     * How tall the strip is, given the width the page has already spent its margins out of.
     *
     * Only the full screen answers from its width. Its panel is a fixed shape - the board's
     * 1184x296 - and asking for a box of that shape is how a caller keeps it from being drawn
     * onto a canvas stretched to whatever height was left over. A pane's strip is laid out in the
     * screen's own dp instead, so its height is a number rather than a ratio.
     */
    fun panelHeight(mode: DashboardLayoutMode, contentWidth: Float): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> BaseTripRenderer.heightFor(contentWidth).dp
        DashboardLayoutMode.MEDIUM -> DenzaMetrics.Component.PANEL_HEIGHT_MEDIUM
        DashboardLayoutMode.NARROW -> DenzaMetrics.Component.PANEL_HEIGHT_NARROW
    }
}

internal enum class DashboardLayoutMode {
    WIDE,
    MEDIUM,
    NARROW,
}
