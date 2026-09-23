package dev.denza.apps.design

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.design.luminofor.SpecJson.list
import dev.denza.apps.design.luminofor.SpecJson.num
import dev.denza.apps.design.luminofor.SpecJson.str
import dev.denza.apps.ui.DashboardLayoutMode
import dev.denza.apps.ui.DashboardLayoutPolicy
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.components.TileFace
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's geometry and its faces, against Luminofor's `spec.json` and `luminofor.js`.
 *
 * `LuminoforSpecContractTest` holds `LuminoforSpec` to the file value by value. That proves the
 * constants and nothing about the screen: the first cut of the tile carried every number off the
 * old board and still looked nothing like it, because the numbers were right and the arithmetic
 * between them was not. So this one does the board's arithmetic - where `drawHead` puts each tile
 * and each chip, where the strip box starts and ends, how bright each word comes out on its plate -
 * and asserts that `DashboardLayoutPolicy`, `DenzaMetrics` and `TileFace` arrive at the same
 * answers in all three windows.
 *
 * It replaces `MainBoardContractTest` and `PaneBoardContractTest`, which read the same facts out of
 * `Main.dc.html`, `TwoThirds.dc.html` and `OneThird.dc.html`. Those boards are still on disk as the
 * underlay the settings-sheet boards are drawn over; they are no longer the dashboard's design.
 *
 * Both directions fail, as before: edit the spec without the Kotlin, or the Kotlin without the
 * spec, and this stops the build. What it still cannot do is run Compose. The drawn result is
 * proved by rendering the app from a fixture and laying it over the board's PNG.
 */
class LuminoforScreenContractTest {

    private fun near(message: String, expected: Double, actual: Float, tolerance: Double = 1e-3) =
        assertEquals(message, expected, actual.toDouble(), tolerance)

    private val modes = listOf(
        Triple(DashboardLayoutMode.WIDE, "full", 0.0),
        Triple(DashboardLayoutMode.MEDIUM, "two", num("head", "two", "captionBar")),
        Triple(DashboardLayoutMode.NARROW, "one", num("head", "one", "captionBar")),
    )

    @Test
    fun theThreeWindowsAreTheSpecsThree() {
        assertEquals(num("head", "full", "size", "0").toInt(), DashboardLayoutPolicy.WIDE_WIDTH_DP)
        assertEquals(num("head", "two", "size", "0").toInt(), DashboardLayoutPolicy.MEDIUM_WIDTH_DP)
        assertEquals(num("head", "one", "size", "0").toInt(), DashboardLayoutPolicy.NARROW_WIDTH_DP)
        for ((mode, key, _) in modes) {
            assertEquals(mode, DashboardLayoutPolicy.resolve(num("head", key, "size", "0").toInt()))
            assertEquals("$key height", 680.0, num("head", key, "size", "1"), 0.0)
        }
        assertTrue("the full screen draws tiles", !DashboardLayoutPolicy.chips(DashboardLayoutMode.WIDE))
        assertTrue(DashboardLayoutPolicy.chips(DashboardLayoutMode.MEDIUM))
        assertTrue(DashboardLayoutPolicy.chips(DashboardLayoutMode.NARROW))
    }

    @Test
    fun theMarginsAreTheStripBoxesOwnEdges() {
        for ((mode, key, _) in modes) {
            val width = num("head", key, "size", "0")
            val box = box(key)
            near("$key margin", num("head", key, "margin"), DashboardLayoutPolicy.sideMargin(mode).value)
            near("$key strip box left", box[0], DashboardLayoutPolicy.sideMargin(mode).value)
            near("$key strip box right", width - box[2], DashboardLayoutPolicy.sideMargin(mode).value)
            near("$key bottom margin", 680.0 - box[3], DashboardLayoutPolicy.bottomMargin(mode).value)
        }
    }

