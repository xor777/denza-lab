package dev.denza.apps.design

import dev.denza.apps.feature.trip.SpectrumRenderer
import dev.denza.apps.feature.trip.TripPanelRenderer
import dev.denza.apps.ui.DashboardLayoutMode
import dev.denza.apps.ui.DashboardLayoutPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pane boards, read at test time, against the code that draws them.
 *
 * `MainBoardContractTest` does this for the full screen and says why. The panes need it more, not
 * less: nobody looks at them. The full screen is what the owner sees every time the car starts, so
 * a design that drifted there is noticed within a day; a two-thirds pane is entered deliberately,
 * a few times a month, and what had been shipping was the wide composition squeezed to 62 per cent
 * - captions at 9 dp - under two boards on disk that drew a screen which had been deleted.
 *
 * So: same join, same both-directions failure. Change `TwoThirds.dc.html` without the Kotlin and
 * this fails; change the Kotlin without the board and it fails too.
 */
class PaneBoardContractTest {

    @Test
    fun eachPaneBoardIsDrawnAtTheWindowTheCarActuallyHandsIt() {
        for (board in listOf(MEDIUM, NARROW)) {
            val (width, height) = frame(board)
            assertEquals(
                "$board should be drawn at the pane's own width",
                if (board == MEDIUM) 828f else 416f,
                width,
                1e-4f,
            )
            assertEquals("$board height", WINDOW_H, height, 1e-4f)
        }
    }

    @Test
    fun aPanePageAddsUpToTheWindowWithTheCaptionBarTakenOffFirst() {
        // The bug this exists for, found on the car and not here. A pane window is 680 dp and the
        // top 24 belong to the system - BYD freeform draws its drag handle there and safeDrawing
        // reports it - so a page laid out against 680 is 24 too tall and its last row is drawn past
        // the bottom edge. That is exactly what happened: the sunset row was cut in half.
        //
        // The app no longer does this arithmetic at all; the strip takes the remainder. The boards
        // still have to, because a board is a picture, so this checks their sums close.
        for ((board, mode) in listOf(
            MEDIUM to DashboardLayoutMode.MEDIUM,
            NARROW to DashboardLayoutMode.NARROW,
        )) {
            val caption = number(board, """<div style="height:([\d.]+)px; flex-shrink:0""")
            assertEquals("caption bar on $board", CAPTION_DP, caption, 1e-4f)

            val page = PAGE_PADDING.find(read(board)) ?: error("no page padding on $board")
            val top = page.groupValues[1].toFloat()
            val side = page.groupValues[2].toFloat()
            val bottom = page.groupValues[3].toFloat()
            assertEquals("top margin on $board", DenzaMetrics.Space.L.value, top, 1e-4f)
            assertEquals("bottom margin on $board", DenzaMetrics.Space.M.value, bottom, 1e-4f)
            assertEquals(
                "side margin on $board",
                DashboardLayoutPolicy.sideMargin(mode).value,
                side,
                1e-4f,
            )

            val width = frame(board).first
            val columns = DashboardLayoutPolicy.columns(mode, FEATURES)
            val rows = (FEATURES + columns - 1) / columns
            val chip = (width - side * 2 - (columns - 1) * DenzaMetrics.Space.M.value) / columns
            val chips = rows * chip + (rows - 1) * DenzaMetrics.Space.M.value
            val strip = WINDOW_H - caption - top - chips - DenzaMetrics.Space.XL.value - bottom
            assertTrue(
                "$board leaves the strip $strip dp, which is under the floor the renderer keeps",
                strip >= TripPanelRenderer.PANE_MIN_ANALYSER,
            )
        }
    }

