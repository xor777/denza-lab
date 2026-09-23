package dev.denza.apps.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.feature.trip.TripPanelLayout

/**
 * Which of the three dashboards the window gets, and everything that follows from it.
 *
 * The live DiLink 5.1 windows are 416 dp (narrow pane), 828 dp (wide pane), and
 * 1280 dp (fullscreen). Keeping the thresholds between those measured sizes
 * makes the decision independent of native root ids and divider side.
 *
 * The answers below belong together and used to be spread across the screen as three separate
 * `when` blocks over the same enum. They are one decision: the margin buys the width, the width
 * sets the column count, and the column count sets how much height is left for the strip.
 *
 * **Every number here is Luminofor's.** `tools/design-canvas/luminofor/spec.json` places the
 * features and the strip box in window dp for all three widths, and this reads them through
 * `LuminoforSpec` rather than restating them - the gaps the spec does not write down are worked out
 * from the positions it does, the same way the board works them out. `LuminoforScreenContractTest`
 * holds the result to the file, width by width. Boards: `main-sound`, `two-sound`, `one-sound`.
 */
internal object DashboardLayoutPolicy {

    /**
     * The three windows the car actually hands this app, measured on DiLink 5.1.
     *
     * These are the source, and the thresholds below are derived from them. There used to be two
     * independent records of the same three windows in this file - thresholds at 599 and 1099, and
     * 1280/828/416 typed again inside [chipWidth] - so "what are the three widths" had two answers
     * and moving one of them moved nothing. Luminofor's spec restates them as its three board
     * widths, and the contract test holds the two records together.
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
     * window narrows: 48, 20, 12 - `head.*.margin`, three rungs of the spacing ladder.
     */
    fun sideMargin(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.MARGIN.dp
        DashboardLayoutMode.MEDIUM -> Head.Two.MARGIN.dp
        DashboardLayoutMode.NARROW -> Head.One.MARGIN.dp
    }

    /**
     * From the top of the box the app may draw in to the top of the features.
     *
     * The spec writes positions in window dp, and a pane's window starts with the 24 dp caption bar
     * BYD's freeform windowing keeps for its drag handle - `safeDrawing` reports it, and the page is
     * padded by it before this applies. So a pane's chips at 40 are 16 under the bar, and the narrow
     * pane's at 36 are 12 under it. The full screen has no bar: its 680 is already what the status
     * band and the dock leave, and its tiles stand 20 into it.
     */
    fun topInset(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.Tiles.TOP.dp
        DashboardLayoutMode.MEDIUM -> (Head.Two.Chips.TOP - Head.Two.CAPTION_BAR).dp
        DashboardLayoutMode.NARROW -> (Head.One.Chips.TOP - Head.One.CAPTION_BAR).dp
    }

    /**
     * Under the strip: the window's height less the strip box's bottom edge, 12 in every width.
     *
     * The strip box ends at 668 of 680 on all three boards, so the foot of the analyser and its
     * dots keep one distance from the bottom of the glass however the window is split.
     */
    fun bottomMargin(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> (Head.Full.HEIGHT - Head.Full.STRIP_BOX.bottom).dp
        DashboardLayoutMode.MEDIUM -> (Head.Two.HEIGHT - Head.Two.STRIP_BOX.bottom).dp
        DashboardLayoutMode.NARROW -> (Head.One.HEIGHT - Head.One.STRIP_BOX.bottom).dp
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
     * The gap between two features side by side.
     *
     * The full screen's is the spec's own `tiles.gap`. A pane's is not written down: the spec
     * gives the chip a size and the board spreads the row across the content width, so the gap is
     * what is left - 12.03 at 828 and 12.04 at 416. It is worked out here the same way, from the
     * spec's own size and count, so eleven features land where the board draws them to the
     * hundredth, and any other count keeps the gap and moves the chip instead.
     */
    fun gapAcross(mode: DashboardLayoutMode): Float = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.Tiles.GAP
        DashboardLayoutMode.MEDIUM -> spread(
            Head.Two.WIDTH - 2 * Head.Two.MARGIN,
            Head.Two.Chips.SIZE,
            Head.Two.Chips.PER_ROW,
        )
        DashboardLayoutMode.NARROW -> spread(
            Head.One.WIDTH - 2 * Head.One.MARGIN,
            Head.One.Chips.SIZE,
            Head.One.Chips.PER_ROW,
        )
    }

