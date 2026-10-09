package dev.denza.apps.ui.dashboard

import dev.denza.apps.design.luminofor.SpecJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tile's panel and its `sheet-*` board say the same paragraph.
 *
 * The paragraph is the one place a panel explains its feature, and the board is drawn from
 * `fixtures.js` while the app writes it in [helpOf]: an edit to one alone left them apart without a
 * test noticing, and the screenshot comparison would only find it as moved pixels. This holds every
 * board of a tile's own panel - not its pages of applications, not «Сервис» or Shortcuts, which
 * have panels of their own - to the app's words.
 */
class SheetBoardContractTest {

    @Test
    fun everyPanelBoardDrawsTheAppsParagraph() {
        val boards = SpecJson.fixtures.keys.filter { it.startsWith("sheet-") || it.startsWith("one-sheet-") }
            .mapNotNull { id ->
                val fixture = SpecJson.fixture(id)
                val state = fixture["state"] as Map<*, *>
                val tile = TileId.valueOf(state["tile"] as String)
                if (tile in OWN_PANELS || state["page"] != null) return@mapNotNull null
                val blocks = (fixture["sheet"] as Map<*, *>)["blocks"] as List<*>
                val notes = blocks.map { it as Map<*, *> }.filter { it["t"] == "note" }.map { it["text"] }
                Triple(id, tile, notes)
            }
        assertTrue("no panel board found", boards.any { it.second == TileId.HUD })

        boards.forEach { (id, tile, notes) ->
            assertEquals("$id draws a paragraph the app does not write", listOf(helpOf(tile)), notes)
        }
    }

    private companion object {
        val OWN_PANELS = setOf(TileId.SERVICE, TileId.DEFAULT_APPS)
    }
}
