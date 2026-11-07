package dev.denza.apps.design.luminofor

import dev.denza.apps.R
import dev.denza.apps.core.AppLanguage
import dev.denza.apps.core.AppText
import dev.denza.apps.core.EnglishWords
import dev.denza.apps.core.StringsXml
import dev.denza.apps.core.figure
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet.Glass as G
import dev.denza.apps.feature.cluster.dashboard.ContourGeometry
import dev.denza.apps.feature.cluster.dashboard.CAPTION_TRIP
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.cluster.dashboard.UNIT_KM
import dev.denza.apps.feature.cluster.dashboard.UNIT_PER_100KM
import dev.denza.apps.feature.cluster.dashboard.UNIT_PER_100KM_FILLING
import dev.denza.apps.feature.split.SplitCrewScene
import dev.denza.apps.feature.trip.StripGeometry
import dev.denza.apps.feature.trip.StripGeometryMeasuredTest
import dev.denza.apps.feature.trip.StripModel
import dev.denza.apps.feature.trip.StripReading
import dev.denza.apps.feature.trip.StripReadings
import dev.denza.apps.feature.trip.TripPanelLayout
import dev.denza.apps.feature.trip.TripPanelRenderer
import dev.denza.apps.feature.trip.VehiclePageWords
import dev.denza.apps.ui.components.NAME_INSET
import dev.denza.apps.ui.dashboard.TileCaptionContractTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Every word drawn in a fixed cell, measured in the font files against that cell's room, in the
 * language the test runs in. `DrawnWordsFitEnglishTest` asks the same of `values-en/`.
 *
 * A paragraph that wraps is not here. A row that holds one line is, because `BaselineText` cuts it
 * with an ellipsis and the driver never sees the rest. Each run writes every cell it measured to
 * `build/reports/drawn-words/<language>.tsv`, tightest first.
 *
 * The widths are [FontMeasure]'s; `docs/system-language.md`, "The English text", says how far they
 * can be trusted against `Paint`.
 */
open class DrawnWordsFitTest {

    /** One line of words and the room it has. */
    private class Cell(val surface: String, val what: String, val text: String, val width: Float, val room: Float) {
        val slack get() = room - width
    }

    private fun fit(cells: List<Cell>) {
        val language = AppText.language
        synchronized(measured) { measured.getOrPut(language) { mutableListOf() } += cells.map { it.row() } }
        val over = cells.filter { it.slack < 0f }
        assertTrue(
            over.joinToString("\n", "$language, ${over.size} of ${cells.size} over:\n") {
                "${it.surface} / ${it.what}: «${it.text}» is ${"%.1f".format(it.width)} of ${"%.1f".format(it.room)}"
            },
            over.isEmpty(),
        )
    }

    private fun Cell.row() = Row(slack, listOf(surface, what, text, "%.1f".format(width), "%.1f".format(room), "%.1f".format(slack)))

    private val jura = FontMeasure.JURA
    private val sans = FontMeasure.SANS
    private val strong = FontMeasure.SANS_STRONG

    // ------------------------------------------------------------------ the Contour

