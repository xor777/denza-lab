package dev.denza.apps.feature.trip

import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.PowerSpan
import dev.denza.apps.feature.vehicle.PowerTrace
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
        assertEquals("engine and the shelf's readings",
            number("""\.head \.val \{ font-size:([\d.]+)px"""),
            VehiclePageRenderer.ENGINE.toDouble(), 1e-6)
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
        assertEquals("sensors", 5, VehiclePageRenderer.SHELF_ROWS)
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
        assertEquals("the zone's own ink", number("""rgba\(255,159,25,([\d.]+)\)"""),
            VehiclePageRenderer.ZONE_ALPHA.toDouble(), 1e-6)
    }

    @Test
    fun theShapeIsTwentyFourStepsOfFiveSeconds() {
        assertEquals(
            "steps",
            number("""TRACE_BINS = (\d+)""", GENERATOR).toInt(),
            PowerTrace.SLOTS / PowerTrace.BIN_SECONDS,
        )
        assertEquals(
            "a step's seconds",
            PowerTrace.BIN_SECONDS,
            PowerTrace.SLOTS / number("""TRACE_BINS = (\d+)""", GENERATOR).toInt(),
        )
        assertEquals("the box", number("""TRACE_H = (\d+)""", GENERATOR),
            VehiclePageRenderer.GRAPH.toDouble(), 1e-6)
        assertEquals("and in a narrow pane", number("""TRACE_H_NARROW = (\d+)""", GENERATOR),
            VehiclePageRenderer.GRAPH_NARROW.toDouble(), 1e-6)
        assertEquals("its edge", number("""TRACE_EDGE = (\d+)""", GENERATOR),
            VehiclePageRenderer.EDGE.toDouble(), 1e-6)
    }

    /**
     * The span is written where a chart writes it, on both records.
     *
     * It was a phrase on the line under the box - `ШКАЛА 5 ↑ 10 ↓ кВт` - and the owner read it and
     * said «тоже не интуитивно, либо убрать либо починить». Two figures against the edges they
     * belong to are not a legend; the gutter they stand in is what keeps them off the shape.
     */
    @Test
    fun theBoxSaysWhatItHoldsInItsOwnGutter() {
        assertEquals(
            "the gutter",
            number("""TRACE_AXIS = (\d+)""", GENERATOR),
            VehiclePageRenderer.AXIS.toDouble(),
            1e-6,
        )
        assertEquals(
            "where the top figure sits",
            number("""TRACE_AXIS_BASELINE = (\d+)""", GENERATOR),
            VehiclePageRenderer.AXIS_BASELINE.toDouble(),
            1e-6,
        )
        assertTrue(
            "and the phrase is gone from the board",
            !BOARD.readText().contains("ШКАЛА"),
        )
    }

    /**
     * A figure going into the pack carries its sign as well as its colour.
     *
     * The sign came off on the reasoning that a minus is not a direction anybody reads at a
     * glance. On the car the owner read the page as «белый разряд, синий заряд… но супер
     * неинтуитивно» - he was decoding the hue, because it was the only cue that was telling the
     * truth at that moment. Three cues that agree cost nothing.
     */
    @Test
    fun aFigureGoingIntoThePackIsSigned() {
        assertTrue(
            "the board signs it",
            BOARD.readText().contains(">${VehiclePageRenderer.MINUS}8<"),
        )
    }

    @Test
    fun bothRecordsClimbTheSameLadder() {
        val rungs = Regex("""TRACE_RUNGS = \(([\d, ]+)\)""").find(GENERATOR.readText())
            ?.groupValues?.get(1)
            ?: error("the generator has no rung ladder")
        assertEquals(
            "rungs",
            rungs.split(",").map { it.trim().toInt() },
            PowerSpan.RUNGS.toList(),
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
            VehiclePageWords.TITLE_FROM_PACK,
            VehiclePageWords.TITLE_FROM_ENGINE,
            VehiclePageWords.TITLE_FROM_CHARGER,
            VehiclePageWords.TITLE_RPM,
            VehiclePageWords.TITLE_ENGINE_MINUTES,
            VehiclePageWords.TITLE_WINDOW,
            VehiclePageWords.TITLE_WINDOW_SHORT,
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
        listOf("Бортсеть", "Запас", "Топливо", "ТОК").forEach {
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