    /** The gap between two rows: `tiles.gap` and `chips.rowGap`; the two-thirds pane has one row. */
    fun gapDown(mode: DashboardLayoutMode): Float = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.Tiles.GAP
        DashboardLayoutMode.MEDIUM, DashboardLayoutMode.NARROW -> Head.One.Chips.ROW_GAP
    }

    private fun spread(content: Float, cell: Float, perRow: Int): Float =
        (content - perRow * cell) / (perRow - 1)

    /** Whether this width writes a feature out as a tile or compresses it to a chip. */
    fun chips(mode: DashboardLayoutMode): Boolean = mode != DashboardLayoutMode.WIDE

    /** A feature's corner: the tile's 22, the chips' 16 and 14 - one per window, off the spec. */
    fun cornerRadius(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.Tiles.RADIUS.dp
        DashboardLayoutMode.MEDIUM -> Head.Two.Chips.RADIUS.dp
        DashboardLayoutMode.NARROW -> Head.One.Chips.RADIUS.dp
    }

    /** The glyph on a feature: 30 on a tile, 26 on a chip in either pane. */
    fun glyphSize(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> Head.Full.Tiles.ICON.dp
        DashboardLayoutMode.MEDIUM -> Head.Two.Chips.ICON.dp
        DashboardLayoutMode.NARROW -> Head.One.Chips.ICON.dp
    }

    /**
     * Where every feature goes, at the width the page has actually been given.
     *
     * The board's own arithmetic: a cell is its share of the row after the gaps, the full screen's
     * tile is `tiles.height` tall and a chip is square. At 1184 that is 187.33 by 164; at 788 and
     * 392 with eleven features it is the spec's 60.7 and 55.3.
     */
    fun band(mode: DashboardLayoutMode, count: Int, contentWidth: Float): FeatureBand {
        val features = count.coerceAtLeast(1)
        val columns = columns(mode, features)
        val across = gapAcross(mode)
        val width = (contentWidth - (columns - 1) * across) / columns
        return FeatureBand(
            columns = columns,
            rows = (features + columns - 1) / columns,
            cellWidth = width,
            cellHeight = if (chips(mode)) width else Head.Full.Tiles.HEIGHT,
            gapAcross = across,
            gapDown = gapDown(mode),
        )
    }

    /**
     * How wide a chip comes out in [mode] with [count] features, at the window that mode was
     * measured in - so a test can say when it is too small.
     */
    fun chipWidth(mode: DashboardLayoutMode, count: Int): Dp =
        band(mode, count, windowWidth(mode) - sideMargin(mode).value * 2).cellWidth.dp

    /** How tall the band of features comes out, tiles or chips, at the width the page has. */
    fun featureBandHeight(mode: DashboardLayoutMode, count: Int, contentWidth: Float): Dp =
        band(mode, count, contentWidth).height.dp

    /**
     * The gap between the band of features and the strip box under it.
     *
     * Not in the spec either, and it is worked out from where the spec puts the two: the strip
     * box's top less the bottom of the rows the board draws its eleven features in. That is 12 on
     * the full screen - the tiles' own gap, because the tiles and the strip are one field of
     * controls with a readout under it - and 24 in both panes, where the chips and the strip are
     * two different things.
     */
    fun bandGap(mode: DashboardLayoutMode): Dp = when (mode) {
        DashboardLayoutMode.WIDE -> {
            val t = Head.Full.Tiles
            Head.Full.STRIP_BOX.top - (t.TOP + BOARD_TILE_ROWS * t.HEIGHT + (BOARD_TILE_ROWS - 1) * t.GAP)
        }
        DashboardLayoutMode.MEDIUM -> {
            val c = Head.Two.Chips
            Head.Two.STRIP_BOX.top - (c.TOP + c.SIZE)
        }
        DashboardLayoutMode.NARROW -> {
            val c = Head.One.Chips
            val rows = DenzaMetrics.Component.CHIP_ROWS_NARROW
            Head.One.STRIP_BOX.top - (c.TOP + rows * c.SIZE + (rows - 1) * c.ROW_GAP)
        }
    }.dp

    /** The two rows of six the full-screen board draws its eleven tiles in. */
    private const val BOARD_TILE_ROWS = 2

    /** Which of the strip's three compositions this window gets. */
    fun panel(mode: DashboardLayoutMode): TripPanelLayout = when (mode) {
        DashboardLayoutMode.WIDE -> TripPanelLayout.WIDE
        DashboardLayoutMode.MEDIUM -> TripPanelLayout.MEDIUM
        DashboardLayoutMode.NARROW -> TripPanelLayout.NARROW
    }

    /**
     * How tall the strip is on the full screen, given the width its margins have left.
     *
     * Only the full screen answers with a number. Its strip box is a fixed shape - `stripBox`,
     * 1184 x 296 - and asking for a box of that shape is how a caller keeps it from being drawn onto
     * a canvas stretched to whatever height was left over.
     *
     * A pane's strip takes the remainder instead, as `weight(1f)`, and its renderer lays itself
     * out in whatever shape it is handed at one unit to one dp. That is not laziness, it is the
     * only arrangement that cannot be wrong: the first cut of these panes worked the remainder out
     * by hand from 680 and the car keeps 24 of that for the freeform caption bar, so the foot of
     * the strip was drawn past the bottom edge - which is precisely the bug the full screen's own
     * panel height exists to prevent, reintroduced one window width along.
     */
    fun wholeScreenPanelHeight(contentWidth: Float): Dp {
        val box = Head.Full.STRIP_BOX
        return (contentWidth * (box.bottom - box.top) / (box.right - box.left)).dp
    }

    /**
     * What is left for the strip once the features have taken theirs, and whether the page fits.
     *
     * [height] is the box the page is laid out in: the window the app was *handed*, with the
     * caption bar and the status band already subtracted by `safeDrawing`, rather than a 680 typed
     * from a board. The full screen used to add up to exactly 680 with no slack and no scroll
     * anywhere, so any inset at all pushed the foot of the analyser past the bottom edge and nothing
     * said so; this is the arithmetic that was missing. When there is room the caller still hands a
     * pane's strip a `weight(1f)` and lets the layout do the sum; this only decides when there is
     * not.
     *
     * Half a dp of slack is allowed before the page is called too short: the full screen adds up
     * to its 680 exactly, and a window reported a rounding under that should not start scrolling.
     */
    fun page(
        mode: DashboardLayoutMode,
        features: Int,
        contentWidth: Float,
        height: Dp,
    ): DashboardPage {
        val room = height - topInset(mode) - featureBandHeight(mode, features, contentWidth) -
            bandGap(mode) - bottomMargin(mode)
        return if (chips(mode)) {
            // A pane's strip takes the remainder, down to the height it stops being an instrument
            // at. Below that the page scrolls rather than the analyser quietly getting shorter.
            val floor = DenzaMetrics.Component.PANEL_HEIGHT_MIN
            DashboardPage(panelHeight = if (room > floor) room else floor, scrolls = room < floor - SLACK)
        } else {
            // The full screen's strip is a fixed shape whatever the height allows - handing it the
            // remainder is what drew the analyser on a canvas stretched to twice its own. Slack
            // goes under it, and a shortfall scrolls.
            val shape = wholeScreenPanelHeight(contentWidth)
            DashboardPage(panelHeight = shape, scrolls = room < shape - SLACK)
        }
    }

    private val SLACK: Dp = 0.5.dp
}

/**
 * Where the features stand, in dp: the grid the band is laid out on.
 *
 * Cell *i* is at column `i % columns` and row `i / columns`, its left edge `column * (cellWidth +
 * gapAcross)` from the band's and its top `row * (cellHeight + gapDown)` - which is `tileFace`'s
 * placement on the board, line for line.
 */
internal data class FeatureBand(
    val columns: Int,
    val rows: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val gapAcross: Float,
    val gapDown: Float,
) {
    val height: Float get() = rows * cellHeight + (rows - 1) * gapDown

    fun left(index: Int): Float = (index % columns) * (cellWidth + gapAcross)

    fun top(index: Int): Float = (index / columns) * (cellHeight + gapDown)
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
