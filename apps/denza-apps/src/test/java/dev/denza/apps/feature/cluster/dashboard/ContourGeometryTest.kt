package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.EnergyScale
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Band
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineBox
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.Silhouette
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.EngineTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Luminofor Contour's geometry: that its numbers are the contract's, and that it fits.
 *
 * `LuminoforSpecContractTest` holds [dev.denza.apps.design.luminofor.LuminoforSpec] to spec.json
 * value by value. This holds the few spec values that are *also* somebody else's record - the
 * trace's two ceilings, its hundred points, the engine box's span, the band's two spans - to that
 * record, and then asks the question a value-by-value test cannot: whether the widest thing each
 * group can print stays inside the group.
 *
 * ### How the words are measured
 *
 * Figures are `WideDigits`, which is arithmetic. Words are Jura, measured by [JuraMeasure] out of
 * the TTF the app ships - the kerned or unkerned run, whichever is wider. The margins asserted
 * are the board's own, with nothing added: a string that fits by a unit fits.
 */
class ContourGeometryTest {

    private val g = ContourGeometry

    private fun figures(text: String, size: Float) = WideDigits.width(text, size)

    // ---------------------------------------------------------------- one record

    @Test
    fun theTracesLadderIsTheContractsLadder() {
        // One chart on both screens, on one ladder (`docs/energy-display-contract.md` §2.3).
        assertEquals(ContourPlan.PETAL_FULL, Trace.UP_TO, 0f)
        assertEquals(ContourPlan.PETAL_RETURN_FULL, Trace.DOWN_TO, 0f)
        assertEquals(60.0, SpecJson.num("cluster", "trace", "upTo"), 0.0)
        assertEquals(20.0, SpecJson.num("cluster", "trace", "downTo"), 0.0)
        assertEquals("a point per hundred metres of the window", ConsumptionChart.POINTS, Trace.POINTS)
        assertEquals(ContourPlan.PETAL_POINTS, Trace.POINTS)
    }

    @Test
    fun theTraceIsTheFiguresOwnThreeLines() {
        // «ноль должен быть у цифры»: zero on the figure's baseline, spending up its cap, a return
        // down a descender. The spec writes the cap out as 327.08; this is where it comes from.
        assertEquals(Trace.ZERO - Digits.CAP_RATIO * Trace.FIGURE_SIZE, Trace.TOP, 1e-3f)
        assertEquals(Grid.FIGURE_SIZE, Trace.FIGURE_SIZE, 0f)
        assertEquals(Trace.ZERO, level(0f), 0f)
    }

    @Test
    fun theEngineBoxIsTheTracesTwoMinutesOnTheReadoutsSpan() {
        assertEquals(ContourReadout.GENERATION_FULL_KW.toFloat(), EngineBox.UP_TO, 0f)
        assertEquals("two minutes of five-second steps", 24, g.ENGINE_BINS)
        assertEquals(EngineTrace.SLOTS, g.ENGINE_BINS * EngineTrace.BIN_SECONDS)
        assertEquals(g.ENGINE_BINS, ContourFrame.GENERATION_BINS)
        assertEquals(EngineBox.WIDTH, g.BOX_PITCH * g.ENGINE_BINS, 1e-3f)
        // The box is the figures' own height, on their baseline.
        assertEquals(Grid.BASELINE, g.BOX_ZERO, 0f)
        assertEquals(Digits.CAP_RATIO * Grid.FIGURE_SIZE, g.BOX_ZERO - g.BOX_TOP, 1e-3f)
        assertEquals(g.BOX_ZERO, g.boxY(0f), 0f)
        assertEquals("a zero or a hole draws nothing above the base", g.BOX_ZERO, g.boxY(-4f), 0f)
        assertEquals(g.BOX_TOP, g.boxY(30f), 1e-4f)
        assertEquals("clamped rather than open-topped", g.BOX_TOP, g.boxY(55f), 1e-4f)
    }

    @Test
    fun theBandSweepsTheEnergyScalesTwoSpans() {
        assertEquals(EnergyScale.FULL_DISCHARGE_KW, Band.OUT_KW, 0f)
        assertEquals(EnergyScale.FULL_REGEN_KW, Band.IN_KW, 0f)
    }

