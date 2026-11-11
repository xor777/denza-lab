package dev.denza.apps.core

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A decimal number as the app's language writes one: «1,2» in Russian, "1.2" in English.
 *
 * For a figure printed by hand or with `Locale.US`, which is every figure that has to reach the same
 * digits whatever the language is. It reads [AppText.locale] and never `Locale.getDefault()`, which
 * is the car's language (docs/system-language.md, "The app's own language"). The Luminofor figures
 * have a glyph for both marks, at one advance, so neither moves a figure's width.
 */
object AppDecimal {
    private val separator: Char get() = AppText.language.decimalMark

    internal fun separator(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator

    /** The mark alone, for a library that writes the figure itself and cannot reach this object. */
    @JvmStatic
    fun mark(): Char = separator

    /** [printed], written with a point, with this language's mark in its place. */
    @JvmStatic
    fun written(printed: String): String = printed.replace('.', separator)

    /** A count of tenths: 12 is «1,2», -4 is «-0,4». */
    @JvmStatic
    fun tenths(tenths: Long): String {
        val sign = if (tenths < 0) "-" else ""
        val value = Math.abs(tenths)
        return "$sign${value / 10}$separator${value % 10}"
    }
}