    @Test
    fun theTilesStandWhereDrawHeadPutsThem() {
        // drawHead: tw = (W - 2 margin - 5 gap) / 6; tile i at (L + (i % 6)(tw + gap), top + (i / 6)(h + gap))
        val w = num("head", "full", "size", "0")
        val margin = num("head", "full", "margin")
        val gap = num("head", "full", "tiles", "gap")
        val height = num("head", "full", "tiles", "height")
        val top = num("head", "full", "tiles", "top")
        val tw = (w - 2 * margin - gap * 5) / 6
        assertEquals("the tile the board draws", 187.333, tw, 1e-3)

        val band = DashboardLayoutPolicy.band(DashboardLayoutMode.WIDE, FEATURES, (w - 2 * margin).toFloat())
        near("tile width", tw, band.cellWidth)
        near("tile height", height, band.cellHeight)
        near("tile height, as DenzaMetrics has it", height, DenzaMetrics.Component.TILE_HEIGHT.value)
        assertEquals("rows", 2, band.rows)
        val inset = DashboardLayoutPolicy.topInset(DashboardLayoutMode.WIDE).value
        for (i in 0 until FEATURES) {
            near("tile $i x", margin + (i % 6) * (tw + gap), margin.toFloat() + band.left(i))
            near("tile $i y", top + (i / 6) * (height + gap), inset + band.top(i))
        }
    }

    @Test
    fun theChipsStandWhereDrawHeadPutsThem() {
        for ((mode, key, bar) in modes.drop(1)) {
            // drawHead: g = (Wd - perRow size) / (perRow - 1). The narrow pane puts chip i at
            // L + (i % perRow)(size + g), top + (i / perRow)(size + rowGap), two rows. The
            // two-thirds pane puts every feature in its one row: the chip is cs = (Wd - (n - 1) g) / n,
            // which is the spec's size at the spec's perRow, and chip i is at L + i (cs + g).
            val w = num("head", key, "size", "0")
            val margin = num("head", key, "margin")
            val content = w - 2 * margin
            val size = num("head", key, "chips", "size")
            val perRow = num("head", key, "chips", "perRow").toInt()
            val rowGap = if (key == "one") num("head", "one", "chips", "rowGap") else 0.0
            val g = (content - perRow * size) / (perRow - 1)
            val top = num("head", key, "chips", "top")
            val drawnPerRow = if (key == "two") FEATURES else perRow
            val chip = if (key == "two") twoThirdsChip(FEATURES) else size

            val band = DashboardLayoutPolicy.band(mode, FEATURES, content.toFloat())
            assertEquals("$key columns", drawnPerRow, band.columns)
            near("$key chip", chip, band.cellWidth)
            near("$key chip is square", chip, band.cellHeight)
            near("$key chip at its measured window", chip, DashboardLayoutPolicy.chipWidth(mode, FEATURES).value)
            val inset = DashboardLayoutPolicy.topInset(mode).value
            for (i in 0 until FEATURES) {
                near("$key chip $i x", margin + (i % drawnPerRow) * (chip + g), margin.toFloat() + band.left(i))
                near("$key chip $i y", top + (i / drawnPerRow) * (chip + rowGap), (bar + inset + band.top(i)).toFloat())
            }
        }
    }

    @Test
    fun atTheSpecsOwnCountTheTwoThirdsChipIsTheSpecs() {
        // The spec's two-thirds row is drawn for perRow features, and that is what fixes its gap.
        val perRow = num("head", "two", "chips", "perRow").toInt()
        near("the spec's chip", num("head", "two", "chips", "size"), twoThirdsChip(perRow).toFloat())
    }

