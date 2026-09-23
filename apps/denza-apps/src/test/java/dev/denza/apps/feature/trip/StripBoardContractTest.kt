package dev.denza.apps.feature.trip

import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.EnergyReadouts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip against the Luminofor board: its counts against `spec.json`, its words against the
 * fixtures the board prints, and its small literals against the board's own drawing code.
 *
 * `SpectrumBoardContractTest` and `StripPagesBoardContractTest` did this for the boards before,
 * reading the numbers off generated HTML. The Luminofor board is three files instead - the numbers
 * (`spec.json`, held by `LuminoforSpecContractTest`), the scenes (`fixtures.js`, exported to the
 * debug build's `fixtures.json`) and the drawing (`luminofor.js`) - and this joins the strip to the
 * last two, in both directions: a word or a literal changed on one side fails here until the other
 * has moved.
 *
 * **What it cannot check** is what a screenshot checks: that the drawing the numbers describe is the
 * drawing on the screen. The debug build's `StripFixtures` exists for that comparison.
 */
class StripBoardContractTest {

    // ----------------------------------------------------------------------------------- spec

    @Test
    fun theAnalyserCountsAreTheSpecs() {
        assertEquals(SpecJson.num("head", "full", "strip", "bars").toInt(), SpectrumSource.BAND_COUNT)
        assertEquals(SpectrumSource.BAND_COUNT, StripModel.BANDS)
        assertEquals(SpecJson.num("head", "two", "sound", "bars").toInt(), Head.Two.Sound.BARS)
        assertEquals(SpecJson.num("head", "one", "sound", "bars").toInt(), Head.One.Sound.BARS)
    }

    /**
     * One chart on both screens (`docs/energy-display-contract.md` §2.3): the car page's ceilings
     * are the cluster trace's, and both are the ten kilometres' hundred points.
     */
    @Test
    fun theChartIsTheContractsLadder() {
        assertEquals(SpecJson.num("cluster", "trace", "upTo"), SpecJson.num("head", "chart", "upTo"), 0.0)
        assertEquals(SpecJson.num("cluster", "trace", "downTo"), SpecJson.num("head", "chart", "downTo"), 0.0)
        assertEquals(SpecJson.num("cluster", "trace", "points").toInt(), VehiclePageRenderer.POINTS)
        assertEquals(ConsumptionWindow.KM, 10.0, 0.0)
    }

    // ------------------------------------------------------------------------------- fixtures

    private val fixtures: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        SpecJson.parse(fixturesFile().readText()) as Map<String, Any?>
    }

    private fun scene(id: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (fixtures[id] as List<Any?>)[1] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>

    private fun Map<String, Any?>.str(key: String) = this[key] as String

    /** The words on the board's car page are the words `EnergyReadouts` decides, in its case. */
    @Test
    fun theBoardsCarWordsAreTheAppsWords() {
        val main = scene("main-car")
        assertEquals(EnergyReadouts.WORD_FROM_PACK_SENTENCE, main.obj("power").str("cap"))
        assertEquals(ContourReadout.UNIT_KW, main.obj("power").str("unit"))
        assertEquals(VehiclePageWords.VOLTS, main.obj("volts").str("cap"))
        assertEquals(VehiclePageWords.UNIT_V, main.obj("volts").str("unit"))
        assertEquals(EnergyReadouts.ENGINE_MINUTES_CAPTION, main.obj("engine").str("cap"))
        assertEquals(EnergyReadouts.ENGINE_MINUTES_UNIT, main.obj("engine").str("unit"))
        assertEquals("42" + EnergyReadouts.TRIP_KM_SENTENCE, main.obj("tripCell").str("cap"))
        assertEquals(ContourReadout.UNIT_KWH, main.obj("tripCell").str("unit"))
        assertEquals(
            VehiclePageWords.SPEND + " 16,9 " + ContourReadout.perHundredKm(10.0, ConsumptionWindow.KM),
            main.str("consumptionCaption"),
        )

        val engine = scene("main-car-engine")
        assertEquals(EnergyReadouts.WORD_FROM_ENGINE_SENTENCE, engine.obj("power").str("cap"))
        assertEquals(true, engine.obj("power")["dot"])
        assertEquals("blue", engine.obj("power")["col"])
        assertEquals(EnergyReadouts.ENGINE_RPM_CAPTION, engine.obj("engine").str("cap"))
        assertEquals(EnergyReadouts.ENGINE_RPM_UNIT, engine.obj("engine").str("unit"))
    }

    /** And the hot scene's colours are the app's thresholds, not the board's opinion. */
    @Test
    fun theBoardsHotCellsAreTheAppsThresholds() {
        @Suppress("UNCHECKED_CAST")
        val temps = scene("main-car-hot")["temps"] as List<Map<String, Any?>>
        val bands = listOf(
            ContourReadout.PACK_BAND_HIGH_C,
            ContourReadout.DRIVE_BAND_HIGH_C,
            ContourReadout.DRIVE_BAND_HIGH_C,
            ContourReadout.DRIVE_BAND_HIGH_C,
            ContourReadout.INVERTER_WATCH_C,
        )
        temps.forEachIndexed { index, cell ->
            val expected = when (ContourReadout.thermalState((cell.str("value")).toDouble(), bands[index])) {
                ContourReadout.Level.ALERT -> "danger"
                ContourReadout.Level.WATCH -> "warning"
                ContourReadout.Level.NORMAL -> "normal"
            }
            assertEquals("cell $index", expected, cell.str("state"))
        }
    }

    @Test
    fun theBoardsSoundWordsAreTheAppsWords() {
        @Suppress("UNCHECKED_CAST")
        val trip = scene("main-sound")["trip"] as List<Map<String, Any?>>
        assertEquals(StripReadings.ON_THE_ROAD, trip[0].str("cap"))
        assertEquals(StripReadings.roadLabel(128_000.0), trip[0].str("unit"))
        assertEquals(BaseTripRenderer.clockHm(1 * 3600L + 42 * 60L), trip[0].str("fig"))
        assertEquals(StripReadings.ALTITUDE, trip[1].str("cap"))
        assertEquals(StripReadings.METRES, trip[1].str("unit"))
        assertEquals(StripReadings.tenths(12), trip[1].str("rate"))
        assertEquals(StripReadings.SUNSET, trip[2].str("cap"))
        // Every scene carries the analyser at the full screen's count; the panes sample it.
        @Suppress("UNCHECKED_CAST")
        val spectrum = scene("one-sound")["spectrum"] as Map<String, List<Any?>>
        assertEquals(SpectrumSource.BAND_COUNT, spectrum.getValue("levels").size)
        assertEquals(SpectrumSource.BAND_COUNT, spectrum.getValue("crowns").size)
    }

    // ------------------------------------------------------------------- the board's literals

    private val board: String by lazy { SpecJson.read("luminofor/luminofor.js") }

    private fun js(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun assertDrawn(what: String, source: String) =
        assertTrue("luminofor.js no longer draws $what as «$source»", board.contains(source))

    /**
     * The small numbers the board writes inline rather than in `spec.json` - the dots, the play
     * mark, the blue mark, the arrow, the one-third rows, the glyphs' drop, the chart's line and
     * dot - each held to the Kotlin constant that draws it.
     */
    @Test
    fun theBoardsInlineNumbersAreTheRenderersConstants() {
        val r = TripPanelRenderer
        assertDrawn(
            "the dots",
            "d.arc(cx - ${js(r.DOT_HALF_PITCH)} + i * ${js(2 * r.DOT_HALF_PITCH)}, y, ${js(r.DOT_RADIUS)}, 0, Math.PI * 2)",
        )
        assertDrawn(
            "the play mark",
            "play.moveTo(x + 1, capY - lpx * ${js(r.PLAY_TOP)}); " +
                "play.lineTo(x + lpx * ${js(r.PLAY_TIP)}, capY - lpx * ${js(r.PLAY_MIDDLE)}); " +
                "play.lineTo(x + 1, capY - 1)",
        )
        assertDrawn("the play mark's beam", "beam(c, play, ${js(r.PLAY_STROKE)}, WHT, ${js(r.PLAY_INTENSITY)}, 0)")
        assertDrawn("the artist", "lab(c, f.track.artist, x + lpx * ${js(r.ARTIST_INDENT)}, capY, lpx)")

        val i = StripInk
        assertDrawn(
            "the blue mark",
            "d.arc(x + ${js(i.MARK_X)}, capY - lpx * ${js(i.MARK_RISE)}, ${js(i.MARK_RADIUS)}, 0, Math.PI * 2); " +
                "glowFill(c, d, HUB, 1, ${js(i.MARK_BLUR)}); lab(c, it.cap, x + ${js(StripGeometry.MARK_INDENT)}, capY, lpx)",
        )
        assertDrawn("the arrow's height", "h = size * ${js(i.ARROW_RISE)}")
        assertDrawn("the arrow", "beam(c, ar, ${js(i.ARROW_STROKE)}, WHT, ${js(i.RATE_INTENSITY)}, 0)")
        assertDrawn("the rate", "num(c, it.rate, ax + ${js(StripGeometry.ARROW_ROOM)}, valY,")

        assertDrawn("a one-third caption", "lab(c, it.cap, L, y, s.labelSize, ${js(r.ROW_LABEL)})")
        assertDrawn(
            "a one-third unit",
            "text(c, it.unit, ux + ${js(StripGeometry.ROW_UNIT_GAP)}, y, ${js(StripGeometry.ROW_SMALL)}, WHT, 0.9,",
        )
        assertDrawn("a one-third arrow's place", "const ax = ux + ${js(StripGeometry.ROW_RATE_OFFSET)}")
        val half = js(r.ROW_ARROW_HALF)
        assertDrawn(
            "a one-third arrow",
            "ar.moveTo(ax + $half, y - 1); ar.lineTo(ax + $half, y - ${js(r.ROW_ARROW_HEIGHT)}); " +
                "ar.moveTo(ax, y - ${js(r.ROW_ARROW_HEIGHT - r.ROW_ARROW_HALF)})",
        )
        assertDrawn(
            "a one-third rate",
            "beam(c, ar, ${js(r.ROW_ARROW_STROKE)}, WHT, 0.9, 0); " +
                "num(c, it.rate, ax + ${js(StripGeometry.ROW_ARROW_ROOM)}, y, ${js(StripGeometry.ROW_SMALL)}, WHT, 0.9, 'left')",
        )

        val v = VehiclePageRenderer
        assertDrawn(
            "a temperature's glyph",
            "glyph(c, KINDS[i], x, capY + ${js(v.GLYPH_DROP)}, col, hot ? 1 : ${js(v.GLYPH_LEVEL)}, hot)",
        )
        assertDrawn("the chart's line", "beam(c, pu, C.stroke, WHT, ${js(v.LINE_INTENSITY)}, 0)")
        assertDrawn("the chart's newest point", "glowFill(c, d, last < 0 ? HUB : WHT, 1, ${js(v.END_BLUR)})")
        assertDrawn("the two-thirds pane's caption", "lab(c, cons, L, s.chartCaption, ${js(Head.Full.Car.CHART_CAPTION_SIZE)},")

        assertDrawn("a column's least height", "h = Math.max(${js(SpectrumRenderer.MIN_HEIGHT)}, lv[si] * fh)")
        assertDrawn("a pane's sampling", "const si = Math.round(i * (NN - 1) / Math.max(1, n - 1))")
    }

    private companion object {
        /** The debug build's copy of the board's scenes, which `shot.py --fixtures` writes. */
        fun fixturesFile(): File {
            val start = requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }
            var dir: File? = File(start).absoluteFile
            while (dir != null) {
                val file = File(dir, "apps/denza-apps/src/debug/assets/luminofor/fixtures.json")
                if (file.isFile) return file
                val local = File(dir, "src/debug/assets/luminofor/fixtures.json")
                if (local.isFile) return local
                dir = dir.parentFile
            }
            error("fixtures.json not found above $start")
        }
    }
}
