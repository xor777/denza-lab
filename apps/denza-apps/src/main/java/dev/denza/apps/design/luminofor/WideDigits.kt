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
 *
 * ### Nothing is allocated to look a glyph up
 *
 * [Digits.GLYPHS] is keyed by `Char`, and a `Char` key is boxed on every lookup - free under 128,
 * where the JVM keeps a cache, and a new object for «°», which is on every temperature on both
 * screens. So the map is read once, here, into arrays indexed by a glyph number, and [glyph] turns a
 * character into that number through a table indexed by its code. The spec's map stays the one
 * record: `LuminoforSpecContractTest` reads it against `spec.json`, and this is built from it.
 */
object WideDigits {

    /** What [glyph] answers for a character the figures do not draw. */
    const val NONE = -1

    /** Each glyph's advance without tracking, in [Digits.GLYPHS]' order. */
    private val advances = FloatArray(Digits.GLYPHS.size)

    /** And its path data, empty for a glyph that is only an advance (the space). */
    private val data = arrayOfNulls<String>(Digits.GLYPHS.size)

    /** Glyph number by character code, [NONE] where there is no glyph; as long as the last code. */
    private val numbers: IntArray

    /** The parsed paths, filled on first draw - `null` either before that or for an empty glyph. */
    private val paths = arrayOfNulls<Path>(Digits.GLYPHS.size)
    private val parsed = BooleanArray(Digits.GLYPHS.size)

    init {
        numbers = IntArray(Digits.GLYPHS.keys.maxOf { it.code } + 1) { NONE }
        var index = 0
        for ((ch, glyph) in Digits.GLYPHS) {
            advances[index] = glyph.first
            data[index] = glyph.second
            numbers[ch.code] = index
            index++
        }
    }

    /** The glyph number of [ch], or [NONE] for a character the figures do not draw. */
    fun glyph(ch: Char): Int {
        val code = ch.code
        return if (code < numbers.size) numbers[code] else NONE
    }

    /** A glyph's advance on its 100-unit cap, without tracking. */
    fun advance(glyph: Int): Float = advances[glyph]

    /** Board units per glyph unit at [size]. */
    fun k(size: Float): Float = size * Digits.CAP_RATIO / Digits.CAP

    /** The stroke a figure of [size] is drawn with unless the caller says otherwise. */
    fun stroke(size: Float): Float = maxOf(Digits.STROKE_MIN, size * Digits.STROKE_PER_SIZE)

    fun width(str: String, size: Float): Float {
        var w = 0f
        for (i in str.indices) {
            val g = glyph(str[i])
            if (g == NONE) continue
            w += advances[g] + if (i < str.length - 1) Digits.TRACK else 0f
        }
        return w * k(size)
    }

    /** A glyph's path on its 100-unit cap, or null for the space; [glyph] is never [NONE]. */
    fun path(glyph: Int): Path? {
        if (!parsed[glyph]) {
            val d = data[glyph]
            paths[glyph] = if (d.isNullOrEmpty()) null else PathParser.createPathFromPathData(d)
            parsed[glyph] = true
        }
        return paths[glyph]
    }
}
