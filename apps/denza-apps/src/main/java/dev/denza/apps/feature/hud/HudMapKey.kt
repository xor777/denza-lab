package dev.denza.apps.feature.hud

import kotlin.math.sqrt

/**
 * Turns the map's ground to black, because a HUD adds light: black is what it does not draw.
 *
 * Night Yandex lays its map on dark blue, which on the glass would be a dim rectangle; day Yandex
 * on light grey, which would be a bright one. The ground is the colour most of the cut has - the
 * fullest cell of a coarse histogram, taken as the mean of the pixels in it - and every pixel
 * keeps as much of itself as it is far from that colour: none within [NEAR], all from [FAR], a
 * ramp between, by plain distance in RGB. Roads, the route and the cursor stand off any ground and
 * survive; the ground and its texture go.
 *
 * The thresholds are first guesses, named so they can be tuned on frames recorded on the road
 * ([HudMapCapture]). The histogram is kept between frames: the frame thread keys several frames a
 * second. One instance per thread.
 */
class HudMapKey {
    private val counts = IntArray(CELLS)
    private val sums = LongArray(CELLS * 3)

    /** The ground of the first [count] pixels, as opaque `0xFFrrggbb`. */
    fun ground(pixels: IntArray, count: Int = pixels.size): Int {
        counts.fill(0)
        sums.fill(0L)
        for (index in 0 until count) {
            val pixel = pixels[index]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            val cell = (red shr SHIFT shl (2 * BITS)) or (green shr SHIFT shl BITS) or (blue shr SHIFT)
            counts[cell]++
            sums[cell * 3] += red.toLong()
            sums[cell * 3 + 1] += green.toLong()
            sums[cell * 3 + 2] += blue.toLong()
        }
        var best = 0
        for (cell in 1 until CELLS) {
            if (counts[cell] > counts[best]) best = cell
        }
        val n = counts[best].coerceAtLeast(1)
        val red = (sums[best * 3] / n).toInt()
        val green = (sums[best * 3 + 1] / n).toInt()
        val blue = (sums[best * 3 + 2] / n).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * Keys the first [count] pixels against [ground] in place; every pixel comes out opaque. The
     * square root is taken only on the ramp: most of a map is ground, and the frame thread keys
     * every frame.
     */
    fun apply(pixels: IntArray, ground: Int, count: Int = pixels.size) {
        val groundRed = ground shr 16 and 0xFF
        val groundGreen = ground shr 8 and 0xFF
        val groundBlue = ground and 0xFF
        for (index in 0 until count) {
            val pixel = pixels[index]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            val dr = red - groundRed
            val dg = green - groundGreen
            val db = blue - groundBlue
            val distance2 = dr * dr + dg * dg + db * db
            pixels[index] = when {
                distance2 <= NEAR_SQUARED -> BLACK
                distance2 >= FAR_SQUARED -> pixel or BLACK
                else -> {
                    val alpha = (sqrt(distance2.toFloat()) - NEAR) / (FAR - NEAR)
                    BLACK or
                        ((red * alpha + 0.5f).toInt() shl 16) or
                        ((green * alpha + 0.5f).toInt() shl 8) or
                        (blue * alpha + 0.5f).toInt()
                }
            }
        }
    }

    companion object {
        /** RGB distance to the ground at and below which a pixel is ground. */
        const val NEAR = 22f

        /** RGB distance from which a pixel is kept whole. */
        const val FAR = 90f

        private const val NEAR_SQUARED = (NEAR * NEAR).toInt()
        private const val FAR_SQUARED = (FAR * FAR).toInt()
        private const val BLACK = 0xFF shl 24

        /** Bits a channel keeps in the histogram: 16 levels, 4096 cells. */
        private const val BITS = 4
        private const val SHIFT = 8 - BITS
        private const val CELLS = 1 shl (3 * BITS)
    }
}

/** Pixel shuffles of the map pipeline, on plain `IntArray`s. */
object HudMapPixels {
    /**
     * Halves [width]×[height] ARGB pixels into [out] by the mean of each 2×2 block; an odd last
     * row or column is dropped. Every pixel of [out] comes out opaque.
     */
    fun halve(pixels: IntArray, width: Int, height: Int, out: IntArray) {
        val outWidth = width / 2
        val outHeight = height / 2
        require(out.size >= outWidth * outHeight) { "${out.size} pixels for $outWidth×$outHeight" }
        for (y in 0 until outHeight) {
            val row = 2 * y * width
            for (x in 0 until outWidth) {
                val a = pixels[row + 2 * x]
                val b = pixels[row + 2 * x + 1]
                val c = pixels[row + width + 2 * x]
                val d = pixels[row + width + 2 * x + 1]
                val red = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF) + 2) shr 2
                val green = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF) + 2) shr 2
                val blue = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
                out[y * outWidth + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }
}
