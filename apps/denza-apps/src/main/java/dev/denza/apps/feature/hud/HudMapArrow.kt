package dev.denza.apps.feature.hud

/** One amber blob in the analysis image: its centroid and bounding box, in that image's pixels. */
data class HudMapBlob(val x: Float, val y: Float, val width: Int, val height: Int, val pixels: Int)

/**
 * What Yandex's car cursor looks like, for putting the cut around it.
 *
 * The default cursor is a compact amber arrow - on the 2026-09-23 frame about 100 dp tall, shaded
 * from about `#F9C21B` to `#E8A317`: strong red and green, little blue. Day-theme major roads and
 * the yellow stretches of the route's traffic line have nearly the same colour, so colour only
 * proposes; the blob's size, its proportions and how much of its box it fills decide. A long
 * yellow road is too long and too thin, a diagonal one fills too little of its box, a yellow
 * button fills all of it.
 *
 * The bounds are first guesses from one recorded frame, to be tuned on frames recorded on the road
 * ([HudMapCapture]).
 */
object HudMapArrow {
    /** The cursor's longer side, in Yandex's dp. */
    const val MIN_SIZE_DP = 40f
    const val MAX_SIZE_DP = 200f

    /** Width over height of the cursor's box, whichever way it points. */
    const val MIN_ASPECT = 0.6f
    const val MAX_ASPECT = 1.6f

    /** The share of its box the cursor covers: a triangle is about a half. */
    const val MIN_FILL = 0.30f
    const val MAX_FILL = 0.85f

    /** Amber: red high, green below it but still strong, blue low. */
    fun isCursorColour(argb: Int): Boolean {
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return red >= 190 && green in 130..230 && blue <= 100 && red - green in 10..120
    }
}

/**
 * The search for the cursor in an analysis image of ARGB pixels: plain arithmetic, tested on drawn
 * images.
 *
 * Its scratch is kept from one search to the next: the visited marks and the walk's stack are as
 * large as the image, and the frame thread searches several times a second. One instance per
 * thread.
 */
class HudMapArrowSearch {
    private var seen = BooleanArray(0)
    private var stack = IntArray(0)

    /**
     * The cursor nearest to ([expectedX], [expectedY]), or null when nothing in the image has its
     * colour, size and shape. [pxPerDp] is how many analysis pixels one of Yandex's dp is.
     */
    fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        pxPerDp: Float,
        expectedX: Float,
        expectedY: Float,
    ): HudMapBlob? = candidates(pixels, width, height, pxPerDp).minByOrNull { blob ->
        val dx = blob.x - expectedX
        val dy = blob.y - expectedY
        dx * dx + dy * dy
    }

    /** Every amber blob with the cursor's size and shape, in no particular order. */
    fun candidates(pixels: IntArray, width: Int, height: Int, pxPerDp: Float): List<HudMapBlob> {
        val count = width * height
        require(pixels.size >= count) { "${pixels.size} pixels for $width×$height" }
        if (seen.size < count) {
            seen = BooleanArray(count)
            stack = IntArray(count)
        } else {
            seen.fill(false, 0, count)
        }
        val seen = seen
        val stack = stack
        val minSide = HudMapArrow.MIN_SIZE_DP * pxPerDp
        val maxSide = HudMapArrow.MAX_SIZE_DP * pxPerDp
        val found = ArrayList<HudMapBlob>()
        for (start in 0 until count) {
            if (seen[start] || !HudMapArrow.isCursorColour(pixels[start])) {
                seen[start] = true
                continue
            }
            // One 8-connected component, walked with an explicit stack: a recursion this deep would
            // not survive a blob the size of the screen. A pixel is marked as it is pushed, so each
            // is pushed once and the stack never outgrows the image.
            var top = 0
            stack[top++] = start
            seen[start] = true
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val next = ny * width + nx
                        if (!seen[next]) {
                            seen[next] = true
                            if (HudMapArrow.isCursorColour(pixels[next])) stack[top++] = next
                        }
                    }
                }
            }
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val longer = maxOf(boxWidth, boxHeight)
            val aspect = boxWidth.toFloat() / boxHeight
            val fill = area.toFloat() / (boxWidth * boxHeight)
            if (longer < minSide || longer > maxSide) continue
            if (aspect < HudMapArrow.MIN_ASPECT || aspect > HudMapArrow.MAX_ASPECT) continue
            if (fill < HudMapArrow.MIN_FILL || fill > HudMapArrow.MAX_FILL) continue
            found += HudMapBlob(sumX.toFloat() / area, sumY.toFloat() / area, boxWidth, boxHeight, area)
        }
        return found
    }
}