    @Test
    fun thePaneChipIsAChipAndNotASmallTile() {
        for (board in listOf(MEDIUM, NARROW)) {
            assertEquals(
                "chip radius on $board",
                DenzaMetrics.Radius.M.value,
                px(board, ".chip", "border-radius"),
                1e-4f,
            )
            assertTrue(
                "the chip on $board is not square",
                boardRule(board, ".chip").contains("aspect-ratio:1"),
            )
            // The glyph and the dot are fractions of the chip, so the board writes what those
            // fractions come to at the chip its own row gives it.
            val chip = DashboardLayoutPolicy.chipWidth(
                if (board == MEDIUM) DashboardLayoutMode.MEDIUM else DashboardLayoutMode.NARROW,
                FEATURES,
            ).value
            val expectedIconSize = Math.round(chip * DenzaMetrics.Component.CHIP_ICON_RATIO).toFloat()
            val featureIcons = featureIcons(board)
            assertEquals("feature icon count on $board", FEATURES, featureIcons.size)
            featureIcons.forEachIndexed { index, svg ->
                val icon = ICON.find(svg) ?: error("feature ${index + 1} on $board has no icon metrics")
                assertEquals(
                    "icon ${index + 1} width on $board",
                    expectedIconSize,
                    icon.groupValues[1].toFloat(),
                    1e-4f,
                )
                assertEquals(
                    "icon ${index + 1} height on $board",
                    expectedIconSize,
                    icon.groupValues[2].toFloat(),
                    1e-4f,
                )
                assertEquals(
                    "icon ${index + 1} optical stroke on $board",
                    DenzaMetrics.Stroke.ICON_WEIGHT * 24f / expectedIconSize,
                    icon.groupValues[3].toFloat(),
                    1e-3f,
                )
            }
            assertEquals(
                "dot on $board",
                chip * DenzaMetrics.Component.CHIP_DOT_RATIO,
                number(board, """<div class="dot" style="top:[\d.]+px; right:[\d.]+px; width:([\d.]+)px"""),
                0.05f,
            )
            assertEquals(
                "dot inset on $board",
                chip * DenzaMetrics.Component.CHIP_DOT_INSET_RATIO,
                number(board, """<div class="dot" style="top:([\d.]+)px"""),
                0.05f,
            )
            // The tile's hold-corner fold, scaled with the chip - the same sign for the same
            // gesture. Written in percent because the chip itself is a fraction of its row.
            val fold = boardRule(board, ".chip::after")
            assertEquals(
                "hold fold on $board",
                DenzaMetrics.Component.CHIP_HOLD_RATIO * 100f,
                Regex("width:([\\d.]+)%").find(fold)?.groupValues?.get(1)?.toFloat()
                    ?: error("no fold width on $board: $fold"),
                0.01f,
            )
            assertTrue(
                "the fold on $board is $fold, no longer a flush corner triangle",
                fold.replace(" ", "").contains("clip-path:polygon(100%0,100%100%,0100%)"),
            )
        }
    }

    @Test
    fun theChipGridsAreTheOnesThePolicyHandsOut() {
        for ((board, mode) in listOf(
            MEDIUM to DashboardLayoutMode.MEDIUM,
            NARROW to DashboardLayoutMode.NARROW,
        )) {
            val grid = GRID.find(read(board)) ?: error("no chip grid on $board")
            assertEquals(
                "columns on $board",
                DashboardLayoutPolicy.columns(mode, FEATURES),
                grid.groupValues[1].toInt(),
            )
            assertEquals("grid gap on $board", DenzaMetrics.Space.M.value, grid.groupValues[2].toFloat(), 1e-4f)
            assertTrue("$mode should draw chips", DashboardLayoutPolicy.chips(mode))
        }
        assertTrue(
            "the full screen draws tiles, not chips",
            !DashboardLayoutPolicy.chips(DashboardLayoutMode.WIDE),
        )
    }

