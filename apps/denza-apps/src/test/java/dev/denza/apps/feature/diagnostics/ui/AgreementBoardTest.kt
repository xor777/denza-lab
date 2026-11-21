package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.core.EnglishWords
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.AgreementBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * `modal-diagnostics` and `-agreed` draw what [AgreementWindow] writes - the title, the three
 * paragraphs in order, the box's words and «ОК» - with no glyph and nothing else, and «ОК» lit
 * exactly where [AgreementBox] lets it answer.
 */
open class AgreementBoardTest {

    private fun modal(id: String) = SpecJson.fixture(id)["modal"] as Map<*, *>

    @Test
    fun bothBoardsHoldTheAppsWords() {
        BOARDS.keys.forEach { id ->
            val modal = modal(id)
            assertEquals(id, AppText.s(R.string.diagnostics_agreement_title), modal["title"])
            assertEquals(id, AGREEMENT_PARAGRAPHS.map(AppText::s), modal["message"])
            assertEquals(id, AppText.s(R.string.diagnostics_agreement_tick), modal["checkbox"])
            assertEquals(id, AppText.s(R.string.diagnostics_agreement_ok), modal["primary"])
            assertNull("$id: no glyph", modal["icon"])
            assertNull("$id: no further words", modal["details"])
            assertNull("$id: no other button", modal["quiet"])
        }
    }

    @Test
    fun okIsLitWhereTheBoxLetsItAnswer() {
        BOARDS.forEach { (id, ticked) ->
            val modal = modal(id)
            val state = SpecJson.fixture(id)["state"] as Map<*, *>
            assertEquals(id, "diagnostics", state["window"])
            assertEquals(id, ticked, state["ticked"])
            assertEquals(id, ticked, modal["checked"])
            assertEquals(id, AgreementBox(ticked).okEnabled, modal["primaryEnabled"])
        }
    }

    private companion object {
        /** Each board and its box. */
        val BOARDS = mapOf("modal-diagnostics" to false, "modal-diagnostics-agreed" to true)
    }
}

/** The same two boards on `fixtures.en.json`, with English on. */
class AgreementBoardEnglishTest : AgreementBoardTest() {
    @get:Rule
    val english = EnglishWords()
}