    @Test
    fun theDisplayIsTheBoardsSpace() {
        // 1507.56 × 424 units laid over 2560 × 720 pixels, one scale both ways.
        assertEquals(Cluster.DISPLAY_W.toFloat() / Cluster.DISPLAY_H, Cluster.W / Cluster.H, 1e-4f)
        assertEquals(Cluster.W / 2f, g.AXIS, 0f)
    }

    // ---------------------------------------------------------------- the band and the trace

    @Test
    fun theBandLeavesZeroOnTheSideTheEnergyIsGoing() {
        assertEquals(0f, g.reach(0f), 0f)
        assertTrue("pulling reaches right", g.reach(40f) > 0f)
        assertTrue("braking reaches left", g.reach(-40f) < 0f)
        // Three hundred out against a hundred back: the same forty kilowatts reach further braking.
        assertTrue(-g.reach(-40f) > g.reach(40f))
        // Clamped at the margins, so nothing the pack reports leaves the glass.
        assertEquals(g.AXIS - Cluster.MARGIN, g.reach(600f), 1e-3f)
        assertEquals(-(g.AXIS - Cluster.MARGIN), g.reach(-600f), 1e-3f)
    }

    @Test
    fun theTraceIsOneFixedLadderClampedAtBothEnds() {
        assertEquals(Trace.TOP, level(60f), 1e-4f)
        assertEquals("and stays there past it", Trace.TOP, level(144f), 1e-4f)
        assertEquals(Trace.DROP, level(-20f), 1e-4f)
        assertEquals(Trace.DROP, level(-60f), 1e-4f)
        assertEquals((Trace.ZERO + Trace.TOP) / 2f, level(30f), 1e-3f)
        assertEquals((Trace.ZERO + Trace.DROP) / 2f, level(-10f), 1e-3f)
        // 60 over 37 units and 20 over 13 are one slope either side, so the line crosses the zero
        // without a kink (contract §2.3).
        val up = Trace.UP_TO / (Trace.ZERO - Trace.TOP)
        val down = Trace.DOWN_TO / (Trace.DROP - Trace.ZERO)
        assertEquals(up, down, 0.1f)
    }

    @Test
    fun aFillingWindowGrowsFromTheRightEdgeAtTheHundredPointPitch() {
        val at = { i: Int, n: Int -> Silhouette.x(i, n, g.TRACE_RIGHT, g.TRACE_PITCH) }
        // A full window runs edge to edge, the newest point on the right edge.
        assertEquals(g.TRACE_LEFT, at(0, Trace.POINTS), 1e-3f)
        assertEquals(g.TRACE_RIGHT, at(Trace.POINTS - 1, Trace.POINTS), 0f)
        // A filling one stands at the same pitch from the right: «за 3,7 км» is 37 per cent of the
        // road and of the box, to a pitch.
        assertEquals(g.TRACE_RIGHT, at(36, 37), 0f)
        assertEquals(36f / 99f * Trace.WIDTH, g.TRACE_RIGHT - at(0, 37), 1e-3f)
    }

    @Test
    fun theRunsBrightenTowardThePresent() {
        val runs = (0 until Trace.RUNS).map { g.runIntensity(it) }
        assertEquals(runs.sorted(), runs)
        assertEquals("the newest run is at full strength", 1f, runs.last(), 1e-6f)
        assertTrue("and the oldest is still there", runs.first() > 0.28f)
    }

    // ---------------------------------------------------------------- it fits

