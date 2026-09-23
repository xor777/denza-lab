package dev.denza.apps.design.luminofor

import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import dev.denza.apps.design.luminofor.SpecJson.list
import dev.denza.apps.design.luminofor.SpecJson.num
import dev.denza.apps.design.luminofor.SpecJson.str
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [LuminoforSpec] against `tools/design-canvas/luminofor/spec.json`, value by value.
 *
 * The board draws from the JSON and the app from the object, so this is the join between them:
 * the same role `MainBoardContractTest` and `ContourBoardContractTest` played for the older boards.
 * A value changed on one side fails here until the other side has moved in the same change.
 */
class LuminoforSpecContractTest {

    private fun near(expected: Double, actual: Float, path: String) =
        assertEquals(path, expected, actual.toDouble(), 1e-3)

    private fun n(actual: Float, vararg path: String) = near(num(*path), actual, path.joinToString("."))

    private fun i(actual: Int, vararg path: String) = assertEquals(path.joinToString("."), num(*path).toInt(), actual)

    private fun hex(s: String): Int = (0xFF000000.toInt() or s.removePrefix("#").toInt(16))

    private fun rgba(s: String): Int {
        val (r, g, b, a) = s.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim().toDouble() }
        return ((a * 255).roundToInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun light(actual: Light, vararg path: String) {
        val pair = list(*path)
        assertEquals("${path.joinToString(".")} halo", hex(pair[0] as String), actual.halo)
        assertEquals("${path.joinToString(".")} core", hex(pair[1] as String), actual.core)
    }

    @Test
    fun compositingIsAdditive() {
        assertEquals("additive", str("compositing"))
        assertEquals(true, LuminoforSpec.COMPOSITING_ADDITIVE)
        assertEquals(hex(str("colors", "background")), LuminoforSpec.BACKGROUND)
    }

    @Test
    fun colours() {
        light(ClusterInk.INK, "colors", "cluster", "ink")
        light(ClusterInk.GREY, "colors", "cluster", "grey")
        light(ClusterInk.BLUE, "colors", "cluster", "blue")
        light(ClusterInk.ORANGE, "colors", "cluster", "orange")
        light(ClusterInk.RED, "colors", "cluster", "red")
        light(HeadInk.WHITE, "colors", "head", "white")
        light(HeadInk.BLUE, "colors", "head", "blue")
        assertEquals(hex(str("colors", "head", "cardOn")), HeadInk.CARD_ON)
        assertEquals(hex(str("colors", "head", "cardOff")), HeadInk.CARD_OFF)
        assertEquals(rgba(str("colors", "head", "crown")), HeadInk.CROWN)
        assertEquals(rgba(str("colors", "head", "handle")), HeadInk.HANDLE)
        assertEquals(hex(str("colors", "head", "dotOn")), HeadInk.DOT_ON)
        assertEquals(rgba(str("colors", "head", "dotOff")), HeadInk.DOT_OFF)
    }

    @Test
    fun typeWeights() {
        i(LuminoforSpec.Type.CLUSTER_WEIGHT, "type", "cluster", "weight")
        i(LuminoforSpec.Type.HEAD_WEIGHT, "type", "head", "weight")
        i(LuminoforSpec.Type.HEAD_STRONG, "type", "head", "strong")
        assertEquals("Jura", str("type", "cluster", "family"))
        assertEquals("Roboto", str("type", "head", "family"))
    }

    @Test
    fun digits() {
        n(Digits.CAP_RATIO, "digits", "capRatio")
        n(Digits.CAP, "digits", "cap")
        n(Digits.TRACK, "digits", "track")
        n(Digits.STROKE_PER_SIZE, "digits", "strokePerSize")
        n(Digits.STROKE_MIN, "digits", "strokeMin")
        n(Digits.HERO_STROKE, "digits", "heroStroke")
        val glyphs = SpecJson.at("digits", "glyphs") as Map<*, *>
        assertEquals(glyphs.keys.map { (it as String).single() }.toSet(), Digits.GLYPHS.keys)
        glyphs.forEach { (k, v) ->
            val (adv, path) = v as List<*>
            val mine = Digits.GLYPHS.getValue((k as String).single())
            assertEquals("glyph $k advance", (adv as Double), mine.first.toDouble(), 1e-6)
            assertEquals("glyph $k path", path, mine.second)
        }
    }

    @Test
    fun figureWidthsAreTheBoardsArithmetic() {
        // the board's numWidth('000', 88): (3 x 70 + 2 x 18) x 0.71 x 88 / 100
        assertEquals(246 * 0.71 * 88 / 100, WideDigits.width("000", 88f).toDouble(), 1e-3)
        assertEquals((70 + 18 + 14 + 18 + 70) * 0.71 * 52 / 100, WideDigits.width("9,3", 52f).toDouble(), 1e-3)
        assertEquals(0.0, WideDigits.width("", 52f).toDouble(), 0.0)
        assertEquals(2.2, WideDigits.stroke(17f).toDouble(), 1e-6)
        assertEquals(88 * 0.075, WideDigits.stroke(88f).toDouble(), 1e-6)
    }

    @Test
    fun clusterStockAndGrid() {
        n(Cluster.W, "cluster", "W"); n(Cluster.H, "cluster", "H"); n(Cluster.MARGIN, "cluster", "margin")
        i(Cluster.DISPLAY_W, "cluster", "display", "0"); i(Cluster.DISPLAY_H, "cluster", "display", "1")
        n(Cluster.STOCK_TOP, "cluster", "stock", "top")
        n(Cluster.STOCK_BOTTOM, "cluster", "stock", "bottom")
        n(Cluster.LEFT_APERTURE_RX, "cluster", "stock", "leftApertureRx")
        n(Cluster.RIGHT_APERTURE_RX, "cluster", "stock", "rightApertureRx")
        n(Cluster.PETAL_RX, "cluster", "stock", "petalRx")
        n(Cluster.PETAL_RY, "cluster", "stock", "petalRy")
        n(Cluster.PETAL_CY, "cluster", "stock", "petalCy")
        val g = Cluster.Grid
        n(g.CAPTION, "cluster", "grid", "caption"); n(g.GLYPH_BASE, "cluster", "grid", "glyphBase")
        n(g.BASELINE, "cluster", "grid", "baseline"); n(g.AXIS, "cluster", "grid", "axis")
        n(g.SIDE, "cluster", "grid", "side"); n(g.TEMP_PITCH, "cluster", "grid", "tempPitch")
        n(g.HERO_SIZE, "cluster", "grid", "heroSize"); n(g.HERO_UNIT_GAP, "cluster", "grid", "heroUnitGap")
        n(g.HERO_UNIT_SIZE, "cluster", "grid", "heroUnitSize"); n(g.FIGURE_SIZE, "cluster", "grid", "figureSize")
        n(g.TEMP_SIZE, "cluster", "grid", "tempSize"); n(g.CAPTION_SIZE, "cluster", "grid", "captionSize")
        n(g.CAPTION_TRACK, "cluster", "grid", "captionTrack"); n(g.CELL_CAPTION_TRACK, "cluster", "grid", "cellCaptionTrack")
        n(g.UNIT_SIZE, "cluster", "grid", "unitSize"); n(g.UNIT_GAP, "cluster", "grid", "unitGap")
        n(g.DETAIL_SIZE, "cluster", "grid", "detailSize"); n(g.DETAIL_TRACK, "cluster", "grid", "detailTrack")
        n(g.DETAIL_DROP, "cluster", "grid", "detailDrop")
    }

    @Test
    fun clusterBandTraceBoxGlyph() {
        val b = Cluster.Band
        n(b.OUT_KW, "cluster", "band", "outKw"); n(b.IN_KW, "cluster", "band", "inKw")
        n(b.FILAMENT_HALO_WIDTH, "cluster", "band", "filamentHalo", "0"); n(b.FILAMENT_HALO_ALPHA, "cluster", "band", "filamentHalo", "1")
        n(b.FILAMENT_CORE_WIDTH, "cluster", "band", "filamentCore", "0"); n(b.FILAMENT_CORE_ALPHA, "cluster", "band", "filamentCore", "1")
        n(b.ZERO_TICK_HALF, "cluster", "band", "zeroTick", "0"); n(b.ZERO_TICK_STROKE, "cluster", "band", "zeroTick", "1")
        n(b.ZERO_TICK_INTENSITY, "cluster", "band", "zeroTick", "2")
        n(b.BEAM_STROKE, "cluster", "band", "beamStroke"); n(b.BEAM_INTENSITY, "cluster", "band", "beamIntensity")
        n(b.HEAD_RADIUS, "cluster", "band", "headRadius"); n(b.HEAD_BLUR, "cluster", "band", "headBlur")
        n(b.PEAK_TICK_HALF, "cluster", "band", "peakTick", "0"); n(b.PEAK_TICK_STROKE, "cluster", "band", "peakTick", "1")
        n(b.THREADS_BASE, "cluster", "band", "threads", "base"); n(b.THREADS_PER_UNIT, "cluster", "band", "threads", "perUnit")
        n(b.THREADS_AMP_BASE, "cluster", "band", "threads", "ampBase"); n(b.THREADS_AMP_RANGE, "cluster", "band", "threads", "ampRange")
        n(b.THREADS_AMP_KW, "cluster", "band", "threads", "ampKw"); n(b.THREADS_WIDTH, "cluster", "band", "threads", "width")
        n(b.GLOW_MAX, "cluster", "band", "glow", "max"); n(b.GLOW_FULL_KW, "cluster", "band", "glow", "fullKw")
        n(b.GLOW_RADIUS, "cluster", "band", "glow", "radius"); n(b.GLOW_REACH_BELOW, "cluster", "band", "glow", "reachBelow")
        val t = Cluster.Trace
        n(t.ZERO, "cluster", "trace", "zero"); n(t.TOP, "cluster", "trace", "top"); n(t.DROP, "cluster", "trace", "drop")
        n(t.UP_TO, "cluster", "trace", "upTo"); n(t.DOWN_TO, "cluster", "trace", "downTo"); n(t.WIDTH, "cluster", "trace", "width")
        i(t.POINTS, "cluster", "trace", "points")
        n(t.GAP_FROM_AXIS, "cluster", "trace", "gapFromAxis"); i(t.RUNS, "cluster", "trace", "runs")
        n(t.TICK, "cluster", "trace", "tick")
        n(t.STROKE, "cluster", "trace", "stroke"); n(t.FIGURE_SIZE, "cluster", "trace", "figureSize")
        n(t.UNIT_GAP, "cluster", "trace", "unitGap"); n(t.UNIT_SIZE, "cluster", "trace", "unitSize")
        val e = Cluster.EngineBox
        n(e.WIDTH, "cluster", "engineBox", "width"); n(e.UP_TO, "cluster", "engineBox", "upTo")
        n(e.STROKE, "cluster", "engineBox", "stroke"); n(e.BASE_STROKE, "cluster", "engineBox", "baseStroke")
        n(Cluster.Glyph.OUTLINE, "cluster", "glyph", "outline"); n(Cluster.Glyph.WHEEL, "cluster", "glyph", "wheel")
        n(Cluster.Glyph.HEIGHT, "cluster", "glyph", "height")
    }

    private fun box(actual: Head.Box, vararg path: String) {
        val v = list(*path).map { (it as Number).toDouble() }
        near(v[0], actual.left, "left"); near(v[1], actual.top, "top"); near(v[2], actual.right, "right"); near(v[3], actual.bottom, "bottom")
    }

    @Test
    fun headFull() {
        val f = Head.Full
        n(f.WIDTH, "head", "full", "size", "0"); n(f.HEIGHT, "head", "full", "size", "1"); n(f.MARGIN, "head", "full", "margin")
        box(f.STRIP_BOX, "head", "full", "stripBox")
        val t = Head.Full.Tiles
        n(t.TOP, "head", "full", "tiles", "top"); n(t.GAP, "head", "full", "tiles", "gap"); n(t.HEIGHT, "head", "full", "tiles", "height")
        n(t.RADIUS, "head", "full", "tiles", "radius"); n(t.ICON, "head", "full", "tiles", "icon")
        n(t.ICON_INSET_X, "head", "full", "tiles", "iconInset", "0"); n(t.ICON_INSET_Y, "head", "full", "tiles", "iconInset", "1")
        n(t.NAME_BASELINE, "head", "full", "tiles", "nameBaseline"); n(t.STATUS_BASELINE, "head", "full", "tiles", "statusBaseline")
        n(t.TEXT_INSET, "head", "full", "tiles", "textInset"); n(t.NAME_SIZE, "head", "full", "tiles", "nameSize")
        n(t.STATUS_SIZE, "head", "full", "tiles", "statusSize")
        val s = Head.Full.Strip
        n(s.CAPTION, "head", "full", "strip", "caption"); n(s.VALUE, "head", "full", "strip", "value")
        n(s.VALUE_SIZE, "head", "full", "strip", "valueSize"); n(s.LABEL_SIZE, "head", "full", "strip", "labelSize")
        n(s.TITLE_SIZE, "head", "full", "strip", "titleSize"); n(s.READING_GAP, "head", "full", "strip", "readingGap")
        n(s.SPECTRUM_TOP, "head", "full", "strip", "spectrumTop"); n(s.FLOOR, "head", "full", "strip", "floor")
        i(s.BARS, "head", "full", "strip", "bars"); n(s.DOTS_Y, "head", "full", "strip", "dotsY")
        val c = Head.Full.Car
        n(c.HERO_SIZE, "head", "full", "car", "heroSize"); n(c.SIDE, "head", "full", "car", "side")
        n(c.TEMP_PITCH, "head", "full", "car", "tempPitch"); n(c.TEMP_SIZE, "head", "full", "car", "tempSize")
        n(c.CHART_CAPTION, "head", "full", "car", "chartCaption"); n(c.CHART_CAPTION_SIZE, "head", "full", "car", "chartCaptionSize")
        n(c.CHART_TOP, "head", "full", "car", "chartTop"); n(c.CHART_HEIGHT, "head", "full", "car", "chartHeight")
    }

    @Test
    fun headTwoThirds() {
        val p = Head.Two
        n(p.WIDTH, "head", "two", "size", "0"); n(p.HEIGHT, "head", "two", "size", "1"); n(p.MARGIN, "head", "two", "margin")
        n(p.CAPTION_BAR, "head", "two", "captionBar"); box(p.STRIP_BOX, "head", "two", "stripBox"); n(p.DOTS_Y, "head", "two", "dotsY")
        n(Head.Two.Chips.TOP, "head", "two", "chips", "top"); n(Head.Two.Chips.SIZE, "head", "two", "chips", "size")
        n(Head.Two.Chips.RADIUS, "head", "two", "chips", "radius"); n(Head.Two.Chips.ICON, "head", "two", "chips", "icon")
        i(Head.Two.Chips.PER_ROW, "head", "two", "chips", "perRow")
        val s = Head.Two.Sound
        n(s.TRACK_CAPTION, "head", "two", "sound", "trackCaption"); n(s.TRACK_VALUE, "head", "two", "sound", "trackValue")
        n(s.TITLE_SIZE, "head", "two", "sound", "titleSize"); n(s.LABEL_SIZE, "head", "two", "sound", "labelSize")
        n(s.CAPTION, "head", "two", "sound", "caption"); n(s.VALUE, "head", "two", "sound", "value")
        n(s.VALUE_SIZE, "head", "two", "sound", "valueSize"); n(s.GAP, "head", "two", "sound", "gap")
        n(s.SPECTRUM_TOP, "head", "two", "sound", "spectrumTop"); n(s.FLOOR, "head", "two", "sound", "floor")
        i(s.BARS, "head", "two", "sound", "bars")
        val c = Head.Two.Car
        n(c.CAPTION, "head", "two", "car", "caption"); n(c.VALUE, "head", "two", "car", "value")
        n(c.HERO_SIZE, "head", "two", "car", "heroSize"); n(c.VALUE_SIZE, "head", "two", "car", "valueSize")
        n(c.GAP, "head", "two", "car", "gap"); n(c.ROW2_CAPTION, "head", "two", "car", "row2Caption")
        n(c.ROW2_VALUE, "head", "two", "car", "row2Value"); n(c.VOLT_SIZE, "head", "two", "car", "voltSize")
        n(c.TEMP_PITCH, "head", "two", "car", "tempPitch"); n(c.TEMP_SIZE, "head", "two", "car", "tempSize")
        n(c.CHART_CAPTION, "head", "two", "car", "chartCaption"); n(c.CHART_TOP, "head", "two", "car", "chartTop")
        n(c.CHART_HEIGHT, "head", "two", "car", "chartHeight")
    }

    @Test
    fun headOneThird() {
        val p = Head.One
        n(p.WIDTH, "head", "one", "size", "0"); n(p.HEIGHT, "head", "one", "size", "1"); n(p.MARGIN, "head", "one", "margin")
        n(p.CAPTION_BAR, "head", "one", "captionBar"); box(p.STRIP_BOX, "head", "one", "stripBox"); n(p.DOTS_Y, "head", "one", "dotsY")
        n(Head.One.Chips.TOP, "head", "one", "chips", "top"); n(Head.One.Chips.SIZE, "head", "one", "chips", "size")
        n(Head.One.Chips.RADIUS, "head", "one", "chips", "radius"); n(Head.One.Chips.ICON, "head", "one", "chips", "icon")
        i(Head.One.Chips.PER_ROW, "head", "one", "chips", "perRow"); n(Head.One.Chips.ROW_GAP, "head", "one", "chips", "rowGap")
        val s = Head.One.Sound
        n(s.TRACK_CAPTION, "head", "one", "sound", "trackCaption"); n(s.TRACK_VALUE, "head", "one", "sound", "trackValue")
        n(s.TITLE_SIZE, "head", "one", "sound", "titleSize"); n(s.LABEL_SIZE, "head", "one", "sound", "labelSize")
        n(s.ROWS_TOP, "head", "one", "sound", "rowsTop"); n(s.ROW_PITCH, "head", "one", "sound", "rowPitch")
        n(s.VALUE_X, "head", "one", "sound", "valueX"); n(s.VALUE_SIZE, "head", "one", "sound", "valueSize")
        n(s.SPECTRUM_TOP, "head", "one", "sound", "spectrumTop"); n(s.FLOOR, "head", "one", "sound", "floor")
        i(s.BARS, "head", "one", "sound", "bars")
        val c = Head.One.Car
        n(c.CAPTION, "head", "one", "car", "caption"); n(c.VALUE, "head", "one", "car", "value")
        n(c.HERO_SIZE, "head", "one", "car", "heroSize"); n(c.ROW2_CAPTION, "head", "one", "car", "row2Caption")
        n(c.ROW2_VALUE, "head", "one", "car", "row2Value"); n(c.ROW2_SIZE, "head", "one", "car", "row2Size")
        n(c.ROW2_GAP, "head", "one", "car", "row2Gap"); n(c.TEMPS_CAPTION, "head", "one", "car", "tempsCaption")
        n(c.TEMPS_VALUE, "head", "one", "car", "tempsValue"); n(c.TEMP_PITCH, "head", "one", "car", "tempPitch")
        n(c.TEMP_SIZE, "head", "one", "car", "tempSize"); n(c.CHART_CAPTION, "head", "one", "car", "chartCaption")
        n(c.CHART_CAPTION_SIZE, "head", "one", "car", "chartCaptionSize"); n(c.CHART_TOP, "head", "one", "car", "chartTop")
        n(c.CHART_HEIGHT, "head", "one", "car", "chartHeight")
    }

    @Test
    fun headShared() {
        n(Head.Handle.WIDTH, "head", "handle", "width"); n(Head.Handle.HEIGHT, "head", "handle", "height")
        n(Head.Handle.TOP, "head", "handle", "top")
        val r = Head.Reading
        n(r.UNIT_RATIO, "head", "reading", "unitRatio"); n(r.UNIT_GAP_RATIO, "head", "reading", "unitGapRatio")
        n(r.RATE_RATIO, "head", "reading", "rateRatio"); n(r.RATE_GAP_RATIO, "head", "reading", "rateGapRatio")
        n(r.UNIT_ALPHA, "head", "reading", "unitAlpha")
        n(Head.Icon.STROKE, "head", "icon", "stroke"); n(Head.Icon.ON_GLOW, "head", "icon", "onGlow")
        n(Head.Icon.OFF_ALPHA, "head", "icon", "offAlpha")
        val ring = Head.Icon.Ring
        n(ring.SIZE, "head", "icon", "ring", "size"); n(ring.CHIP_SIZE, "head", "icon", "ring", "chipSize")
        n(ring.CHIP_INSET, "head", "icon", "ring", "chipInset"); n(ring.SWEEP, "head", "icon", "ring", "sweep")
        val sp = Head.Spectrum
        n(sp.BAR_WIDTH, "head", "spectrum", "barWidth"); n(sp.LINE_WIDTH, "head", "spectrum", "lineWidth")
        n(sp.LINE_PITCH, "head", "spectrum", "linePitch"); n(sp.CROWN_HEIGHT, "head", "spectrum", "crownHeight")
        n(sp.CROWN_LIFT, "head", "spectrum", "crownLift"); n(sp.HEADROOM, "head", "spectrum", "headroom")
        n(sp.GRADIENT_FOOT, "head", "spectrum", "gradient", "0"); n(sp.GRADIENT_TOP, "head", "spectrum", "gradient", "1")
        n(sp.GLOW_ALPHA, "head", "spectrum", "glowAlpha"); n(sp.GLOW_PAD, "head", "spectrum", "glowPad")
        n(sp.HAZE_RX, "head", "spectrum", "haze", "rx"); n(sp.HAZE_RY, "head", "spectrum", "haze", "ry")
        n(sp.HAZE_CY, "head", "spectrum", "haze", "cy")
        val stops = list("head", "spectrum", "haze", "stops").map { (it as List<*>).map { v -> (v as Number).toFloat() } }
        assertEquals(stops.map { it[0] to it[1] }, sp.HAZE_STOPS)
        val ch = Head.Chart
        i(ch.POINTS, "head", "chart", "points"); n(ch.TICK, "head", "chart", "tick")
        n(ch.ZERO_AT, "head", "chart", "zeroAt"); n(ch.UP_TO, "head", "chart", "upTo"); n(ch.DOWN_TO, "head", "chart", "downTo")
        n(ch.FILL_UP, "head", "chart", "fillUp"); n(ch.FILL_DOWN, "head", "chart", "fillDown"); n(ch.STROKE, "head", "chart", "stroke")
        n(ch.ZERO_STROKE, "head", "chart", "zeroStroke"); n(ch.ZERO_ALPHA, "head", "chart", "zeroAlpha"); n(ch.DOT, "head", "chart", "dot")
        n(ch.CAPTION_ALPHA, "head", "chart", "captionAlpha")
    }

    @Test
    fun theSheetIsTheSpecs() {
        val sh = LuminoforSpec.Sheet
        n(sh.SCRIM, "sheet", "scrim")
        val p = LuminoforSpec.Sheet.Panel
        n(p.WIDTH, "sheet", "panel", "width"); assertEquals(hex(SpecJson.str("sheet", "panel", "ground")), p.GROUND)
        n(p.EDGE_ALPHA, "sheet", "panel", "edgeAlpha"); n(p.PAD_X, "sheet", "panel", "padX")
        n(p.PAD_TOP, "sheet", "panel", "padTop"); n(p.PAD_BOTTOM, "sheet", "panel", "padBottom"); n(p.GAP, "sheet", "panel", "gap")
        val k = LuminoforSpec.Sheet.Compact
        n(k.PAD_X, "sheet", "compact", "padX"); n(k.PAD_TOP, "sheet", "compact", "padTop")
        n(k.PAD_BOTTOM, "sheet", "compact", "padBottom"); n(k.GAP, "sheet", "compact", "gap")
        val h = LuminoforSpec.Sheet.Header
        n(h.HEIGHT, "sheet", "header", "height"); n(h.GLYPH, "sheet", "header", "glyph"); n(h.GLYPH_ALPHA, "sheet", "header", "glyphAlpha")
        n(h.GLYPH_GAP, "sheet", "header", "glyphGap"); n(h.TITLE_SIZE, "sheet", "header", "titleSize"); n(h.CLOSE, "sheet", "header", "close")
        n(h.CLOSE_ALPHA, "sheet", "header", "closeAlpha"); n(h.SUBTITLE_SIZE, "sheet", "header", "subtitleSize")
        n(h.SUBTITLE_ALPHA, "sheet", "header", "subtitleAlpha")
        n(LuminoforSpec.Sheet.Roboto.ASCENT, "sheet", "roboto", "ascent"); n(LuminoforSpec.Sheet.Roboto.DESCENT, "sheet", "roboto", "descent")
        n(LuminoforSpec.Sheet.Roboto.CENTRE, "sheet", "roboto", "centre")
        assertEquals(hex(SpecJson.str("sheet", "plate", "color")), LuminoforSpec.Sheet.Plate.COLOR)
        n(LuminoforSpec.Sheet.Plate.RADIUS, "sheet", "plate", "radius"); n(LuminoforSpec.Sheet.Plate.HAIRLINE_ALPHA, "sheet", "plate", "hairlineAlpha")
        n(LuminoforSpec.Sheet.Plate.HAIRLINE_INSET, "sheet", "plate", "hairlineInset")
        val r = LuminoforSpec.Sheet.Row
        n(r.SINGLE_HEIGHT, "sheet", "row", "single", "0"); n(r.SINGLE_BASELINE, "sheet", "row", "single", "1")
        n(r.TWO_HEIGHT, "sheet", "row", "twoLine", "0"); n(r.TWO_TITLE, "sheet", "row", "twoLine", "1"); n(r.TWO_SUMMARY, "sheet", "row", "twoLine", "2")
        n(r.ICONS_HEIGHT, "sheet", "row", "withIcons", "0"); n(r.ICONS_TITLE, "sheet", "row", "withIcons", "1")
        n(r.ICONS_TOP, "sheet", "row", "withIcons", "2"); n(r.ICONS_VALUE, "sheet", "row", "withIcons", "3")
        n(r.PAD_X, "sheet", "row", "padX"); n(r.TITLE_SIZE, "sheet", "row", "titleSize"); n(r.TITLE_ALPHA, "sheet", "row", "titleAlpha")
        n(r.SUMMARY_SIZE, "sheet", "row", "summarySize"); n(r.SUMMARY_ALPHA, "sheet", "row", "summaryAlpha")
        n(r.SUMMARY_STEP, "sheet", "row", "summaryStep")
        n(r.CHEVRON, "sheet", "row", "chevron"); n(r.CHOICE_ICON, "sheet", "row", "choiceIcon"); n(r.CHOICE_GAP, "sheet", "row", "choiceGap")
        val pr = LuminoforSpec.Sheet.KeyValue
        n(pr.MIN_HEIGHT, "sheet", "pair", "minHeight"); n(pr.PAD_Y, "sheet", "pair", "padY"); n(pr.SIZE, "sheet", "pair", "size")
        n(pr.KEY_ALPHA, "sheet", "pair", "keyAlpha"); n(pr.VALUE_ALPHA, "sheet", "pair", "valueAlpha")
        n(pr.GAP, "sheet", "pair", "gap"); n(pr.STEP, "sheet", "pair", "step")
        n(LuminoforSpec.Sheet.Label.SIZE, "sheet", "label", "size"); n(LuminoforSpec.Sheet.Label.ALPHA, "sheet", "label", "alpha"); n(LuminoforSpec.Sheet.Label.GAP, "sheet", "label", "gap")
        val w = LuminoforSpec.Sheet.Switch
        n(w.WIDTH, "sheet", "switch", "width"); n(w.HEIGHT, "sheet", "switch", "height"); n(w.THUMB, "sheet", "switch", "thumb")
        assertEquals(hex(SpecJson.str("sheet", "switch", "on")), w.ON); assertEquals(hex(SpecJson.str("sheet", "switch", "off")), w.OFF)
        assertEquals(hex(SpecJson.str("sheet", "switch", "thumbColor")), w.THUMB_COLOR)
        n(w.OFF_ALPHA, "sheet", "switch", "offAlpha"); n(w.DISABLED_ALPHA, "sheet", "switch", "disabledAlpha")
        val g = LuminoforSpec.Sheet.Segmented
        n(g.HEIGHT, "sheet", "segmented", "height"); n(g.RADIUS, "sheet", "segmented", "radius"); n(g.PAD, "sheet", "segmented", "pad")
        n(g.TRACK_ALPHA, "sheet", "segmented", "trackAlpha"); n(g.PILL_ALPHA, "sheet", "segmented", "pillAlpha")
        n(g.ON_TEXT_ALPHA, "sheet", "segmented", "onTextAlpha"); n(g.OFF_TEXT_ALPHA, "sheet", "segmented", "offTextAlpha")
        n(g.SIZE, "sheet", "segmented", "size")
        val a = LuminoforSpec.Sheet.Apps
        n(a.TILE, "sheet", "apps", "tile"); i(a.COLUMNS, "sheet", "apps", "columns"); i(a.NAVIGATION_COLUMNS, "sheet", "apps", "navigationColumns")
        n(a.RADIUS, "sheet", "apps", "radius"); n(a.ICON, "sheet", "apps", "icon"); n(a.NAME_SIZE, "sheet", "apps", "nameSize")
        n(a.NAME_ALPHA, "sheet", "apps", "nameAlpha"); n(a.GAP, "sheet", "apps", "gap"); n(a.BADGE, "sheet", "apps", "badge")
        assertEquals(hex(SpecJson.str("sheet", "apps", "badgeColor")), a.BADGE_COLOR); n(a.BADGE_INSET, "sheet", "apps", "badgeInset")
        n(LuminoforSpec.Sheet.Note.SIZE, "sheet", "note", "size"); n(LuminoforSpec.Sheet.Note.ALPHA, "sheet", "note", "alpha"); n(LuminoforSpec.Sheet.Note.LEADING, "sheet", "note", "leading")
        n(LuminoforSpec.Sheet.Status.SIZE, "sheet", "status", "size"); n(LuminoforSpec.Sheet.Status.LEADING, "sheet", "status", "leading")
        val b = LuminoforSpec.Sheet.Button
        n(b.HEIGHT, "sheet", "button", "height"); n(b.RADIUS, "sheet", "button", "radius")
        assertEquals(hex(SpecJson.str("sheet", "button", "primary")), b.PRIMARY)
        n(b.DISABLED_ALPHA, "sheet", "button", "disabledAlpha"); n(b.SIZE, "sheet", "button", "size")
        n(b.SECONDARY_ALPHA, "sheet", "button", "secondaryAlpha"); n(b.SECONDARY_HEIGHT, "sheet", "button", "secondaryHeight")
        n(b.SECONDARY_SIZE, "sheet", "button", "secondarySize")
        n(LuminoforSpec.Sheet.Footnote.SIZE, "sheet", "footnote", "size"); n(LuminoforSpec.Sheet.Footnote.ALPHA, "sheet", "footnote", "alpha"); n(LuminoforSpec.Sheet.Footnote.GAP, "sheet", "footnote", "gap")
        val rd = LuminoforSpec.Sheet.Reading
        n(rd.LABEL_SIZE, "sheet", "reading", "labelSize"); n(rd.LABEL_ALPHA, "sheet", "reading", "labelAlpha")
        n(rd.VALUE_SIZE, "sheet", "reading", "valueSize"); n(rd.VALUE_ALPHA, "sheet", "reading", "valueAlpha"); n(rd.GAP, "sheet", "reading", "gap")
        val m = LuminoforSpec.Sheet.Modal
        n(m.WIDTH, "sheet", "modal", "width"); n(m.RADIUS, "sheet", "modal", "radius"); n(m.PAD, "sheet", "modal", "pad")
        n(m.ICON, "sheet", "modal", "icon"); n(m.ICON_GAP, "sheet", "modal", "iconGap")
        n(m.GAP, "sheet", "modal", "gap"); n(m.TITLE_SIZE, "sheet", "modal", "titleSize"); n(m.TEXT_SIZE, "sheet", "modal", "textSize")
        n(m.TEXT_ALPHA, "sheet", "modal", "textAlpha"); n(m.COMPACT_TITLE_SIZE, "sheet", "modal", "compactTitleSize")
    }
}
