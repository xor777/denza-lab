package dev.denza.apps.feature.trip

import dev.denza.apps.feature.cluster.dashboard.ContourPlan
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.EnergyReadouts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StripPages.dc.html` read at test time, against the constants the strip is drawn from.
 *
 * The same join `MainBoardContractTest` and `SpectrumBoardContractTest` make, for the same reason:
 * this page and its board are one design in two records, and a number that lives in two places
 * without a test between them drifts. The board is not a picture of the code here - it is the other
 * half of it, and either may be edited first as long as neither may be edited alone.
 *
 * **What it cannot check.** Whether the swipe feels right, whether the shape reads at arm's length,
 * and whether the scale rungs are the right rungs. Those are a car, an owner and a drive.
 */
class StripPagesBoardContractTest {

    @Test
    fun theFieldGivesItsFootToTheDots() {
        assertEquals("dots band", number("""height:([\d.]+)px; flex-shrink:0; display:flex; """ +
            """align-items:center; justify-content:center"""), TripPanelRenderer.DOTS.toDouble(), 1e-6)
        val dot = number("""width:([\d.]+)px; height:[\d.]+px; border-radius:[\d.]+px; background:#FEEFAB""")
        assertEquals("dot", dot, TripPanelRenderer.DOT.toDouble(), 1e-6)
        assertEquals(
            "dot gap",
            number("""justify-content:center; gap:([\d.]+)px;">"""),
            TripPanelRenderer.DOT_GAP.toDouble(),
            1e-6,
        )
    }

    /**
     * Every frame draws one lit dot and one idle one, because there are two pages and one of them
     * is up. Counted rather than located: the board carries six frames of the strip and the point
     * is that none of them can quietly gain a third page or lose the indicator.
     */
    @Test
    fun thereAreTwoDotsBecauseThereAreTwoPages() {
        val lit = count("""border-radius:4px; background:#FEEFAB""")
        val idle = count("""border-radius:4px; background:rgba\(134,144,155,[\d.]+\)""")
        assertTrue("the board draws dots", lit > 0)
        assertEquals("one lit dot per idle one", lit, idle)
        assertEquals("pages", 2, StripPage.entries.size)
        assertEquals(
            "an idle dot's ink",
            number("""border-radius:4px; background:rgba\(134,144,155,([\d.]+)\)"""),
            TripPanelRenderer.DOT_IDLE_ALPHA.toDouble(),
            1e-6,
        )
    }

    @Test
    fun theHeroAndTheEngineAreTwoRungsApart() {
        assertEquals("hero", number("""\.hero \{ font-size:([\d.]+)px"""),
            VehiclePageRenderer.HERO.toDouble(), 1e-6)
        assertEquals("everything beside the hero",
            number("""\.head \.val \{ font-size:([\d.]+)px"""),
            VehiclePageRenderer.SECOND.toDouble(), 1e-6)
        assertEquals("a temperature", number("""\.val \{ font-size:([\d.]+)px"""),
            VehiclePageRenderer.READING.toDouble(), 1e-6)
    }

    @Test
    fun theColumnsSplitTheFieldTheSameWay() {
        assertEquals(
            "left share",
            number("""LEFT_SHARE = ([\d.]+)""", GENERATOR),
            VehiclePageRenderer.LEFT_SHARE.toDouble(),
            1e-6,
        )
        assertEquals(
            "the rule's own air",
            number("""\.vrule \{ width:1px; align-self:stretch; background:[^;]+; margin:0 ([\d.]+)px"""),
            VehiclePageRenderer.RULE_MARGIN.toDouble(),
            1e-6,
        )
    }

    @Test
    fun theShelfIsFiveRowsOfThirty() {
        assertEquals("row", number("""\.temp \{[^}]*height:([\d.]+)px"""),
            VehiclePageRenderer.ROW.toDouble(), 1e-6)
        assertEquals("glyph", number("""<svg width="(\d+)" height="\d+" viewBox="0 0 24 24" fill="none" stroke="#86909B"""),
            VehiclePageRenderer.GLYPH.toDouble(), 1e-6)
        assertEquals("reading's field", number("""\.temp \.val \{ width:([\d.]+)px"""),
            VehiclePageRenderer.READING_FIELD.toDouble(), 1e-6)
        assertEquals("track", number("""\.track \{ position:relative; flex:1; min-width:0; height:([\d.]+)px"""),
            VehiclePageRenderer.TRACK.toDouble(), 1e-6)
        // Five temperatures and the cell spread, which is a reading of the same kind: a number
        // with a window it is ordinary inside of and two zones past it.
        assertEquals("rows", 6, VehiclePageRenderer.SHELF_ROWS)
    }

    /**
     * The zones are the cluster's thresholds, and the board states them as the fractions it draws.
     *
     * This is the check that matters most in this file. The first drawing of this page invented
     * 45/120/100 out of nothing, which would have put the head unit and the driver's display on
     * two different ideas of "hot" in one car. The board computes its zone boundaries from
     * `ContourReadout`'s own numbers, so if either record moves the other has to move with it.
     */
    @Test
    fun theZonesAreTheClustersOwnThresholds() {
        assertEquals("the margin", ContourReadout.HOT_MARGIN_C.toFloat(), VehiclePageRenderer.HOT_MARGIN)
        assertEquals(
            "the pack's band",
            number("""'pack': ([\d.]+)""", GENERATOR),
            ContourReadout.PACK_BAND_HIGH_C,
            1e-6,
        )
        assertEquals(
            "a drive motor's",
            number("""'front': ([\d.]+)""", GENERATOR),
            ContourReadout.DRIVE_BAND_HIGH_C,
            1e-6,
        )
        assertEquals(
            "the margin the board draws with",
            number("""HOT_MARGIN = ([\d.]+)""", GENERATOR),
            ContourReadout.HOT_MARGIN_C,
            1e-6,
        )
        // The spread is a row of the same kind now, so its two thresholds are held the same way.
        assertEquals(
            "the spread's watch",
            number("""SPREAD_WATCH = ([\d.]+)""", GENERATOR),
            ContourReadout.SPREAD_WATCH_MV,
            1e-6,
        )
        assertEquals(
            "and its alert",
            number("""SPREAD_ALERT = ([\d.]+)""", GENERATOR),
            ContourReadout.SPREAD_ALERT_MV,
            1e-6,
        )
        assertEquals("the zone's own ink", number("""rgba\(255,159,25,([\d.]+)\)"""),
            VehiclePageRenderer.ZONE_ALPHA.toDouble(), 1e-6)
        // One zone, and the end of the track is the alert. Two of them - amber then red - put a
        // wide brown-and-red band on all six rows of a car sitting at 15 °C, which is five rows
        // saying "almost" about things that are stone cold. What separates watch from alert is the
        // colour of the figure, the mark and the fill, together.
        assertTrue(
            "no second zone on the board",
            !BOARD.readText().contains("rgba(255,64,70,"),
        )
        assertTrue(
            "and the board's own window ends at the alert",
            GENERATOR.readText().contains("top = band + HOT_MARGIN\n"),
        )
    }

    /**
     * The shape is the cluster's chart, and both records say so in the same numbers.
     *
     * `docs/energy-display-contract.md` §2.3: one history of one quantity on both screens. It
     * replaced two minutes of pack power - a second history of the quantity the headline already
     * shows, and the reason the two screens' graphs could not be the same graph.
     */
    @Test
    fun theShapeIsTheClustersOwnTwentyBins() {
        // Against the shared constant rather than against a copy of it in this renderer: five
        // aliases used to stand there and a test asserted each equalled its own initialiser.
        assertEquals(
            "bins",
            number("""CHART_BINS = (\d+)""", GENERATOR).toInt(),
            ConsumptionChart.BINS,
        )
        assertEquals(
            "which is half a kilometre a step over the window",
            ConsumptionWindow.KM / ConsumptionChart.BIN_KM,
            ConsumptionChart.BINS.toDouble(),
            1e-9,
        )
        assertEquals("the box", number("""CHART_H = (\d+)""", GENERATOR),
            VehiclePageRenderer.CHART.toDouble(), 1e-6)
        assertEquals("and in a narrow pane", number("""CHART_H_NARROW = (\d+)""", GENERATOR),
            VehiclePageRenderer.CHART_NARROW.toDouble(), 1e-6)
        assertEquals("its edge", number("""CHART_EDGE = (\d+)""", GENERATOR),
            VehiclePageRenderer.CHART_EDGE.toDouble(), 1e-6)
    }

    /**
     * And the ladder it is drawn on is the petal's own, clamped, with the cut marked.
     *
     * Two constants in one place: `ContourPlan` owns them, both screens read them, and the plan
     * board prints them. If the recording says 40 and 20 are wrong they move once.
     */
    @Test
    fun bothScreensClampOnOneLadder() {
        assertEquals("the ceiling", number("""CHART_FULL = (\d+)""", GENERATOR),
            ContourPlan.PETAL_FULL.toDouble(), 1e-6)
        assertEquals("and the floor", number("""CHART_RETURN_FULL = (\d+)""", GENERATOR),
            ContourPlan.PETAL_RETURN_FULL.toDouble(), 1e-6)
        assertEquals("the tick over a cut bin", number("""CHART_TICK = (\d+)""", GENERATOR),
            ContourPlan.PETAL_TICK.toDouble(), 1e-6)
        // And the gutter's own labels are those two ceilings written out, on both records.
        assertEquals("40", ContourPlan.PETAL_FULL_LABEL)
        assertEquals("−20", ContourPlan.PETAL_RETURN_FULL_LABEL)
        assertEquals(VehiclePageRenderer.AXIS_CEILING, ContourPlan.PETAL_FULL_LABEL)
        assertTrue("the board's gutter", BOARD.readText().contains(">${ContourPlan.PETAL_RETURN_FULL_LABEL}<"))
        // And the board draws the two cases: a bin past the ceiling, and one the log has no
        // energy for.
        assertTrue("a launch scene", GENERATOR.readText().contains("history(31.6, launch=True)"))
        assertTrue("and a hole", GENERATOR.readText().contains("hole=(6, 7)"))
    }

    /**
     * The hero prints a magnitude, and the direction is the word and the colour.
     *
     * The sign went back on for a while, on the owner's «белый разряд, синий заряд… но супер
     * неинтуитивно» - he was decoding the hue because the sentence above the figure was wrong at
     * that moment. What was broken was the sentence, not the absence of a minus: the word and the
     * sign were decided in two places. There is one place now (`EnergyReadouts`), so the two cues
     * agree by construction and the third one is not needed (contract §2.1).
     */
    @Test
    fun theHeroIsUnsignedAndItsDirectionIsTheWordAndTheColour() {
        val board = BOARD.readText()
        assertTrue("the generating scene prints 8, not -8", board.contains(">8</div>"))
        assertTrue("no minus on any hero", !Regex("""class="hero"[^>]*>[-\u2212]""").containsMatchIn(board))
        // And the word says which way, in the words the code uses.
        assertTrue(board.contains(EnergyReadouts.WORD_FROM_ENGINE))
        assertTrue(board.contains(EnergyReadouts.WORD_FROM_CHARGER))
        assertTrue(board.contains(EnergyReadouts.WORD_FROM_PACK))
    }

    /**
     * The foot line is the figure, its unit and its window, and the pane drops only the word.
     *
     * «РАСХОД 19,4 кВт·ч/100 км · ЗА 10 КМ» wide, «19,4 кВт·ч/100 км · 10 КМ» at 392 dp. The
     * consumption used to be the thing that went in a pane, and the contract's §5 says the
     * opposite: the figure and its window are what the shape above cannot be read without.
     */
    @Test
    fun theFootLineKeepsItsFigureAndItsWindowInAPane() {
        val board = BOARD.readText()
        assertTrue(
            "the wide line",
            board.contains(
                "${VehiclePageWords.TITLE_SPEND} <span class=\"spend-figure\">19,4</span> " +
                    ContourReadout.windowFoot(ConsumptionWindow.KM, ConsumptionWindow.KM, narrow = false),
            ),
        )
        assertTrue(
            "and the pane's, which drops the word and nothing else",
            board.contains(
                "<span class=\"spend-figure\">19,4</span> " +
                    ContourReadout.windowFoot(ConsumptionWindow.KM, ConsumptionWindow.KM, narrow = true),
            ),
        )
        // Never a whole-number rounding of a filling window, on either record.
        assertEquals("ЗА 3,7 КМ", ContourReadout.windowCaps(3.7, ConsumptionWindow.KM, narrow = false))
        assertEquals("10 КМ", ContourReadout.windowCaps(10.0, ConsumptionWindow.KM, narrow = true))
    }

    /**
     * The sixth row is named by a mark, on both records and with the same geometry.
     *
     * The Contour's family has no mark for the cell spread - the cluster names it with the one
     * word left in its row - so this screen draws two cells at two levels, and the word is on
     * neither record. What holds them together is the drawing.
     */
    @Test
    fun theSpreadIsNamedByTheSameTwoCells() {
        assertEquals(
            "a cell's width",
            number("""CELLS_GLYPH = \(\s*'<rect x="[\d.]+" y="[\d.]+" width="([\d.]+)""", GENERATOR),
            VehiclePageRenderer.CELL_WIDTH.toDouble(),
            1e-6,
        )
        assertEquals(
            "where the pair starts",
            number("""CELLS_GLYPH = \(\s*'<rect x="([\d.]+)""", GENERATOR),
            VehiclePageRenderer.CELL_INSET_X.toDouble(),
            1e-6,
        )
        assertTrue(
            "and the word is on neither record",
            !BOARD.readText().contains(ContourReadout.CAPTION_SPREAD),
        )
    }

    /**
     * The words on the board are the words in the code.
     *
     * A page whose whole argument is "one quantity, one sentence" cannot have two sentences, and
     * the sentence is the one thing on this page that a picture of it can be read for directly.
     */
    @Test
    fun theBoardSaysWhatTheCodeSays() {
        listOf(
            EnergyReadouts.WORD_FROM_PACK,
            EnergyReadouts.WORD_FROM_ENGINE,
            EnergyReadouts.WORD_FROM_CHARGER,
            ContourReadout.TITLE_ENGINE_RPM_CAPS,
            ContourReadout.TITLE_ENGINE_MINUTES_CAPS,
            VehiclePageWords.TITLE_VOLTS,
            VehiclePageWords.TITLE_SPEND,
            VehiclePageRenderer.TITLE_CLOSED,
        ).forEach { phrase ->
            assertTrue("«$phrase» is on the board", BOARD.readText().contains(phrase))
        }
    }

    /**
     * And the four the owner struck off are on neither.
     *
     * The 12 V rail («бессмысленное значение»), the charge, the range and the fuel - the car shows
     * three of those itself - and the current, which this firmware cannot stand behind. They came
     * off the board on 2026-09-05; this is what stops them coming back to one side only.
     */
    @Test
    fun neitherRecordPrintsWhatWasStruckOff() {
        val board = BOARD.readText()
        listOf("Бортсеть", "Запас", "Топливо", "ТОК", "ПОСЛЕДНИЕ 2 МИНУТЫ").forEach {
            assertTrue("«$it» is off the board", !board.contains(it))
        }
    }

    private fun number(pattern: String, file: File = BOARD): Double =
        Regex(pattern).find(file.readText())?.groupValues?.get(1)?.toDouble()
            ?: error("${file.name} has nothing matching $pattern")

    private fun count(pattern: String): Int = Regex(pattern).findAll(BOARD.readText()).count()

    private companion object {
        private val CANVAS: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "tools/design-canvas") }
            .firstOrNull { it.isDirectory }
            ?: error("tools/design-canvas not found above ${System.getProperty("user.dir")}")

        val BOARD = File(CANVAS, "StripPages.dc.html")

        /**
         * Some of what this page promises is in the generator rather than in the board it emits:
         * a ladder of rungs and a table of thresholds are decisions, and the board only shows the
         * one scene each of them produced.
         */
        val GENERATOR = File(CANVAS, "gen_strippages.py")
    }
}
