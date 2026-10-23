package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.design.luminofor.SpecJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [ScannerSpec] against `tools/design-canvas/luminofor/scanner.json`, value by value.
 *
 * `scanner.js` draws the `scanner-*` boards from the JSON and the screens in this package
 * lay themselves out from [ScannerSpec], so this is the join between them, as
 * `LuminoforSpecContractTest` is for the dashboard: a colour, a text style or a column moved on one
 * side fails here until the other side moves in the same change.
 */
class ScannerSpecContractTest {

    @Suppress("UNCHECKED_CAST")
    private val json: Map<String, Any?> by lazy { SpecJson.parse(SpecJson.read("luminofor/scanner.json")) as Map<String, Any?> }

    private fun at(vararg path: String): Any? {
        var v: Any? = json
        for (key in path) {
            v = when (v) {
                is Map<*, *> -> v[key]
                is List<*> -> v[key.toInt()]
                else -> error("${path.joinToString(".")}: not a container at $key")
            }
        }
        return v
    }

    private fun num(vararg path: String): Double = (at(*path) as? Number)?.toDouble() ?: error("${path.joinToString(".")} is not a number")

    private fun n(actual: Float, vararg path: String) = assertEquals(path.joinToString("."), num(*path), actual.toDouble(), 1e-4)

