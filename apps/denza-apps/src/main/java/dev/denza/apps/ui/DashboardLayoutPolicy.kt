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

    /**
     * The three windows the car actually hands this app, measured on DiLink 5.1.
     *
     * These are the source, and the thresholds below are derived from them. There used to be two
     * independent records of the same three windows in this file - thresholds at 599 and 1099, and
     * 1280/828/416 typed again inside [chipWidth] - so "what are the three widths" had two answers
     * and moving one of them moved nothing.
     */
    const val NARROW_WIDTH_DP = 416
    const val MEDIUM_WIDTH_DP = 828
    const val WIDE_WIDTH_DP = 1_280

    /**
     * Where one window stops and the next begins: halfway, so any width resolves to the measured
     * window nearest it. The car sends one of the three and nothing between, and the split path of
     * the firmware has been seen to report a width before it has finished resizing - the nearest
     * measured window is the only answer to that which does not need a fourth layout.
     */
    const val NARROW_MAX_WIDTH_DP = (NARROW_WIDTH_DP + MEDIUM_WIDTH_DP) / 2
    const val MEDIUM_MAX_WIDTH_DP = (MEDIUM_WIDTH_DP + WIDE_WIDTH_DP) / 2

    fun resolve(widthDp: Int): DashboardLayoutMode = when {
        widthDp <= NARROW_MAX_WIDTH_DP -> DashboardLayoutMode.NARROW
        widthDp <= MEDIUM_MAX_WIDTH_DP -> DashboardLayoutMode.MEDIUM
        else -> DashboardLayoutMode.WIDE
    }

    /** The window this mode was measured in. */
    fun windowWidth(mode: DashboardLayoutMode): Int = when (mode) {
        DashboardLayoutMode.WIDE -> WIDE_WIDTH_DP
        DashboardLayoutMode.MEDIUM -> MEDIUM_WIDTH_DP
        DashboardLayoutMode.NARROW -> NARROW_WIDTH_DP
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
     * How wide a chip comes out in [mode] with [count] features, at the window that mode was
     * measured in - so a test can say when it is too small.
     */
    fun chipWidth(mode: DashboardLayoutMode, count: Int): Dp =
        chipSide(mode, count, windowWidth(mode) - sideMargin(mode).value * 2).dp

    /** The same chip, at whatever width the page has actually been given. */
    private fun chipSide(mode: DashboardLayoutMode, count: Int, contentWidth: Float): Float {
        val columns = columns(mode, count)
        return (contentWidth - (columns - 1) * DenzaMetrics.Space.M.value) / columns
    }

    /** Whether this width writes a feature out as a tile or compresses it to a chip. */
    fun chips(mode: DashboardLayoutMode): Boolean = mode != DashboardLayoutMode.WIDE

    /**
     * The gap between the band of features and the strip under it.
     *
     * A pane's chips and its strip are two different things and take the gap between groups; the
     * full screen's tiles and strip are one field of controls with a readout under it.
     */
    fun bandGap(mode: DashboardLayoutMode): Dp =
        if (chips(mode)) DenzaMetrics.Space.XL else DenzaMetrics.Space.M

    /** How tall the band of features comes out, tiles or chips, at the width the page has. */
    fun featureBandHeight(mode: DashboardLayoutMode, count: Int, contentWidth: Float): Dp {
        val features = count.coerceAtLeast(1)
        val columns = columns(mode, features)
        val rows = (features + columns - 1) / columns
        val cell = if (chips(mode)) {
            chipSide(mode, features, contentWidth)
        } else {
            DenzaMetrics.Component.TILE_HEIGHT.value
        }
        return (rows * cell + (rows - 1) * DenzaMetrics.Space.M.value).dp
    }

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

    /**
     * What is left for the strip once the features have taken theirs, and whether the page fits.
     *
     * The full screen used to add up to exactly 680 - 20 + 340 + 12 + 296 + 12 - with no slack and
     * no scroll anywhere, so any inset at all pushed the foot of the analyser past the bottom edge
     * and nothing said so. The pane asked for `heightIn(min = PANEL_HEIGHT_MIN)` under a
     * `weight(1f)`, which a Column measures as an exact height: the floor was a comment, not a
     * floor.
     *
     * This is the arithmetic that was missing, and the difference from the arithmetic that caused
     * the original bug is [contentHeight]: it is the window the app was *handed*, with the caption
     * bar already subtracted by `safeDrawing`, rather than a 680 typed from a board. When there is
     * room the caller still hands the strip a `weight(1f)` and lets the layout do the sum; this only
     * decides when there is not.
     */
    fun page(
        mode: DashboardLayoutMode,
        features: Int,
        contentWidth: Float,
        contentHeight: Dp,
    ): DashboardPage {
        val room = contentHeight - featureBandHeight(mode, features, contentWidth) - bandGap(mode)
        return if (chips(mode)) {
            // A pane's strip takes the remainder, down to the height it stops being an instrument
            // at. Below that the page scrolls rather than the analyser quietly getting shorter.
            val floor = DenzaMetrics.Component.PANEL_HEIGHT_MIN
            DashboardPage(panelHeight = if (room > floor) room else floor, scrolls = room < floor)
        } else {
            // The full screen's strip is a fixed shape whatever the height allows - handing it the
            // remainder is what drew the analyser on a canvas stretched to twice its own. Slack
            // goes under it, and a shortfall scrolls.
            val shape = wholeScreenPanelHeight(contentWidth)
            DashboardPage(panelHeight = shape, scrolls = room < shape)
        }
    }
}

/**
 * How the page comes out at the height it was given.
 *
 * [panelHeight] is what the strip gets whenever it is given a height at all - the full screen
 * always, a pane only while the column is scrolling. A pane with room to spare is handed a
 * `weight(1f)` and never reads this.
 */
internal data class DashboardPage(
    val panelHeight: Dp,
    val scrolls: Boolean,
)

internal enum class DashboardLayoutMode {
    WIDE,
    MEDIUM,
    NARROW,
}