    @Test
    fun theStripBoxIsTheSpecsInAllThreeWidths() {
        for ((mode, key, bar) in modes) {
            val w = num("head", key, "size", "0")
            val content = (w - 2 * DashboardLayoutPolicy.sideMargin(mode).value).toFloat()
            val box = box(key)
            // The page is laid out in what safeDrawing leaves: the window less its caption bar.
            val page = DashboardLayoutPolicy.page(mode, FEATURES, content, (680.0 - bar).toFloat().dp)
            assertFalse("$key should fit its window without scrolling", page.scrolls)

            val stripTop = bar + DashboardLayoutPolicy.topInset(mode).value +
                DashboardLayoutPolicy.featureBandHeight(mode, FEATURES, content).value +
                DashboardLayoutPolicy.bandGap(mode).value
            // The spec's two-thirds box hangs 24 under a row of perRow chips; a row of any other
            // count moves it by what the chip gained or lost, and drawHead moves what hangs in it.
            val dy = if (key == "two") twoThirdsChip(FEATURES) - num("head", "two", "chips", "size") else 0.0
            near("$key strip box top", box[1] + dy, stripTop.toFloat(), 0.05)
            near("$key strip box height", box[3] - box[1] - dy, page.panelHeight.value, 0.05)
            near("$key strip box width", box[2] - box[0], content)
        }
        near("the full screen's strip is its box's shape", 296.0, DashboardLayoutPolicy.wholeScreenPanelHeight(1184f).value)
        near("the band gap on the full screen", 12.0, DashboardLayoutPolicy.bandGap(DashboardLayoutMode.WIDE).value)
        near("the band gap in a pane", 24.0, DashboardLayoutPolicy.bandGap(DashboardLayoutMode.MEDIUM).value)
        near("and in the other", 24.0, DashboardLayoutPolicy.bandGap(DashboardLayoutMode.NARROW).value)
    }

    @Test
    fun theCaptionBarIsOnlyAPanesAndTheBandStandsUnderIt() {
        near("full screen", num("head", "full", "tiles", "top"), DashboardLayoutPolicy.topInset(DashboardLayoutMode.WIDE).value)
        near("two thirds: 16 under the bar", 16.0, DashboardLayoutPolicy.topInset(DashboardLayoutMode.MEDIUM).value)
        near("one third: 12 under the bar", 12.0, DashboardLayoutPolicy.topInset(DashboardLayoutMode.NARROW).value)
    }

    @Test
    fun cornersAndGlyphs() {
        near("tile radius", num("head", "full", "tiles", "radius"), DashboardLayoutPolicy.cornerRadius(DashboardLayoutMode.WIDE).value)
        near("tile radius, as the tile has it", num("head", "full", "tiles", "radius"), DenzaMetrics.Tile.RADIUS.value)
        near("two-thirds chip radius", num("head", "two", "chips", "radius"), DashboardLayoutPolicy.cornerRadius(DashboardLayoutMode.MEDIUM).value)
        near("one-third chip radius", num("head", "one", "chips", "radius"), DashboardLayoutPolicy.cornerRadius(DashboardLayoutMode.NARROW).value)
        near("tile glyph", num("head", "full", "tiles", "icon"), DashboardLayoutPolicy.glyphSize(DashboardLayoutMode.WIDE).value)
        near("tile glyph, as DenzaMetrics has it", num("head", "full", "tiles", "icon"), DenzaMetrics.Component.TILE_ICON.value)
        near("chip glyph", num("head", "two", "chips", "icon"), DashboardLayoutPolicy.glyphSize(DashboardLayoutMode.MEDIUM).value)
        near("chip glyph", num("head", "one", "chips", "icon"), DashboardLayoutPolicy.glyphSize(DashboardLayoutMode.NARROW).value)
        near("glyph box", num("head", "full", "tiles", "iconInset", "0"), DenzaMetrics.Tile.GLYPH_LEFT.value)
        near("glyph box", num("head", "full", "tiles", "iconInset", "1"), DenzaMetrics.Tile.GLYPH_TOP.value)
        near("icon stroke, in grid units", num("head", "icon", "stroke"), DenzaMetrics.Stroke.ICON)
    }

