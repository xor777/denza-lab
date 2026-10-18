package dev.denza.apps.ui.dashboard

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.hud.HudCameraPlace
import dev.denza.apps.feature.hud.HudNavigatorInstall
import dev.denza.apps.feature.hud.HudRadarInstall
import dev.denza.apps.ui.components.DenzaTileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `sheet-hud*` board of `fixtures.js` is [HudPanel.of] its `state`: the picture's lit parts,
 * each row's words, value, greying and colour, the place of the cameras and the paragraph - in
 * that order and nothing else. The board and the panel move in one change or neither moves.
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
            seen += panel.glass.cameras ?: "no cameras"
            seen += HudNavigatorInstall.valueOf(state["navigator"] as String)
            seen += HudRadarInstall.valueOf(state["radar"] as String)
            val sheet = fixture["sheet"] as Map<*, *>
            assertEquals("$id title", "HUD Подсказки", sheet["title"])
            assertEquals("$id subtitle", HudPanel.SUBTITLE, sheet["subtitle"])
            assertEquals("$id blocks", expected(panel), (sheet["blocks"] as List<*>).map { normal(it as Map<*, *>) })
        }
        // Every install and both places stand on some board.
        val needed = HudNavigatorInstall.entries + HudRadarInstall.entries + HudCameraPlace.entries + "no cameras"
        assertEquals(needed.toSet(), seen.filter { it in needed }.toSet())
    }

    private fun expected(panel: HudPanel): List<Map<String, Any?>> = buildList {
        add(mapOf("t" to "glass", "turn" to panel.glass.turn, "sign" to panel.glass.sign,
            "map" to panel.glass.map, "cameras" to panel.glass.cameras?.name))
        add(mapOf("t" to "group", "rows" to panel.rows.map { row ->
            mapOf("kind" to "switch", "title" to row.title, "summary" to row.summary, "on" to row.checked,
                "enabled" to row.enabled, "tone" to if (row.tone == DenzaTileTone.ATTENTION) "attention" else null)
        }))
        panel.place?.let { place ->
            add(mapOf("t" to "section", "label" to HudPanel.PLACE_LABEL, "body" to mapOf(
                "t" to "segmented", "labels" to HudPanel.PLACES, "selected" to place.ordinal, "enabled" to panel.placeEnabled,
            )))
        }
        panel.note?.let { add(mapOf("t" to "note", "text" to it)) }
    }

    /** A board's block with its defaults spelled out: enabled true, no tone, numbers as Int. */
    private fun normal(block: Map<*, *>): Map<String, Any?> = when (block["t"]) {
        "glass" -> mapOf("t" to "glass", "turn" to block["turn"], "sign" to block["sign"], "map" to block["map"],
            "cameras" to block["cameras"])
        "group" -> mapOf("t" to "group", "rows" to (block["rows"] as List<*>).map { it as Map<*, *> }.map { row ->
            mapOf("kind" to row["kind"], "title" to row["title"], "summary" to row["summary"], "on" to row["on"],
                "enabled" to (row["enabled"] ?: true), "tone" to row["tone"])
        })
        "section" -> {
            val body = block["body"] as Map<*, *>
            mapOf("t" to "section", "label" to block["label"], "body" to mapOf(
                "t" to body["t"], "labels" to body["labels"], "selected" to (body["selected"] as Number).toInt(),
                "enabled" to (body["enabled"] ?: true),
            ))
        }
        else -> block.entries.associate { (k, v) -> k as String to v }
    }

    companion object {
        /** The panel a HUD board's `state` stands for: what [SheetFixtures] lays on the app's state. */
        internal fun panelOf(state: Map<*, *>): HudPanel = HudPanel.of(
            navigator = HudNavigatorInstall.valueOf(state["navigator"] as String),
            radar = HudRadarInstall.valueOf(state["radar"] as String),
            hints = state["hud"] == true,
            map = state["map"] == true,
            sign = state["sign"] != false,
            cameras = state["cameras"] != false,
            place = HudCameraPlace.valueOf(state["place"] as String),
            busy = false,
        )
    }
}
