package dev.denza.apps.feature.hud

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Repaints the keyed map window's frame for an additive projector, every pixel by its own colour.
 *
 * Yandex's night colours are made for a screen. On the glass its lime (free) and yellow (slow) are
 * 28° of hue apart and merge by day, blue is a twelfth of white's light, and its blue-grey roads
 * and grey route are dim, soft lines. So a coloured pixel takes the projector's colour for its hue
 * ([RED], [ORANGE] - the cursor -, [YELLOW], [GREEN], [BLUE], [MAGENTA]) at full saturation, its own
 * value kept as shading; a pixel with little colour - roads, labels, white arrows - goes to a cool
 * white ([COOL]) through a tone curve that lifts the dim road grey; and Yandex's grey route alone,
 * exactly neutral at mid value, turns [CYAN] where nothing white is within a pixel, so white text
 * keeps no cyan rim.
 *
 * Nothing is recognised and nothing is removed. The palette before this one (2026-10-12) cut labels
 * and pins out as islands, told signs from pins by shape and the blue route by its length, and
 * found the cursor by place. Each such decision flips with a pixel's change, and on the glass
 * (2026-10-14) that showed as rectangular cut-outs, objects coming and going between frames, an
 * object changing colour as it entered the window and a damaged cursor outline. A colour mapped
 * pixel by pixel, the transitions smooth, cannot flip an object: on the drive's frames, moved a pixel and 1.5 % in scale,
 * solid patches that changed outright fell from 59 to 9 (docs/hud-projection-findings.md, «Отзыв
 * владельца на стекле и мягкий вариант»). Labels, house numbers and pins stay as Yandex drew them:
 * by colour they are its roads.
 *
 * Built for the night theme: [isNight] says when the ground is one. Values are tabled by the
 * brightest channel and colours by whole degrees of hue, so a frame costs a 3×3 maximum and a few
 * multiplications a pixel. The scratch is kept between frames; one instance per thread.
 */
class HudMapPalette {
    private val value = IntArray(SIZE)
    private val near = IntArray(SIZE)

    /**
     * Repaints [pixels], the map window's 300×180 as opaque ARGB keyed to black ([HudMapKey]), into
     * [out]; [out] may be [pixels].
     */
    fun apply(pixels: IntArray, out: IntArray) {
        require(pixels.size >= SIZE && out.size >= SIZE) { "${pixels.size} pixels for $WIDTH×$HEIGHT" }
        for (i in 0 until SIZE) {
            val p = pixels[i]
            value[i] = max(p shr 16 and 0xFF, max(p shr 8 and 0xFF, p and 0xFF))
        }
        localMax()
        for (i in 0 until SIZE) out[i] = paint(pixels[i], value[i], near[i])
    }

    /** [near] := the brightest of each pixel's 3×3, the window's edge repeated. */
    private fun localMax() {
        for (y in 0 until HEIGHT) {
            val up = max(0, y - 1) * WIDTH
            val row = y * WIDTH
            val down = min(HEIGHT - 1, y + 1) * WIDTH
            for (x in 0 until WIDTH) {
                val l = max(0, x - 1)
                val r = min(WIDTH - 1, x + 1)
                var m = 0
                for (base in intArrayOf(up, row, down)) {
                    for (k in l..r) {
                        val v = value[base + k]
                        if (v > m) m = v
                    }
                }
                near[row + x] = m
            }
        }
    }