/** Where the cut's cursor came from on a frame. */
enum class HudMapAnchorSource {
    /** Found in this window's picture, now or at the last search. */
    DETECTED,

    /** Not found at the last search; where it was last found in a window of the same bounds. */
    REMEMBERED,

    /** Never found in this window: Yandex's usual place for it. */
    DEFAULT,
}

/** The cursor the cut is placed around, in frame pixels. */
data class HudMapAnchor(val x: Int, val y: Int, val source: HudMapAnchorSource)

/**
 * The cursor from one search to the next: found, remembered for the same window, or the default.
 *
 * A window with other bounds - Yandex moved from the full screen to a pane, or the pane resized -
 * forgets what it knew, because Yandex puts its cursor at a fraction of its own window. Moves are
 * smoothed lightly so a pixel of jitter in the search does not shake the map: a move smaller than
 * [DEADBAND_DP] is ignored, one up to [SNAP_DP] goes half the way, and a larger one - Yandex
 * recentred after the driver panned - is taken at once.
 */
class HudMapAnchorTracker {
    private var window: PxRect? = null
    private var x = 0f
    private var y = 0f
    private var known = false
    private var lastSource = HudMapAnchorSource.DEFAULT

    /**
     * The anchor for [window] between searches: the last answer if the window is the same one,
     * else the default for this window.
     */
    fun current(window: PxRect): HudMapAnchor =
        if (window == this.window && known) {
            HudMapAnchor(x.toInt(), y.toInt(), lastSource)
        } else {
            default(window)
        }

    /**
     * The anchor after a search of [window] that found the cursor at ([foundX], [foundY]) in frame
     * pixels, or found nothing (both null).
     */
    fun resolve(window: PxRect, density: Float, foundX: Float?, foundY: Float?): HudMapAnchor {
        if (window != this.window) {
            this.window = window
            known = false
        }
        if (foundX == null || foundY == null) {
            if (!known) return default(window)
            lastSource = HudMapAnchorSource.REMEMBERED
            return HudMapAnchor(x.toInt(), y.toInt(), lastSource)
        }
        if (!known) {
            x = foundX
            y = foundY
            known = true
        } else {
            val dx = foundX - x
            val dy = foundY - y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            when {
                distance < DEADBAND_DP * density -> Unit
                distance > SNAP_DP * density -> {
                    x = foundX
                    y = foundY
                }
                else -> {
                    x += dx * SMOOTHING
                    y += dy * SMOOTHING
                }
            }
        }
        lastSource = HudMapAnchorSource.DETECTED
        return HudMapAnchor(x.toInt(), y.toInt(), lastSource)
    }

    fun reset() {
        window = null
        known = false
        lastSource = HudMapAnchorSource.DEFAULT
    }

    private fun default(window: PxRect): HudMapAnchor {
        val row = if (window.width >= window.height) DEFAULT_ROW_LANDSCAPE else DEFAULT_ROW_PORTRAIT
        return HudMapAnchor(
            window.left + (window.width * DEFAULT_COLUMN).toInt(),
            window.top + (window.height * row).toInt(),
            HudMapAnchorSource.DEFAULT,
        )
    }

    companion object {
        /** Yandex's cursor on the 2026-09-23 landscape frame: centred, at 0.71 of the height. */
        const val DEFAULT_COLUMN = 0.50f
        const val DEFAULT_ROW_LANDSCAPE = 0.71f

        /** Not measured: no portrait window has been recorded yet. */
        const val DEFAULT_ROW_PORTRAIT = 0.75f

        const val DEADBAND_DP = 3f
        const val SNAP_DP = 96f
        const val SMOOTHING = 0.5f
    }
}
