package dev.denza.apps.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Everything the palette does not carry: how far apart things sit, how round they are, how thick a
 * line is, how large type may be, and how long a change takes.
 *
 * [DenzaPalette] was the whole of this app's design system and it is only colour, which is why the
 * head unit ended up with forty-one distinct spacings, fourteen type sizes and seven corner radii
 * inside one screen - including the pairs 12/13, 15/16 and 19/20, differences you can measure and
 * cannot see. A ladder is what stops that: a value not on the ladder does not exist, and no two
 * rungs sit closer than about 1.2x, so two of them can never read as the same.
 *
 * These are the head unit's ladders. The driver's cluster is a different screen at a different
 * distance and keeps its own, in [dev.denza.apps.design.instrument.InstrumentDensity]. Both are
 * restated by the design boards in `tools/design-canvas/`, where `audit.py` measures the boards
 * against them; `DenzaMetricsTest` measures this file. A rung is added to one record and the other
 * in the same change, or not at all.
 */
object DenzaMetrics {

    /**
     * The spacing ladder, in density-independent pixels.
     *
     * Six rungs, none closer than one and a half times the one below. The screen this replaces
     * spent seventeen adjacent values between 4 and 32 - a scale so fine that every gap was a fresh
     * decision and none of them agreed.
     */
    object Space {
        /** Between a glyph and the word it belongs to. */
        val XS: Dp = 4.dp

        /** Between two lines of the same thought. */
        val S: Dp = 8.dp

        /** Between neighbours in a group: tiles in a row, rows in a grid. */
        val M: Dp = 12.dp

        /** Inside a surface, between its edge and its content. */
        val L: Dp = 20.dp

        /** Between two groups that are not the same thought. */
        val XL: Dp = 32.dp

        /** The screen's own side margin. */
        val XXL: Dp = 48.dp

        val RUNGS: List<Dp> = listOf(XS, S, M, L, XL, XXL)
    }

    /**
     * The corner ladder. A track or a pill takes its own half-height instead and is not on it.
     */
    object Radius {
        /** A tick, a chip, the end of a progress track. */
        val XS: Dp = 2.dp

        /** An icon well, a small inline control. */
        val S: Dp = 6.dp

        /** A row inside a surface: a switch row, a segmented cell. */
        val M: Dp = 12.dp

        /** A surface in its own right: a tile, a card, a dialog. */
        val L: Dp = 22.dp

        val RUNGS: List<Dp> = listOf(XS, S, M, L)
    }

    /**
     * The type ladder, in scale-independent pixels: 62 down to 15, six rungs at about 1.3x.
     *
     * The bottom rung is 15 and there is deliberately nothing under it. This screen is read at
     * arm's length from a driver's seat, and the eleven-, twelve- and thirteen-point captions the
     * old screen was full of were legible on a desk and not in a car.
     */
    object Type {
        /** A panel's headline figure. */
        val DISPLAY: TextUnit = 62.sp

        /** A dialog's title, a page's own name. */
        val HEADLINE: TextUnit = 46.sp

        /** A reading beside its label. */
        val TITLE: TextUnit = 34.sp

        /** A section heading. */
        val SECTION: TextUnit = 24.sp

        /** A tile's name, and anything else a finger points at. */
        val LABEL: TextUnit = 19.sp

        /** A tile's state line, and body text everywhere. */
        val BODY: TextUnit = 15.sp

        val RUNGS: List<TextUnit> = listOf(DISPLAY, HEADLINE, TITLE, SECTION, LABEL, BODY)

        /**
         * Leading for a name, a figure or a label - the board's `.nm`.
         *
         * A name that wraps is still one name, so its two lines sit closer than two lines of prose
         * would. This is not decoration: at 19 sp the difference between 1.2 and 1.3 is 2.5 dp a
         * line, and a tile that holds a two-line name over a two-line caption has about that much
         * room in hand.
         */
        const val LEADING_TIGHT: Float = 1.2f

        /** Leading for anything read as a sentence - the board's `.st`. */
        const val LEADING_BODY: Float = 1.3f
    }

    /**
     * One border weight, and one optical weight for icons.
     *
     * Selection is carried by fill and ink, never by a thicker edge - a border that thickens on
     * selection moves everything beside it by a pixel, and the eye reads the movement rather than
     * the selection.
     */
    object Stroke {
        val HAIRLINE: Dp = 1.dp

        /** An icon's stroke at its drawn size: ICON_WEIGHT * 24 / size. */
        const val ICON_WEIGHT: Float = 2.0f
    }

    /** Sizes that belong to one component rather than to the ladders. */
    object Component {
        /** The dashboard tile, measured off Main.dc.html. */
        val TILE_HEIGHT: Dp = 164.dp

        /** The tile's icon, at the size the board draws it. */
        val TILE_ICON: Dp = 30.dp

        /**
         * The chip's insides, as fractions of the chip.
         *
         * At ten features the chip is 68.0 dp in the two-thirds pane and 68.8 in the narrow one,
         * and these three come to 30, 7 and 9 - which is what they were written as. They are
         * ratios because a chip is a fraction of its row, so adding a feature shrinks it: an
         * eleventh puts it at 60.7, and a fixed 30 dp glyph in a 60.7 chip has its top-right
         * corner under the dot.
         *
         * The dot is the whole of what a chip says beyond which feature it is, so it has to stay
         * clear of the glyph at every size a chip can be.
         */
        const val CHIP_ICON_RATIO: Float = 30f / 68f
        const val CHIP_DOT_RATIO: Float = 7f / 68f
        const val CHIP_DOT_INSET_RATIO: Float = 9f / 68f

