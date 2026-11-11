package dev.denza.apps.ui

import dev.denza.apps.R
import dev.denza.apps.core.AppLanguage
import dev.denza.apps.core.AppText
import dev.denza.apps.core.EnglishWords
import dev.denza.apps.design.luminofor.SpecJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The language row of «Сервис» on the boards of the panel's first page says what `ServiceMain` writes. */
open class LanguageRowBoardTest {
    private val boards = listOf("sheet-service", "sheet-service-trouble", "sheet-service-access", "one-sheet-service-trouble")

    private fun blocks(id: String) =
        ((SpecJson.fixture(id)["sheet"] as Map<*, *>)["blocks"] as List<*>).map { it as Map<*, *> }

    @Test
    fun everyFirstPageHoldsTheRowInTheAppsWords() {
        boards.forEach { id ->
            val section = blocks(id).single { it["label"] == AppText.s(R.string.service_language_title) }
            val (tabs, note) = (section["body"] as List<*>).map { it as Map<*, *> }
            assertEquals(id, "segmented", tabs["t"])
            assertEquals(
                id,
                listOf(AppText.s(R.string.service_language_russian), AppText.s(R.string.service_language_english)),
                tabs["labels"],
            )
            val tab = if (AppText.language == AppLanguage.ENGLISH) ENGLISH_TAB else RUSSIAN_TAB
            assertEquals(id, tab, (tabs["selected"] as Number).toInt())
            assertEquals(id, AppText.s(R.string.service_language_note), note["text"])
        }
    }

    /** Above the pages, so that it is on the first screen however long the list under it grows. */
    @Test
    fun theRowStandsAboveThePages() {
        boards.forEach { id ->
            val blocks = blocks(id)
            val row = blocks.indexOfFirst { it["label"] == AppText.s(R.string.service_language_title) }
            val pages = blocks.indexOfLast { it["t"] == "group" }
            assertTrue(id, row < pages)
        }
    }

    private companion object {
        /** No stored choice: Russian. */
        const val RUSSIAN_TAB = 0

        /** The second tab; an English board is a board of the app running in English. */
        const val ENGLISH_TAB = 1
    }
}

/** The row on `fixtures.en.json`, with English on. */
class LanguageRowBoardEnglishTest : LanguageRowBoardTest() {
    @get:Rule
    val english = EnglishWords()
}