    @Test
    fun theContoursWordsFitTheirGroups() {
        val g = ContourGeometry
        val figures = WideDigits::width
        val left = g.GROUP_LEFT
        val end = g.GROUP_LEFT_END
        val start = g.GROUP_RIGHT_START
        val right = g.GROUP_RIGHT_END
        val track = Grid.CELL_CAPTION_TRACK
        fun caption(text: String) = jura.width(text, Grid.CAPTION_SIZE, track)
        fun detail(text: String) = jura.width(text, Grid.DETAIL_SIZE, Grid.DETAIL_TRACK)
        fun cell(what: String, text: String, width: Float, room: Float) = Cell("contour", what, text, width, room)

        val spreadLine = detail(ContourReadout.CAPTION_SPREAD) + g.SPREAD_CAPTION_GAP + figures("999", g.DETAIL_FIGURE) +
            g.SPREAD_UNIT_GAP + jura.width(ContourReadout.UNIT_MILLIVOLT, Grid.DETAIL_SIZE)
        val headingMinutes = caption(ContourReadout.TITLE_ENGINE_MINUTES)
        val headingRpm = caption(ContourReadout.TITLE_ENGINE_RPM)
        val engineReach = max(start + headingMinutes, start + figures("8888", Grid.FIGURE_SIZE))
        val unitKwh = jura.width(ContourReadout.UNIT_KWH, Grid.UNIT_SIZE)
        val trip = "999 ${ContourReadout.UNIT_KM} ${ContourReadout.CAPTION_TRIP}"
        val sentence = "${ContourReadout.LEGEND_PREFIX} 99 ${ContourReadout.UNIT_KW}"
        val window = ContourReadout.intoPack(ContourReadout.MAX_WINDOW_SECONDS)
        val park = detail(ContourReadout.CAPTION_ENGINE_GAVE) + g.DETAIL_AFTER_CAPTION +
            jura.width(ContourReadout.UNIT_KWH, Grid.DETAIL_SIZE) + g.DETAIL_AFTER_UNIT + figures("99,9", g.DETAIL_FIGURE) +
            g.DETAIL_PAIR_GAP + detail(ContourReadout.CAPTION_REGEN) + g.DETAIL_AFTER_CAPTION +
            jura.width(ContourReadout.UNIT_KWH, Grid.DETAIL_SIZE) + g.DETAIL_AFTER_UNIT + figures("99,9", g.DETAIL_FIGURE) +
            g.DETAIL_AFTER_FIGURE + g.DOT_ADVANCE
        val petal = g.petalRight(Trace.ZERO) - g.TRACE_FIGURE_X
        fun inPetal(number: String, unit: String) = cell(
            "the trace's figure and unit", "$number $unit",
            figures(number, Trace.FIGURE_SIZE) + Trace.UNIT_GAP + jura.width(unit, Trace.UNIT_SIZE), petal,
        )

        fit(
            listOf(
                cell("the pack's caption", ContourReadout.TITLE_PACK, jura.width(ContourReadout.TITLE_PACK, Grid.CAPTION_SIZE, Grid.CAPTION_TRACK), g.tempX(0) - left),
                cell("the spread's line", "${ContourReadout.CAPTION_SPREAD} 999 ${ContourReadout.UNIT_MILLIVOLT}", spreadLine, end - left),
                cell(
                    "the hero's unit", ContourReadout.UNIT_KW, jura.width(ContourReadout.UNIT_KW, Grid.HERO_UNIT_SIZE),
                    2 * min(g.AXIS - end, start - g.AXIS) - g.HERO_FIELD - Grid.HERO_UNIT_GAP,
                ),
                cell("the engine's heading", ContourReadout.TITLE_ENGINE_RPM, headingRpm, g.BOX_LEFT - start),
                cell("the sleeping engine's heading", ContourReadout.TITLE_ENGINE_MINUTES, headingMinutes, g.BOX_LEFT - start),
                cell("the trip's caption", trip, caption(trip), right - engineReach),
                cell("the trip's unit", ContourReadout.UNIT_KWH, g.tripPayload(figures("99,9", Grid.FIGURE_SIZE), unitKwh), right - engineReach),
                cell("the engine box's sentence", sentence, caption(sentence), right - g.BOX_LEFT),
                cell("the engine box's window", window, jura.width(window, Grid.DETAIL_SIZE, track), right - g.BOX_LEFT),
                cell("the park line", "${ContourReadout.CAPTION_ENGINE_GAVE} ${ContourReadout.CAPTION_REGEN}", park, right - start),
                inPetal("123,4", ContourReadout.UNIT_PER_100KM_FILLING),
                inPetal("-12,3", ContourReadout.UNIT_PER_100KM_FILLING),
                inPetal("123,4", ContourReadout.UNIT_PER_100KM),
                inPetal(ContourReadout.chargeLeft(ContourReadout.MAX_CHARGE_MINUTES), ContourReadout.UNIT_CHARGE_LEFT),
            ),
        )
    }

    /** Centred on the axis along the trace's zero, in place of the figure, inside the petal's chord. */
    @Test
    fun theClustersMessagesFitThePetal() {
        val g = ContourGeometry
        val chord = 2 * min(g.AXIS - g.petalLeft(Trace.ZERO), g.petalRight(Trace.ZERO) - g.AXIS)
        val messages = listOf(R.string.vehicle_closed_authorization, R.string.vehicle_closed_no_channel, R.string.vehicle_closed_no_answer)
        fit(messages.map { AppText.s(it) }.map { Cell("cluster message", "closed", it, jura.width(it, Trace.UNIT_SIZE), chord) })
    }

    // ------------------------------------------------------------------ the strip and the car page

    private val strip = StripGeometryMeasuredTest()

    private fun left(layout: TripPanelLayout) = TripPanelRenderer.box(layout).left

    private fun right(layout: TripPanelLayout) = TripPanelRenderer.box(layout).right

    /** A reading from its left edge to where the next thing on its line starts. */
    private inner class Reading(val what: String, val r: StripReading, val x: Float, val size: Float, val label: Float) {
        val width get() = strip.readingExtent(r, size, label)
        val text get() = listOfNotNull(r.caption, r.figure, r.unit).joinToString(" ")
    }

    /**
     * Each reading against what stands after it on its line. A reading set flush right has been placed by
     * its own width and says nothing against the edge; the one before it is the measure of that gap.
     */
    private fun line(surface: String, readings: List<Reading>, end: Float, temps: Float = Float.NaN): List<Cell> {
        val sorted = readings.sortedBy { it.x }
        return sorted.mapIndexed { i, it ->
            val next = listOfNotNull(sorted.getOrNull(i + 1)?.x, temps.takeIf { t -> t > it.x }).minOrNull() ?: end
            Cell(surface, it.what, it.text, it.width, next - it.x)
        }.filterNot { kotlin.math.abs(it.slack) < 0.01f }
    }

