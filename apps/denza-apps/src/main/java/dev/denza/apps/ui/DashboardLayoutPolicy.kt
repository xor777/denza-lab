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
     * window narrows.
     */
    fun sideMargin(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> DenzaMetrics.Space.XXL
        DashboardLayoutMode.MEDIUM -> DenzaMetrics.Space.L
        DashboardLayoutMode.NARROW -> DenzaMetrics.Space.M
    }

    /**
     * How many features stand in one row, given how many there are.
     *
     * Six on the full screen, where a feature is a tile with its name and its state written out
     * and six is what 1184 dp affords at the width those words need - so a seventh tile wraps to a
     * third row.
     *
     * A pane counts differently, because a chip is not a tile: the band is a toolbar and a toolbar
     * fits rather than wraps. The two-thirds pane puts every feature in one row and the chip is
     * its share of it, so an eleventh feature costs each chip 7 dp instead of costing the analyser
     * a row of 80. The narrow pane does the same inside two rows.
     *
     * How far that goes is [DenzaMetrics.Component.CHIP_MIN], and `ChipDensity.dc.html` draws it.
     */
    fun columns(mode: DashboardLayoutMode, count: Int): Int {
        val features = count.coerceAtLeast(1)
        return when (mode) {
            DashboardLayoutMode.WIDE -> DenzaMetrics.Component.TILE_COLUMNS_WIDE
            DashboardLayoutMode.MEDIUM -> features
            DashboardLayoutMode.NARROW -> {
                val rows = DenzaMetrics.Component.CHIP_ROWS_NARROW
                (features + rows - 1) / rows
            }
        }
    }

    /**
     * How wide a chip comes out in [mode] with [count] features, so a test can say when it is too
     * small. The window widths are the car's measured three.
     */
    fun chipWidth(mode: DashboardLayoutMode, count: Int): Dp {
        val window = when (mode) {
            DashboardLayoutMode.WIDE -> 1_280
            DashboardLayoutMode.MEDIUM -> 828
            DashboardLayoutMode.NARROW -> 416
        }
        val columns = columns(mode, count)
        val gap = DenzaMetrics.Space.M.value
        val content = window - sideMargin(mode).value * 2
        return ((content - (columns - 1) * gap) / columns).dp
    }

    /** Whether this width writes a feature out as a tile or compresses it to a chip. */
    fun chips(mode: DashboardLayoutMode): Boolean = mode != DashboardLayoutMode.WIDE

    /** Which of the strip's three compositions this window gets. */
    fun panel(mode: DashboardLayoutMode): TripPanelLayout = when (mode) {
        DashboardLayoutMode.WIDE -> TripPanelLayout.WIDE
        DashboardLayoutMode.MEDIUM -> TripPanelLayout.MEDIUM
        DashboardLayoutMode.NARROW -> TripPanelLayout.NARROW
    }

    /**
     * How tall the strip is on the full screen, given the width its margins have left.
     *
     * Only the full screen answers with a number. Its panel is a fixed shape - the board's
     * 1184x296 - and asking for a box of that shape is how a caller keeps it from being drawn onto
     * a canvas stretched to whatever height was left over.
     *
     * A pane's strip takes the remainder instead, as `weight(1f)`, and its renderer lays itself
     * out in whatever shape it is handed at one unit to one dp. That is not laziness, it is the
     * only arrangement that cannot be wrong: the first cut of these panes worked the remainder out
     * by hand from 680 and the car keeps 24 of that for the freeform caption bar, so the foot of
     * the strip was drawn past the bottom edge - which is precisely the bug the full screen's own
     * panel height exists to prevent, reintroduced one window width along.
     */
    fun wholeScreenPanelHeight(contentWidth: Float): Dp =
        BaseTripRenderer.heightFor(contentWidth).dp
}

internal enum class DashboardLayoutMode {
    WIDE,
    MEDIUM,
    NARROW,
}
