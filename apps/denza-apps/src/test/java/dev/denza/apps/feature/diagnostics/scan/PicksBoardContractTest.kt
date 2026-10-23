package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.ui.ScannerWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every live table's board against [GraphPicks] (architecture, 7.3; `pickBox()`, `pick` in `scanner.json`): the
 * rows with a square are the rows the table lets on the graphs - a key and a line - the numbers in the squares are
 * the session's default where the board shows it and at most four, «Графики · N» counts them and cannot be chosen at
 * none, and «Выбор PID» is gone from every engine board.
 */
@Suppress("UNCHECKED_CAST")
class PicksBoardContractTest {
    private val systems = SystemStreams.ROWS.keys.filter { it != CarSystem.PACK }.map { "scanner-sys-${it.name.lowercase()}-live" }

    /** Boards whose ticks are the driver's choice, not the default: the engine's four, the pack's two. */
    private val chosen = listOf("scanner-ecu-live", "scanner-sys-engine", "scanner-sys-pack-live")

    private fun rows(id: String): List<Map<String, Any?>> = SpecJson.fixture(id)["rows"] as List<Map<String, Any?>>

    /** A board's row as the table has it: its key - a PID's, or a reading's by its source - and its trace. */
    private fun live(id: String, r: Map<String, Any?>): LiveRow {
        val trace = (r["trace"] as List<Number>).mapIndexed { i, v -> Sample(i * 1_000_000_000L, v.toFloat()) }
        val pid = r["pid"] as String
        val key: ParamKey? = if (id.contains("engine") || id.contains("ecu")) {
            ParamKey.Pid(0x7E0, pid.toInt(16))
        } else {
            val abbr = r["abbr"] as String
            val source = if (abbr.isEmpty()) pid else "$pid·$abbr"
            SystemStreams.ROWS.values.flatten().firstOrNull { it.source == source }?.key?.let(ParamKey::Signal)
        }
        return LiveRow(0, r["name"] as String, r["abbr"] as String, "", "", "", "", "", false, "", trace, key)
    }

    private fun pickOf(r: Map<String, Any?>) = (r["pick"] as Number).toInt()

    @Test
    fun theSquaresAreTheRowsWithAKeyAndALine() {
        (systems + chosen).forEach { id ->
            val f = SpecJson.fixture(id)
            assertEquals(id, true, f["pick"])
            rows(id).forEach { r -> assertEquals("$id ${r["name"]}", GraphPicks.pickable(live(id, r)), r["key"]) }
        }
    }

    @Test
    fun theTicksAreTheDefaultOrAChoiceOfAtMostFourAndTheSwitchCountsThem() {
        (systems + chosen).forEach { id ->
            val rs = rows(id)
            val ticked = rs.filter { pickOf(it) > 0 }.sortedBy(::pickOf)
            // numbered 1…n, only rows with a square
            assertEquals(id, (1..ticked.size).toList(), ticked.map(::pickOf))
            assertTrue(id, ticked.size <= GraphPicks.MAX)
            assertTrue(id, ticked.all { it["key"] == true })
            if (id in systems) assertEquals(id, GraphPicks.default(rs.map { live(id, it) }), ticked.map { live(id, it).key })
            val right = (SpecJson.fixture(id)["tabs"] as Map<String, Any?>)["right"] as Map<String, Any?>
            assertEquals(id, ScannerWords.views(ticked.size), right["items"])
            assertEquals(id, if (ticked.isEmpty()) 1 else null, (right["off"] as Number?)?.toInt())
        }
        // words only: nothing to tick, «Графики · 0» off
        listOf("scanner-sys-safety-live", "scanner-sys-body-live").forEach { id -> assertTrue(id, rows(id).none { it["key"] == true }) }
        // the engine's choice is the same on the block's board and the system's
        assertEquals(rows("scanner-ecu-live").map(::pickOf), rows("scanner-sys-engine").map(::pickOf))
    }

    @Test
    fun noEngineBoardHasAPidPickerAnyMore() {
        listOf("scanner-ecu-live", "scanner-sys-engine", "scanner-sys-engine-scheme", "scanner-sys-engine-graphs").forEach { id ->
            val buttons = ((SpecJson.fixture(id)["footer"] as Map<String, Any?>)["buttons"] as List<List<Any?>>).map { it[0] }
            assertFalse(id, "Выбор PID" in buttons)
            assertFalse(id, "Метка" in buttons)
            assertTrue(id, buttons.none { (it as String).startsWith("Сигналы") })
        }
    }
}
