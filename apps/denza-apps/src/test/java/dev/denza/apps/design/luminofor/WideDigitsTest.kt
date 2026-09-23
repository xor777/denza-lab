package dev.denza.apps.design.luminofor

import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WideDigits]' lookup is [Digits.GLYPHS] read once into arrays, and nothing else.
 *
 * The figures are held to the boards pixel for pixel, so a lookup that allocates nothing has to
 * answer exactly what the map answers: the same glyph for every character the spec draws, none for
 * any other, and widths equal to the map's own arithmetic to the last bit rather than to a
 * tolerance.
 */
class WideDigitsTest {

    @Test
    fun everyCharacterTheSpecDrawsHasItsOwnGlyphAndNoOtherHasOne() {
        Digits.GLYPHS.forEach { (ch, glyph) ->
            val number = WideDigits.glyph(ch)
            assertTrue("«$ch» has a glyph", number != WideDigits.NONE)
            assertEquals("«$ch»'s advance", glyph.first, WideDigits.advance(number), 0f)
        }
        assertEquals(Digits.GLYPHS.size, Digits.GLYPHS.keys.map { WideDigits.glyph(it) }.toSet().size)
        listOf('a', 'Z', 'в', 'Я', '·', '−', '€', '\u0000', '\u00AF', '\u00B1', '\uFFFF').forEach {
            assertEquals("«$it» is not a figure", WideDigits.NONE, WideDigits.glyph(it))
        }
    }

    @Test
    fun aWidthIsTheMapsOwnArithmeticToTheBit() {
        val sizes = listOf(17f, 19f, 30f, 34f, 52f, 88f)
        val strings = listOf(
            "", "0", "000", "9,3", "-188,8", "12:30", "28°", "-9°", "+1,2", "1 2", "12.5",
            // A character the figures do not draw takes no advance and leaves the tracking as it was.
            "1a2", "°x", "кВт",
        )
        for (size in sizes) {
            for (s in strings) {
                assertEquals("«$s» at $size", mapWidth(s, size), WideDigits.width(s, size), 0f)
            }
        }
    }

    /** The width as the map computed it before the lookup was an array: the reference. */
    private fun mapWidth(str: String, size: Float): Float {
        var w = 0f
        str.forEachIndexed { i, ch ->
            val g = Digits.GLYPHS[ch] ?: return@forEachIndexed
            w += g.first + if (i < str.length - 1) Digits.TRACK else 0f
        }
        return w * (size * Digits.CAP_RATIO / Digits.CAP)
    }
}
