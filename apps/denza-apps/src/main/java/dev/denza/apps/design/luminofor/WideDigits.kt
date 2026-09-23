package dev.denza.apps.design.luminofor

import android.graphics.Path
import androidx.core.graphics.PathParser
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits

/**
 * The wide square figures of the car's charging screen, as stroked paths.
 *
 * A figure is one stroke of constant weight on a 100-unit cap; its advance is the glyph's width
 * plus [Digits.TRACK]. A size is a font size in the old sense - the cap is 0.71 of it - so the
 * figures sit in the ladders the boards were already built on. Widths are pure arithmetic and hold
 * in a JVM test; the paths are parsed once, on first draw.
 */
object WideDigits {

    private val paths = HashMap<Char, Path?>()

    /** Board units per glyph unit at [size]. */
    fun k(size: Float): Float = size * Digits.CAP_RATIO / Digits.CAP

    /** The stroke a figure of [size] is drawn with unless the caller says otherwise. */
    fun stroke(size: Float): Float = maxOf(Digits.STROKE_MIN, size * Digits.STROKE_PER_SIZE)

    fun width(str: String, size: Float): Float {
        var w = 0f
        str.forEachIndexed { i, ch ->
            val g = Digits.GLYPHS[ch] ?: return@forEachIndexed
            w += g.first + if (i < str.length - 1) Digits.TRACK else 0f
        }
        return w * k(size)
    }

    /** The glyph's path on its 100-unit cap, or null for a space or an unknown character. */
    fun path(ch: Char): Path? = paths.getOrPut(ch) {
        val data = Digits.GLYPHS[ch]?.second
        if (data.isNullOrEmpty()) null else PathParser.createPathFromPathData(data)
    }
}
