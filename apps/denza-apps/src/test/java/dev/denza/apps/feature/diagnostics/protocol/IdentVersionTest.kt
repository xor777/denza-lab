package dev.denza.apps.feature.diagnostics.protocol

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The identification values the car's own updater reads as versions (architecture, 5.5), against the
 * fresh answers of 2026-10-21 and what the stock parser made of each (research/vehicle-diagnostics).
 */
class IdentVersionTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }
    private fun research(name: String) = JSONObject(File(root, "research/vehicle-diagnostics/$name").readText())

    /** `<address> <DID>` to the answer and the stock parser's value: the eleven core blocks and the front ZCU. */
    private fun fresh(field: String): Map<String, Pair<String, String>> {
        val core = research("ota-core-fields-live-20261021.json").getJSONObject("selectedEcus")
        val rows = core.keys().asSequence().mapNotNull { id ->
            val e = core.getJSONObject(id)
            e.getJSONObject("fields").optJSONObject(field)?.let { f -> e.getString("address") to (f.getString("responseHex") to f.getString("actual")) }
        }.toMap()
        return rows
    }

    @Test
    fun theSoftwareVersionIsWhatTheCarsUpdaterReads() {
        val read = fresh("ecuSoftVer").toMutableMap()
        // The front ZCU's F195, read alone that morning.
        research("ota-request-completeness-20261021.json").getJSONObject("frontZcuRead").getJSONArray("responses").let { r ->
            (0 until r.length()).map { r.getJSONObject(it) }.filter { it.getString("requestHex") == "22F195" }.forEach {
                read[it.getString("targetHex")] = it.getString("responseHex") to it.getString("stockParsedVersion")
            }
        }
        assertEquals(11, read.size)
        read.forEach { (address, answer) ->
            val (uds, stock) = answer
            assertEquals(address, "62F195", uds.take(6))
            val data = uds.drop(6)
            // The stock parser's version, then the other four bytes as they came.
            assertEquals(address, "$stock · ${data.drop(4)}", IdentCodec.softwareVersion(data))
        }
        assertEquals("3.48.07 · 1A011402", IdentCodec.softwareVersion("87F71A011402"))
        assertEquals("4.00.10 · 19031900", IdentCodec.softwareVersion("9C4A19031900"))
        assertEquals("4.00.36 · 1A011301", IdentCodec.softwareVersion("9C641A011301"))
        // Five digits however small: 0x0001 is 0.00.01.
        assertEquals("0.00.01 · 00000000", IdentCodec.softwareVersion("000100000000"))
    }

    @Test
    fun aVersionNotOfThatShapeStaysAsItCame() {
        // All FF (the stock parser's 6.55.35), FFFF before other bytes, another length, not hex.
        listOf("FFFFFFFFFFFF", "FFFF1A011301", "87F7", "87F71A01140200", "", "87F71A01140").forEach {
            assertEquals(it, it, IdentCodec.softwareVersion(it))
        }
        // As a row: an Identification shows F195 so and keeps its text as the bytes came.
        val f195 = Identification(IdentDid.F195, "87F71A011402", false, "87F71A011402")
        assertEquals("3.48.07 · 1A011402", f195.shown)
        assertEquals("87F71A011402", f195.text)
        assertEquals("4A520011E00D00047B", Identification(IdentDid.F194, "4A520011E00D00047B", false, "4A520011E00D00047B").shown)
    }
}
