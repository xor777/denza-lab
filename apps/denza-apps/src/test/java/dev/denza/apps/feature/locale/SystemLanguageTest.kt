package dev.denza.apps.feature.locale

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile's word for a language against the word the car writes on the row.
 *
 * The subtitle exists to tell the driver what they are about to change, and the press opens BYD's
 * own list. If the two disagree - the tile saying "русский" over a row saying "Русский язык" - the
 * driver has to work out that they are the same thing. So the names are the firmware's own,
 * copied out of `CarSettingPlatform.apk`, and this holds them to it.
 */
class SystemLanguageTest {

    @Test
    fun aLanguageIsNamedTheWayTheCarsOwnListNamesIt() {
        assertEquals("Русский язык", SystemLanguage.nameOf(Locale("ru", "RU")))
        assertEquals("English", SystemLanguage.nameOf(Locale("en", "US")))
        assertEquals("Türkçe", SystemLanguage.nameOf(Locale("tr", "TR")))
        assertEquals("Қазақ", SystemLanguage.nameOf(Locale("kk", "KZ")))
        assertEquals("中文（简体）", SystemLanguage.nameOf(Locale("zh", "CN")))
    }

    /**
     * Android reports Hebrew as `iw` and Indonesian as `in`, and so does BYD's own presenter.
     * They meet without a translation table, and this is what says so.
     */
    @Test
    fun theLegacyLanguageCodesLandOnTheRightNames() {
        assertEquals("עִבְרִית", SystemLanguage.nameOf(Locale("iw", "IL")))
        assertEquals("Bahasa Indonesia", SystemLanguage.nameOf(Locale("in", "ID")))
    }

    /**
     * Region is how the list distinguishes four of its rows, and dropping it would name the wrong
     * one: Brazil and Portugal, Spain and Latin America, France and Canada, the US and Australia.
     */
    @Test
    fun theRegionalPairsAreKeptApart() {
        assertEquals("Português (Brasil)", SystemLanguage.nameOf(Locale("pt", "BR")))
        assertEquals("Português (Portugal)", SystemLanguage.nameOf(Locale("pt", "PT")))
        assertEquals("Español (América)", SystemLanguage.nameOf(Locale("es", "US")))
        assertEquals("Français (Canada)", SystemLanguage.nameOf(Locale("fr", "CA")))
        assertEquals("English (Australia)", SystemLanguage.nameOf(Locale("en", "AU")))
    }

    /**
     * A locale the list cannot be set to still has to read as something.
     *
     * `ru-UA` is not a row, and the car cannot be put into it from this picker - but a system left
     * in it by something else must not leave the tile blank. The language alone is enough to name
     * the row the car would use.
     */
    @Test
    fun aLocaleOutsideTheListStillReadsAsItsLanguage() {
        assertEquals("Русский язык", SystemLanguage.nameOf(Locale("ru", "UA")))
        assertEquals("Deutsch", SystemLanguage.nameOf(Locale("de")))
    }

    /** And a language the firmware never shipped falls back to Android's own word for it. */
    @Test
    fun aLanguageTheFirmwareDoesNotShipFallsBackToAndroid() {
        val name = SystemLanguage.nameOf(Locale("is", "IS"))
        assertTrue("an unshipped language still has to be named, got \"$name\"", name.isNotBlank())
        assertEquals(name.first(), name.first().uppercaseChar())
    }
}
