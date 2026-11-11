package dev.denza.apps.core

import dev.denza.apps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AppTextTest {
    @Test
    fun aStringResolvesToWhatTheFileSays() {
        assertEquals("Переносим…", AppText.s(R.string.navigation_transfer_overlay_text))
        assertEquals("Denza Apps", AppText.s(R.string.app_name))
    }

    @Test
    fun anUnknownIdSaysSo() {
        assertThrows(IllegalStateException::class.java) { AppText.s(-1) }
    }

    /** A word on a draw loop reaches its source once, and every frame after gets the same string. */
    @Test
    fun aWordIsReadOnce() {
        val first = AppText.s(R.string.contour_unit_kw)
        val before = StringsXmlSource.lookups.get()
        repeat(1_000) { assertSame(first, AppText.s(R.string.contour_unit_kw)) }
        assertEquals(before, StringsXmlSource.lookups.get())
    }

    /** A format string is the same word, with the formatting left to its caller's memo. */
    @Test
    fun aFormatStringIsReadOnceAndFilledEachTime() {
        assertEquals("ПОСЛЕДНИЕ 1:22", AppText.s(R.string.contour_engine_window, "1:22"))
        val before = StringsXmlSource.lookups.get()
        assertEquals("ПОСЛЕДНИЕ 2:00", AppText.s(R.string.contour_engine_window, "2:00"))
        assertEquals("ПОСЛЕДНИЕ %1\$s", AppText.s(R.string.contour_engine_window))
        assertEquals(before, StringsXmlSource.lookups.get())
    }

    /** Two ids in one slot cost a second read, and never answer for each other. */
    @Test
    fun idsThatShareASlotDoNotAnswerForEachOther() {
        val memo = WordMemo()
        val id = 0x7f130001
        val other = id + WordMemo.SIZE
        assertNull(memo.get(id))
        memo.put(id, "a")
        assertEquals("a", memo.get(id))
        assertNull(memo.get(other))
        memo.put(other, "b")
        assertEquals("b", memo.get(other))
        assertNull(memo.get(id))
    }

    /** The decimal comma is the locale's, as `Resources.getString(id, args)` would write it. */
    @Test
    fun argumentsFormatInRussian() {
        assertEquals("Экран 2: 1,5 км", AppText.format("Экран %1\$d: %2\$.1f км", arrayOf<Any>(2, 1.5)))
    }

    /** `getString(id)` does not format, so a bare percent sign is safe in a string with no arguments. */
    @Test
    fun noArgumentsMeansNoFormatting() {
        assertEquals("100 %", AppText.format("100 %", emptyArray<Any>()))
    }

    @Test
    fun pluralFormsFollowRussian() {
        val xml = StringsXml.parse(
            """
            <resources>
                <plurals name="apps">
                    <item quantity="one">%d приложение</item>
                    <item quantity="few">%d приложения</item>
                    <item quantity="many">%d приложений</item>
                    <item quantity="other">%d приложения</item>
                </plurals>
                <plurals name="onlyOther">
                    <item quantity="other">всегда</item>
                </plurals>
            </resources>
            """.trimIndent(),
        )
        val forms = listOf(0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111)
            .associateWith { xml.quantity("apps", it).removePrefix("%d приложени") }
        assertEquals(
            mapOf(
                0 to "й", 1 to "е", 2 to "я", 4 to "я", 5 to "й", 11 to "й", 12 to "й",
                14 to "й", 21 to "е", 22 to "я", 25 to "й", 101 to "е", 111 to "й",
            ),
            forms,
        )
        assertEquals("всегда", xml.quantity("onlyOther", 3))
    }

    /** The text as aapt2 compiles it, or a test would pass on words the APK does not hold. */
    @Test
    fun theFileIsReadTheWayAapt2CompilesIt() {
        assertEquals("a b", StringsXml.compiled("  a \n   b  "))
        assertEquals("it's", StringsXml.compiled("it\\'s"))
        assertEquals("a\nb\tc", StringsXml.compiled("a\\nb\\tc"))
        assertEquals("a  b", StringsXml.compiled("\"a  b\""))
        assertEquals("\"a\"", StringsXml.compiled("\\\"a\\\""))
        assertEquals("ё", StringsXml.compiled("\\u0451"))
        assertEquals("@x ?y", StringsXml.compiled("\\@x \\?y"))
        assertEquals("@x", StringsXml.compiled("\"@x\""))
    }

    /** Whitespace is ASCII alone: aapt2 keeps these at the ends and between words. */
    @Test
    fun wideSpacesSurvive() {
        listOf("\u00a0", "\u202f", "\u2009", "\u3000").forEach { space ->
            assertEquals("${space}a$space", StringsXml.compiled("${space}a$space"))
        }
        assertEquals("\u202fa \u202f b\u202f", StringsXml.compiled("\u202fa \u202f b\u202f"))
    }

    /** Each expected value is what `aapt2 dump resources` printed for the same text. */
    @Test
    fun quotesEndARunOfSpaces() {
        assertEquals("a  b", StringsXml.compiled("a \"\" b"))
        assertEquals("a b", StringsXml.compiled("a \"b\""))
        assertEquals("a  b  c", StringsXml.compiled("a \" b \" c"))
        assertEquals("a b", StringsXml.compiled("\"a\"  \"b\""))
        assertEquals(" a ", StringsXml.compiled("\" a \""))
    }

    @Test
    fun aTrailingBackslashIsDropped() {
        assertEquals("a", StringsXml.compiled("a\\"))
        assertEquals("a ", StringsXml.compiled("a \\ "))
        assertEquals("a  b", StringsXml.compiled("a\\ \\ b"))
        assertEquals("a  b c", StringsXml.compiled("a\\u0020\\u0020b  c"))
    }

    @Test
    fun aReferenceFailsLoudly() {
        listOf("@string/x", "?attr/x", "  @string/x", "\n?attr/x").forEach {
            assertThrows(it, IllegalStateException::class.java) { StringsXml.compiled(it) }
        }
    }
}
