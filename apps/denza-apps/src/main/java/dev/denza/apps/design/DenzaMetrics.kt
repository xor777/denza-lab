package dev.denza.apps.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.denza.apps.design.luminofor.LuminoforSpec

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
 * distance and keeps its own sizes, in [LuminoforSpec.Cluster], which `LuminoforSpecContractTest`
 * holds to the Luminofor board's `spec.json`. These are restated by the design boards in
 * `tools/design-canvas/`, where `audit.py` measures the boards against them; `DenzaMetricsTest`
 * measures this file. A rung is added to one record and the other in the same change, or not at
 * all.
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
     * One border weight, and one stroke for icons.
     *
     * Selection is carried by fill and ink, never by a thicker edge - a border that thickens on
     * selection moves everything beside it by a pixel, and the eye reads the movement rather than
     * the selection.
     */
    object Stroke {
        val HAIRLINE: Dp = 1.dp

        /**
         * An icon's stroke, in units of its own 24-unit grid - Luminofor's `head.icon.stroke`.
         *
         * A grid stroke and not an optical weight, which is what this used to be: 2.0 dp at
         * whatever size the glyph was drawn, so 1.6 units at 30 and more on anything smaller. The
         * approved board strokes every glyph at 1.5 units and lets the stroke scale with the glyph -
         * 1.875 dp on a tile at 30, 1.625 on a chip at 26 - and the tile draws what the board draws.
         */
        const val ICON: Float = LuminoforSpec.Head.Icon.STROKE
    }

    /**
     * The dashboard tile's face, as Luminofor's `head.full.tiles` places it.
     *
     * Named here rather than read off [LuminoforSpec] inside the tile, so the tile has one record of
     * its own numbers and `LuminoforScreenContractTest` can hold that record to `spec.json`: the
     * glyph's box, the two baselines measured from the plate's top edge, the words' left inset, both
     * sizes and both weights. The words hang on baselines, not on a stack - see
     * [dev.denza.apps.ui.components.DenzaTile] for why that matters.
     */
    object Tile {
        val RADIUS: Dp = LuminoforSpec.Head.Full.Tiles.RADIUS.dp
        val GLYPH_LEFT: Dp = LuminoforSpec.Head.Full.Tiles.ICON_INSET_X.dp
        val GLYPH_TOP: Dp = LuminoforSpec.Head.Full.Tiles.ICON_INSET_Y.dp
        val TEXT_INSET: Dp = LuminoforSpec.Head.Full.Tiles.TEXT_INSET.dp
        val NAME_BASELINE: Dp = LuminoforSpec.Head.Full.Tiles.NAME_BASELINE.dp
        val STATUS_BASELINE: Dp = LuminoforSpec.Head.Full.Tiles.STATUS_BASELINE.dp

        /** In dp, like the board's pixel sizes - the tile pins them against the font scale. */
        const val NAME_SIZE: Float = LuminoforSpec.Head.Full.Tiles.NAME_SIZE
        const val STATUS_SIZE: Float = LuminoforSpec.Head.Full.Tiles.STATUS_SIZE

        /** `type.head.strong` and `type.head.weight`: Roboto 500 over Roboto 400. */
        const val NAME_WEIGHT: Int = LuminoforSpec.Type.HEAD_STRONG
        const val STATUS_WEIGHT: Int = LuminoforSpec.Type.HEAD_WEIGHT
    }

    /** Sizes that belong to one component rather than to the ladders. */
    object Component {
        /** The dashboard tile, Luminofor's `head.full.tiles.height`. */
        val TILE_HEIGHT: Dp = LuminoforSpec.Head.Full.Tiles.HEIGHT.dp

        /**
         * The tile's icon, at the size the board draws it - and the size a panel header's glyph is
         * built at, because every [DenzaIcons] vector is declared at this size.
         */
        val TILE_ICON: Dp = LuminoforSpec.Head.Full.Tiles.ICON.dp

        // The chip used to size its glyph, its dot and the dot's inset as fractions of itself. The
        // Luminofor chip has no dot, and its glyph is a fixed 26 in both panes - the board centres
        // a 26 box in whatever chip the row gives it - so the three ratios went with the dot. The
        // chip's own numbers are per window and live with the rest of the window's geometry, in
        // `DashboardLayoutPolicy`.

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
         * entry in a registry. `ChipDensity.dc.html` draws all four counts, on the older chip.
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
         * `DashboardLayoutPolicy.columns`.
         */
        const val TILE_COLUMNS_WIDE: Int = 6

        /**
         * How many applications a chooser puts in one row.
         *
         * Four, off `AppChooser.dc.html`. The panel is 480 dp wide and gives its content 416, so
         * four columns with 12 between them is 95 dp a tile and 79 of label - ten Cyrillic letters
         * at 15 sp, which is «Навигатор», «Кинопоиск» and «Настройки» whole and «Калькулятор» cut.
         * Five was 73.6 and 57 of label, and cut every Russian name over eight letters; six, which
         * the dashboard's tiles have and this used to borrow, was 62.7. The chooser has the panel
         * to itself now, so there is nothing to make room for by packing the row.
         */
        const val PICKER_COLUMNS: Int = 4

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
         * See [dev.denza.apps.ui.components.DenzaChip] and Luminofor's `one-sound` board, which
         * draws `head.one.chips.perRow` of them to a row.
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
         * An application offered for choosing: 12 + 44 + 8 + one line of 15 + 12, off the board.
         */
        val APP_TILE: Dp = 96.dp

        /**
         * An application's icon on the value line of a row that opens a chooser.
         *
         * Sized to the line of text it stands in rather than to the ladder: the row answers a
         * "what is chosen" with three or four of these where a sentence would have been, and an
         * icon taller than its line pushes the title off the row's own vertical centre.
         */
        val CHOICE_ICON: Dp = 24.dp

        /**
         * How tall a grid of applications may grow before it scrolls **inside a panel that
         * scrolls itself**.
         *
         * That is the only case left: the cluster panel holds the navigators inline, between a
         * heading and a switch, so the grid is one child of a scrolling column and has to be told
         * how tall it may be before it will measure at all.
         *
         * A chooser is not that case and does not read this. It is a page of its own - it takes
         * the panel, the panel stops scrolling, and the grid is the one thing that scrolls, so
         * bounding it would be inventing a floor under a list that already ends where the sheet
         * does. Two scrolls inside one another is what this number used to paper over: the roles'
         * grid opened four rows into itself because the page it was nested in had kept its offset.
         *
         * Five whole rows of [APP_TILE] with [Space.S] between them is 512; this is that plus a
         * glimpse of the sixth, which is the only thing on the panel saying there is a sixth.
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

        /** A panel's one full-width action. */
        val PRIMARY_HEIGHT: Dp = LuminoforSpec.Sheet.Button.HEIGHT.dp

        /**
         * A centred modal, for the two windows that cannot be a panel at the edge.
         *
         * The settings panel is the app's surface and it hangs off the right edge, which is right
         * for it and wrong for the ADB gate: the gate exists to say that nothing behind it can be
         * used yet, and a surface leaving the dashboard beside it says the opposite. So those keep
         * the centre - and stop each picking their own width. They were 0.72 and 0.68 of the
         * screen, which is two guesses that happen to look alike at 1280 and are 30 dp apart in a
         * pane, and the pane is where the difference shows.
         *
         * Half the full screen, and a ceiling rather than a share: in a pane the modal fills the
         * width it is given, because 0.72 of 416 dp is a card with 40 dp of prose in it.
         */
        val MODAL_WIDTH: Dp = 640.dp

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

    }

    /**
     * Motion. One duration: a surface, a fill or an edge arriving or leaving.
     *
     * There were two. The shorter one - a control answering a finger - was declared and never read,
     * because every control on this screen answers with a colour that is already crossfading at
     * [TRANSITION_MS]. A rung nothing stands on is not a ladder, it is a promise, and the next
     * person to reach for it would have had two plausible durations to choose between.
     */
    object Motion {
        const val TRANSITION_MS: Int = 220
    }
}
