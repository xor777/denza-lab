package dev.denza.apps.feature.trip

import dev.denza.apps.R
import dev.denza.apps.core.AppDuration
import dev.denza.apps.core.AppText
import dev.denza.apps.core.EnglishWords
import dev.denza.apps.core.figure
import dev.denza.apps.design.luminofor.FontMeasure
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.cluster.dashboard.UNIT_PER_100KM_FILLING
import dev.denza.apps.feature.vehicle.TRIP_KM_SENTENCE
import dev.denza.apps.feature.vehicle.WORD_FROM_CHARGER_SENTENCE
import dev.denza.apps.ui.DashboardLayoutMode
import dev.denza.apps.ui.DashboardLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.max

/**
 * The strip's layout with the widest strings it can realistically be asked to print, in every
 * composition, without an Android text measurement.
 *
 * The board draws one scene per composition and every string in it was chosen; the car prints what
 * the car says. So this feeds [StripGeometry] - the same arithmetic the renderer lays the strip out
 * with - the widest of each reading ([widestSound], [widestCar]) and checks three things: no two
 * things on one line overlap, everything stands inside the strip box, and the analyser's columns
 * fit their width.
 *
 * **How a width is known here.** The wide figures are arithmetic ([WideDigits], every digit is 70
 * units, a digit is the widest glyph there is, so «888» is the widest three-figure reading). Roboto
 * is [RobotoEstimate]: a measured table, rounded up, plus eight per cent - so a layout that clears
 * here clears with room on the car.
 */
open class StripGeometryTest {

    internal open val m: StripGeometry.Measure = RobotoEstimate

    private fun left(layout: TripPanelLayout) = TripPanelRenderer.box(layout).left
    private fun right(layout: TripPanelLayout) = TripPanelRenderer.box(layout).right

    /**
     * Two days on the road at the widest two figures of hours, four figures of kilometres under the
     * longer road word, a peak's altitude climbing fast with its unit, and the longer sun word.
     */
    internal fun widestSound(hint: Boolean = false) = StripModel().apply {
        title = "Midnight City"
        artist = "M83"
        val d = AppDuration.of(12L * 3600L + 5L * 60L)
        trip[0].setDuration(longer(StripReadings.ON_THE_ROAD, StripReadings.REMAINING), d.first, d.firstUnit, d.second, d.secondUnit)
        trip[1].set(longer(StripReadings.DISTANCE, StripReadings.TO_GO), StripReadings.roadFigure(1_284_000.0), StripReadings.KILOMETRES)
        if (hint) {
            trip[2].setHint(BaseTripRenderer.LOCATION_HINT)
            tripCount = 3
        } else {
            trip[2].set(
                StripReadings.ALTITUDE, "8888", StripReadings.METRES, rate = figure("88,8"),
                rateUnit = StripReadings.METRES_PER_SECOND,
            )
            trip[3].set(StripReadings.SUNRISE, "19:44")
            tripCount = 4
        }
    }

    /**
     * The car at its widest: a charger named in the headline, three figures everywhere a figure is
     * three, a motor past a hundred degrees, the engine's longer heading over four figures of
     * revolutions, and a trip of a hundred kilowatt-hours under nine hundred kilometres.
     */
    internal fun widestCar() = StripModel().apply {
        power.set(EnergyReadouts.WORD_FROM_CHARGER_SENTENCE, "888", ContourReadout.UNIT_KW, dot = true, blue = true)
        volts.set(VehiclePageWords.VOLTS, "888", VehiclePageWords.UNIT_V)
        engine.set(
            longer(EnergyReadouts.ENGINE_MINUTES_CAPTION, EnergyReadouts.ENGINE_RPM_CAPTION),
            "8888",
            longer(EnergyReadouts.ENGINE_RPM_UNIT, EnergyReadouts.ENGINE_MINUTES_UNIT),
        )
        tripCell.set("888" + EnergyReadouts.TRIP_KM_SENTENCE, figure("188,8"), ContourReadout.UNIT_KWH)
        // The widest the row prints, −40° to 150°: a pack in a Siberian winter beside a motor at its
        // limit. Once a one hangs the two reach equally far.
        temps.forEachIndexed { i, it -> it.set(if (i % 2 == 0) "150°" else "-40°", StripHeat.DANGER) }
        spendWord = VehiclePageWords.SPEND
        spendFigure = figure("-188,8")
        spendWindow = ContourReadout.UNIT_PER_100KM_FILLING
    }