    @Test
    fun theStripsReadingsFitTheirLine() {
        val cells = mutableListOf<Cell>()
        val out = FloatArray(3)
        for (hint in listOf(false, true)) {
            val sound = strip.widestSound(hint)
            val whole = Head.Full.Strip
            StripGeometry.fullTrip(sound, right(TripPanelLayout.WIDE), FontMeasure.Strip, out)
            cells += line(
                "strip, full", (0 until sound.tripCount).map { Reading("reading $it", sound.trip[it], out[it], whole.VALUE_SIZE, whole.LABEL_SIZE) },
                right(TripPanelLayout.WIDE),
            )
            val two = Head.Two.Sound
            StripGeometry.twoTrip(sound, left(TripPanelLayout.MEDIUM), FontMeasure.Strip, out)
            cells += line(
                "strip, two thirds", (0 until sound.tripCount).map { Reading("reading $it", sound.trip[it], out[it], two.VALUE_SIZE, two.LABEL_SIZE) },
                right(TripPanelLayout.MEDIUM),
            )
            val width = right(TripPanelLayout.NARROW) - left(TripPanelLayout.NARROW)
            for (k in 0 until sound.tripCount) {
                val r = sound.trip[k]
                cells += Cell("strip, one third", "row $k", listOfNotNull(r.caption, r.figure, r.unit).joinToString(" "), strip.oneRowExtent(r), width)
            }
        }
        // A caption stops before its row's value column.
        for (word in listOf(StripReadings.ON_THE_ROAD, StripReadings.REMAINING, StripReadings.ALTITUDE, StripReadings.SUNSET, StripReadings.SUNRISE)) {
            cells += Cell("strip, one third", "caption", word, sans.width(word, Head.One.Sound.LABEL_SIZE), Head.One.Sound.VALUE_X)
        }
        fit(cells)
    }

    @Test
    fun theCarPagesReadingsFitTheirLine() {
        val cells = mutableListOf<Cell>()
        val model: StripModel = strip.widestCar()
        val at = StripGeometry.CarPlacement()

        val wide = TripPanelLayout.WIDE
        val full = Head.Full.Strip
        val fullCar = Head.Full.Car
        StripGeometry.fullCar(model, left(wide), right(wide), FontMeasure.Strip, at)
        cells += line(
            "car page, full",
            listOf(
                Reading("the volts", model.volts, at.volts, full.VALUE_SIZE, full.LABEL_SIZE),
                Reading("the power", model.power, at.power, fullCar.HERO_SIZE, full.LABEL_SIZE),
                Reading("the engine", model.engine, at.engine, full.VALUE_SIZE, full.LABEL_SIZE),
                Reading("the trip", model.tripCell, at.tripCell, full.VALUE_SIZE, full.LABEL_SIZE),
            ),
            right(wide),
            at.temps,
        )

        val medium = TripPanelLayout.MEDIUM
        val two = Head.Two.Car
        val twoLabel = Head.Two.Sound.LABEL_SIZE
        StripGeometry.twoCar(model, left(medium), FontMeasure.Strip, at)
        cells += line(
            "car page, two thirds",
            listOf(
                Reading("the power", model.power, at.power, two.HERO_SIZE, twoLabel),
                Reading("the engine", model.engine, at.engine, two.VALUE_SIZE, twoLabel),
                Reading("the trip", model.tripCell, at.tripCell, two.VALUE_SIZE, twoLabel),
            ),
            right(medium),
        )
        cells += line("car page, two thirds", listOf(Reading("the volts", model.volts, at.volts, two.VOLT_SIZE, twoLabel)), at.temps)

        val narrow = TripPanelLayout.NARROW
        val one = Head.One.Car
        val oneLabel = Head.One.Sound.LABEL_SIZE
        StripGeometry.oneCar(model, left(narrow), FontMeasure.Strip, at)
        cells += line("car page, one third", listOf(Reading("the power", model.power, at.power, one.HERO_SIZE, oneLabel)), right(narrow))
        cells += line(
            "car page, one third",
            listOf(
                Reading("the volts", model.volts, at.volts, one.ROW2_SIZE, oneLabel),
                Reading("the engine", model.engine, at.engine, one.ROW2_SIZE, oneLabel),
            ),
            right(narrow),
        )

        for ((layout, size) in listOf(
            wide to Head.Full.Car.CHART_CAPTION_SIZE,
            medium to Head.Full.Car.CHART_CAPTION_SIZE,
            narrow to Head.One.Car.CHART_CAPTION_SIZE,
        )) {
            val text = "${model.spendWord} ${model.spendFigure} ${model.spendWindow}"
            cells += Cell("car page", "the consumption line, $layout", text, sans.width(text, size), right(layout) - left(layout))
            cells += Cell("car page", "the closed caption, $layout", VehiclePageWords.CLOSED, sans.width(VehiclePageWords.CLOSED, oneLabel), right(layout) - left(layout))
        }
        fit(cells)
    }