    @Test
    fun theWordsAreTheBoardsWords() {
        val t = DenzaMetrics.Tile
        near("name baseline", num("head", "full", "tiles", "nameBaseline"), t.NAME_BASELINE.value)
        near("status baseline", num("head", "full", "tiles", "statusBaseline"), t.STATUS_BASELINE.value)
        near("text inset", num("head", "full", "tiles", "textInset"), t.TEXT_INSET.value)
        near("name size", num("head", "full", "tiles", "nameSize"), t.NAME_SIZE)
        near("status size", num("head", "full", "tiles", "statusSize"), t.STATUS_SIZE)
        assertEquals("name weight", num("type", "head", "strong").toInt(), t.NAME_WEIGHT)
        assertEquals("status weight", num("type", "head", "weight").toInt(), t.STATUS_WEIGHT)
        assertEquals("Roboto", str("type", "head", "family"))
        // And the drawing code sets them as those two weights: the name at `w: 500`, the status
        // at lab's own 400.
        assertTrue(Regex("""lab\(c, tile\.name,[^)]*\{ w: 500 \}\)""").containsMatchIn(board))
    }

    @Test
    fun theFacesIntensitiesAreTheDrawingCodes() {
        // tileFace() writes these as literals; spec.json does not carry them.
        val name = Regex("""lab\(c, tile\.name,[^;]*on \? ([\d.]+) : ([\d.]+)""").find(board)
            ?: error("tileFace no longer draws the name as it did")
        near("lit name", name.groupValues[1].toDouble(), TileFace.NAME_LIT)
        near("dark name", name.groupValues[2].toDouble(), TileFace.NAME_UNLIT)
        val status = Regex("""lab\(c, tile\.status,[^;]*on \? ([\d.]+) : ([\d.]+)""").find(board)
            ?: error("tileFace no longer draws the status as it did")
        near("lit status", status.groupValues[1].toDouble(), TileFace.STATUS_LIT)
        near("dark status", status.groupValues[2].toDouble(), TileFace.STATUS_UNLIT)
        val mask = Regex("""c\.arc\(k\[1\], k\[2\], k\[3\] \+ ([\d.]+)""").find(board)
            ?: error("tileFace no longer masks a knob")
        near("knob mask", mask.groupValues[1].toDouble(), TileFace.KNOB_MASK)
        assertTrue(
            "tileFace should light its glyph blue at 1, an alarm in its own colour, and grey at offAlpha",
            board.contains("const col = alarm ? [alarm[0], alarm[0]] : on ? HUB : WHT, I = on ? 1 : IC.offAlpha;"),
        )
        assertTrue(
            "and lay an alarm's line over rather than add it",
            board.contains("beam(c, ic.strokes, IC.stroke, col, I, on ? IC.onGlow : 0, !!alarm);"),
        )
        assertTrue(
            "an alarm's status is its colour laid over, whole",
            board.contains("text(c, tile.status, x + T.textInset, y + T.statusBaseline, T.statusSize, col, 1, { font: ROBOTO, w: 400, over: true })"),
        )
        assertTrue("a chip centres its glyph's box", board.contains("x + (w - isz) / 2"))

        val lit = TileFace.of(DenzaTileTone.LIVE)
        assertEquals(LuminoforSpec.HeadInk.BLUE, lit.glyph)
        near("lit glyph", 1.0, lit.glyphIntensity)
        near("lit halo", num("head", "icon", "onGlow"), lit.glyphGlow)
        assertEquals(hex(str("colors", "head", "cardOn")), lit.plate)
        val dark = TileFace.of(DenzaTileTone.IDLE)
        assertEquals(LuminoforSpec.HeadInk.WHITE, dark.glyph)
        near("dark glyph", num("head", "icon", "offAlpha"), dark.glyphIntensity)
        near("dark halo", 0.0, dark.glyphGlow)
        assertEquals(hex(str("colors", "head", "cardOff")), dark.plate)
    }

