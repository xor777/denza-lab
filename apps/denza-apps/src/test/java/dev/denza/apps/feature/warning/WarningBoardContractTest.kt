package dev.denza.apps.feature.warning

import dev.denza.apps.design.luminofor.SpecJson
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warnings boards draw words, the page writes them from a record: every word on every board is
 * what [WarningText] makes of the record the board carries, and the service panel's row says the
 * same as the page it opens.
 */
class WarningBoardContractTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss yyyy")

    private fun at(time: String): Long = LocalDateTime.parse("$time 2026", stamp).atZone(zone).toInstant().toEpochMilli()

    private class Record(val state: WarningLogState, val names: WarningNames, val nowMs: Long)

    private fun record(w: Map<*, *>): Record {
        val names = (w["names"] as Map<*, *>).entries.associate { (k, v) -> (k as String).toInt(16) to v as String }
        val sightings = (w["sightings"] as List<*>).map { it as List<*> }.map {
            WarningSighting((it[0] as String).toInt(16), at(it[1] as String), at(it[2] as String), (it[3] as Number).toInt())
        }
        val events = (w["events"] as List<*>).map { it as List<*> }.map {
            WarningEvent(at(it[0] as String), WarningEventKind.of(it[1] as String)!!, (it[2] as String).takeIf(String::isNotEmpty)?.toInt(16))
        }
        val state = WarningLogState(
            enabled = w["enabled"] == true,
            sinceMs = at(w["since"] as String),
            linked = w["linked"] as Boolean,
            lostAtMs = (w["lostAt"] as String).takeIf(String::isNotEmpty)?.let(::at),
            events = events,
            sightings = sightings,
        )
        return Record(state, WarningNames(names), at(w["now"] as String))
    }

    private fun blocks(id: String) = ((SpecJson.fixture(id)["sheet"] as Map<*, *>)["blocks"] as List<*>).map { it as Map<*, *> }

    private fun warnings(id: String) = record((SpecJson.fixture(id)["state"] as Map<*, *>)["warnings"] as Map<*, *>)

    @Test
    fun everyWarningsBoardSaysWhatThePageWritesFromItsRecord() {
        val ids = SpecJson.fixtures.keys.filter { it.contains("sheet-service-warnings") }
        assertEquals(setOf("sheet-service-warnings", "sheet-service-warnings-off", "one-sheet-service-warnings"), ids.toSet())
        ids.forEach { id ->
            val r = warnings(id)
            val sheet = SpecJson.fixture(id)["sheet"] as Map<*, *>
            assertEquals(WarningText.TITLE, sheet["title"])
            val blocks = blocks(id)

            val status = blocks.first { it["t"] == "status" }
            val (text, tone) = WarningText.status(r.state, r.nowMs, zone)
            assertEquals(id, text, status["text"])
            assertEquals(id, if (tone == WarningTone.ATTENTION) "attention" else null, status["tone"])

            val switch = blocks.single { it["t"] == "switch" }
            assertEquals(WarningText.SWITCH, switch["title"])
            assertEquals(WarningText.SWITCH_SUMMARY, switch["summary"])
            assertEquals(r.state.enabled, switch["on"])

            val sections = blocks.filter { it["t"] == "section" }
            assertEquals(listOf(WarningText.CODES, WarningText.EVENTS), sections.map { it["label"] })
            val codes = ((sections[0]["body"] as Map<*, *>)["rows"] as List<*>).map { it as Map<*, *> }
            assertEquals(
                r.state.sightings.map {
                    WarningText.sightingTitle(it, r.nowMs, zone) to WarningText.sightingSummary(it, r.names)
                },
                codes.map { it["title"] to it["summary"] },
            )
            val events = ((sections[1]["body"] as Map<*, *>)["rows"] as List<*>).map { it as Map<*, *> }
            assertEquals(
                r.state.events.map { WarningText.eventTime(it.atMs, zone) to WarningText.eventText(it, r.names) },
                events.map { it["title"] to it["value"] },
            )
            assertTrue(events.all { it["kind"] == "pair" })

            val buttons = (blocks.single { it["t"] == "stack" }["items"] as List<*>).map { (it as Map<*, *>)["text"] }
            assertEquals(listOf(WarningText.EXPORT, WarningText.CLEAR), buttons)
        }
    }

    @Test
    fun theServiceRowSaysWhatThePageHolds() {
        val ids = SpecJson.fixtures.keys.filter { id ->
            val state = SpecJson.fixture(id)["state"] as? Map<*, *>
            state?.get("tile") == "SERVICE" && state["page"] == "main"
        }
        assertTrue(ids.size >= 4)
        ids.forEach { id ->
            val r = warnings(id)
            val rows = blocks(id).filter { it["t"] == "group" }
                .flatMap { (it["rows"] as List<*>).map { row -> row as Map<*, *> } }
            val row = rows.single { it["title"] == WarningText.TITLE }
            assertEquals(id, WarningText.row(r.state, r.nowMs, zone), row["summary"])
        }
    }
}