    @Test
    fun theLeftGroupsWidestPrintStaysInsideIt() {
        val left = g.GROUP_LEFT
        val end = g.GROUP_LEFT_END
        val firstCell = g.tempX(0)
        // «БАТАРЕЯ · В» and three-digit volts both end before the first glyph starts.
        val caption = JuraMeasure.width(ContourReadout.TITLE_PACK, Grid.CAPTION_SIZE, Grid.CAPTION_TRACK)
        assertTrue("the caption ends at ${left + caption}, the glyphs start at $firstCell", left + caption < firstCell)
        val volts = figures("888", Grid.FIGURE_SIZE)
        assertTrue("three-digit volts end at ${left + volts}", left + volts < firstCell)
        // Five two-digit temperatures end on the group's edge, never past it. Every digit is one
        // advance, so «00°» is every two-digit reading; a winter minus is narrower still.
        val cell = figures("00°", Grid.TEMP_SIZE)
        assertEquals("flush with the edge", end, g.tempX(ContourFrame.CELLS - 1) + cell, 1e-3f)
        assertTrue(figures("-9°", Grid.TEMP_SIZE) <= cell)
        assertEquals(
            "and the «°» drawn apart lands where the run would put it",
            g.tempX(4) + cell - figures("°", Grid.TEMP_SIZE),
            g.degreeX(g.tempX(4), "00"),
            1e-3f,
        )
        // The spread's line, at its widest: a three-digit spread is a pack that is failing.
        val spread = JuraMeasure.width(ContourReadout.CAPTION_SPREAD, Grid.DETAIL_SIZE, Grid.DETAIL_TRACK) +
            g.SPREAD_CAPTION_GAP + figures("999", g.DETAIL_FIGURE) + g.SPREAD_UNIT_GAP +
            JuraMeasure.width(ContourReadout.UNIT_MILLIVOLT, Grid.DETAIL_SIZE)
        assertTrue("the spread ends at ${left + spread}", left + spread < end)
    }

    @Test
    fun theHeroStandsBetweenTheTwoGroupsCentredOnTheAxis() {
        val unit = JuraMeasure.width(ContourReadout.UNIT_KW, Grid.HERO_UNIT_SIZE)
        val fieldRight = g.heroFieldRight(unit)
        val fieldLeft = fieldRight - g.HERO_FIELD
        val unitRight = fieldRight + Grid.HERO_UNIT_GAP + unit
        assertEquals("one group on the axis", g.AXIS, (fieldLeft + unitRight) / 2f, 1e-3f)
        assertTrue("the field starts at $fieldLeft", fieldLeft > g.GROUP_LEFT_END)
        assertTrue("«кВт» ends at $unitRight", unitRight < g.GROUP_RIGHT_START)
        // Three digits is what the field is: 196 kW fills it exactly and a launch moves nothing.
        assertEquals(g.HERO_FIELD, figures("196", Grid.HERO_SIZE), 1e-4f)
    }

    @Test
    fun theRightGroupsWidestPrintStaysInsideIt() {
        val start = g.GROUP_RIGHT_START
        val end = g.GROUP_RIGHT_END
        val track = Grid.CELL_CAPTION_TRACK
        // The engine's cell at the group's left edge, at its widest: the sleeping engine's heading
        // and four-digit revolutions.
        val iceCaption = start + JuraMeasure.width(ContourReadout.TITLE_ENGINE_MINUTES, Grid.CAPTION_SIZE, track)
        val iceFigure = start + figures("8888", Grid.FIGURE_SIZE)

        // The trip flush right, at the widest its two lines get.
        val unit = JuraMeasure.width(ContourReadout.UNIT_KWH, Grid.UNIT_SIZE)
        for (km in listOf("42", "999")) {
            val caption = "$km ${ContourReadout.UNIT_KM} ${ContourReadout.CAPTION_TRIP}"
            val payload = g.tripPayload(figures("99,9", Grid.FIGURE_SIZE), unit)
            val tripLeft = g.tripLeft(JuraMeasure.width(caption, Grid.CAPTION_SIZE, track), payload)
            assertTrue("«$caption» starts at $tripLeft, inside the group", tripLeft > start)
            assertTrue("and clear of the engine's heading at $iceCaption", tripLeft > iceCaption)
            assertTrue("and of its revolutions at $iceFigure", tripLeft > iceFigure)
            assertTrue("and the payload ends on the margin", tripLeft + payload <= end + 1e-3f)
        }

        // The engine's box in the trip's place: its sentence and its window inside the box.
        assertTrue("the box starts at ${g.BOX_LEFT}", g.BOX_LEFT > iceCaption && g.BOX_LEFT > iceFigure)
        val sentence = "${ContourReadout.LEGEND_PREFIX} 99 ${ContourReadout.UNIT_KW}"
        assertTrue(g.BOX_LEFT + JuraMeasure.width(sentence, Grid.CAPTION_SIZE, track) < end)
        val window = ContourReadout.intoPack(ContourReadout.MAX_WINDOW_SECONDS, short = false)
        assertTrue(g.BOX_LEFT + JuraMeasure.width(window, Grid.DETAIL_SIZE, track) < end)

        // The park line, right to left, with both seats and three-digit figures in them.
        val detail = JuraMeasure.width(ContourReadout.CAPTION_ENGINE_GAVE, Grid.DETAIL_SIZE, Grid.DETAIL_TRACK) +
            g.DETAIL_AFTER_CAPTION + JuraMeasure.width(ContourReadout.UNIT_KWH, Grid.DETAIL_SIZE) +
            g.DETAIL_AFTER_UNIT + figures("99,9", g.DETAIL_FIGURE) +
            g.DETAIL_PAIR_GAP +
            JuraMeasure.width(ContourReadout.CAPTION_REGEN, Grid.DETAIL_SIZE, Grid.DETAIL_TRACK) +
            g.DETAIL_AFTER_CAPTION + JuraMeasure.width(ContourReadout.UNIT_KWH, Grid.DETAIL_SIZE) +
            g.DETAIL_AFTER_UNIT + figures("99,9", g.DETAIL_FIGURE) + g.DETAIL_AFTER_FIGURE + g.DOT_ADVANCE
        assertTrue("the park line reaches ${end - detail}", end - detail > start)
    }