    // ------------------------------------------------------------------ the HUD

    /** The panel's picture at a content width: `GlassDrawing`'s arithmetic, none of its drawing. */
    private inner class Glass(val width: Float, val windowPad: Float) {
        val k = width / G.VIEW[2]

        fun x(rect: FloatArray) = (rect[0] - G.VIEW[0]) * k

        fun w(rect: FloatArray) = rect[2] * k

        fun h(rect: FloatArray) = rect[3] * k

        fun label(word: String) = strong.width(word, G.Label.SIZE)

        val sign = x(G.SIGN) + w(G.SIGN) / 2
        val turn = (x(G.TURN) + x(G.DISTANCE) + w(G.DISTANCE)) / 2
        val map = x(G.MAP) + w(G.MAP) / 2
        val q = h(G.LANES) / G.Warning.HEIGHT
        val strip = G.Warning.WIDTH * q

        /** The middle of the lane window, which the camera's label centres on. */
        val cameras = x(G.LANES) + strip / 2

        fun cells(): List<Cell> {
            val surface = "glass at ${width.toInt()}"
            val signWord = AppText.s(R.string.hud_glass_sign)
            val turnWord = AppText.s(R.string.hud_glass_turn)
            val mapWord = AppText.s(R.string.hud_glass_map)
            val camerasWord = AppText.s(R.string.hud_glass_cameras)
            val distance = AppText.s(R.string.hud_glass_distance)
            val speed = AppText.s(R.string.hud_glass_unit_speed)
            val metres = AppText.s(R.string.hud_glass_unit_metres)
            val digits = G.Warning.DIGITS * q
            val tile = (G.Warning.TILE + G.Warning.GAP) * q
            val afterDigits = 2 * tile + strong.width("350", digits) + G.Warning.GAP * q / 2
            return listOf(
                Cell(surface, "the turn's label, from the left edge", turnWord, label(turnWord), 2 * turn),
                Cell(surface, "the turn's and the sign's labels", "$turnWord $signWord", (label(turnWord) + label(signWord)) / 2, sign - turn),
                Cell(surface, "the sign's and the map's labels", "$signWord $mapWord", (label(signWord) + label(mapWord)) / 2, map - sign),
                Cell(surface, "the map's label, to the right edge", mapWord, label(mapWord), 2 * (width - map)),
                Cell(surface, "the cameras' label, in the window", camerasWord, label(camerasWord), 2 * (cameras + windowPad)),
                Cell(surface, "the cameras' label, to the right edge", camerasWord, label(camerasWord), 2 * (width - cameras)),
                Cell(surface, "the speed's unit", speed, sans.width(speed, h(G.UNIT) * 0.72f), w(G.UNIT)),
                Cell(surface, "the turn's distance", distance, strong.width(distance, h(G.DISTANCE) * 0.62f), w(G.DISTANCE)),
                Cell(surface, "the lane's unit", metres, sans.width(metres, digits * G.Warning.UNIT), strip - afterDigits),
            )
        }

        /** How far the cameras' label stands past the glass's own left edge; below nothing, it is inside. */
        val overrun get() = label(AppText.s(R.string.hud_glass_cameras)) / 2 - cameras
    }

    /** The two widths the panel opens at: its 480 less 24 each side, a pane's 416 less 16. */
    private val glasses get() = listOf(Glass(GLASS_FULL, Sheet.Panel.PAD_X), Glass(GLASS_ONE_THIRD, Sheet.Compact.PAD_X))

    @Test
    fun theGlassPicturesWordsFit() {
        fit(glasses.flatMap { it.cells() })
    }

    /**
     * The label is centred on the lane window, near the glass's left edge, so it can be no wider than
     * twice that distance. The panel's row, «Камеры и опасности», would not fit; the label is shorter
     * in both languages, and this holds it inside the picture at both widths.
     */
    @Test
    fun theCamerasLabelAgainstTheGlasssLeftEdge() {
        glasses.forEach { assertTrue("at ${it.width}, the label stands ${it.overrun} past the glass", it.overrun <= 0f) }
    }

