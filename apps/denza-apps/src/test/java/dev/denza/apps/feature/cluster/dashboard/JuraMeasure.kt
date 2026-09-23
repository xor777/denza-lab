package dev.denza.apps.feature.cluster.dashboard

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.io.File
import kotlin.math.max

/**
 * How wide a string comes out in the Jura the app ships, measured without Android.
 *
 * The Contour's words are Jura 500 and its figures are `WideDigits`, which are arithmetic. A JVM
 * test has no `Paint` to measure the words with, and the old table of advances
 * (`ContourType.BOARD`) was Chrome's Roboto, whose Cyrillic runs up to a fifth wider or narrower
 * than Jura's. So this opens `res/font/jura_medium.ttf` - the file `LightPen` draws with - through
 * AWT and reads its advances: with the Luminofor board's font loading fixed, Chrome's Jura and this
 * agree to the hundredth on every glyph the panel prints.
 *
 * **Conservative in two ways.** It takes the wider of the kerned and the unkerned run, because the
 * car's Minikin and Chrome both kern and AWT only does when asked; and tracking is the board's,
 * `track × size` after every glyph but the last.
 */
internal object JuraMeasure {

    private val context = FontRenderContext(null, true, true)

    private val font: Font by lazy {
        System.setProperty("java.awt.headless", "true")
        Font.createFont(Font.TRUETYPE_FONT, locate())
    }

    fun width(text: String, size: Float, track: Float = 0f): Float {
        if (text.isEmpty()) return 0f
        val face = font.deriveFont(size)
        val plain = face.getStringBounds(text, context).width
        val kerned = face.deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))
            .getStringBounds(text, context).width
        return (max(plain, kerned) + track * size * (text.length - 1)).toFloat()
    }

    private fun locate(): File {
        var dir: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (dir != null) {
            listOf("src/main/res/font/jura_medium.ttf", "apps/denza-apps/src/main/res/font/jura_medium.ttf")
                .map { File(dir, it) }
                .firstOrNull { it.isFile }
                ?.let { return it }
            dir = dir.parentFile
        }
        error("jura_medium.ttf not found above ${System.getProperty("user.dir")}")
    }
}
