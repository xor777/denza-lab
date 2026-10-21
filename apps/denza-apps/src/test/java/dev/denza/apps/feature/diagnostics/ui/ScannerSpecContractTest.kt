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
        n(h.DOT_GLOW, "header", "dotGlow")
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
        n(f.LEGEND_W, "footer", "legend", "w"); n(f.LEGEND_H, "footer", "legend", "h")
        n(f.LEGEND_R, "footer", "legend", "r"); n(f.LEGEND_GAP, "footer", "legend", "gap")
        val g = ScannerSpec.Badge
        n(g.H, "badge", "h"); n(g.PAD, "badge", "pad"); n(g.R, "badge", "r")
        n(g.SMALL_H, "badge", "small", "h"); n(g.SMALL_PAD, "badge", "small", "pad")
        n(g.BIG_H, "badge", "big", "h"); n(g.BIG_PAD, "badge", "big", "pad")
    }

    @Test
    fun autoscanAndTopology() {
        val s = ScannerSpec.Scan
        n(s.TABLE_TOP, "scan", "table", "top"); n(s.TABLE_HEAD, "scan", "table", "head")
        n(s.PAD, "scan", "table", "pad"); n(s.NUM_PAD, "scan", "table", "numPad")
        n(s.LIST_TOP, "scan", "list", "top"); n(s.LIST_BOTTOM, "scan", "list", "bottom")
        n(s.GROUP, "scan", "list", "group"); n(s.ROW, "scan", "list", "row"); n(s.RULE, "scan", "list", "rule")
        n(s.GROUP_GAP, "scan", "list", "groupGap"); n(s.CHEVRON, "scan", "list", "chevron")

        val p = ScannerSpec.Topology
        n(p.COLUMN, "topology", "column"); n(p.LEFT, "topology", "left")
        n(p.TILE_W, "topology", "tile", "w"); n(p.TILE_H, "topology", "tile", "h"); n(p.TILE_R, "topology", "tile", "r")
        n(p.TILE_PAD_X, "topology", "tile", "padX"); n(p.TILE_INSET, "topology", "tile", "inset")
        n(p.TILE_PITCH, "topology", "tile", "pitch"); n(p.TILE_ROW_GAP, "topology", "tile", "rowGap")
        n(p.HEAD_X, "topology", "head", "x"); n(p.HEAD_Y, "topology", "head", "y"); n(p.HEAD_W, "topology", "head", "w")
        n(p.HEAD_H, "topology", "head", "h"); n(p.HEAD_R, "topology", "head", "r"); n(p.HEAD_PAD, "topology", "head", "pad")
        n(p.FIRST_LABEL, "topology", "firstLabel"); n(p.LABEL_H, "topology", "labelH"); n(p.GROUP_GAP, "topology", "groupGap")
        n(p.TRUNK_Y, "topology", "trunkY"); n(p.TRUNK_FIRST_Y, "topology", "trunkFirstY"); n(p.TAIL_Y, "topology", "tailY")
        n(p.BUS_INSET, "topology", "busInset")
        val columns = (at("topology", "columns") as List<*>).map { column ->
            (column as List<*>).map { part ->
                val a = part as List<*>
                ScannerSpec.Topology.Part(
                    a[0] as String,
                    (a.getOrNull(1) as? Number)?.toInt() ?: 0,
                    (a.getOrNull(2) as? Number)?.toInt(),
                    a.getOrNull(3) == "continued",
                )
            }
        }
        assertEquals(columns, p.COLUMNS)
    }

    @Test
    fun theTopologyHoldsEveryBlockAtTheRailsWidth() {
        // the catalog's groups after the roles of 2026-10-21: 9/9/7/19/19/4/1
        val sizes = mapOf("power" to 9, "chassis" to 9, "assist" to 7, "body" to 19, "salon" to 19, "climate" to 4, "lv" to 1)
        val p = ScannerSpec.Topology
        val r = ScannerSpec.Rail
        val placed = mutableMapOf<String, Int>()
        p.COLUMNS.forEachIndexed { k, parts ->
            var y = p.FIRST_LABEL
            var bottom = 0f
            parts.forEachIndexed { gi, part ->
                val n = (part.to ?: sizes.getValue(part.id)) - part.from
                if (gi > 0) y += p.GROUP_GAP
                y += p.LABEL_H
                bottom = y + (n - 1) * p.TILE_PITCH + p.TILE_H
                y += n * p.TILE_PITCH
                placed[part.id] = (placed[part.id] ?: 0) + n
            }
            assertTrue("column $k ends at $bottom", bottom <= ScannerSpec.Content.BOTTOM)
            assertTrue("column $k", p.LEFT + k * r.TOPO_COLUMN + r.TOPO_TILE_INSET + r.TOPO_TILE_W <= ScannerSpec.W - r.W - 24f)
        }
        assertEquals(sizes, placed.toMap())
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

        val b = ScannerSpec.BodyDtc
        n(b.TOP, "bodyDtc", "top"); n(b.HEAD, "bodyDtc", "head")
        assertEquals(cols("bodyDtc", "cols"), b.COLS)
        n(b.ROW_MIN, "bodyDtc", "rowMin"); n(b.BIT, "bodyDtc", "bit"); n(b.BIT_R, "bodyDtc", "bitR"); n(b.BELOW, "bodyDtc", "below")
        n(b.BELOW_GAP, "bodyDtc", "belowGap")
        n(b.EXPLAIN_W, "bodyDtc", "explainW"); n(b.EXPLAIN_GAP, "bodyDtc", "explainGap")
        n(b.LEGEND_X, "bodyDtc", "legend", "x"); n(b.LEGEND_W, "bodyDtc", "legend", "w"); n(b.LEGEND_KEY, "bodyDtc", "legend", "key")
        n(b.LEGEND_GAP, "bodyDtc", "legend", "gap"); n(b.LEGEND_ROW_GAP, "bodyDtc", "legend", "rowGap")
    }

    @Test
    fun systemScreens() {
        val m = ScannerSpec.Bms
        n(m.TOP, "bms", "top"); n(m.HEAD, "bms", "head"); n(m.ROW, "bms", "row"); n(m.VALUE_PAD, "bms", "valuePad")
        assertEquals(cols("bms", "cols"), m.COLS)

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
        n(b.BITS_H, "brakes", "bits", "h"); n(b.BITS_GAP, "brakes", "bits", "gap"); n(b.BITS_R, "brakes", "bits", "r")
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
        assertEquals((at(h, "volts") as List<*>).indices.map { pair(h, "volts", "$it") }, v.VOLTS)
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
        n(r.SEP_GAP, "rail", "sepGap"); n(r.BAR, "rail", "bar")
        assertEquals(cols("rail", "scanCols"), r.SCAN_COLS)
        assertEquals(cols("rail", "liveCols"), r.LIVE_COLS)
        n(r.TOPO_COLUMN, "rail", "topology", "column"); n(r.TOPO_TILE_W, "rail", "topology", "tile", "w")
        n(r.TOPO_TILE_PAD_X, "rail", "topology", "tile", "padX"); n(r.TOPO_TILE_INSET, "rail", "topology", "tile", "inset")
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
        val r = ScannerSpec.RangeBar
        n(r.H, "rangeBar", "h"); n(r.HEAD_GAP, "rangeBar", "headGap"); n(r.TICK_GAP, "rangeBar", "tickGap"); n(r.MIN_SPAN, "rangeBar", "minSpan")
        val p = ScannerSpec.PackScheme
        n(p.X, "packScheme", "x"); n(p.Y, "packScheme", "y"); n(p.W, "packScheme", "w"); n(p.H, "packScheme", "h")
        n(p.R, "packScheme", "r"); n(p.PAD, "packScheme", "pad"); assertEquals(pair("packScheme", "poles"), p.POLES)
        n(p.BUS_X, "packScheme", "busX"); n(p.ISO_X, "packScheme", "iso", "x"); n(p.ISO_W, "packScheme", "iso", "w")
        n(p.ISO_H, "packScheme", "iso", "h"); n(p.RANGES_X, "packScheme", "ranges", "x"); n(p.RANGES_Y, "packScheme", "ranges", "y")
        n(p.RANGES_W, "packScheme", "ranges", "w"); n(p.RANGES_STEP, "packScheme", "ranges", "step")
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
        // beside the rail the autoscan's last column takes 1016 - 970
        val c = ScannerSpec.columns(ScannerSpec.Rail.SCAN_COLS, 24f, ScannerSpec.W - ScannerSpec.Rail.W - 48f)
        assertEquals(994f, c[7].first, 1e-4f)
        assertEquals(46f, c[7].second, 1e-4f)
        val g = ScannerSpec.columns(listOf(ScannerSpec.FR, ScannerSpec.FR), 0f, 100f, 10f)
        assertEquals(listOf(0f to 45f, 55f to 45f), g)
        assertTrue(ScannerSpec.columns(ScannerSpec.Adas.COLS, 24f, 1016f).all { it.second > 0f })
    }
}