    /** `HudRadarPainter.lanes`: the digits shrink until tiles, digits and unit fill 246 at most. */
    @Test
    fun theRadarsDigitsNeverHaveToShrink() {
        val w = G.Warning
        val fixed = 2 * (w.TILE + w.GAP)
        val metres = AppText.s(R.string.hud_radar_unit_metres)
        val kilometres = AppText.s(R.string.hud_radar_unit_kilometres)
        val cases = listOf("990" to metres, figure("99,9") to kilometres, figure("1,2") to kilometres, "Ø 197" to "")
        fit(
            cases.map { (amount, unit) ->
                // Condensed has no file here: Roboto's medium stands over its bold and its regular over
                // its regular, so this is an upper bound. Where this fits, the loop leaves 46 alone.
                val caption = 2 + strong.width(amount, w.DIGITS) + 2 +
                    (if (unit.isEmpty()) 0f else RADAR_UNIT_GAP + sans.width(unit, w.DIGITS * w.UNIT))
                Cell("radar", "tiles, digits and unit", "$amount $unit".trim(), fixed + caption, w.WIDTH)
            },
        )
    }

    // ------------------------------------------------------------------ overlays

    /**
     * The check's line on the central screen: 18 dp, centred on a rectangle the firmware reports. That
     * rectangle is a whole screen, about a thousand dp, so what is asserted is the width nobody could
     * mistake for tight. The bold has no file here: Roboto's medium and five per cent.
     */
    @Test
    fun theSimulcastLineStaysShort() {
        val word = AppText.s(R.string.simulcast_checking_screens)
        fit(listOf(Cell("simulcast overlay", "the check's line", word, strong.width(word, SIMULCAST_SIZE) * 1.05f, SIMULCAST_ROOM)))
    }

    /** `SplitCrewScene`'s caption: 18 units centred on a 1280-unit scene, drawn in the system sans. */
    @Test
    fun theSplitCaptionFitsTheScene() {
        val word = AppText.s(R.string.split_launch_overlay_text)
        fit(listOf(Cell("split crew", "the caption", word, sans.width(word, SplitCrewScene.CAPTION_SIZE.toFloat()), SplitCrewScene.W.toFloat())))
    }

    // ------------------------------------------------------------------ the tiles

    /** The two lines of a tile: every name and state the app has, and every caption a producer can publish. */
    @Test
    fun theTileFacesFit() {
        val t = Head.Full.Tiles
        val words = wordsOf(AppText.language)
        val cells = mutableListOf<Cell>()
        words.filterKeys { it.startsWith("tile_") }.forEach { (key, value) ->
            val text = value.replace(Regex("%(\\d+\\$)?[sd]"), "99")
            cells += if (key.endsWith("_name")) {
                Cell("tile", "name", text, strong.width(text, t.NAME_SIZE), TILE_ROOM)
            } else {
                Cell("tile", "state", text, sans.width(text, t.STATUS_SIZE), TILE_ROOM)
            }
        }
        TileCaptionContractTest().captions().map { it.third }.toSet().forEach { caption ->
            cells += Cell("tile", "state", caption, sans.width(caption, t.STATUS_SIZE), TILE_ROOM)
        }
        fit(cells)
    }

    // ------------------------------------------------------------------ the panels' single lines

