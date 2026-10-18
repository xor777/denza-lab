package dev.denza.apps.feature.network

import dev.denza.apps.design.luminofor.SpecJson
import org.junit.Assert.*
import org.junit.Test

class TrafficMonitorBoardTest {
    @Test fun `service boards expose the same states and words as the real page`() {
        val ids = SpecJson.fixtures.keys.filter { it.contains("sheet-service-mobile") }
        assertEquals(8, ids.size)
        ids.forEach { id ->
            val fixture = SpecJson.fixture(id)
            val sheet = fixture["sheet"] as Map<*, *>
            val state = (fixture["state"] as Map<*, *>)["trafficMonitor"] as Map<*, *>
            val snapshot = TrafficMonitorSnapshot(TrafficMonitorState.valueOf(state["state"] as String),
                state["busy"] == true, state["error"] as? String, state["result"] as? String)
            assertEquals(TrafficMonitorText.TITLE, sheet["title"])
            val blocks = (sheet["blocks"] as List<*>).map { it as Map<*, *> }
            assertTrue(blocks.none { it["t"] == "note" })
            assertTrue(blocks.none { it["t"] == "switch" })
            val row = (blocks.first { it["t"] == "group" }["rows"] as List<*>).single() as Map<*, *>
            assertEquals(TrafficMonitorText.MONITOR, row["title"])
            assertEquals(snapshot.summary, row["summary"])
            val buttons = blocks.filter { it["t"] == "button" }
            when {
                snapshot.known -> {
                    assertEquals(TrafficMonitorText.DISABLE,
                        buttons.single()["text"])
                    assertEquals(snapshot.canDisable, buttons.single()["enabled"])
                }
                snapshot.state == TrafficMonitorState.UNKNOWN && !snapshot.busy -> assertEquals(TrafficMonitorText.CHECK, buttons.single()["text"])
                else -> assertTrue(buttons.isEmpty())
            }
            assertEquals(listOfNotNull(snapshot.error ?: snapshot.result), blocks.filter { it["t"] == "status" }.map { it["text"] })
        }
    }
}
