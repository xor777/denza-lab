package dev.denza.apps.ui.components

import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import kotlin.math.roundToInt

/**
 * What a feature's face is drawn with, decided from its tone alone: Luminofor's `tileFace()` as
 * data.
 *
 * The board has two faces and nothing else - a lit plate with a blue glyph haloed at
 * `head.icon.onGlow`, and a dark plate with a white glyph at `head.icon.offAlpha` and no halo -
 * and every word on either is white added onto the plate at an intensity of its own. That is the
 * whole of the design: no border, no accent caption, no dot. The app's five tones map onto those
 * two faces, and the two it has that the board does not draw keep the lit plate and change only
 * the light: a feature waiting on the driver lights its glyph and its status in the cluster's
 * orange, a broken one in its red, which are the same two alarm colours the car's own instruments
 * use for the same two things.
 *
 * Plain `Int` ARGB and no Compose, so a JVM test can hold every colour here to the board's own
 * arithmetic - which is the point, because these are not colours anybody picked. The board draws a
 * word as `rgba(white, a)` composited `lighter` over the plate, so what reaches the glass is the
 * plate plus `a` of white, channel by channel; Compose draws text source-over, so the tile is handed
 * that sum as an opaque colour instead. Over an opaque plate the two agree at every pixel a glyph
 * covers fully, and at its antialiased edge too until the sum clips at white.
 */
internal class TileFace private constructor(
    /** The lit plate or the dark one. */
    val lit: Boolean,
    /** `head.cardOn` or `head.cardOff`; also the colour a fader's knob is masked with. */
    val plate: Int,
    /** What the glyph is drawn with: its core is the stroke, its halo tints the glow. */
    val glyph: Light,
    val glyphIntensity: Float,
    /** The halo's share; zero draws the stroke alone. */
    val glyphGlow: Float,
    /** Whether the glyph's line is laid over the plate rather than added to it: an alarm's. */
    val glyphOver: Boolean,
    /** The name and the status line, already added onto the plate. */
    val name: Int,
    val status: Int,
) {

    companion object {

        /**
         * The name's intensity on a lit face and on a dark one - `tileFace()`'s `on ? 1 : 0.7`.
         *
         * These four and [KNOB_MASK] are the board's drawing code rather than its numbers:
         * `luminofor.js` writes them as literals and `spec.json` does not carry them, so
         * `LuminoforScreenContractTest` reads them out of the drawing code itself.
         */
        const val NAME_LIT: Float = 1f
        const val NAME_UNLIT: Float = 0.7f

        /** The status line's - `on ? 0.62 : 0.4`. */
        const val STATUS_LIT: Float = 0.62f
        const val STATUS_UNLIT: Float = 0.4f

        /** How much wider than a knob the plate-coloured disc under it is, in grid units. */
        const val KNOB_MASK: Float = 1.1f

        private val LIVE = lit(glyph = HeadInk.BLUE, status = HeadInk.WHITE)
        private val ATTENTION = alarm(ClusterInk.ORANGE)
        private val BROKEN = alarm(ClusterInk.RED)
        private val IDLE = TileFace(
            lit = false,
            plate = HeadInk.CARD_OFF,
            glyph = HeadInk.WHITE,
            glyphIntensity = Head.Icon.OFF_ALPHA,
            glyphGlow = 0f,
            glyphOver = false,
            name = lighter(HeadInk.CARD_OFF, HeadInk.WHITE.core, NAME_UNLIT),
            status = lighter(HeadInk.CARD_OFF, HeadInk.WHITE.core, STATUS_UNLIT),
        )

        /**
         * The face a tone is drawn with.
         *
         * [DenzaTileTone.WORKING] is lit: something is happening and the driver need do nothing,
         * which is the live face with a ring turning beside the glyph - the ring is the tile's, not
         * this table's, because the board has no working state to take one from.
         */
        fun of(tone: DenzaTileTone): TileFace = when (tone) {
            DenzaTileTone.LIVE, DenzaTileTone.WORKING -> LIVE
            DenzaTileTone.ATTENTION -> ATTENTION
            DenzaTileTone.BROKEN -> BROKEN
            DenzaTileTone.IDLE -> IDLE
        }

        /**
         * The lit plate with its glyph in [glyph] and its status line in [status]; the name stays
         * white.
         *
         * A calm status is white at the lit face's 0.62. A status that says something is wrong is
         * the car's own colour for it, whole - `sys_color_abnormal` `#FF9F19` or `sys_color_warning`
         * `#FF4046`, the light's halo. Dimmed like the calm line, their pale cores came out tan and
         * dusty pink on the plate, which is no colour the car uses for anything.
         */
        private fun lit(glyph: Light, status: Light) = TileFace(
            lit = true,
            plate = HeadInk.CARD_ON,
            glyph = glyph,
            glyphIntensity = 1f,
            glyphGlow = Head.Icon.ON_GLOW,
            glyphOver = false,
            name = lighter(HeadInk.CARD_ON, HeadInk.WHITE.core, NAME_LIT),
            status = if (status === HeadInk.WHITE) lighter(HeadInk.CARD_ON, status.core, STATUS_LIT) else status.halo,
        )

        /**
         * The lit plate with its glyph and its status in an alarm's own colour, whole: the glyph's
         * line is the light's halo laid over the plate, its glow still added round it. Added, as the
         * live glyph is, the car's orange came out yellow on the plate and its red came out pink -
         * the tile's `tileFace()` on the board draws both the same way.
         */
        private fun alarm(light: Light) = TileFace(
            lit = true,
            plate = HeadInk.CARD_ON,
            glyph = Light(light.halo, light.halo),
            glyphIntensity = 1f,
            glyphGlow = Head.Icon.ON_GLOW,
            glyphOver = true,
            name = lighter(HeadInk.CARD_ON, HeadInk.WHITE.core, NAME_LIT),
            status = light.halo,
        )

        /**
         * `rgba(light, intensity)` composited `lighter` over an opaque [under], as Chrome does it.
         *
         * Chrome quantises the colour's alpha to eight bits, premultiplies the colour by it with
         * rounding, and adds with a clamp at white. Measured on the boards' own Chrome: white at
         * 0.62 over `#2C2B33` is `#CAC9D1`, at 0.7 over `#17161B` it is `#CAC9CE`, and `#FFC882` at
         * 0.62 over `#2C2B33` is `#CAA784` - which is what this returns, to the level.
         */
        fun lighter(under: Int, light: Int, intensity: Float): Int {
            val a8 = (intensity.coerceIn(0f, 1f) * 255f).roundToInt()
            fun channel(shift: Int): Int {
                val dst = (under ushr shift) and 0xFF
                val src = (((light ushr shift) and 0xFF) * a8 / 255f).roundToInt()
                return minOf(255, dst + src) shl shift
            }
            return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
        }
    }
}
