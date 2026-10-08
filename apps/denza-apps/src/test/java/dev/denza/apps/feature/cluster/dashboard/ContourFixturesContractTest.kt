package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words on the Luminofor cluster boards against the words the app prints.
 *
 * The boards draw from `fixtures.js`, exported to the debug build as `luminofor/fixtures.json`,
 * and the fixtures carry the exact strings the app prints for their scenes. This is the join the
 * old `ContourBoardContractTest` was for the SVG boards, in the one direction a fixture can be
 * joined: every word a board prints is a word [ContourReadout] owns, spelled the same, and every
 * figure is one the wide numerals can draw. A caption renamed on either side fails here until the
 * other has moved too.
 *
 * `ContourFrameBuilderTest` is the other half: the car of a scene, run through the scene, the
 * followers and the builder, prints that scene's board.
 */
class ContourFixturesContractTest {

    /**
     * Every cluster scene `fixtures.js` draws, read off the file rather than listed here: the list
     * this replaced had ten of the twelve, and the stale and waking boards' words were held to
     * nothing. An empty list would agree with anything, so it may not be.
     */
    private val boards = SpecJson.fixtures.keys.filter { it.startsWith("cluster-") }
        .also { check(it.isNotEmpty()) { "no cluster-* scene in fixtures.json" } }

    @Test
    fun theBoardsCaptionsAreTheAppsWords() {
        // The waking and the closed panel print no captions at all: ContourFrameBuilderTest holds
        // those two to their boards. Every other scene prints the same five.
        boards.map { it to fixture(it) }.filter { it.second["batteryCaption"] != null }.forEach { (id, f) ->
            assertEquals(id, ContourReadout.TITLE_PACK, f["batteryCaption"])
            assertEquals(id, ContourReadout.UNIT_KWH, f["tripUnit"])
            assertTrue(
                "$id: «${f["iceCaption"]}»",
                f["iceCaption"] in setOf(ContourReadout.TITLE_ENGINE_MINUTES, ContourReadout.TITLE_ENGINE_RPM),
            )
            // A stale trip loses its kilometres and the separator with them.
            assertEquals(
                id,
                if (f["tripKwh"] == null) {
                    ContourReadout.CAPTION_TRIP_ALONE
                } else {
                    "42 ${ContourReadout.UNIT_KM} ${ContourReadout.CAPTION_TRIP}"
                },
                f["tripCaption"],
            )
            if (f["parked"] == true) {
                assertEquals(id, ContourReadout.CAPTION_ENGINE_GAVE, f["gaveCaption"])
                assertEquals(id, ContourReadout.CAPTION_REGEN, f["regenCaption"])
            }
        }
    }

    @Test
    fun thePetalsUnitIsTheWindowTheAppNames() {
        assertEquals(ContourReadout.UNIT_PER_100KM, fixture("cluster-city")["consumptionUnit"])
        // Thirty-seven points and «за 3,7 км» are one number.
        val filling = fixture("cluster-filling")
        val points = (filling["chart"] as List<*>).size
        assertEquals(
            ContourReadout.perHundredKm(points * 0.1, ConsumptionWindow.KM),
            filling["consumptionUnit"],
        )
        val charging = fixture("cluster-charging")
        assertEquals(ContourReadout.UNIT_CHARGE_LEFT, charging["consumptionUnit"])
        assertEquals(ContourReadout.chargeLeft(2 * 60 + 15), charging["consumption"])
        assertEquals(ContourReadout.consumption(16.8, parked = true), fixture("cluster-park")["consumption"])
        assertEquals(ContourReadout.consumption(17.0, parked = false), fixture("cluster-city")["consumption"])
    }

    @Test
    fun theEnginesSentenceIsTheAppsSentence() {
        val engine = fixture("cluster-engine")
        assertEquals(ContourReadout.TITLE_ENGINE_RPM, engine["iceCaption"])
        assertEquals(
            "${ContourReadout.LEGEND_PREFIX} 14 ${ContourReadout.UNIT_KW}",
            engine["engineCaption"],
        )
        assertEquals(ContourReadout.intoPack(120), engine["engineWindow"])
        assertEquals("two minutes of five-second steps", ContourGeometry.ENGINE_BINS, (engine["generation"] as List<*>).size)
    }

    @Test
    fun theSpreadIsTheAppsException() {
        val spread = fixture("cluster-spread")["spread"] as Map<*, *>
        assertEquals(ContourReadout.CAPTION_SPREAD, spread["caption"])
        assertEquals(ContourReadout.UNIT_MILLIVOLT, spread["unit"])
        val level = ContourReadout.spreadState((spread["value"] as String).toDouble())
        assertEquals("warning", spread["state"])
        assertEquals(ContourReadout.Level.WATCH, level)
    }

    @Test
    fun everyFigureIsOneTheWideNumeralsDraw() {
        // `LightPen.figures` skips a character it has no glyph for, so a figure with a letter in it
        // would be drawn with a hole: the reason the countdown is «12:30» rather than «12 ч».
        val drawable = SpecJson.at("digits", "glyphs") as Map<*, *>
        boards.map(::fixture).forEach { f ->
            val figures = listOfNotNull(f["volts"], f["tripKwh"], f["iceFigure"], f["consumption"], f["gaveKwh"], f["regenKwh"]) +
                // A stale cell keeps its glyph and prints no figure.
                (f["temps"] as List<*>? ?: emptyList<Any>()).mapNotNull { (it as Map<*, *>)["value"] }
            figures.map { it as String }.forEach { figure ->
                assertTrue("«$figure»", figure.all { drawable.containsKey(it.toString()) })
                assertTrue(WideDigits.width(figure, 52f) > 0f)
            }
        }
    }

    private fun fixture(id: String): Map<String, Any?> = SpecJson.fixture(id)
}