    companion object {
        const val WIDTH = HudMapGeometry.MAP_WIDTH
        const val HEIGHT = HudMapGeometry.MAP_HEIGHT
        private const val SIZE = WIDTH * HEIGHT

        val RED = floatArrayOf(1f, 40 / 255f, 40 / 255f)

        /** The cursor's gold, (233,177,53), comes out here; slow traffic's yellow is 19° away. */
        val ORANGE = floatArrayOf(1f, 120 / 255f, 0f)
        val YELLOW = floatArrayOf(1f, 230 / 255f, 0f)
        val GREEN = floatArrayOf(40 / 255f, 1f, 60 / 255f)

        /**
         * Lane balloons, road signs and pins: a dark blue, so their white glyphs stand out - the
         * owner's choice of 2026-10-14 (Yandex's own blue at about 0.6). A route Yandex draws blue
         * comes out as dark.
         */
        val BLUE = floatArrayOf(15 / 255f, 60 / 255f, 150 / 255f)
        val MAGENTA = floatArrayOf(230 / 255f, 60 / 255f, 1f)

        /** Yandex's grey route, a route with no traffic data. */
        val CYAN = floatArrayOf(0f, 225 / 255f, 1f)

        /** Roads, labels and white arrows. */
        val COOL = floatArrayOf(235 / 255f, 240 / 255f, 1f)

        /** Hue in degrees to the projector's colour: plateaus at Yandex's colours, slopes between. */
        private val ANCHORS: List<Pair<Int, FloatArray>> = listOf(
            0 to RED, 20 to RED, 32 to ORANGE, 46 to ORANGE, 54 to YELLOW, 66 to YELLOW,
            80 to GREEN, 165 to GREEN, 195 to BLUE, 255 to BLUE, 290 to MAGENTA, 335 to RED, 360 to RED,
        )

        /** Red, green and blue of the target for each whole degree, 0..360. */
        private val TARGET = FloatArray(361 * 3).also { table ->
            for (h in 0..360) {
                val k = ANCHORS.indexOfLast { it.first <= h }.coerceAtMost(ANCHORS.size - 2)
                val (h0, c0) = ANCHORS[k]
                val (h1, c1) = ANCHORS[k + 1]
                val t = if (h1 == h0) 0f else (h - h0).toFloat() / (h1 - h0)
                for (c in 0..2) table[h * 3 + c] = c0[c] + (c1[c] - c0[c]) * t
            }
        }

        /** How blue a hue is: Yandex's blue needs more saturation to count as a colour than others. */
        private val BLUENESS = FloatArray(361) { h -> smooth(175f, 195f, h.toFloat()) * (1f - smooth(255f, 275f, h.toFloat())) }

        /** A colour's shading by its value: its own light, a dim heavy-jam red included. */
        private val SHADE = FloatArray(256) { v -> (v / 255f / 0.85f).coerceIn(0f, 1f).pow(0.8f) }

        /** The tone curve of the colourless: road grey 0.47 to 0.64, grey route 0.63 to 0.74, white stays. */
        private val TONE = FloatArray(256) { v -> smooth(0.06f, 0.25f, v / 255f) * (v / 255f).pow(0.65f) }

        /** The grey route's value: mid, neither a dim edge nor white. */
        private val GREY_VALUE = FloatArray(256) { v -> smooth(0.42f, 0.60f, v / 255f) * (1f - smooth(0.70f, 0.90f, v / 255f)) }

        /** No white within a pixel: the brightest of the 3×3 is under white. */
        private val NO_WHITE_NEAR = FloatArray(256) { v -> 1f - smooth(0.78f, 0.96f, v / 255f) }

        /** The cyan of the grey route by its value. */
        private val CYAN_LEVEL = FloatArray(256) { v -> (v / 255f / 0.65f).coerceIn(0f, 1f) }

        private const val BLACK = 0xFF shl 24

        /** One pixel [p], its brightest channel [v] (0..255), the brightest of its 3×3 [nearMax]. */
        private fun paint(p: Int, v: Int, nearMax: Int): Int {
            if (v == 0) return BLACK
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val lo = min(r, min(g, b))
            val chroma = v - lo
            val s = chroma.toFloat() / v
            val hue = if (chroma == 0) {
                0f
            } else {
                val d = chroma.toFloat()
                60f * when (v) {
                    r -> ((g - b) / d).mod(6f)
                    g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                }
            }
            val degree = hue.toInt().coerceIn(0, 359)
            val frac = hue - degree
            val blueness = BLUENESS[degree]
            // Yandex's blue-grey roads are no colour (saturation 0.41 typical, 0.47 at the 99th
            // percentile on the drive of 2026-10-11); its blue is 0.9, an inactive lane's 0.55.
            val colourWeight = smooth(0.30f + 0.18f * blueness, 0.55f + 0.05f * blueness, s)
            val shade = SHADE[v]
            val tone = TONE[v]
            val grey = (1f - smooth(0.03f, 0.14f, s)) * GREY_VALUE[v] * NO_WHITE_NEAR[nearMax]
            val cyan = grey * CYAN_LEVEL[v]
            var out = BLACK
            for (c in 0..2) {
                val target = TARGET[degree * 3 + c] + (TARGET[(degree + 1) * 3 + c] - TARGET[degree * 3 + c]) * frac
                val neutral = tone * COOL[c] * (1f - grey) + cyan * CYAN[c]
                val level = colourWeight * target * shade + (1f - colourWeight) * neutral
                val byte = (level.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                out = out or (byte shl (16 - 8 * c))
            }
            return out
        }

        /**
         * Whether [ground] ([HudMapKey.ground]) is a night map's: the palette is made for those.
         * Yandex's day theme lays light grey ground and draws major roads in the yellow of slow
         * traffic, which would come out as route.
         */
        fun isNight(ground: Int): Boolean {
            val r = ground shr 16 and 0xFF
            val g = ground shr 8 and 0xFF
            val b = ground and 0xFF
            return max(r, max(g, b)) < NIGHT_GROUND_MAX
        }

        /** Above the brightest channel of a night ground: Yandex's, on the drive of 2026-10-11, was 62 (0x3E). */
        const val NIGHT_GROUND_MAX = 0x60

        private fun smooth(lo: Float, hi: Float, x: Float): Float {
            val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