    @Test
    fun theTracesFigureAndUnitEndInsideThePetal() {
        val x = g.TRACE_FIGURE_X
        // The widest figure the petal prints - a tenth on P over a hundred, a countdown of hours,
        // a signed return - each behind the widest unit it can carry.
        val cases = listOf(
            "123,4" to ContourReadout.UNIT_PER_100KM_FILLING,
            "-12,3" to ContourReadout.UNIT_PER_100KM_FILLING,
            "123,4" to ContourReadout.UNIT_PER_100KM,
            ContourReadout.chargeLeft(ContourReadout.MAX_CHARGE_MINUTES) to ContourReadout.UNIT_CHARGE_LEFT,
        )
        val descender = Trace.ZERO + 0.25f * Trace.UNIT_SIZE
        for ((figure, unit) in cases) {
            val end = x + figures(figure, Trace.FIGURE_SIZE) + Trace.UNIT_GAP + JuraMeasure.width(unit, Trace.UNIT_SIZE)
            assertTrue("«$figure $unit» ends at $end", end < g.petalRight(Trace.ZERO))
            assertTrue("and under its baseline too", end < g.petalRight(descender))
        }
        // And the trace itself stands inside the cut-out at its lowest corner.
        assertTrue(g.TRACE_LEFT > g.petalLeft(Trace.DROP))
        // The cap of the figure stays under the axis the beam runs along.
        assertTrue(Trace.TOP > Grid.AXIS + Band.PEAK_TICK_HALF)
    }

    // ---------------------------------------------------------------- what moves in time

    @Test
    fun onlyTheThreadsAndAHotCellMoveWithTimeAlone() {
        val frame = ContourFrame()
        assertFalse("an empty panel is still", g.flickers(frame))
        frame.powerFresh = true
        frame.powerKw = 0f
        assertFalse("a beam at zero has no threads", g.flickers(frame))
        frame.powerKw = 26f
        assertTrue("a beam has threads", g.flickers(frame))
        frame.powerFresh = false
        assertFalse("and a stale one is not drawn", g.flickers(frame))
        frame.temps[1].shown = true
        frame.temps[1].level = ContourReadout.Level.ALERT
        assertTrue("a hot cell breathes", g.flickers(frame))
        frame.unavailable = true
        assertFalse("and nothing does on a closed shell", g.flickers(frame))
    }

    private fun level(v: Float): Float =
        Silhouette.y(v, Trace.ZERO, Trace.TOP, Trace.DROP, Trace.UP_TO, Trace.DOWN_TO)
}
