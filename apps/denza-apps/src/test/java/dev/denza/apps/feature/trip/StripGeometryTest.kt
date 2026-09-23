package dev.denza.apps.feature.trip

import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.EnergyReadouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class StripGeometryTest {

    private val m = RobotoEstimate

    private fun left(layout: TripPanelLayout) = TripPanelRenderer.box(layout).left
    private fun right(layout: TripPanelLayout) = TripPanelRenderer.box(layout).right

    /** A route with ten hours on it, a peak's altitude climbing fast, and the longer sun word. */
    private fun widestSound(hint: Boolean = false) = StripModel().apply {
        title = "Midnight City"
        artist = "M83"
        trip[0].set(StripReadings.REMAINING, "10:42", StripReadings.roadLabel(99_940.0))
        if (hint) {
            trip[1].setHint(BaseTripRenderer.LOCATION_HINT)
        } else {
            trip[1].set(StripReadings.ALTITUDE, "8888", StripReadings.METRES, rate = "88,8")
        }
        trip[2].set(StripReadings.SUNRISE, "19:44")
        tripCount = 3
    }

    /**
     * The car at its widest: a charger named in the headline, three figures everywhere a figure is
     * three, a motor past a hundred degrees, the engine's longer heading over four figures of
     * revolutions, and a trip of a hundred kilowatt-hours under nine hundred kilometres.
     */
    private fun widestCar() = StripModel().apply {
        power.set(EnergyReadouts.WORD_FROM_CHARGER_SENTENCE, "888", ContourReadout.UNIT_KW, dot = true, blue = true)
        volts.set(VehiclePageWords.VOLTS, "888", VehiclePageWords.UNIT_V)
        engine.set(
            longer(EnergyReadouts.ENGINE_MINUTES_CAPTION, EnergyReadouts.ENGINE_RPM_CAPTION),
            "8888",
            longer(EnergyReadouts.ENGINE_RPM_UNIT, EnergyReadouts.ENGINE_MINUTES_UNIT),
        )
        tripCell.set("888" + EnergyReadouts.TRIP_KM_SENTENCE, "188,8", ContourReadout.UNIT_KWH)
        temps.forEach { it.set("888°", StripHeat.DANGER) }
        spendWord = VehiclePageWords.SPEND
        spendFigure = "-188,8"
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
        Span(what, x, x + StripGeometry.readingExtent(r, size, label, m))

    // ------------------------------------------------------------------------------ sound page

    @Test
    fun theFullScreensReadingsLeaveTheTitleItsRoom() {
        val layout = TripPanelLayout.WIDE
        val s = Head.Full.Strip
        for (hint in listOf(false, true)) {
            val model = widestSound(hint)
            val out = FloatArray(3)
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
            val out = FloatArray(3)
            StripGeometry.twoTrip(model, left(layout), m, out)
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
                assertTrue("row $k reaches ${StripGeometry.oneRowExtent(r, m)} of $width", StripGeometry.oneRowExtent(r, m) <= width)
                if (r.figure != null) {
                    val caption = m.sans(r.caption, s.LABEL_SIZE, false)
                    assertTrue("row $k's caption ($caption) reaches its value column", caption < s.VALUE_X)
                }
            }
        }
        // Every caption the rows can carry, not only the widest scene's.
        for (word in listOf(
            StripReadings.ON_THE_ROAD, StripReadings.REMAINING, StripReadings.ALTITUDE,
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
            Span("temperature $it", x, x + max(GLYPH_WIDTH, WideDigits.width(model.temps[it].figure!!, size)))
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
            reading("the trip", model.tripCell, at.tripCell, s.VALUE_SIZE, s.LABEL_SIZE),
        ) + temperatures(model, at.temps, c.TEMP_PITCH, c.TEMP_SIZE)
        assertApart("full car", spans, left(layout), right(layout))
        assertEquals("the trip is flush right", right(layout), at.tripCell + StripGeometry.readingWidth(model.tripCell, s.VALUE_SIZE, s.LABEL_SIZE, m), 1e-3f)
        // The board's own scene centres the power on the page, and the row's two halves are the
        // same width from it.
        assertEquals((left(layout) + right(layout)) / 2f, at.engine - c.SIDE, 1e-3f)
    }

    @Test
    fun theTwoThirdsCarRowsStandApart() {
        val layout = TripPanelLayout.MEDIUM
        val c = Head.Two.Car
        val label = Head.Two.Sound.LABEL_SIZE
        val model = widestCar()
        val at = StripGeometry.CarPlacement()
        StripGeometry.twoCar(model, left(layout), m, at)
        assertApart(
            "two-thirds car, first row",
            listOf(
                reading("the power", model.power, at.power, c.HERO_SIZE, label),
                reading("the engine", model.engine, at.engine, c.VALUE_SIZE, label),
                reading("the trip", model.tripCell, at.tripCell, c.VALUE_SIZE, label),
            ),
            left(layout), right(layout),
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
        val one = Head.One.STRIP_BOX.top
        assertTrue(Head.One.Sound.TRACK_CAPTION - Head.One.Sound.LABEL_SIZE >= one)
        assertTrue(Head.One.Car.CAPTION - Head.One.Sound.LABEL_SIZE >= one)
        assertTrue(Head.One.Car.VALUE - 0.71f * Head.One.Car.HERO_SIZE > Head.One.Car.CAPTION)
    }

    /**
     * The closed page's instruction, broken to the page's width at the title's size, stays above
     * the dots - the longest instruction this app prints, at its narrowest.
     */
    @Test
    fun theClosedPagesInstructionFitsUnderItsCaption() {
        val message = "ADB-ключ не подтверждён · Помощь → Диагностика"
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
        // The blue mark is outside the board's width and inside the extent.
        val marked = StripReading().apply { set("В батарею от ДВС", "14", "кВт", dot = true) }
        assertEquals(
            StripGeometry.readingWidth(marked, 56f, 17f, m) + StripGeometry.MARK_INDENT,
            StripGeometry.readingExtent(marked, 56f, 17f, m),
            1e-3f,
        )
    }

    companion object {
        /** A title keeps at least this much of the full screen beside the widest readings. */
        const val TITLE_ROOM_FLOOR = 400f

        /** The widest of the five glyphs (the pack's, with its terminal) and the tallest (a motor). */
        const val GLYPH_WIDTH = 24f
        const val GLYPH_HEIGHT = 23f
    }
}
