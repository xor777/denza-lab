package dev.denza.apps.ui.dashboard

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.hud.HudNavigatorInstall
import dev.denza.apps.feature.hud.HudRadarInstall
import dev.denza.apps.ui.components.DenzaTileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `sheet-hud*` board of `fixtures.js` is [HudPanel.of] its `state`: the picture's lit parts,
 * then each row's words, value, greying and colour - in that order and nothing else. The board and
 * the panel move in one change or neither moves.
 */
class HudPanelBoardContractTest {

    @Test
    fun everyHudBoardIsThePanelOfItsState() {
        val boards = SpecJson.fixtures.keys.filter { it.startsWith("sheet-hud") }
        assertTrue("no HUD boards", boards.isNotEmpty())
        val seen = mutableSetOf<Any>()
        boards.forEach { id ->
            val fixture = SpecJson.fixture(id)
            val state = fixture["state"] as Map<*, *>
            val panel = panelOf(state)
            seen += if (panel.glass.cameras) "cameras" else "no cameras"
            seen += HudNavigatorInstall.valueOf(state["navigator"] as String)
            seen += HudRadarInstall.valueOf(state["radar"] as String)
            val sheet = fixture["sheet"] as Map<*, *>
            assertEquals("$id title", "HUD Подсказки", sheet["title"])
            assertEquals("$id subtitle", HudPanel.SUBTITLE, sheet["subtitle"])
            assertEquals("$id blocks", expected(panel), (sheet["blocks"] as List<*>).map { normal(it as Map<*, *>) })
        }
        // Every install stands on some board, and the glass with the cameras and without.
        val needed = HudNavigatorInstall.entries + HudRadarInstall.entries + "cameras" + "no cameras"
        assertEquals(needed.toSet(), seen.filter { it in needed }.toSet())
    }

    private fun expected(panel: HudPanel): List<Map<String, Any?>> = buildList {
        add(mapOf("t" to "glass", "turn" to panel.glass.turn, "sign" to panel.glass.sign,
            "map" to panel.glass.map, "cameras" to panel.glass.cameras))
        add(mapOf("t" to "group", "rows" to panel.rows.map { row ->
            mapOf("kind" to "switch", "title" to row.title, "summary" to row.summary, "on" to row.checked,
                "enabled" to row.enabled, "tone" to if (row.tone == DenzaTileTone.ATTENTION) "attention" else null)
        }))
    }

    /** A board's block with its defaults spelled out: enabled true, no tone. */
    private fun normal(block: Map<*, *>): Map<String, Any?> = when (block["t"]) {
        "glass" -> mapOf("t" to "glass", "turn" to block["turn"], "sign" to block["sign"], "map" to block["map"],
            "cameras" to block["cameras"])
        "group" -> mapOf("t" to "group", "rows" to (block["rows"] as List<*>).map { it as Map<*, *> }.map { row ->
            mapOf("kind" to row["kind"], "title" to row["title"], "summary" to row["summary"], "on" to row["on"],
                "enabled" to (row["enabled"] ?: true), "tone" to row["tone"])
        })
        else -> block.entries.associate { (k, v) -> k as String to v }
    }

    /** The panel a HUD board's `state` stands for: what [SheetFixtures] lays on the app's state. */
    private fun panelOf(state: Map<*, *>): HudPanel = HudPanel.of(
        navigator = HudNavigatorInstall.valueOf(state["navigator"] as String),
        radar = HudRadarInstall.valueOf(state["radar"] as String),
        hints = state["hud"] == true,
        map = state["map"] == true,
        sign = state["sign"] != false,
        cameras = state["cameras"] != false,
        busy = false,
    )
}
