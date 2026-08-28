package dev.denza.apps.design

import dev.denza.apps.feature.trip.TripPanelRenderer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design board, read at test time, against the constants the screen is built from.
 *
 * This exists because of how the first cut of the tile grid went wrong. Every number in
 * [DenzaMetrics] was lifted correctly off `Main.dc.html` - 164, 22, 20, 12, 19, 15 - and the screen
 * still looked nothing like the board, because the board's tile puts its icon at the top and its
 * words at the bottom and the code stacked all three from the top. The numbers agreed and the
 * composition did not, and nothing anywhere could tell: `audit.py` measures the board against
 * itself, `DenzaMetricsTest` measures the ladders against themselves, and the only thing joining
 * the two records was somebody remembering.
 *
 * So this is the join. It parses the board and asserts the code matches it, which means the board
 * cannot be edited without the code failing, and the code cannot be edited without the board
 * failing. Both records move together or neither does.
 *
 * **What it cannot check.** It reads numbers and declarations out of CSS; it cannot run Compose, so
 * it cannot prove the Kotlin actually arranges the tile the way the board's `justify-content` says.
 * It asserts the board still declares it, so that changing that line is loud. Proving the drawn
 * result still takes rendering the board and looking at the screen beside it - which is the step
 * that was skipped, and the reason this file exists.
 */
class MainBoardContractTest {

    @Test
    fun theTileIsTheBoardsTile() {
        val css = boardRule(".tile")
        assertEquals("tile height", DenzaMetrics.Component.TILE_HEIGHT.value, px(css, "height"), 1e-4f)
        assertEquals("tile padding", DenzaMetrics.Space.L.value, px(css, "padding"), 1e-4f)
        assertEquals("tile radius", DenzaMetrics.Radius.L.value, px(css, "border-radius"), 1e-4f)
    }

    @Test
    fun theTileHangsItsWordsFromTheBottom() {
        // The whole of the first wave's visible failure, in one declaration.
        val css = boardRule(".tile")
        assertTrue(
            "the board's tile is $css, which no longer hangs its content apart",
            css.contains("flex-direction:column") && css.contains("justify-content:space-between"),
        )
    }

    @Test
    fun theTwoTextStylesAreTheBoardsTwo() {
        val nameCss = boardRule(".nm")
        assertEquals("name size", DenzaMetrics.Type.LABEL.value, px(nameCss, "font-size"), 1e-4f)
        assertEquals(
            "name leading",
            DenzaMetrics.Type.LEADING_TIGHT,
            number(nameCss, "line-height"),
            1e-4f,
        )

        val stateCss = boardRule(".st")
        assertEquals("state size", DenzaMetrics.Type.BODY.value, px(stateCss, "font-size"), 1e-4f)
        assertEquals(
            "state leading",
            DenzaMetrics.Type.LEADING_BODY,
            number(stateCss, "line-height"),
            1e-4f,
        )
    }

    @Test
    fun theIconIsTheBoardsIcon() {
        val svg = ICON.find(board()) ?: error("no tile icon on the board")
        assertEquals(
            "icon size",
            DenzaMetrics.Component.TILE_ICON.value,
            svg.groupValues[1].toFloat(),
            1e-4f,
        )
        // The ladder states an optical weight at the drawn size; the board writes what that comes
        // to on its own 24-unit grid. They are the same statement twice, so they must agree.
        val onTheGrid = DenzaMetrics.Stroke.ICON_WEIGHT * 24f / DenzaMetrics.Component.TILE_ICON.value
        assertEquals("icon stroke", onTheGrid, svg.groupValues[2].toFloat(), 1e-3f)
    }

    @Test
    fun theGridAndTheMarginsAreTheBoards() {
        val grid = GRID.find(board()) ?: error("no tile grid on the board")
        assertEquals("columns", DenzaMetrics.Component.TILE_COLUMNS_WIDE, grid.groupValues[1].toInt())
        assertEquals("grid gap", DenzaMetrics.Space.M.value, grid.groupValues[2].toFloat(), 1e-4f)

        val page = PAGE_PADDING.find(board()) ?: error("no page padding on the board")
        assertEquals("top margin", DenzaMetrics.Space.L.value, page.groupValues[1].toFloat(), 1e-4f)
        assertEquals("side margin", DenzaMetrics.Space.XXL.value, page.groupValues[2].toFloat(), 1e-4f)
    }