    private fun colour(s: String): Int = if (s.startsWith("#")) {
        (0xFF shl 24) or s.removePrefix("#").toInt(16)
    } else {
        val (r, g, b, a) = s.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim().toDouble() }
        ((a * 255).roundToInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun ink(name: String): Int = colour(at("colors", name) as String)

    /** A column list: dp, or `"Nfr"` as -N. */
    private fun cols(vararg path: String): List<Float> = (at(*path) as List<*>).map {
        when (it) {
            is Number -> it.toFloat()
            is String -> -it.removeSuffix("fr").toFloat()
            else -> error("${path.joinToString(".")}: $it")
        }
    }

    @Test
    fun size() {
        n(ScannerSpec.W, "size", "0")
        n(ScannerSpec.H, "size", "1")
    }

    @Test
    fun fonts() {
        val f = ScannerSpec.Fonts
        n(f.ROBOTO_ASCENT, "fonts", "roboto", "ascent")
        n(f.ROBOTO_DESCENT, "fonts", "roboto", "descent")
        n(f.MONO_ASCENT, "fonts", "mono", "ascent")
        n(f.MONO_DESCENT, "fonts", "mono", "descent")
        n(f.MONO_ADVANCE, "fonts", "mono", "advance")
        n(ScannerSpec.Fonts.Arrow.MONO_CELLS, "fonts", "arrow", "monoCells")
        n(ScannerSpec.Fonts.Arrow.ROBOTO, "fonts", "arrow", "roboto")
        n(ScannerSpec.Fonts.Arrow.Y, "fonts", "arrow", "y")
        n(ScannerSpec.Fonts.Arrow.HEAD, "fonts", "arrow", "head")
        n(ScannerSpec.Fonts.Arrow.SPREAD, "fonts", "arrow", "spread")
        n(ScannerSpec.Fonts.Arrow.STROKE, "fonts", "arrow", "stroke")
        assertEquals("Roboto", at("fonts", "roboto", "family"))
        assertEquals("Roboto Mono", at("fonts", "mono", "family"))
    }

    @Test
    fun everyColourBothWays() {
        val names = (at("colors") as Map<*, *>).keys.map { it as String }
        assertEquals("colours in scanner.json and ScannerSpec.Ink", names.toSet(), ScannerSpec.Ink.byName.keys)
        names.forEach { name ->
            assertEquals("colors.$name", Integer.toHexString(ink(name)), Integer.toHexString(ScannerSpec.Ink.byName.getValue(name)))
        }
    }

    @Test
    fun everyTextStyleBothWays() {
        @Suppress("UNCHECKED_CAST")
        val type = at("type") as Map<String, Map<String, Any?>>
        assertEquals("text styles in scanner.json and ScannerSpec.Type", type.keys, ScannerSpec.Type.byName.keys)
        type.forEach { (name, s) ->
            val style = ScannerSpec.Type.byName.getValue(name)
            assertEquals("type.$name.size", (s["size"] as Number).toFloat(), style.size, 1e-4f)
            assertEquals("type.$name.lh", (s["lh"] as Number).toFloat(), style.lh, 1e-4f)
            assertEquals("type.$name.w", (s["w"] as? Number)?.toInt() ?: 400, style.weight)
            assertEquals("type.$name.mono", s["mono"] == true, style.mono)
            assertEquals("type.$name.track", (s["track"] as? Number)?.toFloat() ?: 0f, style.track, 1e-4f)
        }
    }

    @Test
    fun baselineIsTheBoardsFormula() {
        // scanner.js base(): top + (lh - (ascent + descent) * size) / 2 + ascent * size
        val s = ScannerSpec.Type.CELL
        assertEquals((18 - (0.9277 + 0.2441) * 14.5) / 2 + 0.9277 * 14.5, s.baseline.toDouble(), 1e-4)
        val m = ScannerSpec.Type.CELL_MONO
        assertEquals((18 - (1.0479 + 0.271) * 13.5) / 2 + 1.0479 * 13.5, m.baseline.toDouble(), 1e-4)
        assertEquals(0.6001 * 13.5, m.arrowWidth.toDouble(), 1e-4)
        assertEquals(0.8 * 11 + 0.07 * 11, ScannerSpec.Type.COL_HEAD.arrowWidth.toDouble(), 1e-4)
    }

    @Test
    fun tones() {
        @Suppress("UNCHECKED_CAST")
        val tones = at("tones") as Map<String, Map<String, String>>
        assertEquals(tones.keys, ScannerSpec.Tones.byName.keys)
        tones.forEach { (name, t) ->
            val tone = ScannerSpec.Tones.byName.getValue(name)
            assertEquals("tones.$name.ink", ink(t.getValue("ink")), tone.ink)
            assertEquals("tones.$name.fill", t["fill"]?.let(::ink), tone.fill)
            assertEquals("tones.$name.line", t["line"]?.let(::ink), tone.line)
        }
    }

    @Test
    fun chrome() {
        val h = ScannerSpec.Header
        n(h.HEIGHT, "header", "height"); n(h.PAD_LEFT, "header", "padLeft"); n(h.PAD_RIGHT, "header", "padRight")
        n(h.GAP, "header", "gap"); n(h.CLOSE_W, "header", "close", "w"); n(h.CLOSE_H, "header", "close", "h")
        n(h.CLOSE_R, "header", "close", "r"); n(h.CLOSE_ICON, "header", "close", "icon")
        n(h.CLOSE_STROKE, "header", "close", "stroke"); n(h.DOT, "header", "dot"); n(h.DOT_GAP, "header", "dotGap")
        n(h.DOT_GLOW, "header", "dotGlow"); n(h.META_GAP, "header", "metaGap"); n(h.VOLTS_GAP, "header", "voltsGap")
        val b = ScannerSpec.Block
        n(b.TOP, "block", "top"); n(b.HEIGHT, "block", "height"); n(b.TITLE_GAP, "block", "titleGap")
        n(b.COUNTER_W, "block", "counterW"); n(b.COUNTER_STEP, "block", "counterStep"); n(b.COUNTER_PAD, "block", "counterPad")
        n(b.COUNTER_GAP, "block", "counterGap"); n(b.BADGE_GAP, "block", "badgeGap")
        val t = ScannerSpec.Tabs
        n(t.TOP, "tabs", "top"); n(t.HEIGHT, "tabs", "height"); n(t.GAP, "tabs", "gap"); n(t.UNDERLINE, "tabs", "underline")
        n(t.DOT_GAP, "tabs", "dotGap")
        val f = ScannerSpec.Footer
        n(f.TOP, "footer", "top"); n(f.HEIGHT, "footer", "height"); n(f.GAP, "footer", "gap")
        n(f.BUTTON_H, "footer", "button", "h"); n(f.BUTTON_PAD, "footer", "button", "pad"); n(f.BUTTON_R, "footer", "button", "r")
        n(f.DIVIDER_W, "footer", "divider", "0"); n(f.DIVIDER_H, "footer", "divider", "1")
        assertEquals(setOf("top", "height", "gap", "button", "divider"), (at("footer") as Map<*, *>).keys)
        val g = ScannerSpec.Badge
        n(g.H, "badge", "h"); n(g.PAD, "badge", "pad"); n(g.R, "badge", "r")
        n(g.SMALL_H, "badge", "small", "h"); n(g.SMALL_PAD, "badge", "small", "pad")
        n(g.BIG_H, "badge", "big", "h"); n(g.BIG_PAD, "badge", "big", "pad")
    }

    @Test
    fun theBlocksTableAndTheTile() {
        val m = ScannerSpec.Modules
        val k = "modules"
        assertEquals(setOf("cols", "numPad", "groupPad", "top", "head", "list"), (at(k) as Map<*, *>).keys)
        assertEquals(cols(k, "cols"), m.COLS)
        n(m.NUM_PAD, k, "numPad"); n(m.GROUP_PAD, k, "groupPad"); n(m.TOP, k, "top"); n(m.HEAD, k, "head")
        n(m.LIST_TOP, k, "list", "top"); n(m.LIST_BOTTOM, k, "list", "bottom"); n(m.GROUP, k, "list", "group")
        n(m.ROW, k, "list", "row"); n(m.RULE, k, "list", "rule")
        // the table fills the width the rail leaves, less its margins
        assertEquals(ScannerSpec.W - ScannerSpec.Rail.W - 48f, m.COLS.sum(), 1e-4f)
        val t = ScannerSpec.Tile
        assertEquals(setOf("w", "h", "r", "padX", "pitch", "rowGap"), (at("tile") as Map<*, *>).keys)
        n(t.W, "tile", "w"); n(t.H, "tile", "h"); n(t.R, "tile", "r"); n(t.PAD_X, "tile", "padX"); n(t.PITCH, "tile", "pitch")
        n(t.ROW_GAP, "tile", "rowGap")
        // the JSON's keys of the autoscan and its topology are gone with them (7.3)
        listOf("scan", "topology", "packScheme", "rangeBar").forEach { assertEquals(it, null, at(it)) }
    }

    @Test
    fun theSignsTheStripAndTheSwitch() {
        val g = ScannerSpec.Sign
        n(g.R, "sign", "r"); n(g.STROKE, "sign", "stroke"); n(g.SQUARE, "sign", "square")
        assertEquals(cols("sign", "dash"), g.DASH.toList())
        n(ScannerSpec.CHEVRON, "chevron")
        val v = ScannerSpec.Segmented
        n(v.H, "segmented", "h"); n(v.PAD, "segmented", "pad"); n(v.R, "segmented", "r"); n(v.GAP, "segmented", "gap")
        val p = ScannerSpec.Pick
        assertEquals(setOf("col", "inset", "size", "r", "stroke", "legendGap", "close"), (at("pick") as Map<*, *>).keys)
        n(p.COL, "pick", "col"); n(p.INSET, "pick", "inset"); n(p.SIZE, "pick", "size"); n(p.R, "pick", "r"); n(p.STROKE, "pick", "stroke")
        n(p.LEGEND_GAP, "pick", "legendGap"); n(p.CLOSE_SIZE, "pick", "close", "size"); n(p.CLOSE_STROKE, "pick", "close", "stroke")
        n(p.CLOSE_GAP, "pick", "close", "gap")
        // the square stands inside its column, and its number inside the square
        assertTrue(p.INSET + p.SIZE <= p.COL)
        assertTrue(ScannerSpec.Type.PICK_NUM.lh <= p.SIZE)
        val b = ScannerSpec.BitStrip
        n(b.CELL, "bitStrip", "cell"); n(b.H, "bitStrip", "h"); n(b.GAP, "bitStrip", "gap"); n(b.R, "bitStrip", "r")
        n(b.CELL_Y, "bitStrip", "cellY"); n(b.NAME_Y, "bitStrip", "nameY"); n(b.NAME_OUT, "bitStrip", "nameOut")
        // the switch fits in the tabs' row
        assertTrue(v.H <= ScannerSpec.Tabs.HEIGHT)
    }

    @Test
    fun theSummary() {
        val s = ScannerSpec.Summary
        val k = "summary"
        n(s.BAND_TOP, k, "band", "top"); n(s.BAND_H, k, "band", "h"); n(s.BAND_GAP, k, "band", "gap"); n(s.BAND_ITEM_GAP, k, "band", "itemGap")
        n(s.TOP, k, "top"); n(s.COL_W, k, "colW"); n(s.COL_GAP, k, "colGap"); n(s.LABEL, k, "label"); n(s.HEAD, k, "head")
        n(s.HEAD_GAP, k, "headGap"); n(s.ROW, k, "row"); n(s.ROW_PAD, k, "rowPad"); assertEquals(cols(k, "rowCols"), s.ROW_COLS)
        n(s.ROW_GAP, k, "rowGap"); n(s.BLOCK_GAP, k, "blockGap"); n(s.SYSTEM_GAP, k, "systemGap")
        n(s.QUIET_GAP, k, "quiet", "gap"); n(s.QUIET_H, k, "quiet", "h"); n(s.QUIET_CHEVRON_GAP, k, "quiet", "chevronGap")
        n(s.TAG_H, k, "tag", "h"); n(s.TAG_PAD, k, "tag", "pad"); n(s.TAG_R, k, "tag", "r")
        // two columns in the width the rail leaves
        assertEquals(ScannerSpec.W - ScannerSpec.Rail.W - 48f, 2 * s.COL_W + s.COL_GAP, 1e-4f)
        assertTrue(s.BAND_TOP >= ScannerSpec.Block.TOP + ScannerSpec.Block.HEIGHT && s.BAND_TOP + s.BAND_H < s.TOP)
    }

    @Test
    fun theCodesScreen() {
        val c = ScannerSpec.Code
        val k = "code"
        n(c.RULE, k, "rule"); assertEquals(cols(k, "x"), c.X); assertEquals(cols(k, "w"), c.W)
        n(c.LABEL, k, "label"); n(c.MEANING, k, "meaning"); n(c.MEANING_NOTE, k, "meaningNote"); n(c.STATUS_LABEL, k, "statusLabel")
        n(c.STRIP, k, "strip"); n(c.WORDS, k, "words"); n(c.WORDS_NOTE, k, "wordsNote"); n(c.READ_LABEL, k, "readLabel"); n(c.READS, k, "reads")
        n(c.PICK_X, k, "pick", "0"); n(c.PICK_Y, k, "pick", "1"); n(c.PICK_R, k, "pick", "2")
        n(c.CARD_TOP, k, "card", "top"); n(c.CARD_H, k, "card", "h"); n(c.CARD_PAD_X, k, "card", "padX"); n(c.CARD_PAD_Y, k, "card", "padY")
        n(c.CARD_R, k, "card", "r"); n(c.CARD_GAP, k, "card", "gap")
        n(c.SAME_LABEL, k, "sameLabel"); n(c.SAME, k, "same"); n(c.SAME_ROW, k, "sameRow"); assertEquals(cols(k, "sameCols"), c.SAME_COLS)
        n(c.SAME_GAP, k, "sameGap"); n(c.SAME_NOTE_GAP, k, "sameNoteGap")
        n(c.TIME_TOP, k, "timeline", "top"); n(c.TIME_DOT, k, "timeline", "dot"); n(c.TIME_BAR, k, "timeline", "bar")
        n(c.TIME_BAR_ALPHA, k, "timeline", "barAlpha"); n(c.TIME_AXIS, k, "timeline", "axis"); n(c.TIME_TICK, k, "timeline", "tick")
        n(c.TIME_LABEL_GAP, k, "timeline", "labelGap"); n(c.TIME_CAPTION, k, "timeline", "caption")
        n(c.SNAP_LABEL, k, "snapLabel"); n(c.SNAP, k, "snap"); n(c.SNAP_ROW, k, "snapRow"); n(c.SNAP_KEY, k, "snapKey")
        n(c.COUNT_GAP, k, "countGap"); n(c.COUNT_ROW, k, "countRow")
        // the strip of eight cells fits the first column
        val b = ScannerSpec.BitStrip
        assertTrue(8 * b.CELL + 7 * b.GAP <= c.W[0])
        assertEquals(ScannerSpec.W - 24f, c.X.last() + c.W.last(), 1e-4f)
    }

    @Test
    fun theHealthGraphsJournalAndReport() {
        val h = ScannerSpec.Health
        n(h.TOP, "health", "table", "top"); n(h.W, "health", "table", "w"); n(h.HEAD, "health", "table", "head"); n(h.ROW, "health", "table", "row")
        assertEquals(cols("health", "table", "cols"), h.COLS); n(h.VALUE_PAD, "health", "table", "valuePad")
        n(h.SUB_GAP, "health", "table", "subGap"); n(h.UNIT_GAP, "health", "table", "unitGap")
        n(h.SPARK_W, "health", "spark", "w"); n(h.SPARK_H, "health", "spark", "h"); n(h.SPARK_STROKE, "health", "spark", "stroke"); n(h.SPARK_DOT, "health", "spark", "dot")
        n(h.CELLS_X, "health", "cells", "x"); n(h.CELLS_LABEL_Y, "health", "cells", "labelY"); n(h.CELLS_TOP, "health", "cells", "top")
        n(h.CELLS_W, "health", "cells", "w"); n(h.CELLS_H, "health", "cells", "h"); n(h.CELLS_MV, "health", "cells", "mv")
        assertEquals(cols("health", "cells", "grid").map { it.toInt() }, h.CELLS_GRID)
        n(h.CELLS_SCALE_W, "health", "cells", "scaleW"); n(h.CELLS_GAP, "health", "cells", "gap"); n(h.CELLS_STATS_Y, "health", "cells", "statsY")
        n(h.PROBES_LABEL_Y, "health", "probes", "labelY"); n(h.PROBES_TOP, "health", "probes", "top"); n(h.PROBES_COLS.toFloat(), "health", "probes", "cols")
        n(h.PROBE_W, "health", "probes", "w"); n(h.PROBE_H, "health", "probes", "h"); n(h.PROBE_STEP_X, "health", "probes", "stepX")
        n(h.PROBE_STEP_Y, "health", "probes", "stepY"); n(h.PROBE_PAD_X, "health", "probes", "padX"); n(h.PROBE_PAD_Y, "health", "probes", "padY")
        n(h.PROBE_R, "health", "probes", "r")
        val g = ScannerSpec.Graphs
        n(g.LEGEND_W, "graphs", "legendW"); n(g.PLOT_X, "graphs", "plotX"); assertEquals(cols("graphs", "lanes"), g.LANES); n(g.LANE, "graphs", "lane")
        n(g.NAME_GAP, "graphs", "nameGap"); n(g.VALUE_GAP, "graphs", "valueGap"); n(g.CURSOR_TOP, "graphs", "cursor", "top")
        n(g.CURSOR_BOTTOM, "graphs", "cursor", "bottom"); n(g.CURSOR_DOT, "graphs", "cursor", "dot"); n(g.AXIS_Y, "graphs", "axisY")
        n(g.TAG_Y, "graphs", "tag", "y"); n(g.TAG_W, "graphs", "tag", "w"); n(g.TAG_H, "graphs", "tag", "h"); n(g.TAG_R, "graphs", "tag", "r")
        n(g.SCALE_INSET, "graphs", "scaleInset")
        // the legend's square, name and «×» end before the plot
        assertTrue(24f + g.LEGEND_W < g.PLOT_X)
        // the four lanes under the tabs, over the axis
        assertTrue(g.LANES.first() >= ScannerSpec.Tabs.TOP + ScannerSpec.Tabs.HEIGHT && g.LANES.last() + g.LANE <= g.AXIS_Y)
        val j = ScannerSpec.Journal
        val k = "journal"
        n(j.PLOT_X, k, "plotX"); n(j.PLOT_W, k, "plotW"); n(j.AXIS_Y, k, "axisY"); n(j.GRID_TOP, k, "gridTop"); n(j.GRID_H, k, "gridH")
        n(j.LANE_W, k, "laneW"); assertEquals(cols(k, "lanes"), j.LANES); n(j.LANE_H, k, "laneH"); assertEquals(cols(k, "rules"), j.RULES)
        n(j.CODES_LABEL, k, "codesLabel"); n(j.CODES_TOP, k, "codesTop"); n(j.CODE_STEP, k, "codeStep"); n(j.CODE_H, k, "codeH")
        n(j.VALUES_LABEL, k, "valuesLabel"); assertEquals(cols(k, "values"), j.VALUES); n(j.VALUE_H, k, "valueH"); n(j.DOT, k, "dot")
        n(j.POINT, k, "point"); n(j.CELL_LABEL, k, "cellLabel")
        n(j.PANEL_X, k, "panel", "x"); n(j.PANEL_TOP, k, "panel", "top"); n(j.PANEL_H, k, "panel", "h"); n(j.PANEL_PAD_X, k, "panel", "padX")
        n(j.PANEL_PAD_Y, k, "panel", "padY"); n(j.PANEL_R, k, "panel", "r"); n(j.PANEL_ROW, k, "panel", "row"); n(j.PANEL_HEAD, k, "panel", "head")
        n(j.PANEL_GAP, k, "panel", "gap")
        assertEquals(setOf("top", "head", "cols", "gap"), (at(k, "compare") as Map<*, *>).keys)
        n(j.COMPARE_TOP, k, "compare", "top"); n(j.COMPARE_HEAD, k, "compare", "head"); n(j.COMPARE_COLS.toFloat(), k, "compare", "cols")
        n(j.COMPARE_GAP, k, "compare", "gap")
        assertEquals(setOf("cols", "top", "head", "list"), (at(k, "codes") as Map<*, *>).keys)
        assertEquals(cols(k, "codes", "cols"), j.TABLE_COLS); n(j.TABLE_TOP, k, "codes", "top"); n(j.TABLE_HEAD, k, "codes", "head")
        n(j.TABLE_LIST_TOP, k, "codes", "list", "top"); n(j.TABLE_LIST_BOTTOM, k, "codes", "list", "bottom")
        n(j.TABLE_ROW, k, "codes", "list", "row"); n(j.TABLE_RULE, k, "codes", "list", "rule")
        // the board journal's nineteen codes on the page without a scroll: the gone one last in sight
        assertTrue(j.TABLE_LIST_TOP + 19 * (j.TABLE_ROW + j.TABLE_RULE) <= j.TABLE_LIST_BOTTOM)
        // the chronology's seven lanes above the rule under them
        assertTrue(j.CODES_TOP + 6 * j.CODE_STEP + j.CODE_H <= j.RULES[1])
        // a full charge's label over its dot clears the lane above
        assertTrue(j.LANES[2] + j.LANE_H / 2f - j.DOT / 2f - j.CELL_LABEL - ScannerSpec.Type.TICK.lh >= j.LANES[1] + j.LANE_H - 4f)
        assertTrue(j.PLOT_X + j.PLOT_W < j.PANEL_X)
        val r = ScannerSpec.Report
        n(r.PAPER_X, "report", "paper", "x"); n(r.PAPER_Y, "report", "paper", "y"); n(r.PAPER_W, "report", "paper", "w"); n(r.PAPER_H, "report", "paper", "h")
        n(r.PAPER_R, "report", "paper", "r"); n(r.X, "report", "x"); n(r.TITLE_Y, "report", "titleY"); n(r.LINE_Y, "report", "lineY")
        n(r.LABEL_Y, "report", "labelY"); n(r.LIST_TOP, "report", "listTop"); n(r.ROW, "report", "row"); n(r.BOX, "report", "box")
        n(r.BOX_R, "report", "boxR"); n(r.BOX_GAP, "report", "boxGap"); n(r.DEST_LABEL, "report", "destLabel"); n(r.DEST, "report", "dest")
        n(r.DEST_NOTE, "report", "destNote"); n(r.DEST_SIZE, "report", "destSize")
        // the page is A4: the painter's sheet scaled into it to the point
        assertEquals(842f / 595f, r.PAPER_H / r.PAPER_W, 2e-3f)
    }

    @Test
    fun blockScreens() {
        val d = ScannerSpec.EcuDtc
        n(d.LEFT_X, "ecuDtc", "left", "x"); n(d.LEFT_W, "ecuDtc", "left", "w")
        n(d.RIGHT_X, "ecuDtc", "right", "x"); n(d.RIGHT_W, "ecuDtc", "right", "w")
        n(d.TOP, "ecuDtc", "top"); n(d.HEAD, "ecuDtc", "head"); n(d.LIST_ROW, "ecuDtc", "listRow"); n(d.MON_ROW, "ecuDtc", "monRow")
        assertEquals(cols("ecuDtc", "listCols"), d.LIST_COLS)
        assertEquals(cols("ecuDtc", "monCols"), d.MON_COLS)
        n(d.PLATE_GAP, "ecuDtc", "plateGap"); n(d.PLATE_PAD_X, "ecuDtc", "plate", "padX"); n(d.PLATE_PAD_Y, "ecuDtc", "plate", "padY")
        n(d.PLATE_GAP_IN, "ecuDtc", "plate", "gap"); n(d.PLATE_R, "ecuDtc", "plate", "r")

        val f = ScannerSpec.EcuFault
        assertEquals(cols("ecuFault", "cols"), f.COLS)
        n(f.ROW_MIN, "ecuFault", "rowMin"); n(f.ROW_PAD, "ecuFault", "rowPad"); n(f.LIST_GAP, "ecuFault", "listGap")
        n(f.LIST_PAD_Y, "ecuFault", "listPad", "0"); n(f.LIST_PAD_X, "ecuFault", "listPad", "1"); n(f.LIST_GAP_IN, "ecuFault", "listGapIn")

        val l = ScannerSpec.EcuLive
        n(l.TOP, "ecuLive", "top"); n(l.HEAD, "ecuLive", "head"); n(l.ROW, "ecuLive", "row")
        assertEquals(cols("ecuLive", "cols"), l.COLS)
        n(l.SPARK_W, "ecuLive", "spark", "w"); n(l.SPARK_H, "ecuLive", "spark", "h")
        n(l.SPARK_STROKE, "ecuLive", "spark", "stroke"); n(l.SPARK_GLOW, "ecuLive", "spark", "glow")

        val i = ScannerSpec.EcuIdent
        n(i.LEFT_X, "ecuIdent", "left", "x"); n(i.LEFT_W, "ecuIdent", "left", "w")
        n(i.RIGHT_X, "ecuIdent", "right", "x"); n(i.RIGHT_W, "ecuIdent", "right", "w")
        assertEquals(cols("ecuIdent", "ownCols"), i.OWN_COLS)
        assertEquals(cols("ecuIdent", "cacheCols"), i.CACHE_COLS)
        n(i.ROW, "ecuIdent", "row"); n(i.NOTE_GAP, "ecuIdent", "noteGap"); n(i.PID_GAP, "ecuIdent", "pidGap")
        n(i.PID_HEAD_H, "ecuIdent", "pidHeadH"); n(i.PID_GRID_GAP, "ecuIdent", "pidGridGap")
        assertEquals(num("ecuIdent", "pid", "cols").toInt(), i.PID_COLS)
        n(i.PID_H, "ecuIdent", "pid", "h"); n(i.PID_CELL_GAP, "ecuIdent", "pid", "gap"); n(i.PID_R, "ecuIdent", "pid", "r")

        val t = ScannerSpec.EcuTests
        val k = "ecuTests"
        assertEquals(setOf("line", "top", "x", "w", "title", "titleY", "row", "raw", "rawY", "rowCols", "valuePad", "limitPad", "scale"), (at(k) as Map<*, *>).keys)
        assertEquals(setOf("inset", "tick", "mark", "out", "outGap"), (at(k, "scale") as Map<*, *>).keys)
        n(t.LINE, k, "line"); n(t.TOP, k, "top"); assertEquals(cols(k, "x"), t.X); n(t.W, k, "w")
        n(t.TITLE, k, "title"); n(t.TITLE_Y, k, "titleY"); n(t.ROW, k, "row"); n(t.RAW, k, "raw"); n(t.RAW_Y, k, "rawY")
        assertEquals(cols(k, "rowCols"), t.ROW_COLS); n(t.VALUE_PAD, k, "valuePad"); n(t.LIMIT_PAD, k, "limitPad")
        n(t.SCALE_INSET, k, "scale", "inset"); n(t.SCALE_TICK, k, "scale", "tick")
        n(t.MARK_W, k, "scale", "mark", "0"); n(t.MARK_H, k, "scale", "mark", "1")
        n(t.OUT_W, k, "scale", "out", "0"); n(t.OUT_H, k, "scale", "out", "1"); n(t.OUT_GAP, k, "scale", "outGap")
        // a title, a line a test and an exchange's line are of one height: the columns balance by lines
        assertEquals(t.ROW, t.TITLE, 0f); assertEquals(t.ROW, t.RAW, 0f)
        // the second column ends where the first one's room does, 24 from the window's edge
        assertEquals(ScannerSpec.W - 24f, t.X[1] + t.W, 0f)
        // the boards' 21 lines of the taller column stand above the footer's content bottom
        assertTrue(t.TOP + 21 * t.ROW <= 620f)

        val b = ScannerSpec.BodyDtc
        n(b.TOP, "bodyDtc", "top"); n(b.HEAD, "bodyDtc", "head")
        assertEquals(setOf("top", "head", "table", "rowMin", "below", "belowGap", "explainGap", "readX"), (at("bodyDtc") as Map<*, *>).keys)
        assertEquals(cols("bodyDtc", "table"), b.TABLE)
        n(b.ROW_MIN, "bodyDtc", "rowMin"); n(b.BELOW, "bodyDtc", "below"); n(b.BELOW_GAP, "bodyDtc", "belowGap")
        n(b.EXPLAIN_GAP, "bodyDtc", "explainGap"); n(b.READ_X, "bodyDtc", "readX")
    }

    @Test
    fun systemScreens() {
        val d = ScannerSpec.Drives
        n(d.TOP, "drives", "top"); n(d.HEAD, "drives", "head"); n(d.ROW, "drives", "row"); n(d.PAD, "drives", "pad")
        assertEquals(cols("drives", "cols"), d.COLS)

        val a = ScannerSpec.Adas
        n(a.TOP, "adas", "top"); n(a.HEAD, "adas", "head"); n(a.ROW, "adas", "row"); n(a.PAD, "adas", "pad")
        assertEquals(cols("adas", "cols"), a.COLS)

        val b = ScannerSpec.Brakes
        n(b.TOP, "brakes", "top"); n(b.HEAD, "brakes", "head"); n(b.ASK_ROW, "brakes", "askRow")
        assertEquals(cols("brakes", "askCols"), b.ASK_COLS)
        n(b.PLATE_GAP, "brakes", "plateGap"); n(b.PLATE_PAD_X, "brakes", "plate", "padX"); n(b.PLATE_PAD_Y, "brakes", "plate", "padY")
        n(b.PLATE_GAP_IN, "brakes", "plate", "gap"); n(b.PLATE_R, "brakes", "plate", "r")
        assertEquals(setOf("top", "head", "askRow", "askCols", "plateGap", "plate"), (at("brakes") as Map<*, *>).keys)
    }

    private fun pair(vararg path: String): Pair<Float, Float> = (at(*path) as List<*>).let { (it[0] as Number).toFloat() to (it[1] as Number).toFloat() }

    @Test
    fun hvSchematic() {
        val v = ScannerSpec.Hv
        val h = "hvRail"
        n(v.PACK_X, h, "pack", "x"); n(v.PACK_Y, h, "pack", "y"); n(v.PACK_W, h, "pack", "w"); n(v.PACK_H, h, "pack", "h")
        n(v.PACK_R, h, "pack", "r"); n(v.PACK_PAD_X, h, "pack", "padX"); n(v.PACK_PAD_Y, h, "pack", "padY")
        n(v.PACK_GAP, h, "pack", "gap")
        n(v.UNIT_W, h, "unit", "w"); n(v.UNIT_H, h, "unit", "h"); n(v.UNIT_R, h, "unit", "r")
        n(v.UNIT_PAD_X, h, "unit", "padX"); n(v.UNIT_ROW_GAP, h, "unit", "rowGap")
        assertEquals(at(h, "contactor", "wire"), v.CONTACTOR_WIRE)
        assertEquals(at(h, "contactor", "blade"), v.CONTACTOR_BLADE)
        assertEquals(at(h, "contactor", "closed"), v.CONTACTOR_CLOSED)
        assertEquals((at(h, "contactor", "pins") as List<*>).indices.map { pair(h, "contactor", "pins", "$it") }, v.CONTACTOR_PINS)
        n(v.CONTACTOR_PIN, h, "contactor", "pin")
        n(v.CONTACTOR_LABEL.first, h, "contactor", "label", "0")
        n(v.CONTACTOR_LABEL.second, h, "contactor", "label", "1")
        n(v.CONTACTOR_LABEL.third, h, "contactor", "label", "2")
        assertEquals(at(h, "bus", "trunk"), v.TRUNK)
        assertEquals(at(h, "bus", "taps"), v.TAPS)
        assertEquals(at(h, "bus", "shafts"), v.SHAFTS)
        assertEquals(at(h, "bus", "coupling"), v.COUPLING)
        assertEquals(at(h, "bus", "feeds"), v.FEEDS)
        val machines = (at(h, "machines") as List<*>).map {
            val m = it as Map<*, *>
            ScannerSpec.Hv.Machine((m["x"] as Number).toFloat(), (m["y"] as Number).toFloat(), m["glyph"] as String, m["live"] == true)
        }
        assertEquals(machines, v.MACHINES)
        n(v.MACHINE_R, h, "machineR")
        n(v.BUS_LABEL_RIGHT, h, "busLabel", "right"); n(v.BUS_LABEL_Y, h, "busLabel", "y")
        assertEquals(null, at(h, "volts"))
        val units = (at(h, "units") as Map<*, *>).entries.associate { (k, p) ->
            val a = p as List<*>
            k as String to ScannerSpec.Hv.Place((a[0] as Number).toFloat(), (a[1] as Number).toFloat(), (a.getOrNull(2) as? Number)?.toFloat())
        }
        assertEquals(units, v.UNITS)
    }

    @Test
    fun theRailAndTheWidthItLeaves() {
        val r = ScannerSpec.Rail
        n(r.W, "rail", "w"); n(r.PAD_X, "rail", "padX"); n(r.PAD_TOP, "rail", "padTop"); n(r.ITEM, "rail", "item")
        n(r.SEP_GAP, "rail", "sepGap"); n(r.BAR, "rail", "bar"); n(r.MARK_GAP, "rail", "mark", "gap")
        assertEquals(cols("rail", "liveCols"), r.LIVE_COLS)
        assertEquals(cols("rail", "packLiveCols"), r.PACK_LIVE_COLS)
        assertEquals(cols("rail", "dtc", "cols"), r.DTC_COLS)
        n(r.DTC_ROW, "rail", "dtc", "row")
        n(r.ECU_LEFT_X, "rail", "ecuDtc", "left", "x"); n(r.ECU_LEFT_W, "rail", "ecuDtc", "left", "w")
        n(r.ECU_RIGHT_X, "rail", "ecuDtc", "right", "x"); n(r.ECU_RIGHT_W, "rail", "ecuDtc", "right", "w")
        assertEquals(cols("rail", "ecuDtc", "listCols"), r.ECU_LIST_COLS)
        assertEquals(cols("rail", "ecuDtc", "monCols"), r.ECU_MON_COLS)
        n(r.PENDING_X, "rail", "pending", "x"); n(r.PENDING_Y, "rail", "pending", "y")
        n(ScannerSpec.Content.TOP, "content", "top"); n(ScannerSpec.Content.BOTTOM, "content", "bottom")
    }

    @Test
    fun thePacksCells() {
        val p = ScannerSpec.PackCells
        val c = arrayOf("packCells", "cells")
        val t = arrayOf("packCells", "probes")
        n(p.CHART_X, *c, "x"); n(p.CHART_TOP, *c, "top"); n(p.CHART_BOTTOM, *c, "bottom"); n(p.CELLS_HEAD_Y, *c, "headY")
        n(p.BAR_GAP, *c, "gap"); n(p.SWEEP, *c, "sweep"); n(p.SWEEP_DOT, *c, "sweepDot")
        n(p.PROBES_HEAD_Y, *t, "headY"); n(p.PROBES_TOP, *t, "top"); n(p.PROBE_H, *t, "h"); n(p.PROBE_GAP, *t, "gap")
        n(p.PROBE_PAD_X, *t, "padX"); n(p.PROBE_PAD_Y, *t, "padY"); n(p.PROBE_VALUE_Y, *t, "valueY"); n(p.PROBE_UNIT_Y, *t, "unitY")
        // the chart under the tabs, its axis over the probes' head, the tiles over the footer
        val type = ScannerSpec.Type
        assertTrue(p.CELLS_HEAD_Y >= ScannerSpec.Tabs.TOP + ScannerSpec.Tabs.HEIGHT)
        assertTrue(p.CHART_BOTTOM + p.TICK_GAP + type.SIG.lh <= p.PROBES_HEAD_Y)
        // the sweep's dot under the chart's foot clears the axis numbers
        assertTrue(p.CHART_BOTTOM + 1f + p.SWEEP / 2f + p.SWEEP_DOT <= p.CHART_BOTTOM + p.TICK_GAP)
        assertTrue(p.PROBES_TOP + p.PROBE_H <= ScannerSpec.Footer.TOP)
    }

    @Test
    fun theGbFlags() {
        val g = ScannerSpec.GbFlags
        val k = "gbFlags"
        // every number of the JSON's gbFlags is held here, and nothing else is in it
        assertEquals(setOf("headY", "cols", "gap", "groupY", "rowY", "tile"), (at(k) as Map<*, *>).keys)
        assertEquals(setOf("h", "r", "padX", "padY", "nameY", "stateY"), (at(k, "tile") as Map<*, *>).keys)
        n(g.HEAD_Y, k, "headY"); n(g.COLS.toFloat(), k, "cols"); n(g.GAP, k, "gap")
        assertEquals(cols(k, "groupY"), g.GROUP_Y)
        assertEquals(cols(k, "rowY"), g.ROW_Y)
        n(g.TILE_H, k, "tile", "h"); n(g.TILE_R, k, "tile", "r"); n(g.PAD_X, k, "tile", "padX"); n(g.PAD_Y, k, "tile", "padY")
        n(g.NAME_Y, k, "tile", "nameY"); n(g.STATE_Y, k, "tile", "stateY")
        // the head under the tabs; each caption over its rows, the rows apart, the last over the footer
        val type = ScannerSpec.Type
        val label = type.COUNTER_LABEL.lh
        assertTrue(g.HEAD_Y >= ScannerSpec.Tabs.TOP + ScannerSpec.Tabs.HEIGHT)
        assertTrue(g.HEAD_Y + label <= g.GROUP_Y[0] && g.GROUP_Y[0] + label <= g.ROW_Y[0])
        assertTrue(g.ROW_Y[0] + g.TILE_H <= g.ROW_Y[1] && g.ROW_Y[1] + g.TILE_H <= g.GROUP_Y[1])
        assertTrue(g.GROUP_Y[1] + label <= g.ROW_Y[g.LATER_ROW] && g.LATER_ROW == g.ROW_Y.lastIndex)
        assertTrue(g.ROW_Y.last() + g.TILE_H <= ScannerSpec.Footer.TOP)
        // inside a tile: the name under the bit, the word and the code over its foot
        assertTrue(g.PAD_Y + label <= g.NAME_Y + g.PAD_Y - g.NAME_LIFT)
        assertTrue(g.NAME_Y + g.PAD_Y - g.NAME_LIFT + type.CELL_SMALL.lh <= g.STATE_Y)
        assertTrue(g.STATE_Y + type.CELL.lh <= g.TILE_H && g.STATE_Y + g.CODE_DROP + type.TILE_ADDR.lh <= g.TILE_H)
        // the model's groups fit the rows: the pack's nine on two, the drive's five on one
        val flags = dev.denza.apps.feature.diagnostics.scan.GbFlags
        assertTrue(flags.PACK.size <= 2 * g.COLS && flags.PACK.size > g.COLS && flags.DRIVE.size <= g.COLS)
        assertEquals(2, g.GROUP_Y.size)
    }

    @Test
    fun theParameterPage() {
        val p = ScannerSpec.Param
        n(p.CHART_X, "param", "chart", "x"); n(p.CHART_TOP, "param", "chart", "top"); n(p.CHART_BOTTOM, "param", "chart", "bottom")
        n(p.LABEL_Y, "param", "hero", "labelY"); n(p.VALUE_Y, "param", "hero", "valueY"); n(p.STATS_Y, "param", "hero", "statsY")
        n(p.STATS_W, "param", "hero", "statsW"); n(p.STATS_STEP, "param", "hero", "statsStep")
        // the reading, the side rows and the chart's top stay inside the area under the tabs, over the footer
        val type = ScannerSpec.Type
        assertTrue(p.VALUE_Y >= p.LABEL_Y + type.COUNTER_LABEL.lh)
        assertTrue(p.STATS_Y >= p.VALUE_Y + type.PARAM_VALUE.lh + p.UNIT_GAP + type.PARAM_UNIT.lh)
        assertTrue(p.CHART_TOP >= ScannerSpec.Tabs.TOP + ScannerSpec.Tabs.HEIGHT)
        assertTrue(p.CHART_BOTTOM + p.TICK_LABEL_GAP + type.SIG.lh <= ScannerSpec.Footer.TOP)
        assertEquals(listOf(10, 60, 300), p.WINDOWS)
    }

    @Test
    fun theCarAndItsCallouts() {
        val c = ScannerSpec.Car
        n(c.TOP, "car", "top"); n(c.H, "car", "h"); n(c.W, "car", "w"); n(c.NOSE_R, "car", "noseR"); n(c.TAIL_R, "car", "tailR")
        n(c.AXLE, "car", "axle"); n(c.WHEEL_W, "car", "wheel", "0"); n(c.WHEEL_H, "car", "wheel", "1")
        n(c.MIRROR_W, "car", "mirror", "0"); n(c.MIRROR_H, "car", "mirror", "1")
        n(c.SHIELD, "car", "glass", "shield"); n(c.REAR, "car", "glass", "rear"); n(c.GLASS_INSET, "car", "glass", "inset")
        n(c.ROOF_INSET, "car", "glass", "roofInset")
        assertEquals(pair("car", "doors", "front"), c.DOORS_FRONT)
        assertEquals(pair("car", "doors", "rear"), c.DOORS_REAR)
        assertEquals(pair("car", "anchors"), c.ANCHORS)
        val b = ScannerSpec.BodyScheme
        n(b.X, "bodyScheme", "x"); n(b.RADAR_FRONT, "bodyScheme", "radarFront"); n(b.RADAR_REAR, "bodyScheme", "radarRear")
        n(b.RADAR_REAR_AT, "bodyScheme", "radarRearAt"); n(b.DOMAIN_TOP, "bodyScheme", "domainTop"); n(b.TAIL_Y, "bodyScheme", "tailY")
        n(ScannerSpec.ChassisScheme.STEER_DROP, "chassisScheme", "steerDrop"); n(ScannerSpec.ChassisScheme.STEER_AT, "chassisScheme", "steerAt")
        n(ScannerSpec.SafetyScheme.GAP, "safetyScheme", "gap")
    }

    /** The readings the body's, the chassis' and the safety's schemes draw (architecture 7.2): their numbers, and nothing else in the JSON. */
    @Test
    fun theSchemesReadings() {
        val b = ScannerSpec.BodyScheme
        val k = "bodyScheme"
        assertEquals(setOf("x", "radarFront", "radarRear", "radarRearAt", "domainTop", "tailY", "lamps", "hood", "words", "lights", "tail"), (at(k) as Map<*, *>).keys)
        assertEquals(pair(k, "lamps", "head"), b.LAMP_HEAD)
        assertEquals(pair(k, "lamps", "reverse"), b.LAMP_REVERSE)
        assertEquals(pair(k, "lamps", "stop"), b.LAMP_STOP)
        n(b.LAMP_W, k, "lamps", "w"); n(b.LAMP_HALO, k, "lamps", "halo")
        n(b.TURN_GAP, k, "lamps", "turn", "gap"); n(b.TURN_LEN, k, "lamps", "turn", "len"); n(b.TURN_Y, k, "lamps", "turn", "y")
        n(b.TURN_HALF, k, "lamps", "turn", "half"); n(b.TURN_HALO, k, "lamps", "turn", "halo")
        n(b.HOOD, k, "hood"); assertEquals(pair(k, "words"), b.WORDS); n(b.LIGHTS, k, "lights")
        n(b.TAIL_PLATE_Y, k, "tail", "y"); n(b.TAIL_PLATE_W, k, "tail", "w")
        // the lamps on their corners' arcs, apart: headlamps on the nose's quarter, the tail's lamps on its own
        assertTrue(b.LAMP_HEAD.first >= 180f && b.LAMP_HEAD.second <= 270f - b.HOOD)
        assertTrue(90f < b.LAMP_REVERSE.first && b.LAMP_REVERSE.second < b.LAMP_STOP.first && b.LAMP_STOP.second <= 180f)
        val c = ScannerSpec.ChassisScheme
        assertEquals(setOf("steerDrop", "steerAt", "caliper", "epbY"), (at("chassisScheme") as Map<*, *>).keys)
        n(c.CALIPER_IN, "chassisScheme", "caliper", "in"); n(c.CALIPER_W, "chassisScheme", "caliper", "w")
        n(c.CALIPER_H, "chassisScheme", "caliper", "h"); n(c.CALIPER_R, "chassisScheme", "caliper", "r"); n(c.EPB_Y, "chassisScheme", "epbY")
        // the rear-wheel steering's tiles over the footer
        assertTrue(ScannerSpec.Car.TOP + ScannerSpec.Car.H - ScannerSpec.Car.AXLE + c.STEER_DROP + ScannerSpec.Tile.H <= ScannerSpec.Content.BOTTOM)
        val s = ScannerSpec.SafetyScheme
        assertEquals(setOf("gap", "rows", "step", "pad", "seat"), (at("safetyScheme") as Map<*, *>).keys)
        assertEquals(pair("safetyScheme", "rows"), s.ROWS)
        n(s.STEP, "safetyScheme", "step"); n(s.PAD, "safetyScheme", "pad")
        n(s.SEAT_W, "safetyScheme", "seat", "w"); n(s.SEAT_H, "safetyScheme", "seat", "h"); n(s.SEAT_R, "safetyScheme", "seat", "r")
        n(s.STRAP_IN, "safetyScheme", "seat", "strapIn"); n(s.STRAP_TOP, "safetyScheme", "seat", "strapTop")
        n(s.STRAP_FOOT, "safetyScheme", "seat", "strapFoot")
        // the seats clear of the belts' tiles, which stand at the B-pillar
        val car = ScannerSpec.Car
        val pillar = (car.DOORS_FRONT.second + car.DOORS_REAR.first) / 2f
        val seatH = ScannerSpec.Type.COUNTER_LABEL.lh + ScannerSpec.Callout.GAP + ScannerSpec.Type.CELL.lh
        assertTrue(s.ROWS.first + seatH < pillar - ScannerSpec.Tile.H / 2)
        assertTrue(s.ROWS.second > pillar + ScannerSpec.Tile.H / 2)
        n(ScannerSpec.LvScheme.MODE_Y, "lvScheme", "modeY")
        val (cx, cy, cw) = ScannerSpec.Hv.CHARGE_LABEL
        n(cx, "hvRail", "charge", "0"); n(cy, "hvRail", "charge", "1"); n(cw, "hvRail", "charge", "2")
    }

    @Test
    fun theCallouts() {
        val o = ScannerSpec.Callout
        n(o.LEFT_X, "callout", "leftX"); n(o.W, "callout", "w"); n(o.PAD_X, "callout", "padX"); n(o.PAD_Y, "callout", "padY")
        n(o.GAP, "callout", "gap"); n(o.R, "callout", "r"); n(o.DOT, "callout", "dot"); n(o.MACHINE_R, "callout", "machineR")
        val a = ScannerSpec.AdasCar
        n(a.TOP, "adasCar", "top"); n(a.H, "adasCar", "h"); assertEquals(pair("adasCar", "corner"), a.CORNER)
        n(a.FRONT_SPREAD, "adasCar", "front", "spread"); n(a.FRONT_LEN, "adasCar", "front", "len")
        n(a.CORNERS_SPREAD, "adasCar", "corners", "spread"); n(a.CORNERS_LEN, "adasCar", "corners", "len")
        n(a.MRR_Y, "adasCar", "mrrY"); n(a.FR_DROP, "adasCar", "frDrop"); n(a.ADC_LIFT, "adasCar", "adcLift"); n(a.IPA_LIFT, "adasCar", "ipaLift")
        val d = ScannerSpec.DrivesCar
        n(d.PAIR, "drivesCar", "pair"); n(d.BOX_W, "drivesCar", "boxW"); n(d.ENGINE_Y, "drivesCar", "engine", "y")
        n(d.ENGINE_W, "drivesCar", "engine", "w"); n(d.ENGINE_H, "drivesCar", "engine", "h"); n(d.DOMAIN_GAP, "drivesCar", "domainGap")
    }

    @Test
    fun theCabin() {
        val c = ScannerSpec.CabinScheme
        val k = "cabinScheme"
        // every number of the JSON's cabinScheme is held here, and nothing else is in it
        assertEquals(
            setOf("x", "plateW", "top", "bottom", "gap", "bendIn", "bendStep", "wheel", "front", "bench", "console", "pad", "fridge",
                "rearFridge", "pip", "lamp", "centreLeader"),
            (at(k) as Map<*, *>).keys,
        )
        n(c.X, k, "x"); n(c.PLATE_W, k, "plateW"); n(c.TOP, k, "top"); n(c.BOTTOM, k, "bottom"); n(c.GAP, k, "gap")
        n(c.BEND_IN, k, "bendIn"); n(c.BEND_STEP, k, "bendStep")
        n(c.WHEEL.first, k, "wheel", "0"); n(c.WHEEL.second, k, "wheel", "1"); n(c.WHEEL.third, k, "wheel", "2")
        assertEquals(cols(k, "front", "x"), c.FRONT_X)
        n(c.FRONT_Y, k, "front", "y"); n(c.FRONT_W, k, "front", "w"); n(c.FRONT_H, k, "front", "h"); n(c.FRONT_R, k, "front", "r")
        assertEquals(cols(k, "bench", "x"), c.BENCH_X)
        n(c.BENCH_Y, k, "bench", "y"); n(c.BENCH_W, k, "bench", "w"); n(c.BENCH_H, k, "bench", "h"); n(c.BENCH_R, k, "bench", "r")
        n(c.CONSOLE_Y, k, "console", "y"); n(c.CONSOLE_H, k, "console", "h"); n(c.CONSOLE_W, k, "console", "w"); n(c.CONSOLE_R, k, "console", "r")
        n(c.PAD_Y, k, "pad", "y"); n(c.PAD_W, k, "pad", "w"); n(c.PAD_H, k, "pad", "h")
        n(c.FRIDGE_Y, k, "fridge", "y"); n(c.FRIDGE_H, k, "fridge", "h")
        n(c.REAR_FRIDGE_Y, k, "rearFridge", "y"); n(c.REAR_FRIDGE_H, k, "rearFridge", "h")
        n(c.PIP, k, "pip", "s"); n(c.PIP_GAP, k, "pip", "gap"); n(c.PIP_PAD, k, "pip", "pad")
        n(c.LAMP_R, k, "lamp", "r"); n(c.LAMP_GLOW, k, "lamp", "glow"); n(c.LAMP_IN, k, "lamp", "in"); n(c.LAMP_DOWN, k, "lamp", "down")
        n(c.CENTRE_LEADER, k, "centreLeader")
        // the plates stand under the tabs and over the footer, clear of the car
        val car = ScannerSpec.Car
        assertTrue(c.TOP >= ScannerSpec.Content.TOP && c.BOTTOM <= ScannerSpec.Footer.TOP)
        val width = ScannerSpec.W - ScannerSpec.Rail.W
        val left = width / 2f - car.W / 2f - car.WHEEL_W / 2f
        assertTrue(c.X + c.PLATE_W < left - c.BEND_IN - 4 * c.BEND_STEP)
        // the seats and the console inside the body
        assertTrue(c.FRONT_X.last() + c.FRONT_W < car.W && c.BENCH_X.last() + c.BENCH_W < car.W)
        assertTrue(c.CONSOLE_Y + c.CONSOLE_H < c.BENCH_Y && c.REAR_FRIDGE_Y + c.REAR_FRIDGE_H <= c.CONSOLE_Y + c.CONSOLE_H)
    }

    @Test
    fun theSchemes() {
        val e = ScannerSpec.EngineScheme
        val k = "engineScheme"
        n(e.X0, k, "x0"); n(e.X1, k, "x1"); n(e.PIPE_Y, k, "pipeY"); n(e.PIPE, k, "pipe"); n(e.THROTTLE, k, "throttle")
        n(e.BLOCK_X, k, "block", "x"); n(e.BLOCK_Y, k, "block", "y"); n(e.BLOCK_W, k, "block", "w"); n(e.BLOCK_H, k, "block", "h")
        n(e.CYL, k, "block", "cyl"); n(e.CAT_X, k, "cat", "x"); n(e.CAT_W, k, "cat", "w"); n(e.CAT_H, k, "cat", "h")
        n(e.O2, k, "o2"); n(e.UP_Y, k, "upY"); n(e.DOWN_Y, k, "downY")
        val points = (at(k, "points") as Map<*, *>).entries.associate { (name, v) ->
            val a = v as List<*>
            name as String to ScannerSpec.EngineScheme.Point(a[0] == "up", (a[1] as Number).toFloat(), (a[2] as Number).toFloat(), (a.getOrNull(3) as? Number)?.toFloat())
        }
        assertEquals(points, e.POINTS)
        val c = ScannerSpec.ClimateScheme
        n(c.LEFT_X, "climateScheme", "leftX"); n(c.ZONE_W, "climateScheme", "zoneW"); n(c.ZONE_H, "climateScheme", "zoneH")
        n(c.ZONE_Y, "climateScheme", "zoneY"); n(c.STEM, "climateScheme", "stem")
        val l = ScannerSpec.LvScheme
        n(l.X0, "lvScheme", "x0"); n(l.X1, "lvScheme", "x1"); n(l.BUS_Y, "lvScheme", "busY"); n(l.DCDC_X, "lvScheme", "dcdcX")
        n(l.BATT_X, "lvScheme", "battX"); n(l.BATT_DROP, "lvScheme", "battDrop"); n(l.LOAD_X, "lvScheme", "loadX")
        n(l.LOAD_STEP, "lvScheme", "loadStep"); n(l.LOAD_DROP, "lvScheme", "loadDrop")
    }

    @Test
    fun columnsShareWhatIsLeftAsTheBoardDoes() {
        // beside the rail a live table's last column takes 1016 - 766
        val c = ScannerSpec.columns(ScannerSpec.Rail.LIVE_COLS, 24f, ScannerSpec.W - ScannerSpec.Rail.W - 48f)
        assertEquals(790f, c[7].first, 1e-4f)
        assertEquals(250f, c[7].second, 1e-4f)
        val g = ScannerSpec.columns(listOf(ScannerSpec.FR, ScannerSpec.FR), 0f, 100f, 10f)
        assertEquals(listOf(0f to 45f, 55f to 45f), g)
        assertTrue(ScannerSpec.columns(ScannerSpec.Adas.COLS, 24f, 1016f).all { it.second > 0f })
    }
}