    private fun longer(a: String, b: String) = if (m.sans(a, 1f, false) >= m.sans(b, 1f, false)) a else b

    /** A span on one line, and whether it overlaps another. */
    private class Span(val what: String, val from: Float, val to: Float)

    private fun assertApart(line: String, spans: List<Span>, from: Float, to: Float) {
        val sorted = spans.sortedBy { it.from }
        sorted.forEach {
            assertTrue("$line: ${it.what} starts at ${it.from}, before the box's $from", it.from >= from - 1e-3f)
            assertTrue("$line: ${it.what} ends at ${it.to}, past the box's $to", it.to <= to + 1e-3f)
        }
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue("$line: ${a.what} (to ${a.to}) runs into ${b.what} (from ${b.from})", a.to <= b.from)
        }
    }

    private fun reading(what: String, r: StripReading, x: Float, size: Float, label: Float) =
        Span(what, x, x + readingExtent(r, size, label))

    /**
     * How far a reading actually reaches from its left edge: the board's `readingW`, which leaves
     * the blue mark out, with the mark's [StripGeometry.MARK_INDENT] put back in front of the caption.
     */
    internal fun readingExtent(r: StripReading, size: Float, label: Float): Float =
        max(StripGeometry.runWidth(r, size, m), (if (r.dot) StripGeometry.MARK_INDENT else 0f) + m.sans(r.caption, label, false))

    /**
     * How far a one-third row's reading reaches from the row's left edge, placed the way
     * `TripPanelRenderer.row` places it: the figure at the value column, the unit
     * [StripGeometry.ROW_UNIT_GAP] past it, a duration's second pair a pair's gap after that, the
     * rate's arrow [StripGeometry.ROW_RATE_OFFSET] past the unit, the rate
     * [StripGeometry.ROW_ARROW_ROOM] past the arrow and its unit [StripGeometry.ROW_RATE_UNIT_GAP]
     * past the rate.
     */
    internal fun oneRowExtent(r: StripReading): Float {
        val s = Head.One.Sound
        val figure = r.figure ?: return m.sans(r.caption, s.LABEL_SIZE, false)
        fun unit(u: String?) = if (u == null) 0f else StripGeometry.ROW_UNIT_GAP + m.sans(u, StripGeometry.ROW_SMALL, false)
        var ux = s.VALUE_X + WideDigits.width(figure, s.VALUE_SIZE) + unit(r.unit)
        r.figure2?.let { ux += s.VALUE_SIZE * Head.Reading.PAIR_GAP_RATIO + WideDigits.width(it, s.VALUE_SIZE) + unit(r.unit2) }
        val rate = r.rate ?: return ux
        var end = ux + StripGeometry.ROW_RATE_OFFSET + StripGeometry.ROW_ARROW_ROOM + WideDigits.width(rate, StripGeometry.ROW_SMALL)
        r.rateUnit?.let { end += StripGeometry.ROW_RATE_UNIT_GAP + m.sans(it, StripGeometry.ROW_SMALL, false) }
        return end
    }

    // ------------------------------------------------------------------------------ sound page

    @Test
    fun theFullScreensReadingsLeaveTheTitleItsRoom() {
        val layout = TripPanelLayout.WIDE
        val s = Head.Full.Strip
        for (hint in listOf(false, true)) {
            val model = widestSound(hint)
            val out = FloatArray(StripModel.TRIP_READINGS)
            val rowLeft = StripGeometry.fullTrip(model, right(layout), m, out)
            val spans = (0 until model.tripCount).map {
                reading("reading $it", model.trip[it], out[it], s.VALUE_SIZE, s.LABEL_SIZE)
            }
            assertApart("full sound${if (hint) " with the hint" else ""}", spans, left(layout), right(layout))
            assertEquals("the row keeps the right margin", right(layout), spans.maxOf { it.to }, 0.5f)

            val room = StripGeometry.fullTitleRoom(left(layout), right(layout), rowLeft, true)
            assertTrue("the title has $room dp at its widest readings", room >= TITLE_ROOM_FLOOR)
            // And the title, which is cut to that room, ends a reading gap short of the row.
            assertEquals(rowLeft - s.READING_GAP, left(layout) + room, 1e-3f)
            assertTrue("the artist has room after the play mark", room - s.LABEL_SIZE * TripPanelRenderer.ARTIST_INDENT > 0f)
        }
    }

    @Test
    fun theTwoThirdsReadingsFitTheirRow() {
        val layout = TripPanelLayout.MEDIUM
        val s = Head.Two.Sound
        for (hint in listOf(false, true)) {
            val model = widestSound(hint)
            val out = FloatArray(StripModel.TRIP_READINGS)
            StripGeometry.twoTrip(model, left(layout), right(layout), m, out)
            val spans = (0 until model.tripCount).map {
                reading("reading $it", model.trip[it], out[it], s.VALUE_SIZE, s.LABEL_SIZE)
            }
            assertApart("two-thirds sound", spans, left(layout), right(layout))
        }
    }

    @Test
    fun theOneThirdRowsFitTheirPane() {
        val layout = TripPanelLayout.NARROW
        val s = Head.One.Sound
        val width = right(layout) - left(layout)
        for (hint in listOf(false, true)) {
            val model = widestSound(hint)
            for (k in 0 until model.tripCount) {
                val r = model.trip[k]
                assertTrue("row $k reaches ${oneRowExtent(r)} of $width", oneRowExtent(r) <= width)
                if (r.figure != null) {
                    val caption = m.sans(r.caption, s.LABEL_SIZE, false)
                    assertTrue("row $k's caption ($caption) reaches its value column", caption < s.VALUE_X)
                }
            }
        }
        // Every caption the rows can carry, not only the widest scene's.
        for (word in listOf(
            StripReadings.ON_THE_ROAD, StripReadings.REMAINING, StripReadings.DISTANCE, StripReadings.TO_GO, StripReadings.ALTITUDE,
            StripReadings.SUNSET, StripReadings.SUNRISE,
        )) {
            assertTrue("«$word» reaches the value column", m.sans(word, s.LABEL_SIZE, false) < s.VALUE_X)
        }
    }

    /** The readings hang from the box's top and must clear the analyser that stands under them. */
    @Test
    fun theReadingsStandClearOfTheAnalyser() {
        // A figure's comma is its only descender, 0.12 of a cap under the baseline; a Roboto line's
        // descender is under a quarter of its size.
        fun descent(figure: Float, words: Float) = max(0.12f * 0.71f * figure, 0.25f * words)
        val full = Head.Full.Strip
        assertTrue(full.VALUE + descent(full.VALUE_SIZE, full.TITLE_SIZE) < full.SPECTRUM_TOP)
        val two = Head.Two.Sound
        assertTrue(two.TRACK_VALUE + descent(0f, two.TITLE_SIZE) < two.CAPTION - two.LABEL_SIZE)
        assertTrue(two.VALUE + descent(two.VALUE_SIZE, StripGeometry.unitSize(two.VALUE_SIZE)) < two.SPECTRUM_TOP)
        val one = Head.One.Sound
        val lastRow = one.ROWS_TOP + (StripModel.TRIP_READINGS - 1) * one.ROW_PITCH
        assertTrue(one.TRACK_VALUE + descent(0f, one.TITLE_SIZE) < one.ROWS_TOP - one.VALUE_SIZE * 0.71f)
        assertTrue(lastRow + descent(one.VALUE_SIZE, one.LABEL_SIZE) < one.SPECTRUM_TOP)
    }

    // -------------------------------------------------------------------------------- car page

    /** Five glyphs over five figures from [x0], [pitch] apart; a glyph is at most 24 wide. */
    private fun temperatures(model: StripModel, x0: Float, pitch: Float, size: Float): List<Span> =
        (0 until StripModel.TEMPERATURES).map {
            val x = x0 + it * pitch
            Span("temperature $it", x, x + max(GLYPH_WIDTH, WideDigits.extent(model.temps[it].figure!!, size)))
        }

    /** The trip, whose caption and run each end on [right]. */
    private fun trip(r: StripReading, caption: Float, run: Float, right: Float) =
        Span("the trip", minOf(caption, run), right)

    /**
     * At every width the row is drawn, the widest two readings stand twice as far apart as the
     * figures inside one, so a row of five never reads as one number.
     * (The cluster's row is held so in `ContourGeometryTest`.)
     */
    @Test
    fun theTemperaturesStandClearAtEveryWidth() {
        for ((name, pitch, size) in listOf(
            Triple("full", Head.Full.Car.TEMP_PITCH, Head.Full.Car.TEMP_SIZE),
            Triple("two-thirds", Head.Two.Car.TEMP_PITCH, Head.Two.Car.TEMP_SIZE),
            Triple("one-third", Head.One.Car.TEMP_PITCH, Head.One.Car.TEMP_SIZE),
        )) {
            val widest = WideDigits.extent(StripGeometry.TEMPERATURE_WIDEST, size)
            for (t in listOf("-40°", "-12°", "00°", "104°", "118°", "121°", "150°")) {
                assertTrue("$name: «$t» stays in its cell", WideDigits.extent(t, size) <= widest + 1e-4f)
            }
            val clear = pitch - widest
            // Ink to ink: from a «°» to a hung one's flag, against a one's stem to the next figure.
            // Twice the air of a reading's inside since 2026-11-19 (at 72 it was 1.4 times, and
            // «104° 118° 121°» read as one number).
            val k = WideDigits.k(size)
            val between = clear / k - 6f
            val inside = 70f - 44f + Digits.TRACK - WideDigits.stroke(size) / k
            assertTrue("$name: $between units between two readings, $inside inside one", between >= 2f * inside)
        }
    }

    @Test
    fun theFullScreensCarRowStandsApart() {
        val layout = TripPanelLayout.WIDE
        val s = Head.Full.Strip
        val c = Head.Full.Car
        val model = widestCar()
        val at = StripGeometry.CarPlacement()
        StripGeometry.fullCar(model, left(layout), right(layout), m, at)
        val spans = listOf(
            reading("the volts", model.volts, at.volts, s.VALUE_SIZE, s.LABEL_SIZE),
            reading("the power", model.power, at.power, c.HERO_SIZE, s.LABEL_SIZE),
            reading("the engine", model.engine, at.engine, s.VALUE_SIZE, s.LABEL_SIZE),
            trip(model.tripCell, at.tripCell, at.tripRun, right(layout)),
        ) + temperatures(model, at.temps, c.TEMP_PITCH, c.TEMP_SIZE)
        assertApart("full car", spans, left(layout), right(layout))
        assertEquals("the trip's caption ends on the margin", right(layout), at.tripCell + m.sans(model.tripCell.caption, s.LABEL_SIZE, false), 1e-3f)
        assertEquals("and so does its run", right(layout), at.tripRun + StripGeometry.runWidth(model.tripCell, s.VALUE_SIZE, m), 1e-3f)

        // Five slots, one gutter: the gutter is the same between every two.
        val slot = Head.Slots
        val gutter = StripGeometry.fullGutter(left(layout), right(layout))
        val temps = StripGeometry.temperaturesWidth(c.TEMP_PITCH, c.TEMP_SIZE)
        assertEquals(gutter, at.temps - (at.volts + slot.VOLTS), 1e-3f)
        assertEquals(gutter, at.power - (at.temps + temps), 1e-3f)
        assertEquals(gutter, at.engine - (at.power + slot.POWER), 1e-3f)
        assertEquals(gutter, right(layout) - slot.TRIP - (at.engine + slot.ENGINE), 1e-3f)
        assertTrue("a gutter of $gutter", gutter >= 40f)

        // Nothing moves with what a cell says: the coasting pack's «Батарея 1 кВт» stands where the
        // charger's sentence did.
        val calm = widestCar().apply { power.set(EnergyReadouts.Direction.NEUTRAL.sentence, "1", ContourReadout.UNIT_KW) }
        val there = StripGeometry.CarPlacement()
        StripGeometry.fullCar(calm, left(layout), right(layout), m, there)
        assertEquals(at.power, there.power, 0f)
        assertEquals(at.engine, there.engine, 0f)
        assertEquals(at.temps, there.temps, 0f)
    }

    /**
     * The widths this file holds are the widths the car hands the app, and no wider composition is
     * reached by a narrower window.
     *
     * The tests above lay each composition out on its board box. That is only the car's layout if
     * every measured window (`DashboardLayoutPolicy`: 416, 828, 1280) resolves to the composition
     * whose box is its own strip, and if the one that resolves to the full screen's car row has the
     * room that row needs - five slots and a gutter of at least 40, and a box tall enough for its
     * chart at the shape `wholeScreenPanelHeight` gives it. The policy's threshold between the two
     * wider windows is 1055 dp: a strip of 959 there would have neither, and no window the car sends
     * is there. A fourth measured window that resolved to the full screen would have to pass here.
     */
    @Test
    fun everyWindowTheCarHandsIsABoxThisFileHolds() {
        val windows = listOf(
            DashboardLayoutPolicy.NARROW_WIDTH_DP,
            DashboardLayoutPolicy.MEDIUM_WIDTH_DP,
            DashboardLayoutPolicy.WIDE_WIDTH_DP,
        )
        for (width in windows) {
            val mode = DashboardLayoutPolicy.resolve(width)
            assertEquals("$width: the window its mode was measured in", width, DashboardLayoutPolicy.windowWidth(mode))
            val layout = DashboardLayoutPolicy.panel(mode)
            val strip = width - 2f * DashboardLayoutPolicy.sideMargin(mode).value
            assertEquals("$width: the strip is its board box", right(layout) - left(layout), strip, 0.5f)
            if (layout != TripPanelLayout.WIDE) continue

            val c = Head.Full.Car
            val s = Head.Full.Strip
            val model = widestCar()
            val at = StripGeometry.CarPlacement()
            val right = left(layout) + strip
            StripGeometry.fullCar(model, left(layout), right, m, at)
            assertApart(
                "$width: full car",
                listOf(
                    reading("the volts", model.volts, at.volts, s.VALUE_SIZE, s.LABEL_SIZE),
                    reading("the power", model.power, at.power, c.HERO_SIZE, s.LABEL_SIZE),
                    reading("the engine", model.engine, at.engine, s.VALUE_SIZE, s.LABEL_SIZE),
                    trip(model.tripCell, at.tripCell, at.tripRun, right),
                ) + temperatures(model, at.temps, c.TEMP_PITCH, c.TEMP_SIZE),
                left(layout),
                right,
            )
            val gutter = StripGeometry.fullGutter(left(layout), right)
            assertTrue("$width: a gutter of $gutter", gutter >= 40f)
            val tall = DashboardLayoutPolicy.wholeScreenPanelHeight(strip).value
            val least = StripGeometry.minimumHeight(layout)
            assertTrue("$width: a strip $tall tall, under the composition's least $least", tall >= least)
        }
        assertEquals(
            "one window is the full screen",
            listOf(DashboardLayoutPolicy.WIDE_WIDTH_DP),
            windows.filter { DashboardLayoutPolicy.resolve(it) == DashboardLayoutMode.WIDE },
        )
    }

    @Test
    fun theTwoThirdsCarRowsStandApart() {
        val layout = TripPanelLayout.MEDIUM
        val c = Head.Two.Car
        val label = Head.Two.Sound.LABEL_SIZE
        val model = widestCar()
        val at = StripGeometry.CarPlacement()
        StripGeometry.twoCar(model, left(layout), right(layout), m, at)
        assertApart(
            "two-thirds car, first row",
            listOf(
                reading("the power", model.power, at.power, c.HERO_SIZE, label),
                reading("the engine", model.engine, at.engine, c.VALUE_SIZE, label),
                trip(model.tripCell, at.tripCell, at.tripRun, right(layout)),
            ),
            left(layout), right(layout),
        )
        // The row spans the pane: the power on the left margin, the trip ending on the right, and
        // the temperatures under the engine.
        assertEquals(left(layout), at.power, 0f)
        assertEquals(right(layout), at.tripRun + StripGeometry.runWidth(model.tripCell, c.VALUE_SIZE, m), 1e-3f)
        assertEquals(at.engine, at.temps, 0f)
        val slot = Head.Slots
        assertEquals(
            "the engine a gutter from each",
            at.engine - (at.power + slot.POWER),
            right(layout) - slot.TRIP - (at.engine + slot.ENGINE),
            1e-3f,
        )
        assertApart(
            "two-thirds car, second row",
            listOf(reading("the volts", model.volts, at.volts, c.VOLT_SIZE, label)) +
                temperatures(model, at.temps, c.TEMP_PITCH, c.TEMP_SIZE),
            left(layout), right(layout),
        )
    }

    @Test
    fun theOneThirdCarRowsStandApart() {
        val layout = TripPanelLayout.NARROW
        val c = Head.One.Car
        val label = Head.One.Sound.LABEL_SIZE
        val model = widestCar()
        val at = StripGeometry.CarPlacement()
        StripGeometry.oneCar(model, left(layout), m, at)
        assertApart(
            "one-third car, the power",
            listOf(reading("the power", model.power, at.power, c.HERO_SIZE, label)),
            left(layout), right(layout),
        )
        assertApart(
            "one-third car, the volts and the engine",
            listOf(
                reading("the volts", model.volts, at.volts, c.ROW2_SIZE, label),
                reading("the engine", model.engine, at.engine, c.ROW2_SIZE, label),
            ),
            left(layout), right(layout),
        )
        assertApart(
            "one-third car, the temperatures",
            temperatures(model, at.temps, c.TEMP_PITCH, c.TEMP_SIZE),
            left(layout), right(layout),
        )
        assertTrue("the one-third pane has no trip cell", at.tripCell.isNaN())
    }

    @Test
    fun theConsumptionLineFitsEveryWidth() {
        val model = widestCar()
        for ((layout, size) in listOf(
            TripPanelLayout.WIDE to Head.Full.Car.CHART_CAPTION_SIZE,
            TripPanelLayout.MEDIUM to Head.Full.Car.CHART_CAPTION_SIZE,
            TripPanelLayout.NARROW to Head.One.Car.CHART_CAPTION_SIZE,
        )) {
            val line = m.sans("${model.spendWord} ${model.spendFigure} ${model.spendWindow}", size, false)
            assertTrue("$layout: the consumption line is $line", line <= right(layout) - left(layout))
        }
    }

    // -------------------------------------------------------------------------- the box itself

    @Test
    fun everyCompositionFitsItsOwnBox() {
        for (layout in TripPanelLayout.entries) {
            val box = TripPanelRenderer.box(layout)
            val height = box.bottom - box.top
            val least = StripGeometry.minimumHeight(layout)
            assertTrue("$layout: the board's box ($height) is under the strip's least ($least)", height >= least)
            // The dots are the lowest thing on either page; the analyser's floor stands above them.
            val dots = TripPanelRenderer.dotsY(layout)
            assertTrue("$layout: the dots are past the box's foot", dots + TripPanelRenderer.DOT_RADIUS <= box.bottom)
        }
        assertTrue(Head.Full.Strip.FLOOR < Head.Full.Strip.DOTS_Y)
        assertTrue(Head.Two.Sound.FLOOR < Head.Two.DOTS_Y)
        assertTrue(Head.One.Sound.FLOOR < Head.One.DOTS_Y)
    }

    /** The first line on every page stands inside the box's top: a caption's cap is under its size. */
    @Test
    fun theTopLineOfEveryPageIsInsideTheBox() {
        val full = Head.Full.STRIP_BOX.top
        assertTrue(Head.Full.Strip.CAPTION - Head.Full.Strip.LABEL_SIZE >= full)
        // The glyphs stand 23 above their base, which is five under the caption line.
        assertTrue(Head.Full.Strip.CAPTION + VehiclePageRenderer.GLYPH_DROP - GLYPH_HEIGHT >= full)
        val two = Head.Two.STRIP_BOX.top
        assertTrue(Head.Two.Sound.TRACK_CAPTION - Head.Two.Sound.LABEL_SIZE >= two)
        assertTrue(Head.Two.Car.CAPTION - Head.Two.Sound.LABEL_SIZE >= two)
        assertTrue(Head.Two.Car.VALUE - 0.71f * Head.Two.Car.HERO_SIZE > Head.Two.Car.CAPTION)
        // The hero's caption stands above the line by the two figures' difference in cap, so its
        // figure keeps the others' distance from its caption - and the caption stays in the box.
        for ((caption, heroCaption, value, hero, cell) in listOf(
            listOf(Head.Full.Strip.CAPTION, Head.Full.Car.HERO_CAPTION, Head.Full.Strip.VALUE, Head.Full.Car.HERO_SIZE, Head.Full.Strip.VALUE_SIZE),
            listOf(Head.Two.Car.CAPTION, Head.Two.Car.HERO_CAPTION, Head.Two.Car.VALUE, Head.Two.Car.HERO_SIZE, Head.Two.Car.VALUE_SIZE),
        )) {
            assertEquals(value - 0.71f * cell - caption, value - 0.71f * hero - heroCaption, 0.5f)
        }
        assertTrue(Head.Full.Car.HERO_CAPTION - Head.Full.Strip.LABEL_SIZE >= full)
        assertTrue(Head.Two.Car.HERO_CAPTION - Head.Two.Sound.LABEL_SIZE >= two)
        val one = Head.One.STRIP_BOX.top
        assertTrue(Head.One.Sound.TRACK_CAPTION - Head.One.Sound.LABEL_SIZE >= one)
        assertTrue(Head.One.Car.CAPTION - Head.One.Sound.LABEL_SIZE >= one)
        assertTrue(Head.One.Car.VALUE - 0.71f * Head.One.Car.HERO_SIZE > Head.One.Car.CAPTION)
    }

    /**
     * The closed page's state, broken to the page's width at the title's size, stays above the
     * dots - the longest of the three it can say, at its narrowest.
     */
    @Test
    fun theClosedPagesStateFitsUnderItsCaption() {
        val message = listOf(R.string.car_page_no_access, R.string.car_page_no_link, R.string.car_page_no_answer)
            .map { AppText.s(it) }
            .maxBy { m.sans(it, 1f, false) }
        for ((layout, value, size) in listOf(
            Triple(TripPanelLayout.WIDE, Head.Full.Strip.VALUE, Head.Full.Strip.TITLE_SIZE),
            Triple(TripPanelLayout.MEDIUM, Head.Two.Car.VALUE, Head.Two.Sound.TITLE_SIZE),
            Triple(TripPanelLayout.NARROW, Head.One.Car.VALUE, Head.One.Sound.TITLE_SIZE),
        )) {
            val room = right(layout) - left(layout)
            var lines = 1
            var line = ""
            message.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || m.sans(candidate, size, false) <= room) line = candidate else {
                    lines++
                    line = word
                }
            }
            val last = value + (lines - 1) * size * VehiclePageRenderer.MESSAGE_LEADING
            assertTrue("$layout: $lines lines end at $last", last + size < TripPanelRenderer.dotsY(layout))
        }
    }

    // ------------------------------------------------------------------------------- analyser

    @Test
    fun theColumnsFitTheirWidthWithTheirGlowApart() {
        val sp = Head.Spectrum
        for ((layout, bars) in listOf(
            TripPanelLayout.WIDE to Head.Full.Strip.BARS,
            TripPanelLayout.MEDIUM to Head.Two.Sound.BARS,
            TripPanelLayout.NARROW to Head.One.Sound.BARS,
        )) {
            val width = right(layout) - left(layout)
            val gap = (width - bars * sp.BAR_WIDTH) / (bars - 1)
            assertTrue("$layout: $bars columns leave a gap of $gap", gap >= 2f * sp.GLOW_PAD)
            // The lines inside a column: five of two dp, spread over its width.
            val lines = Math.round(sp.BAR_WIDTH / sp.LINE_PITCH)
            assertEquals(5, lines)
            assertTrue((sp.BAR_WIDTH - lines * sp.LINE_WIDTH) / (lines - 1) > 0f)
            // A glow and the haze may stand past the box by their own few dp, never past the view's
            // overhang (see `SpectrumPanel`).
            assertTrue(sp.GLOW_PAD <= TripPanelView.OVERHANG_DP)
        }
        // The chart's end dot stands on the box's right edge; the dot and its glow stand past it by
        // no more than the view's overhang.
        assertTrue(Head.Chart.DOT + VehiclePageRenderer.END_BLUR <= TripPanelView.OVERHANG_DP)
    }

    @Test
    fun aPaneSamplesTheBandsTheBoardsWay() {
        val bands = SpectrumSource.BAND_COUNT
        assertEquals(36, bands)
        for (n in listOf(Head.Full.Strip.BARS, Head.Two.Sound.BARS, Head.One.Sound.BARS)) {
            val picked = (0 until n).map { SpectrumRenderer.sample(it, n, bands) }
            assertEquals("the first column is the first band", 0, picked.first())
            assertEquals("the last column is the last band", bands - 1, picked.last())
            assertTrue("columns never go back: $picked", picked.zipWithNext().all { (a, b) -> b > a })
        }
        assertEquals((0 until 36).toList(), (0 until 36).map { SpectrumRenderer.sample(it, 36, bands) })
        // The board's own arithmetic for the two panes: Math.round(i * 35 / (n - 1)).
        assertEquals(listOf(0, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18, 20, 21, 23, 24, 26, 27, 29, 30, 32, 33, 35),
            (0 until 24).map { SpectrumRenderer.sample(it, 24, bands) })
        assertEquals(listOf(0, 3, 6, 10, 13, 16, 19, 22, 25, 29, 32, 35),
            (0 until 12).map { SpectrumRenderer.sample(it, 12, bands) })
    }

    @Test
    fun theReadingArithmeticIsTheBoards() {
        // `Math.round(size * 0.42)` and `Math.round(size * 0.45)`, sizes the board actually uses.
        assertEquals(19f, StripGeometry.unitSize(46f))
        assertEquals(24f, StripGeometry.unitSize(56f))
        assertEquals(18f, StripGeometry.unitSize(44f))
        assertEquals(14f, StripGeometry.unitSize(34f))
        assertEquals(12f, StripGeometry.unitSize(28f))
        assertEquals(21f, StripGeometry.rateSize(46f))
        // A figure that is not there takes its unit and its rate with it: the width is the caption.
        val absent = StripReading().apply { set("Напряжение", null, "В", rate = "1,2") }
        assertEquals(
            m.sans("Напряжение", 17f, false),
            StripGeometry.readingWidth(absent, 46f, 17f, m),
            1e-4f,
        )
    }

    companion object {
        /**
         * A title keeps at least this much of the full screen beside the widest readings. Four
         * readings since time and road parted - «12 ч 05 мин», «1284 км», a peak at 8888 м climbing
         * 88,8 м/с, the sunrise - leave it 282 at their widest, where three left it 400: still a
         * title and its artist whole, and on a usual trip a good deal more.
         */
        const val TITLE_ROOM_FLOOR = 280f

        /** The widest of the five glyphs (the pack's, with its terminal) and the tallest (a motor). */
        const val GLYPH_WIDTH = 24f
        const val GLYPH_HEIGHT = 23f
    }
}

/** Every composition with its widest English words, on the same over-estimate. */
class StripGeometryEnglishTest : StripGeometryTest() {
    @get:Rule
    val english = EnglishWords()
}

/** And on Roboto's own advances: what the estimate over-states, the font does not. */
class StripGeometryMeasuredTest : StripGeometryTest() {
    override val m: StripGeometry.Measure = FontMeasure.Strip
}

/** Both of the above. */
class StripGeometryMeasuredEnglishTest : StripGeometryTest() {
    override val m: StripGeometry.Measure = FontMeasure.Strip

    @get:Rule
    val english = EnglishWords()
}