    private fun panelCells(): List<Cell> {
        val out = mutableListOf<Cell>()
        fun row(id: String, content: Float, rows: Map<*, *>) {
            val title = rows["title"] as String? ?: return
            if (rows["kind"] == "pair") {
                // A firmware's fingerprint is one word that breaks anywhere, and its slashes say so.
                val value = (rows["value"] as String).split(' ', '\n').filter { '/' !in it }
                    .maxByOrNull { sans.width(it, Sheet.KeyValue.SIZE) }.orEmpty()
                val key = sans.width(title, Sheet.KeyValue.SIZE)
                out += Cell(id, "pair", "$title $value", key + Sheet.KeyValue.GAP + sans.width(value, Sheet.KeyValue.SIZE), content - 2 * Sheet.Row.PAD_X)
            } else {
                val room = content - 2 * Sheet.Row.PAD_X - (Sheet.Switch.WIDTH + Sheet.Row.PAD_X)
                out += Cell(id, "row title", title, sans.width(title, Sheet.Row.TITLE_SIZE), room)
            }
        }

        fun block(id: String, content: Float, v: Any?) {
            when (v) {
                is List<*> -> v.forEach { block(id, content, it) }
                is Map<*, *> -> when (v["t"]) {
                    "group" -> (v["rows"] as List<*>).forEach { row(id, content, it as Map<*, *>) }
                    "switch" -> row(id, content, v)
                    "section" -> {
                        val label = v["label"] as String
                        out += Cell(id, "section label", label, strong.width(label, Sheet.Label.SIZE), content)
                        block(id, content, v["body"])
                    }
                    "segmented" -> {
                        val labels = v["labels"] as List<*>
                        val room = (content - 2 * Sheet.Segmented.PAD) / labels.size
                        labels.forEach { out += Cell(id, "segmented", it as String, strong.width(it, Sheet.Segmented.SIZE), room) }
                    }
                    "button" -> {
                        val text = v["text"] as String
                        val size = if (v["kind"] == "secondary") Sheet.Button.SECONDARY_SIZE else Sheet.Button.SIZE
                        out += Cell(id, "button", text, strong.width(text, size), content - 2 * Sheet.Row.PAD_X)
                    }
                    "stack" -> block(id, content, v["items"])
                    "apps" -> (v["items"] as List<*>).forEach { app(id, it) { name -> out += name } }
                    "appSections" -> (v["runs"] as List<*>).forEach { run ->
                        val r = run as Map<*, *>
                        val label = r["label"] as String
                        out += Cell(id, "section label", label, strong.width(label, Sheet.Label.SIZE), content)
                        (r["items"] as List<*>).forEach { app(id, it) { name -> out += name } }
                    }
                }
            }
        }

        SpecJson.fixtures.forEach { (id, pair) ->
            if (id.startsWith("scanner") || pair !is List<*>) return@forEach
            val board = pair[0] as Map<*, *>
            val fixture = pair[1] as? Map<*, *> ?: return@forEach
            val one = board["mode"] == "one"
            (fixture["sheet"] as? Map<*, *>)?.let { sheet ->
                val content = if (one) GLASS_ONE_THIRD else GLASS_FULL
                val title = sheet["title"] as String
                val lead = if (sheet["back"] == true) Sheet.Header.CLOSE + Sheet.Header.GLYPH_GAP else Sheet.Header.GLYPH + Sheet.Header.GLYPH_GAP
                out += Cell(id, "header", title, strong.width(title, Sheet.Header.TITLE_SIZE), content - lead - Sheet.Header.CLOSE)
                (sheet["subtitle"] as String?)?.let { out += Cell(id, "subtitle", it, sans.width(it, Sheet.Header.SUBTITLE_SIZE), content - lead) }
                block(id, content, sheet["blocks"])
                block(id, content, sheet["footer"])
            }
            (fixture["modal"] as? Map<*, *>)?.let { modal ->
                val m = Sheet.Modal
                val content = if (one) GLASS_ONE_THIRD else m.WIDTH - 2 * m.PAD
                val title = modal["title"] as String
                val size = if (one) m.COMPACT_TITLE_SIZE else m.TITLE_SIZE
                out += Cell(id, "modal title", title, strong.width(title, size), content - m.ICON - m.ICON_GAP)
                (modal["primary"] as String?)?.let { out += Cell(id, "modal button", it, strong.width(it, Sheet.Button.SIZE), content - 2 * Sheet.Row.PAD_X) }
                (modal["quiet"] as List<*>?)?.forEach { q ->
                    val text = (q as Map<*, *>)["text"] as String
                    out += Cell(id, "modal button", text, strong.width(text, Sheet.Button.SECONDARY_SIZE), content - 2 * Sheet.Row.PAD_X)
                }
            }
        }
        return out
    }

    private fun app(id: String, item: Any?, add: (Cell) -> Unit) {
        val name = (item as Map<*, *>)["name"] as String
        add(Cell(id, "app tile", name, strong.width(name, Sheet.Apps.NAME_SIZE), Sheet.Apps.TILE - 2 * NAME_INSET))
    }