    @Test
    fun theWordsAreAddedOntoThePlate() {
        // lighter: plate + a x white, channel by channel, with Chrome's eight-bit alpha - so each
        // expected colour here is worked from spec.json alone.
        val on = hex(str("colors", "head", "cardOn"))
        val off = hex(str("colors", "head", "cardOff"))
        val white = hex(list("colors", "head", "white")[1] as String)
        fun add(plate: Int, light: Int, a: Double): Int {
            val a8 = (a * 255).roundToInt()
            var out = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val sum = ((plate shr shift) and 0xFF) + (((light shr shift) and 0xFF) * a8 / 255.0).roundToInt()
                out = out or (minOf(255, sum) shl shift)
            }
            return out
        }
        val lit = TileFace.of(DenzaTileTone.LIVE)
        val dark = TileFace.of(DenzaTileTone.IDLE)
        assertEquals("a lit name is white", 0xFFFFFFFF.toInt(), lit.name)
        assertEquals("lit name", add(on, white, 1.0), lit.name)
        assertEquals("lit status", add(on, white, 0.62), lit.status)
        assertEquals("dark name", add(off, white, 0.7), dark.name)
        assertEquals("dark status", add(off, white, 0.4), dark.status)
        // What the boards' own Chrome put on the glass for the same four, read back off a canvas.
        assertEquals(0xFFCAC9D1.toInt(), lit.status)
        assertEquals(0xFFCAC9CE.toInt(), dark.name)
        assertEquals(0xFF7D7C81.toInt(), dark.status)
        // The two alarms (`main-first`) keep the lit plate and light the glyph and the status in
        // the car's own colour, whole: the line is the halo, laid over.
        val orange = hex(list("colors", "cluster", "orange")[0] as String)
        val red = hex(list("colors", "cluster", "red")[0] as String)
        for ((tone, light) in listOf(DenzaTileTone.ATTENTION to orange, DenzaTileTone.BROKEN to red)) {
            val face = TileFace.of(tone)
            assertEquals("$tone plate", on, face.plate)
            assertEquals("$tone glyph", light, face.glyph.core)
            assertTrue("$tone glyph is laid over", face.glyphOver)
            assertEquals("$tone name", 0xFFFFFFFF.toInt(), face.name)
            assertEquals("$tone glyph halo", if (tone == DenzaTileTone.ATTENTION) ClusterInk.ORANGE.halo else ClusterInk.RED.halo, face.glyph.halo)
            // a status that says something is wrong is the car's own colour for it, whole
            assertEquals("$tone status", face.glyph.halo, face.status)
        }
        assertEquals(0xFFFF9F19.toInt(), TileFace.of(DenzaTileTone.ATTENTION).status)
        assertEquals(0xFFFF4046.toInt(), TileFace.of(DenzaTileTone.BROKEN).status)
        assertEquals("working is lit", lit.plate, TileFace.of(DenzaTileTone.WORKING).plate)
        assertEquals("disabled reads as dark", dark.plate, TileFace.of(DenzaTileTone.shown(DenzaTileTone.LIVE, false)).plate)
    }

    @Test
    fun theGroundIsTheBoardsBlack() {
        assertEquals(hex(str("colors", "background")), DenzaColors.Ground.toArgb())
    }

    private fun box(key: String): List<Double> = list("head", key, "stripBox").map { (it as Number).toDouble() }

    /** drawHead's two-thirds chip for [n] features: the spec's gap kept, the row shared out. */
    private fun twoThirdsChip(n: Int): Double {
        val content = num("head", "two", "size", "0") - 2 * num("head", "two", "margin")
        val size = num("head", "two", "chips", "size")
        val perRow = num("head", "two", "chips", "perRow").toInt()
        val g = (content - perRow * size) / (perRow - 1)
        return (content - (n - 1) * g) / n
    }

    private fun hex(s: String): Int = (0xFF000000.toInt() or s.removePrefix("#").toInt(16))

    private val board: String by lazy { SpecJson.read("luminofor/luminofor.js") }

    private companion object {
        /** The features every board draws, and the dashboard's own count today. */
        const val FEATURES = 12
    }
}
