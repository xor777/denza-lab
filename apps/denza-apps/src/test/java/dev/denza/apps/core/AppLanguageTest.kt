package dev.denza.apps.core

import dev.denza.apps.R
import dev.denza.apps.feature.locale.SystemLanguage
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The stored choice, read as a starting process reads it, and what the words do with it. The car's
 * locale is set to the one that could do harm and is held to staying where it was.
 */
class AppLanguageTest {
    private val car = Locale.getDefault()

    @Before
    fun carOnEnglish() = Locale.setDefault(Locale.US)

    @After
    fun carBack() = Locale.setDefault(car)

    @Test
    fun noStoredChoiceIsRussianOnAnEnglishCar() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.ofStored(null))
        assertEquals(AppLanguage.RUSSIAN, AppText.language)
        assertEquals(Locale.forLanguageTag("ru-RU"), AppText.locale)
        assertEquals("Переносим…", AppText.s(R.string.navigation_transfer_overlay_text))
        assertEquals("Экран 2: 1,5 км", AppText.format("Экран %1\$d: %2\$.1f км", arrayOf<Any>(2, 1.5)))
        assertEquals("1,2", AppDecimal.tenths(12))
        assertEquals("21 функция ждёт", AppText.p(R.plurals.tile_service_waiting, 21, 21))
    }

    @Test
    fun anUnknownValueIsRussian() {
        listOf("", "de", "EN", "en-US", "english", "ru-RU").forEach {
            assertEquals("«$it»", AppLanguage.RUSSIAN, AppLanguage.ofStored(it))
        }
    }

    @Test
    fun aStoredValueNamesItsLanguage() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.ofStored("en"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.ofStored("ru"))
    }

    @Test
    fun eachLanguageIsStoredAsTheValueThatReadsItBack() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.ofStored(it.stored)) }
    }

    @Test
    fun aStoredEnglishGivesEnglishWordsAndADecimalPoint() = inLanguage(AppLanguage.ofStored("en")) {
        assertEquals(AppLanguage.ENGLISH, AppText.language)
        assertEquals("en", AppText.locale.language)
        assertEquals("Moving…", AppText.s(R.string.navigation_transfer_overlay_text))
        assertEquals("Screen 2: 1.5 km", AppText.format("Screen %1\$d: %2\$.1f km", arrayOf<Any>(2, 1.5)))
        assertEquals("1.2", AppDecimal.tenths(12))
        assertEquals("-0.4", AppDecimal.written("-0.4"))
    }

    /** Russian's `one` takes 21; English's does not, and it has no `few` or `many` to fall to. */
    @Test
    fun englishPicksEnglishPluralForms() = inEnglish {
        assertEquals("1 feature waits", AppText.p(R.plurals.tile_service_waiting, 1, 1))
        assertEquals("2 features wait", AppText.p(R.plurals.tile_service_waiting, 2, 2))
        assertEquals("21 features wait", AppText.p(R.plurals.tile_service_waiting, 21, 21))
        assertEquals("0 features wait", AppText.p(R.plurals.tile_service_waiting, 0, 0))
    }

    @Test
    fun theCategoriesOfEnglish() {
        val xml = StringsXml.parse(
            """<resources><plurals name="p"><item quantity="one">one</item><item quantity="other">other</item></plurals></resources>""",
        )
        assertEquals("one", xml.quantity("p", 1, AppLanguage.ENGLISH))
        (listOf(0, 2, 11, 21, 101, 1001)).forEach { assertEquals("$it", "other", xml.quantity("p", it, AppLanguage.ENGLISH)) }
    }

    /** What `values-en` leaves out comes from `values`, as `Resources` would answer. */
    @Test
    fun whatEnglishDoesNotTranslateFallsBackToTheDefault() = inEnglish {
        assertEquals("Русский", AppText.s(R.string.service_language_russian))
        assertEquals("English", AppText.s(R.string.service_language_english))
    }

    /** Someone who cannot read the panel finds the row by words that do not move with its language. */
    @Test
    fun theRowReadsTheSameInEitherLanguage() {
        fun row() = listOf(R.string.service_language_title, R.string.service_language_russian, R.string.service_language_english)
            .map(AppText::s)
        assertEquals(listOf("Язык / Language", "Русский", "English"), row())
        assertEquals(row(), inEnglish { row() })
    }

    /** The line under it is the one part in the current language. */
    @Test
    fun theRestartNoteIsInTheCurrentLanguage() {
        val russian = AppText.s(R.string.service_language_note)
        val english = inEnglish { AppText.s(R.string.service_language_note) }
        assertNotEquals(russian, english)
        assertTrue(russian, russian.any { it in 'а'..'я' })
        assertTrue(english, english.none { it in 'а'..'я' })
    }

    @Test
    fun theCarsLocaleIsNotTouchedAndTheTileStillReadsIt() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        val name = SystemLanguage.currentName()
        val inside = inEnglish {
            assertEquals(Locale.forLanguageTag("ru-RU"), Locale.getDefault())
            SystemLanguage.currentName()
        }
        assertEquals(name, inside)
        assertEquals(Locale.forLanguageTag("ru-RU"), Locale.getDefault())
    }

    @Test
    fun aRunInEnglishLeavesRussianBehind() {
        inEnglish { AppText.s(R.string.contour_unit_kw) }
        assertEquals(AppLanguage.RUSSIAN, AppText.language)
        assertEquals("кВт", AppText.s(R.string.contour_unit_kw))
        assertEquals("1,2", AppDecimal.tenths(12))
    }

    private class Recorder(private val written: Boolean = true) : AppLanguageSwitch.Steps {
        val steps = mutableListOf<String>()

        override fun write(language: AppLanguage): Boolean {
            steps += "write ${language.stored}"
            return written
        }

        override fun endPicker() {
            steps += "end picker"
        }

        override fun relaunch() {
            steps += "relaunch"
        }

        override fun endSelf() {
            steps += "end self"
        }
    }

    @Test
    fun theSameLanguageDoesNothing() {
        AppLanguage.entries.forEach { language ->
            val recorder = Recorder()
            assertEquals(
                AppLanguageSwitch.Result.SAME_LANGUAGE,
                AppLanguageSwitch.choose(language, language, recorder),
            )
            assertEquals(emptyList<String>(), recorder.steps)
        }
    }

    @Test
    fun aFailedWriteEndsNothing() {
        val recorder = Recorder(written = false)
        assertEquals(
            AppLanguageSwitch.Result.NOT_WRITTEN,
            AppLanguageSwitch.choose(AppLanguage.RUSSIAN, AppLanguage.ENGLISH, recorder),
        )
        assertEquals(listOf("write en"), recorder.steps)
    }

    /** The value is on disk before anything that could read it starts, and this process goes last. */
    @Test
    fun aNewLanguageIsWrittenThenThePickerThenTheRelaunchThenThisProcess() {
        val recorder = Recorder()
        assertEquals(
            AppLanguageSwitch.Result.RESTARTING,
            AppLanguageSwitch.choose(AppLanguage.ENGLISH, AppLanguage.RUSSIAN, recorder),
        )
        assertEquals(listOf("write ru", "end picker", "relaunch", "end self"), recorder.steps)
    }
}
