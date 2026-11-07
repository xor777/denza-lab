package dev.denza.apps.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the unit-test seam picks the form Android picks. `Resources.getQuantityString` asks ICU's
 * `PluralRules` for the locale, which holds CLDR's rules for Russian. A plural test proves nothing if
 * the seam picks differently, so the rules are written here as CLDR writes them, for integers:
 *
 *     one:  i % 10 = 1 and i % 100 != 11
 *     few:  i % 10 = 2..4 and i % 100 != 12..14
 *     many: i % 10 = 0 or i % 10 = 5..9 or i % 100 = 11..14
 */
class RussianPluralCategoriesTest {
    private fun cldr(i: Int): String = when {
        i % 10 == 1 && i % 100 != 11 -> "one"
        i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
        i % 10 == 0 || i % 10 in 5..9 || i % 100 in 11..14 -> "many"
        else -> "other"
    }

    @Test
    fun theSeamPicksCldrsCategory() {
        val xml = StringsXml.parse(
            """
            <resources>
                <plurals name="p">
                    <item quantity="one">one</item>
                    <item quantity="few">few</item>
                    <item quantity="many">many</item>
                    <item quantity="other">other</item>
                </plurals>
            </resources>
            """.trimIndent(),
        )
        (0..1200).forEach { assertEquals("count $it", cldr(it), xml.quantity("p", it)) }
    }

    /** Android reads `other` for what CLDR's integer rules do not cover, so it is always filled. */
    @Test
    fun everyPluralInTheFileHasAllFourForms() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "apps/denza-apps/src/main/res/values/strings.xml") }
            .first { it.isFile }
        val plurals = StringsXml.parse(file.readText()).plurals
        assertTrue(plurals.isNotEmpty())
        plurals.forEach { (name, forms) ->
            assertEquals(name, setOf("one", "few", "many", "other"), forms.keys)
        }
    }
}