    @Test
    fun aPaneIsLaidOutOneUnitToOneDp() {
        // The whole reason the panes are separate compositions rather than the wide one rescaled.
        // A virtual width that is not the content width is a scale factor, and a scale factor on
        // this panel walks its type off the bottom of the ladder: at 828 the wide space put the
        // strip's captions at 9 dp.
        assertEquals(
            "two-thirds content width",
            828f - DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.MEDIUM).value * 2,
            TripPanelRenderer.MEDIUM_VIRTUAL_W,
            1e-4f,
        )
        assertEquals(
            "one-third content width",
            416f - DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW).value * 2,
            TripPanelRenderer.NARROW_VIRTUAL_W,
            1e-4f,
        )
    }

    @Test
    fun theFiguresAreTheBoardsInBothOfTheirShapes() {
        assertEquals(
            "the block band on the two-thirds board",
            TripPanelRenderer.PANE_BLOCK,
            px(MEDIUM, ".across", "height"),
            1e-4f,
        )
        assertEquals("block figure", DenzaMetrics.Type.HEADLINE.value, px(MEDIUM, ".num", "font-size"), 1e-4f)
        assertEquals("block unit", TripPanelRenderer.PANE_VALUE, px(MEDIUM, ".un", "font-size"), 1e-4f)
        assertEquals(
            "the rule between two readings is a hairline with the group gap either side",
            DenzaMetrics.Space.XL.value / 2f,
            number(MEDIUM, """\.rule \{[^}]*margin:0 ([\d.]+)px"""),
            1e-4f,
        )
        assertEquals("rule weight", TripPanelRenderer.PANE_RULE, px(MEDIUM, ".rule", "width"), 1e-4f)

        assertEquals("row height", TripPanelRenderer.PANE_ROW, px(NARROW, ".row", "height"), 1e-4f)
        assertEquals("row gap", TripPanelRenderer.PANE_ROW_GAP, px(NARROW, ".rows", "gap"), 1e-4f)
        assertEquals("row reading", TripPanelRenderer.PANE_VALUE, px(NARROW, ".val", "font-size"), 1e-4f)
        // The one number that makes three rows comparable rather than three right-hung strings.
        // The board spends it as a box plus the row's own gap, so that the two rects do not touch.
        assertEquals(
            "the label column on the narrow board",
            TripPanelRenderer.PANE_LABEL_COLUMN,
            number(NARROW, """\.row \.cap \{[^}]*width:([\d.]+)px""") +
                number(NARROW, """\.row \{[^}]*gap:([\d.]+)px"""),
            1e-4f,
        )
        // One glyph, one size: the rate is 19 in both shapes, so the arrow beside it is 20 in both.
        for (board in listOf(MEDIUM, NARROW)) {
            val arrow = ARROW.find(read(board)) ?: error("no variometer arrow on $board")
            assertEquals("arrow on $board", TripPanelRenderer.ARROW_SIZE, arrow.groupValues[1].toFloat(), 1e-4f)
            assertEquals(
                "arrow optical stroke on $board",
                DenzaMetrics.Stroke.ICON_WEIGHT * 24f / TripPanelRenderer.ARROW_SIZE,
                arrow.groupValues[2].toFloat(),
                1e-3f,
            )
        }

        for (board in listOf(MEDIUM, NARROW)) {
            assertEquals("label on $board", TripPanelRenderer.PANE_LABEL, px(board, ".cap", "font-size"), 1e-4f)
            assertEquals("rate on $board", TripPanelRenderer.PANE_RATE, px(board, ".rate", "font-size"), 1e-4f)
        }

        // Every one of those is a rung, which is the point of writing them down twice.
        val type = DenzaMetrics.Type.RUNGS.map { it.value }
        assertTrue("label off the ladder", TripPanelRenderer.PANE_LABEL in type)
        assertTrue("reading off the ladder", TripPanelRenderer.PANE_VALUE in type)
        assertTrue("rate off the ladder", TripPanelRenderer.PANE_RATE in type)
        val space = DenzaMetrics.Space.RUNGS.map { it.value }
        assertTrue("row gap off the ladder", TripPanelRenderer.PANE_ROW_GAP in space)
        assertTrue("group gap off the ladder", TripPanelRenderer.PANE_GROUP in space)
    }

    @Test
    fun theTickerBandAndTheBarFieldAreTheBoards() {
        for (board in listOf(MEDIUM, NARROW)) {
            val strip = number(board, """align-items:center; height:([\d.]+)px""")
            assertEquals("ticker band on $board", SpectrumRenderer.STRIP_UNITS, strip, 1e-4f)

            // Where the baseline falls is the code's, so the board's reflection is the
            // consequence of its bar field: the field is 0.8319 of the box below the ticker, what
            // is under the baseline is the rest of it, and the analyser crops that at 40.
            val field = number(board, """align-items:flex-end; gap:[\d.]+px; height:([\d.]+)px""")
            val reflect = number(board, """height:([\d.]+)px; overflow:hidden; opacity""")
            val belowBaseline =
                field / SpectrumRenderer.BASELINE_FRACTION * (1f - SpectrumRenderer.BASELINE_FRACTION)
            assertEquals(
                "reflection on $board",
                minOf(belowBaseline, SpectrumRenderer.REFLECT_UNITS),
                reflect,
                0.5f,
            )

            val width = number(board, """<div style="width:([\d.]+)px; display:flex""")
            val gap = number(board, """align-items:flex-end; gap:([\d.]+)px""")
            assertEquals(
                "bar width fraction on $board",
                SpectrumRenderer.BAR_WIDTH_FRACTION,
                width / (width + gap),
                1e-3f,
            )
        }
    }

    @Test
    fun thePaneBoardsCarryTheSameFeaturesAsTheFullScreen() {
        // These boards are generated from `Main.dc.html` for exactly this reason. A pane that
        // quietly keeps a feature the dashboard has dropped, or draws it with another glyph, is
        // worse than no pane board at all - it looks like a decision somebody made.
        val main = icons(File(CANVAS, "Main.dc.html"))
        assertEquals("features on the full screen", FEATURES, main.size)
        for (board in listOf(MEDIUM, NARROW)) {
            val pane = icons(board)
            assertEquals("$board should carry every feature", FEATURES, pane.size)
            assertEquals("the glyphs on $board", main, pane)
        }
    }

    private fun read(board: File): String = board.readText()

    /**
     * Every feature's glyph, in the order the board draws it.
     *
     * Compared with its rendering scale taken off: the row decides both the chip icon's width and
     * height and the viewport stroke that preserves one optical weight at that size. Their exact
     * values are checked for every icon above; everything that defines the glyph itself remains in
     * this equality.
     */
    private fun icons(board: File): List<String> =
        featureIcons(board).map(::withoutRenderingScale)

    private fun featureIcons(board: File): List<String> =
        FEATURE_ICON.findAll(read(board)).map { it.groupValues[1] }.toList()

    private fun withoutRenderingScale(svg: String): String {
        val openingEnd = svg.indexOf('>')
        check(openingEnd >= 0) { "feature icon has no closing bracket: $svg" }
        val opening = svg.substring(0, openingEnd + 1)
            .replace(SIZE, "")
            .replace(STROKE_WIDTH, "")
        return opening + svg.substring(openingEnd + 1)
    }

    private fun frame(board: File): Pair<Float, Float> {
        val found = FRAME.find(read(board)) ?: error("no artboard frame on $board")
        return found.groupValues[1].toFloat() to found.groupValues[2].toFloat()
    }

    private fun boardRule(board: File, selector: String): String =
        Regex("\\Q$selector\\E\\s*\\{([^}]*)}").find(read(board))?.groupValues?.get(1)
            ?: error("$board has no $selector rule")

    private fun px(board: File, selector: String, property: String): Float =
        Regex("(?<![\\w-])$property:([\\d.]+)").find(boardRule(board, selector))
            ?.groupValues?.get(1)?.toFloat()
            ?: error("no $property in ${boardRule(board, selector)}")

    private fun number(board: File, pattern: String): Float =
        Regex(pattern).find(read(board))?.groupValues?.get(1)?.toFloat()
            ?: error("$board has nothing matching $pattern")

    private companion object {
        const val WINDOW_H = 680f
        const val FEATURES = 11

        /** What BYD's freeform windowing keeps at the top of a pane. Measured on the car. */
        const val CAPTION_DP = 24f

        val CANVAS: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "tools/design-canvas") }
            .firstOrNull { it.isDirectory }
            ?: error("tools/design-canvas not found above ${System.getProperty("user.dir")}")

        val MEDIUM = File(CANVAS, "TwoThirds.dc.html")
        val NARROW = File(CANVAS, "OneThird.dc.html")

        val ICON = Regex(
            """<svg width="([\d.]+)" height="([\d.]+)"[^>]*stroke-width="([\d.]+)""",
        )

        /** The variometer's arrow, which is the one svg on a pane board that is not a feature. */
        val ARROW = Regex(
            """<svg width="([\d.]+)"[^>]*stroke-width="([\d.]+)"[^>]*><path d="M12 19V6""",
        )
        val SIZE = Regex("""width="[\d.]+" height="[\d.]+" """)
        val STROKE_WIDTH = Regex("""stroke-width="[\d.]+" """)

        /**
         * A feature's glyph: the svg inside a tile or a chip, and nothing else on the board.
         *
         * Scoped rather than "every svg", because both records also draw a sun beside the sunset
         * and an arrow beside the variometer, and those are not features. It takes the first svg
         * after the opening div rather than the next thing along: the mirrors tile wraps its glyph
         * in a row so it can hang three position dots beside it.
         */
        val FEATURE_ICON = Regex(
            """<div class="(?:tile|chip) (?:on|off)">(?:(?!<svg)[\s\S])*(<svg [\s\S]*?</svg>)""",
        )
        val GRID = Regex("""grid-template-columns:repeat\((\d+),[^;]*;\s*gap:([\d.]+)px""")
        val PAGE_PADDING = Regex("""padding:([\d.]+)px ([\d.]+)px ([\d.]+)px ([\d.]+)px""")
        val FRAME = Regex("""width:([\d.]+)px; height:([\d.]+)px; box-sizing""")
    }
}