        /**
         * The smallest chip this design has.
         *
         * At 52 the glyph is 23 dp. Under that it stops reading at arm's length from a driver's
         * seat, and the target stops being comfortable - the platform's own floor for something a
         * finger has to hit is 48, and this keeps a little over it.
         *
         * Twelve features fit both panes: one row of twelve at 54.7 dp, two rows of six at 55.3.
         * A thirteenth is 49.5 and 45.7, and `DashboardLayoutPolicyTest` fails rather than the
         * screen quietly getting smaller - adding one is a design decision at that point, not an
         * entry in a registry. `ChipDensity.dc.html` draws all four counts.
         */
        val CHIP_MIN: Dp = 52.dp

        /**
         * How many tiles a full-width dashboard puts in one row.
         *
         * A measurement rather than a taste: "Экран водителя" is 145.2 dp at 19/500 and a tile
         * spends [Space.L] either side of its text, so a tile under about 186 dp loses a word to
         * an ellipsis. Six columns of 1184 is 187.3, and there is no seventh.
         *
         * A pane does not have a seventh either, which is why it has no tiles at all - see
         * [CHIP_COLUMNS_MEDIUM].
         */
        const val TILE_COLUMNS_WIDE: Int = 6

        /**
         * How many applications a picker puts in one row.
         *
         * Not the dashboard's six, which is what it used to borrow. The panel is 480 dp wide and
         * gives its content 416, so six columns with 8 between them is 62.7 dp a tile - around a
         * 44 dp icon well, leaving nine either side and eight characters of label. Five is 76.8.
         */
        const val PICKER_COLUMNS: Int = 5

        /**
         * How many rows of chips the narrow pane uses.
         *
         * The two-thirds pane has no matching constant because it has one row: a band of icons is
         * a toolbar, and a toolbar does not wrap, it fits. So the column count is the number of
         * features and the chip is its share of the row - which is what makes an eleventh feature
         * cost every chip 7 dp instead of costing the analyser a whole row of 80.
         *
         * The narrow pane is 392 dp of content and would put ten chips at 26 dp in one row, so it
         * takes two and the same rule applies inside them.
         *
         * See [dev.denza.apps.ui.components.DenzaChip], `TwoThirds.dc.html`, `OneThird.dc.html`,
         * `ChipDensity.dc.html`.
         */
        const val CHIP_ROWS_NARROW: Int = 2

        /**
         * The driver-screen picker's row.
         *
         * Fewer and larger than the app picker's [PICKER_COLUMNS], because it lists the navigators
         * on the car rather than everything installed. It used to read the dashboard's pane
         * column count, which happened to be three as well - so a pane's layout and this picker
         * were one constant, and moving either moved the other for no reason anybody had stated.
         */
        const val NAVIGATION_PICKER_COLUMNS: Int = 3

        /**
         * The default-app roles' row, off `DefaultApps.dc.html`.
         *
         * Fewer than [PICKER_COLUMNS] because this grid does not have the panel to itself: the
         * three roles are a segmented row above it and what the choice does is a line below it,
         * and the board draws the applications four across under them.
         */
        const val DEFAULT_APPS_PICKER_COLUMNS: Int = 4

        /**
         * An application offered for choosing: 12 + 44 + 8 + one line of 15 + 12, off the board.
         */
        val APP_TILE: Dp = 96.dp

        /**
         * How tall a picker's grid may grow before it scrolls.
         *
         * Five whole rows of [APP_TILE] with [Space.S] between them is 512; this is that plus a
         * glimpse of the sixth, which is the only thing on the panel saying there is a sixth. It
         * used to be 360 - three rows and a stub of the fourth - inside a panel 680 dp tall, so the
         * picker scrolled while a third of the panel below it stayed empty.
         */
        val PICKER_HEIGHT: Dp = 540.dp

        /**
         * The settings panel, measured off `Config.dc.html`.
         *
         * It hangs off the right edge for the whole height rather than sitting in the middle of
         * the screen. A dialog in the centre covers the tile it belongs to and every other tile
         * equally; a panel at the edge leaves the dashboard visible beside it, so the thing being
         * configured stays in sight while it is configured.
         */
        val SHEET_WIDTH: Dp = 480.dp

        /** The icon well of an application offered inside a settings panel. */
        val SHEET_APP_ICON: Dp = 44.dp

        /** A panel's one full-width action. */
        val PRIMARY_HEIGHT: Dp = 62.dp

        /** The panel header's two glyphs: the tile's own icon, and the way out. */
        val SHEET_HEADER_ICON: Dp = 32.dp
        val SHEET_CLOSE_ICON: Dp = 26.dp

        /**
         * How tall the narrow pane's strip is when nothing else has claimed the height.
         *
         * Kept only as the floor a `weight(1f)` cannot express. A pane's strip takes whatever the
         * chips leave, which is the one arrangement that cannot be wrong: the first cut of these
         * panes computed the remainder by hand, from 680, and the car takes 24 of that for the
         * freeform caption bar - so the foot of the strip was drawn past the bottom edge of the
         * window, exactly the failure the full screen's own panel height was introduced to fix.
         */
        val PANEL_HEIGHT_MIN: Dp = 300.dp

        /** A row a finger has to hit. */
        val ROW_HEIGHT: Dp = 56.dp

        /** A segmented control's own height. */
        val SEGMENT_HEIGHT: Dp = 42.dp
    }

    /**
     * Motion. Two durations and nothing between them: one for a control answering a finger, one for
     * a surface arriving or leaving.
     */
    object Motion {
        const val RESPONSE_MS: Int = 90
        const val TRANSITION_MS: Int = 220
    }
}
