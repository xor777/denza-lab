package dev.denza.apps.design.luminofor

import dev.denza.apps.feature.trip.StripGeometry
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.io.File
import kotlin.math.max

/**
 * How wide a line comes out in the font files themselves, on the JVM, with no `Paint`.
 *
 * The same reading as `JuraMeasure`, for every face a drawn word is set in: Jura is the file the app
 * ships in `res/font`. The system sans has no file in `res/font`, because the app takes it from the
 * firmware, so Roboto here is `tools/design-canvas/luminofor/fonts/`, the cut the boards are rendered
 * with. `docs/system-language.md`, "The English text", says how this compares with `Paint`.
 *
 * It takes the wider of the kerned and the unkerned run, as `Paint` and Chrome kern and an AWT run
 * asked for nothing does not. Tracking is `track × size` after every glyph but the last. A glyph the
 * face does not have is a full em, since on the car it comes from a fallback font nobody here can see.
 */
internal enum class FontMeasure(private val path: String) {
    JURA("apps/denza-apps/src/main/res/font/jura_medium.ttf"),
    SANS("tools/design-canvas/luminofor/fonts/Roboto-400.ttf"),
    SANS_STRONG("tools/design-canvas/luminofor/fonts/Roboto-500.ttf"),
    ;

    private val font: Font by lazy {
        System.setProperty("java.awt.headless", "true")
        Font.createFont(Font.TRUETYPE_FONT, locate(path))
    }

    /** What this face lacks of [text], spaces aside: on a car, those come from elsewhere. */
    fun missing(text: String): Set<Char> = text.filter { it != ' ' && !font.canDisplay(it) }.toSet()

    fun width(text: String, size: Float, track: Float = 0f): Float {
        if (text.isEmpty()) return 0f
        val face = font.deriveFont(size)
        val kerning = face.deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))
        var width = 0.0
        // The runs either side of a missing glyph, because the two cannot kern across one.
        text.split(*missing(text).toCharArray()).filter { it.isNotEmpty() }.forEach { run ->
            width += max(face.getStringBounds(run, CONTEXT).width, kerning.getStringBounds(run, CONTEXT).width)
        }
        width += text.count { it != ' ' && !font.canDisplay(it) } * size
        return (width + track * size * (text.length - 1)).toFloat()
    }

    /** [StripGeometry]'s two faces on these files, where `StripGeometryTest` has an over-estimate. */
    object Strip : StripGeometry.Measure {
        override fun sans(text: String, size: Float, strong: Boolean): Float =
            (if (strong) SANS_STRONG else SANS).width(text, size)
    }

    private companion object {
        val CONTEXT = FontRenderContext(null, true, true)

        fun locate(path: String): File =
            generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("$path not found above ${System.getProperty("user.dir")}")
    }
}
