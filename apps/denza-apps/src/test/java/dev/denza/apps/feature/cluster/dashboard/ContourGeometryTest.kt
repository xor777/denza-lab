package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.core.EnglishWords
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Band
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineCharge as Block
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.Silhouette
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.vehicle.EngineCharge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Luminofor Contour's geometry: that its numbers are the contract's, and that it fits.
 *
 * `LuminoforSpecContractTest` holds [dev.denza.apps.design.luminofor.LuminoforSpec] to spec.json
 * value by value. This holds the few spec values that are *also* somebody else's record - the
 * trace's two ceilings, its hundred points, the engine box's span, the band's one scale - to that
 * record, and then asks the question a value-by-value test cannot: whether the widest thing each
 * group can print stays inside the group.
 *
 * ### How the words are measured
 *
 * Figures are `WideDigits`, which is arithmetic. Words are Jura, measured by [JuraMeasure] out of
 * the TTF the app ships - the kerned or unkerned run, whichever is wider. The margins asserted
 * are the board's own, with nothing added: a string that fits by a unit fits.
 */
open class ContourGeometryTest {

    private val g = ContourGeometry

    private fun figures(text: String, size: Float) = WideDigits.width(text, size)

    // ---------------------------------------------------------------- one record

    @Test
    fun theTracesLadderIsTheContractsLadder() {
        // One chart on both screens, on one ladder (`docs/energy-display-contract.md` §2.3): the
        // car page's chart is the head unit's spec block, and it climbs and drops as far as this.
        assertEquals(Head.Chart.UP_TO, Trace.UP_TO, 0f)
        assertEquals(Head.Chart.DOWN_TO, Trace.DOWN_TO, 0f)
        assertEquals("as many points on the car page", Trace.POINTS, Head.Chart.POINTS)
        assertEquals("on the same ticks", Trace.TICK, Head.Chart.TICK, 0f)
        assertEquals(60.0, SpecJson.num("cluster", "trace", "upTo"), 0.0)
        assertEquals(20.0, SpecJson.num("cluster", "trace", "downTo"), 0.0)
        assertEquals("a point per hundred metres of the window", ConsumptionChart.POINTS, Trace.POINTS)
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
    fun theEngineBlockIsTwoMinutesOfSecondsOnTheFiguresBaseline() {
        assertEquals("a point a second over two minutes", 120, Block.POINTS)
        assertEquals(EngineCharge.WINDOW_SECONDS, Block.POINTS)
        assertEquals(Block.POINTS, ContourFrame().charge.size)
        // The chart runs from the block's left edge to a gutter short of the figure's column.
        assertEquals(g.GROUP_RIGHT_END - Block.WIDTH, g.CHARGE_LEFT, 0f)
        assertEquals(g.GROUP_RIGHT_END - Block.COLUMN, g.CHARGE_FIGURE_X, 0f)
        assertEquals(g.CHARGE_FIGURE_X - 2f * Trace.GAP_FROM_AXIS, g.CHARGE_RIGHT, 0f)
        assertEquals(g.CHARGE_RIGHT - g.CHARGE_LEFT, g.CHARGE_PITCH * (Block.POINTS - 1), 1e-3f)
        // The chart is the figures' own height, on their baseline.
        assertEquals(Grid.BASELINE, g.CHARGE_ZERO, 0f)
        assertEquals(Digits.CAP_RATIO * Grid.FIGURE_SIZE, g.CHARGE_ZERO - g.CHARGE_TOP, 1e-3f)
    }

    /**
     * A pause's seam (contract §2.5, since 2026-11-21): halfway between the last point before it and
     * the first after it, in a cut wide enough to hide the fall between them, inside the box where the
     * ceiling's tick stands outside it, and in the zero's own ink rather than the chart's blue.
     */
    @Test
    fun aPausesSeamStandsInACutBetweenTwoPoints() {
        val n = 90
        val before = g.CHARGE_RIGHT - (n - 1 - 59) * g.CHARGE_PITCH
        val after = g.CHARGE_RIGHT - (n - 1 - 60) * g.CHARGE_PITCH
        assertEquals((before + after) / 2f, g.chargeMarkX(60, n), 1e-3f)
        assertEquals("a full window's last seam", g.CHARGE_RIGHT - g.CHARGE_PITCH / 2f, g.chargeMarkX(119, 120), 1e-3f)
        assertTrue("the cut hides the step between the two points", Block.PAUSE_GAP > g.CHARGE_PITCH)
        assertTrue("and leaves the seam a dark gap on each side", Block.PAUSE_GAP / 2f > Block.BASE_STROKE * 2f)
        assertTrue("and is a cut, not a hole: under a tenth of the line", Block.PAUSE_GAP < (g.CHARGE_RIGHT - g.CHARGE_LEFT) / 10f)
        assertTrue("the seam is quieter than the curve's newest run", Block.PAUSE_INTENSITY < g.runIntensity(Trace.RUNS - 1))
        assertTrue(
            "luminofor.js stands it in the same place",
            SpecJson.read("luminofor/luminofor.js").contains("lineR - (vals.length - i - 0.5) * pitch"),
        )
    }

    @Test
    fun theEngineChartsCeilingIsFiftyOrTwentyFiveByItsOwnWindow() {
        assertEquals(50f, EngineCharge.CEILING_KW, 0f)
        assertEquals(25f, EngineCharge.CEILING_LOW_KW, 0f)
        assertEquals(25f, EngineCharge.ceilingKw(floatArrayOf(8f, 10f, 19.9f)), 0f)
        assertEquals(50f, EngineCharge.ceilingKw(floatArrayOf(8f, 20f)), 0f)
        assertEquals("only the drawn points count", 25f, EngineCharge.ceilingKw(floatArrayOf(8f, 40f), count = 1), 0f)
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
        // One scale both ways: equal power, equal length, out of the pack or into it.
        for (kw in listOf(3f, 14f, 26f, 80f, 226f, 300f)) assertEquals("$kw kW", g.reach(kw), -g.reach(-kw), 1e-4f)
        // So 14 kW back is shorter than 26 kW out, and 80 back shorter than 226 out.
        assertTrue(-g.reach(-14f) < g.reach(26f))
        assertTrue(-g.reach(-80f) < g.reach(226f))
        // On the square root: a quarter of the scale reaches half the half.
        assertEquals((g.AXIS - Cluster.MARGIN) / 2f, g.reach(Band.FULL_KW / 4f), 1e-3f)
        // Clamped at the margins, so nothing the pack reports leaves the glass, and past the scale
        // the tick stands beyond the head on its own side.
        assertEquals(g.AXIS - Cluster.MARGIN, g.reach(600f), 1e-3f)
        assertEquals(-(g.AXIS - Cluster.MARGIN), g.reach(-600f), 1e-3f)
        assertFalse(g.clipped(Band.FULL_KW))
        assertTrue(g.clipped(Band.FULL_KW + 1f) && g.clipped(-Band.FULL_KW - 1f))
        assertEquals(g.W - Cluster.MARGIN + Band.CLIP_TICK_HALF, g.clipTickX(320f), 1e-3f)
        assertEquals(Cluster.MARGIN - Band.CLIP_TICK_HALF, g.clipTickX(-320f), 1e-3f)
        assertTrue("and stays on the glass", g.clipTickX(-320f) > 0f && g.clipTickX(320f) < g.W)
        assertTrue(
            "luminofor.js reaches the same way",
            SpecJson.read("luminofor/luminofor.js").contains(
                "return Math.sign(v) * half * Math.sqrt(Math.min(Math.abs(v), CB.fullKw) / CB.fullKw);",
            ),
        )
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
        // The widest the row prints, −40° to 150° - a one hangs, so «150°» reaches no further than
        // «−40°» - and every reading of the stress set within it.
        val widest = WideDigits.extent(g.TEMPERATURE_WIDEST, Grid.TEMP_SIZE)
        for (t in listOf("-40°", "-12°", "00°", "104°", "110°", "118°", "121°", "150°")) {
            assertTrue("«$t» stays in its cell", WideDigits.extent(t, Grid.TEMP_SIZE) <= widest + 1e-4f)
        }
        val clear = Grid.TEMP_PITCH - widest
        // Ink to ink: from a «°» to a hung one's flag, against a one's stem to the figure after it.
        // Two readings stand twice as far apart as the figures inside one (since 2026-11-19: at 80
        // it was 1.4 times, and «104° 118° 121°» read as one number).
        val k = WideDigits.k(Grid.TEMP_SIZE)
        val between = clear / k - 6f
        val inside = 70f - 44f + Digits.TRACK - WideDigits.stroke(Grid.TEMP_SIZE) / k
        assertTrue("$between units between two readings, $inside inside one", between >= 2f * inside)
        // «НАПРЯЖЕНИЕ» stands further from the pack's glyph than two readings stand apart, and
        // three-digit volts with their «В» end that far before the first figure.
        val caption = JuraMeasure.width(ContourReadout.TITLE_VOLTS, Grid.CAPTION_SIZE, Grid.CAPTION_TRACK)
        assertTrue("the caption ends at ${left + caption}, the glyphs start at $firstCell", left + caption + clear <= firstCell)
        val volts = figures("888", Grid.FIGURE_SIZE) + Grid.UNIT_GAP +
            JuraMeasure.width(ContourReadout.UNIT_VOLT, Grid.UNIT_SIZE)
        assertTrue("three-digit volts end at ${left + volts}", left + volts + clear <= firstCell)
        // The widest reading in the last cell runs past the group's edge into the air before the
        // hero's three figures and stops half as far again from them as two readings stand apart.
        val heroField = g.heroFieldRight(JuraMeasure.width(ContourReadout.UNIT_KW, Grid.HERO_UNIT_SIZE)) - g.HERO_FIELD
        val last = g.tempX(ContourFrame.CELLS - 1) + widest
        assertTrue("the row ends at $last, the hero starts at $heroField", heroField - last >= 1.5f * clear)
        assertEquals(
            "and the «°» drawn apart lands where the run would put it",
            g.tempX(4) + WideDigits.extent("118°", Grid.TEMP_SIZE) - figures("°", Grid.TEMP_SIZE),
            g.degreeX(g.tempX(4), "118"),
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
        // and four-digit revolutions with their unit beside them.
        val iceCaption = start + JuraMeasure.width(ContourReadout.TITLE_ENGINE_MINUTES, Grid.CAPTION_SIZE, track)
        val iceFigure = start + figures("8888", Grid.FIGURE_SIZE) + Grid.UNIT_GAP +
            JuraMeasure.width(EnergyReadouts.ENGINE_RPM_UNIT, Grid.UNIT_SIZE)

        // The trip flush right, at the widest its two lines get.
        val unit = JuraMeasure.width(ContourReadout.UNIT_KWH, Grid.UNIT_SIZE)
        val caption = ContourReadout.CAPTION_TRIP
        val payload = g.tripPayload(figures("99,9", Grid.FIGURE_SIZE), unit)
        val captionWidth = JuraMeasure.width(caption, Grid.CAPTION_SIZE, track)
        val tripLeft = g.tripCaptionLeft(captionWidth)
        val figureLeft = g.tripFigureLeft(payload)
        assertTrue("«$caption» starts at $tripLeft, inside the group", tripLeft > start)
        assertTrue("and clear of the engine's heading at $iceCaption", tripLeft > iceCaption)
        assertTrue("and the figure of its revolutions at $iceFigure", figureLeft > iceFigure)
        assertEquals("the caption ends on the margin", end, tripLeft + captionWidth, 1e-3f)
        assertEquals("and so does the payload", end, figureLeft + payload, 1e-3f)

        // The engine's block in the trip's place: clear of the engine's cell - revolutions, since the
        // minutes are never beside it - by two of the trace's gaps, its caption over the chart, its
        // figure and its run's time in the column after it.
        val rpmCaption = start + JuraMeasure.width(ContourReadout.TITLE_ENGINE_RPM, Grid.CAPTION_SIZE, track)
        assertTrue("the block starts at ${g.BLOCK_LEFT}", g.BLOCK_LEFT > rpmCaption)
        assertTrue(
            "«8888 об/мин» ends at $iceFigure, the block starts at ${g.BLOCK_LEFT}",
            iceFigure + 2 * Trace.GAP_FROM_AXIS <= g.BLOCK_LEFT,
        )
        assertTrue(g.CHARGE_LEFT + JuraMeasure.width(ContourReadout.CAPTION_ENGINE_CHARGE, Grid.CAPTION_SIZE, track) < g.CHARGE_RIGHT)
        val kwh = maxOf(figures("9,9", Grid.FIGURE_SIZE), figures("99", Grid.FIGURE_SIZE)) + Grid.UNIT_GAP +
            JuraMeasure.width(ContourReadout.UNIT_KWH, Grid.UNIT_SIZE)
        assertTrue(g.CHARGE_FIGURE_X + kwh < end)
        // the run's time keeps counting: the widest a hub keeps is two-figure hours and minutes
        val run = ContourReadout.engineRun(99 * 3600 + 59 * 60)
        assertTrue("«$run»", g.CHARGE_FIGURE_X + JuraMeasure.width(run, Grid.DETAIL_SIZE, track) < end)

        // The park line, right to left, each reading caption, figure, unit: the road, the engine's
        // charge and the recuperation. With a two-digit road and both seats under ten it stays in
        // the group; at its widest - a four-digit road and both seats past ten - it runs into the
        // air before the group and stops a pair's gap short of the hero's «кВт».
        fun reading(caption: String?, figure: String, unit: String) =
            (caption?.let { JuraMeasure.width(it, Grid.DETAIL_SIZE, Grid.DETAIL_TRACK) + g.DETAIL_CAPTION_GAP } ?: 0f) +
                figures(figure, g.DETAIL_FIGURE) + g.DETAIL_UNIT_GAP + JuraMeasure.width(unit, Grid.DETAIL_SIZE)
        fun line(km: String, kwh: String) =
            reading(null, km, ContourReadout.UNIT_KM) + g.DETAIL_PAIR_GAP +
                reading(ContourReadout.CAPTION_ENGINE_CHARGE, kwh, ContourReadout.UNIT_KWH) + g.DETAIL_PAIR_GAP +
                reading(ContourReadout.CAPTION_REGEN, kwh, ContourReadout.UNIT_KWH) + g.DETAIL_BEFORE_DOT + g.DOT_ADVANCE
        val usual = line("42", "9,9")
        assertTrue("the usual park line reaches ${end - usual}", end - usual > start)
        val heroUnit = JuraMeasure.width(ContourReadout.UNIT_KW, Grid.HERO_UNIT_SIZE)
        val heroEnd = g.heroFieldRight(heroUnit) + Grid.HERO_UNIT_GAP + heroUnit
        val widest = line("9999", "99,9")
        assertTrue("the widest reaches ${end - widest}, «кВт» ends at $heroEnd", end - widest >= heroEnd + g.DETAIL_PAIR_GAP)
    }

    @Test
    fun everyCaptionHasOneTracking() {
        // The pack's, the cells' and the detail line's captions were 0.12, 0.08 and 0.06 apart.
        assertEquals(0.08f, Grid.CAPTION_TRACK, 0f)
        assertEquals(Grid.CAPTION_TRACK, Grid.CELL_CAPTION_TRACK, 0f)
        assertEquals(Grid.CAPTION_TRACK, Grid.DETAIL_TRACK, 0f)
    }

    @Test
    fun theTracesFigureAndUnitEndInsideThePetal() {
        val x = g.TRACE_FIGURE_X
        // The widest figure the petal prints - a tenth on P over a hundred, a signed return - each
        // behind the widest unit it can carry, and the widest countdown: «99 ч 59 мин до полной».
        val cases = listOf(
            "123,4" to ContourReadout.UNIT_PER_100KM_FILLING,
            "-12,3" to ContourReadout.UNIT_PER_100KM_FILLING,
            "123,4" to ContourReadout.UNIT_PER_100KM,
        )
        val left = ContourReadout.chargeLeft(ContourReadout.MAX_CHARGE_MINUTES)
        val countdown = "$left ${ContourReadout.UNIT_CHARGE_LEFT}" to
            figures(left.first, Trace.FIGURE_SIZE) + Trace.UNIT_GAP + JuraMeasure.width(left.firstUnit, Trace.UNIT_SIZE) +
            Trace.PAIR_GAP + figures(left.second!!, Trace.FIGURE_SIZE) + Trace.UNIT_GAP +
            JuraMeasure.width(left.secondUnit!!, Trace.UNIT_SIZE) + Trace.UNIT_GAP +
            JuraMeasure.width(ContourReadout.UNIT_CHARGE_LEFT, Trace.UNIT_SIZE)
        val descender = Trace.ZERO + 0.25f * Trace.UNIT_SIZE
        val runs = cases.map { (figure, unit) ->
            "$figure $unit" to figures(figure, Trace.FIGURE_SIZE) + Trace.UNIT_GAP + JuraMeasure.width(unit, Trace.UNIT_SIZE)
        } + countdown
        for ((words, width) in runs) {
            val end = x + width
            assertTrue("«$words» ends at $end", end < g.petalRight(Trace.ZERO))
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

    /**
     * The glow is the band's own share raised to [Band.GLOW_EXPONENT]: dark through ordinary
     * driving, full exactly where the band is full, and luminofor.js computes the same curve.
     */
    @Test
    fun theGlowStaysDarkUntilTheBandNearsItsEnd() {
        assertEquals(0f, g.glowAlpha(0f), 0f)
        assertEquals(Band.GLOW_MAX, g.glowAlpha(Band.FULL_KW), 1e-6f)
        assertEquals(Band.GLOW_MAX, g.glowAlpha(-Band.FULL_KW), 1e-6f)
        assertEquals("past the span it holds", Band.GLOW_MAX, g.glowAlpha(2 * Band.FULL_KW), 1e-6f)
        for (kw in listOf(14f, 80f, 150f)) assertEquals("one rule both ways at $kw", g.glowAlpha(kw), g.glowAlpha(-kw), 1e-7f)
        val half = g.glowAlpha(Band.FULL_KW / 2)
        assertTrue("slow, then fast: the second half of the span adds more", Band.GLOW_MAX - half > half)
        // Recorded drives spend 90 % of their moving time under 28 kW.
        assertTrue("28 kW lights ${g.glowAlpha(28f)}", g.glowAlpha(28f) < 0.025f)
        // Back into the pack: an ordinary regeneration is below two levels of 255, a hard one 23.
        assertEquals(1.67f, g.glowAlpha(-14f) * 255f, 0.01f)
        assertEquals(22.8f, g.glowAlpha(-80f) * 255f, 0.1f)
        assertTrue(
            "luminofor.js draws the same curve",
            SpecJson.read("luminofor/luminofor.js").contains(
                "const ga = CB.glow.max * Math.pow(Math.min(1, absP / CB.fullKw), CB.glow.exponent);",
            ),
        )
    }
}

/** The widest print of every group, its words in `values-en/`, against the same margins. */
class ContourGeometryEnglishTest : ContourGeometryTest() {
    @get:Rule
    val english = EnglishWords()
}
