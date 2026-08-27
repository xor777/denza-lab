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
 * a few times a month, and the version this replaces had been drawing the wide composition
 * squeezed to 62 per cent - captions at 9 dp - for as long as it had existed, with a board on disk
 * showing a screen that had been deleted.
 *
 * So: same join, same both-directions failure. Change `TwoThirds.dc.html` without the Kotlin and
 * this fails; change the Kotlin without the board and it fails too.
 */
class PaneBoardContractTest {

    @Test
    fun eachPaneBoardIsDrawnAtTheWindowTheCarActuallyHandsIt() {
        assertEquals("two-thirds width", 828f, frame(MEDIUM).first, 1e-4f)
        assertEquals("one-third width", 416f, frame(NARROW).first, 1e-4f)

        // 680 is the drawable height at every width - dock and status band removed. The
        // two-thirds page is drawn at exactly that, because it fits. The narrow page is drawn at
        // its own full scrolling height, because five rows of tiles do not: it is 1288, and the
        // ticks in the margins mark where the window ends.
        assertEquals("two-thirds height", WINDOW_H, frame(MEDIUM).second, 1e-4f)
        assertTrue(
            "the narrow board is ${frame(NARROW).second} and should be taller than one window",
            frame(NARROW).second > WINDOW_H,
        )
    }

    @Test
    fun theGridsAndTheMarginsAreTheBoards() {
        for ((board, mode) in listOf(MEDIUM to DashboardLayoutMode.MEDIUM, NARROW to DashboardLayoutMode.NARROW)) {
            val grid = GRID.find(read(board)) ?: error("no tile grid on $board")
            assertEquals(
                "columns on $board",
                DashboardLayoutPolicy.columns(mode),
                grid.groupValues[1].toInt(),
            )
            assertEquals("grid gap on $board", DenzaMetrics.Space.M.value, grid.groupValues[2].toFloat(), 1e-4f)

            val page = PAGE_PADDING.find(read(board)) ?: error("no page padding on $board")
            assertEquals("top margin on $board", DenzaMetrics.Space.L.value, page.groupValues[1].toFloat(), 1e-4f)
            assertEquals(
                "side margin on $board",
                DashboardLayoutPolicy.sideMargin(mode).value,
                page.groupValues[2].toFloat(),
                1e-4f,
            )
            assertEquals("bottom margin on $board", DenzaMetrics.Space.M.value, page.groupValues[3].toFloat(), 1e-4f)
        }
    }

    @Test
    fun aPaneTileIsNeverNarrowerThanTheLongestNameOnIt() {
        // The rule the column counts exist to satisfy, and the one thing about these boards that
        // is a measurement rather than a taste. "Экран водителя" is 145.2 dp at 19/500 in Roboto,
        // measured in a browser against the face the car draws - there is no text engine here, so
        // the number is carried rather than computed, and it is the reason the margin steps down
        // from 48 to 20 to 12 instead of staying put.
        for (mode in DashboardLayoutMode.entries) {
            val text = tileWidth(mode) - DenzaMetrics.Space.L.value * 2
            assertTrue(
                "$mode gives a tile ${tileWidth(mode)} dp, ${text} dp of words, and the longest " +
                    "name needs $LONGEST_NAME_DP",
                text >= LONGEST_NAME_DP,
            )
        }
    }