    /**
     * The lines the boards never happen to show: a row or a button the code writes with a string key,
     * measured at the narrowest room it can open in.
     */
    private fun sourceCells(): List<Cell> {
        val words = wordsOf(AppText.language)
        val rowRoom = GLASS_ONE_THIRD - 2 * Sheet.Row.PAD_X - (Sheet.Switch.WIDTH + Sheet.Row.PAD_X)
        val out = mutableListOf<Cell>()
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, SOURCES) }.first { it.isDirectory }
        root.walkTopDown()
            .filter { it.extension == "kt" && "diagnostics" !in it.path }
            .forEach { file ->
                val source = file.readText()
                SOURCE_ROW.findAll(source).forEach { m ->
                    val text = words[m.groupValues[2]] ?: return@forEach
                    if ('%' in text) return@forEach
                    out += when (m.groupValues[1]) {
                        "PrimaryButton" -> Cell(file.name, "button in code", text, strong.width(text, Sheet.Button.SIZE), GLASS_ONE_THIRD - 2 * Sheet.Row.PAD_X)
                        "SecondaryButton" -> Cell(file.name, "button in code", text, strong.width(text, Sheet.Button.SECONDARY_SIZE), GLASS_ONE_THIRD - 2 * Sheet.Row.PAD_X)
                        "SheetHeader" -> Cell(file.name, "header in code", text, strong.width(text, Sheet.Header.TITLE_SIZE), GLASS_ONE_THIRD - Sheet.Header.CLOSE - Sheet.Header.GLYPH_GAP - Sheet.Header.GLYPH)
                        else -> Cell(file.name, "row title in code", text, sans.width(text, Sheet.Row.TITLE_SIZE), rowRoom)
                    }
                }
            }
        return out
    }

    @Test
    fun thePanelsSingleLinesFit() {
        val cells = panelCells() + sourceCells()
        val words = wordsOf(AppText.language)
        val cut = CUT_IN_BOTH_LANGUAGES.map { words.getValue(it) }.toSet()
        fit(cells.filterNot { it.what == "app tile" && it.text in cut })
    }

    /**
     * A row's summary takes two lines and then an ellipsis (`SheetRow`), so a long one loses its end.
     * Breaks as the board's `wrap` does: at a space, and after a hyphen or a slash before a letter.
     */
    private fun lines(text: String, size: Float, room: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        text.split(' ').forEach { word ->
            BREAK.split(word).filter { it.isNotEmpty() }.forEachIndexed { i, piece ->
                val next = if (line.isEmpty()) piece else line + (if (i == 0) " " else "") + piece
                if (line.isNotEmpty() && sans.width(next, size) > room) {
                    out += line
                    line = piece
                } else {
                    line = next
                }
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Every summary a board shows, at its width, and every one the code writes, at a pane's. */
    private fun summaries(): List<Triple<String, String, Float>> {
        val out = mutableListOf<Triple<String, String, Float>>()
        fun walk(id: String, room: Float, v: Any?) {
            when (v) {
                is List<*> -> v.forEach { walk(id, room, it) }
                is Map<*, *> -> {
                    (v["summary"] as? String)?.let { out += Triple(id, it, room) }
                    listOf("rows", "body", "items").forEach { walk(id, room, v[it]) }
                }
            }
        }
        SpecJson.fixtures.forEach { (id, pair) ->
            if (id.startsWith("scanner") || pair !is List<*>) return@forEach
            val sheet = (pair[1] as? Map<*, *>)?.get("sheet") as? Map<*, *> ?: return@forEach
            val content = if ((pair[0] as Map<*, *>)["mode"] == "one") GLASS_ONE_THIRD else GLASS_FULL
            walk(id, content - 2 * Sheet.Row.PAD_X - (Sheet.Switch.WIDTH + Sheet.Row.PAD_X), sheet["blocks"])
        }
        val words = wordsOf(AppText.language)
        val narrow = GLASS_ONE_THIRD - 2 * Sheet.Row.PAD_X - (Sheet.Switch.WIDTH + Sheet.Row.PAD_X)
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, SOURCES) }.first { it.isDirectory }
        root.walkTopDown().filter { it.extension == "kt" && "diagnostics" !in it.path }.forEach { file ->
            SOURCE_SUMMARY.findAll(file.readText()).forEach { m ->
                val text = words[m.groupValues[1]] ?: return@forEach
                if ('%' !in text) out += Triple(file.name, text, narrow)
            }
        }
        return out
    }

    @Test
    fun aRowsSummaryEndsInsideItsTwoLines() {
        val cut = CARS_OWN_WORDS
        val over = summaries().filter { (_, text, room) ->
            lines(text, Sheet.Row.SUMMARY_SIZE, room).size > 2 && cut.none { it in text }
        }
        assertTrue(over.joinToString("\n") { (id, text, _) -> "$id: «$text»" }, over.isEmpty())
    }

    /** «Wi-» at a line's end reads as a mistake, and every Android breaker allows it after the hyphen. */
    @Test
    fun noEnglishLineEndsInsideWiFi() {
        if (AppText.language != AppLanguage.ENGLISH) return
        val found = mutableListOf<String>()
        fun check(id: String, text: String, size: Float, room: Float) {
            if (lines(text, size, room).any { it.endsWith("Wi-") }) found += "$id: «$text»"
        }
        fun walk(id: String, content: Float, v: Any?) {
            when (v) {
                is List<*> -> v.forEach { walk(id, content, it) }
                is Map<*, *> -> {
                    if (v["t"] == "status") check(id, v["text"] as String, Sheet.Note.SIZE, content)
                    if (v["t"] == "note") check(id, v["text"] as String, Sheet.Note.SIZE, content)
                    (v["summary"] as? String)?.let {
                        check(id, it, Sheet.Row.SUMMARY_SIZE, content - 2 * Sheet.Row.PAD_X - (Sheet.Switch.WIDTH + Sheet.Row.PAD_X))
                    }
                    listOf("rows", "body", "items").forEach { walk(id, content, v[it]) }
                }
            }
        }
        SpecJson.fixtures.forEach { (id, pair) ->
            if (pair !is List<*>) return@forEach
            val sheet = (pair[1] as? Map<*, *>)?.get("sheet") as? Map<*, *> ?: return@forEach
            val one = (pair[0] as Map<*, *>)["mode"] == "one"
            // A board shows one width; the app can open any panel at the other.
            walk(id, if (one) GLASS_ONE_THIRD else GLASS_FULL, sheet["blocks"])
            walk(id, if (one) GLASS_FULL else GLASS_ONE_THIRD, sheet["blocks"])
        }
        assertTrue(found.distinct().joinToString("\n"), found.isEmpty())
    }

    // ------------------------------------------------------------------ glyphs

    /** A word's letters are in the face that draws it. ★ is the one the system sans has to borrow. */
    @Test
    fun everyLetterIsInTheFaceThatDrawsIt() {
        val words = wordsOf(AppText.language)
        val contour = words.filterKeys { it.startsWith("contour_") || it.startsWith("vehicle_closed_") }
        val inJura = contour.values.flatMap { jura.missing(it) }.toSet()
        assertEquals("what Jura has to borrow", emptySet<Char>(), inJura)
        for (face in listOf(sans, strong)) {
            val borrowed = words.filterValues { face.missing(it).isNotEmpty() }.keys
            assertEquals("the keys with a letter $face lacks", setOf("sheet_cluster_wheel_button"), borrowed)
        }
    }

    private companion object {
        /** Panel 480 less 24 each side; the pane's 416 less 16. */
        val GLASS_FULL = Sheet.Panel.WIDTH - 2 * Sheet.Panel.PAD_X
        val GLASS_ONE_THIRD = Head.One.WIDTH - 2 * Sheet.Compact.PAD_X

        /** The full screen less its margins, over the tiles and their gaps, less a tile's insets: 147.3. */
        const val TILE_ROOM = (Head.Full.WIDTH - 2 * Head.Full.MARGIN -
            (DenzaMetrics.Component.TILE_COLUMNS_WIDE - 1) * Head.Full.Tiles.GAP) /
            DenzaMetrics.Component.TILE_COLUMNS_WIDE - 2 * Head.Full.Tiles.TEXT_INSET

        /**
         * A copy of `HudRadarPainter.LANE_UNIT_GAP`, line 47. That class is package-private Java in
         * `feature/hud`, so this package cannot read it however the field is declared.
         */
        const val RADAR_UNIT_GAP = 2f

        /** A copy of the `dp(18)` in `SimulcastDialogOverlay.DrawView`'s constructor, line 1029; it has no name. */
        const val SIMULCAST_SIZE = 18f
        const val SIMULCAST_ROOM = 1000f

        const val SOURCES = "apps/denza-apps/src/main/java/dev/denza/apps"
        val SOURCE_ROW = Regex(
            """Denza(SwitchRow|SwitchLine|InfoRow|ChosenRow|ChoiceRow|PrimaryButton|SecondaryButton|SheetHeader)\(\s*(?:(?:title|text)\s*=\s*)?AppText\.s\(R\.string\.(\w+)\)""",
        )

        /**
         * Products' own names on a 96 dp app tile: wider than it in Russian and English alike, cut with
         * an ellipsis on both, and no wording of ours can make a product's name shorter.
         */
        val CUT_IN_BOTH_LANGUAGES = setOf("defaultapps_app_yandex_navigator", "defaultapps_app_yandex_music")

        val SOURCE_SUMMARY = Regex("""summary\s*=\s*AppText\.s\(R\.string\.(\w+)\)""")
        val BREAK = Regex("""(?<=[/-])(?=[^0-9])""")

        /**
         * The car's own warning names reach a row with the count in front, in the firmware's words, and
         * `one-sheet-service-warnings` shows the long one cut, in Russian and in English alike.
         */
        val CARS_OWN_WORDS = setOf("Состояние автомобиля")

        class Row(val slack: Float, val cells: List<String>)

        val measured = mutableMapOf<AppLanguage, MutableList<Row>>()

        fun wordsOf(language: AppLanguage): Map<String, String> {
            fun file(path: String) = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
                .map { File(it, path) }.first { it.isFile }
            fun read(path: String): Map<String, String> {
                val xml = StringsXml.parse(file(path).readText())
                return xml.strings + xml.plurals.flatMap { (key, forms) -> forms.map { (form, text) -> "$key#$form" to text } }
            }
            val russian = read("apps/denza-apps/src/main/res/values/strings.xml")
            if (language != AppLanguage.ENGLISH) return russian
            // A language has its own plural forms: Russian's few and many are no English string.
            val english = read("apps/denza-apps/src/main/res/values-en/strings.xml")
            val plurals = english.keys.filter { '#' in it }.map { it.substringBefore('#') }.toSet()
            return russian.filterKeys { it.substringBefore('#') !in plurals } + english
        }

        @JvmStatic
        @AfterClass
        fun report() {
            val folder = File("build/reports/drawn-words").apply { mkdirs() }
            synchronized(measured) {
                measured.forEach { (language, rows) ->
                    val lines = rows.sortedBy { it.slack }.map { it.cells.joinToString("\t") }.distinct()
                    File(folder, "${language.name.lowercase()}.tsv")
                        .writeText((listOf("surface\tcell\ttext\twidth\troom\tslack") + lines).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}

/** The same cells, measured in `values-en/`. */
class DrawnWordsFitEnglishTest : DrawnWordsFitTest() {
    @get:Rule
    val english = EnglishWords()
}