    @Test
    fun aTwoLineNameOverATwoLineCaptionStillFits() {
        // The case the board actually draws - "Пассажирский экран" over "Установить приложение" -
        // and the one the first cut clipped. The old test added one line of each and never came
        // near the ceiling, so it passed while the screen was losing words to an ellipsis.
        val name = DenzaMetrics.Type.LABEL.value * DenzaMetrics.Type.LEADING_TIGHT * 2
        val state = DenzaMetrics.Type.BODY.value * DenzaMetrics.Type.LEADING_BODY * 2
        val needed = DenzaMetrics.Component.TILE_ICON.value +
            name + DenzaMetrics.Space.S.value + state +
            DenzaMetrics.Space.L.value * 2
        assertTrue(
            "a tile of ${DenzaMetrics.Component.TILE_HEIGHT} cannot hold $needed",
            needed <= DenzaMetrics.Component.TILE_HEIGHT.value,
        )
    }

    @Test
    fun thePanelIsGivenExactlyWhatTheBoardLeavesIt() {
        // The bug this exists for: the panel asked for 416 dp of a screen that has 680, while two
        // rows of tiles and the page margins had already spent 384 of it. The bottom 120 dp was
        // drawn past the edge of the window and nobody saw it, because nothing anywhere subtracted
        // one number from the other. This does.
        val frame = FRAME.find(board()) ?: error("no artboard frame on the board")
        val height = frame.groupValues[2].toFloat()
        val page = PAGE_PADDING.find(board()) ?: error("no page padding on the board")
        val top = page.groupValues[1].toFloat()
        val bottom = page.groupValues[3].toFloat()
        val grid = GRID.find(board()) ?: error("no tile grid on the board")
        val gap = grid.groupValues[2].toFloat()

        val rows = 2
        val left = height - top - bottom - rows * DenzaMetrics.Component.TILE_HEIGHT.value -
            (rows - 1) * gap - gap
        assertEquals(
            "the board leaves $left dp under the tiles",
            left,
            TripPanelRenderer.WIDE_VIRTUAL_H,
            1e-4f,
        )
    }

    @Test
    fun theBoardIsDrawnOnTheHeightTheCarActuallyGives() {
        // 680, not the screen's 800: a system dock takes 64 dp below the app's window and an
        // opaque status band 56 above it. Measured off the car, and the one number that makes
        // every other number on this board mean anything.
        val frame = FRAME.find(board()) ?: error("no artboard frame on the board")
        assertEquals("board width", 1280f, frame.groupValues[1].toFloat(), 1e-4f)
        assertEquals("board height", 680f, frame.groupValues[2].toFloat(), 1e-4f)
    }

    private fun board(): String = BOARD.readText()

    /** One CSS rule's body, whitespace squeezed out so a declaration is one token. */
    private fun boardRule(selector: String): String {
        val body = Regex("\\Q$selector\\E\\s*\\{([^}]*)}").find(board())
            ?: error("the board has no $selector rule")
        return body.groupValues[1].replace(" ", "")
    }

    private fun px(css: String, property: String): Float = number(css, property)

    private fun number(css: String, property: String): Float =
        Regex("(?<![\\w-])$property:([\\d.]+)").find(css)?.groupValues?.get(1)?.toFloat()
            ?: error("no $property in $css")

    private companion object {
        val BOARD: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "tools/design-canvas/Main.dc.html") }
            .firstOrNull { it.isFile }
            ?: error("Main.dc.html not found above ${System.getProperty("user.dir")}")

        val ICON = Regex("""<svg width="([\d.]+)"[^>]*stroke-width="([\d.]+)"""")
        val GRID = Regex("""grid-template-columns:repeat\((\d+),[^;]*;\s*gap:([\d.]+)px""")
        val PAGE_PADDING =
            Regex("""padding:([\d.]+)px ([\d.]+)px ([\d.]+)px ([\d.]+)px""")
        val FRAME = Regex("""width:([\d.]+)px; height:([\d.]+)px; box-sizing""")
    }
}