    @Test
    fun theTwoThirdsPageComesToExactlyTheWindowItIsDrawnIn() {
        // The same arithmetic MainBoardContractTest does for the full screen: margins, three rows
        // of tiles, the gap under them, and what is left is the strip. If the strip asks for more
        // than that, its foot is drawn past the bottom of the window and nobody ever sees it.
        val left = WINDOW_H - DenzaMetrics.Space.L.value - DenzaMetrics.Space.M.value -
            3 * DenzaMetrics.Component.TILE_HEIGHT.value - 3 * DenzaMetrics.Space.M.value
        assertEquals(
            "the two-thirds board leaves $left dp under its tiles",
            left,
            DenzaMetrics.Component.PANEL_HEIGHT_MEDIUM.value,
            1e-4f,
        )
        assertEquals(
            "and the renderer lays its pane out in that height",
            DenzaMetrics.Component.PANEL_HEIGHT_MEDIUM.value,
            TripPanelRenderer.MEDIUM_VIRTUAL_H,
            1e-4f,
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
    fun thePaneRowIsTheBoardsRow() {
        for (board in listOf(MEDIUM, NARROW)) {
            assertEquals("row height on $board", TripPanelRenderer.PANE_ROW, px(board, ".row", "height"), 1e-4f)
            assertEquals("row gap on $board", TripPanelRenderer.PANE_ROW_GAP, px(board, ".rows", "gap"), 1e-4f)
            assertEquals("label size on $board", TripPanelRenderer.PANE_LABEL, px(board, ".cap", "font-size"), 1e-4f)
            assertEquals("value size on $board", TripPanelRenderer.PANE_VALUE, px(board, ".val", "font-size"), 1e-4f)
            assertEquals("rate size on $board", TripPanelRenderer.PANE_RATE, px(board, ".rate", "font-size"), 1e-4f)
        }
        // Every one of those five is a rung, which is the point of writing them down twice.
        val ladder = DenzaMetrics.Type.RUNGS.map { it.value }
        assertTrue("label off the ladder", TripPanelRenderer.PANE_LABEL in ladder)
        assertTrue("value off the ladder", TripPanelRenderer.PANE_VALUE in ladder)
        assertTrue("rate off the ladder", TripPanelRenderer.PANE_RATE in ladder)
        assertTrue("row gap off the spacing ladder", TripPanelRenderer.PANE_ROW_GAP in DenzaMetrics.Space.RUNGS.map { it.value })
    }

    @Test
    fun theAnalyserGetsWhatTheBoardLeavesItBesideTheFigures() {
        // The two-thirds strip is one row: the analyser, a gap between groups, and a column of
        // figures whose width the board writes down. What the analyser gets is the remainder, and
        // the renderer has to agree about where that ends.
        val column = number(MEDIUM, """<div class="rows" style="width:([\d.]+)px""")
        val gap = number(MEDIUM, """display:flex; gap:([\d.]+)px; min-height:0""")
        assertEquals("group gap", DenzaMetrics.Space.XL.value, gap, 1e-4f)
        assertEquals(
            "the analyser's right edge",
            TripPanelRenderer.MEDIUM_VIRTUAL_W - column - gap,
            TripPanelRenderer.MEDIUM_SPECTRUM_RIGHT,
            1e-4f,
        )
        assertEquals(
            "and the figures start where the column does",
            TripPanelRenderer.MEDIUM_VIRTUAL_W - column,
            TripPanelRenderer.MEDIUM_ROWS_LEFT,
            1e-4f,
        )
    }

    @Test
    fun theTickerBandAndTheBarFieldAreTheBoards() {
        for ((board, bottom) in listOf(
            MEDIUM to TripPanelRenderer.MEDIUM_VIRTUAL_H,
            NARROW to TripPanelRenderer.NARROW_SPECTRUM_BOTTOM,
        )) {
            val strip = number(board, """align-items:center; height:([\d.]+)px""")
            assertEquals("ticker band on $board", SpectrumRenderer.STRIP_UNITS, strip, 1e-4f)

            // Where the baseline falls is the code's, so the board's bar field is the consequence.
            val field = number(board, """align-items:flex-end; gap:[\d.]+px; height:([\d.]+)px""")
            assertEquals(
                "bar field on $board",
                (bottom - strip) * SpectrumRenderer.BASELINE_FRACTION,
                field,
                0.01f,
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
    fun thePaneBoardsDrawTheSameTilesAsTheFullScreen() {
        // These boards are generated from `Main.dc.html` for exactly this reason. A pane that
        // quietly renames a tile, or keeps one the dashboard has dropped, is worse than no pane
        // board at all - it looks like a decision somebody made.
        val main = tiles(File(CANVAS, "Main.dc.html"))
        assertEquals("ten tiles on the full screen", 10, main.size)
        assertEquals("two-thirds tiles", main, tiles(MEDIUM))
        assertEquals("one-third tiles", main, tiles(NARROW))
    }

    private fun read(board: File): String = board.readText()

    private fun tiles(board: File): List<String> =
        TILE.findAll(read(board)).map { it.value.trim() }.toList()

    private fun frame(board: File): Pair<Float, Float> {
        val found = FRAME.find(read(board)) ?: error("no artboard frame on $board")
        return found.groupValues[1].toFloat() to found.groupValues[2].toFloat()
    }

    private fun px(board: File, selector: String, property: String): Float {
        val body = Regex("\\Q$selector\\E\\s*\\{([^}]*)}").find(read(board))
            ?: error("$board has no $selector rule")
        return Regex("(?<![\\w-])$property:([\\d.]+)").find(body.groupValues[1])
            ?.groupValues?.get(1)?.toFloat()
            ?: error("no $property in ${body.groupValues[1]}")
    }

    private fun number(board: File, pattern: String): Float =
        Regex(pattern).find(read(board))?.groupValues?.get(1)?.toFloat()
            ?: error("$board has nothing matching $pattern")

    private fun tileWidth(mode: DashboardLayoutMode): Float {
        val window = when (mode) {
            DashboardLayoutMode.WIDE -> 1280f
            DashboardLayoutMode.MEDIUM -> 828f
            DashboardLayoutMode.NARROW -> 416f
        }
        val columns = DashboardLayoutPolicy.columns(mode)
        val content = window - DashboardLayoutPolicy.sideMargin(mode).value * 2
        return (content - (columns - 1) * DenzaMetrics.Space.M.value) / columns
    }

    private companion object {
        const val WINDOW_H = 680f

        /** "Экран водителя" at 19 sp, weight 500, in Roboto. */
        const val LONGEST_NAME_DP = 145.2f

        val CANVAS: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "tools/design-canvas") }
            .firstOrNull { it.isDirectory }
            ?: error("tools/design-canvas not found above ${System.getProperty("user.dir")}")

        val MEDIUM = File(CANVAS, "TwoThirds.dc.html")
        val NARROW = File(CANVAS, "OneThird.dc.html")

        val TILE = Regex("""^    <div class="tile (?:on|off)">.*?\n    </div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        val GRID = Regex("""grid-template-columns:repeat\((\d+),[^;]*;\s*gap:([\d.]+)px""")
        val PAGE_PADDING = Regex("""padding:([\d.]+)px ([\d.]+)px ([\d.]+)px ([\d.]+)px""")
        val FRAME = Regex("""width:([\d.]+)px; height:([\d.]+)px; box-sizing""")
    }
}
