package dev.denza.apps.feature.hud

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Repaints the keyed cut for an additive projector, at twice the map window's size, and reduces it
 * to the window's 300×180.
 *
 * Yandex's night colours are made for a screen. On the glass its lime (free) and yellow (slow) are
 * 28° of hue apart and merge by day; blue is a twelfth of white's light; the grey of a route with
 * no traffic data and the blue-grey of the roads are dim, soft lines. So every role gets its own
 * bright colour round the hue circle ([GREEN], [YELLOW], [RED], [CYAN]; [WHITE] maneuver arrows,
 * [CURSOR], [ROAD] at [ROAD_LEVEL]), while Yandex's shapes are kept: house numbers, labels and POI
 * pins go as whole islands, holes are closed, the grey route is grown back from a thick seed so its
 * arrows keep their tips. Every road is flat, a wide carriageway filled like a thin one: the
 * owner found outlines busy on 2026-10-12. Yandex's road signs on the route and its lane balloon
 * stay as it drew them, their blue plate dimmed ([PLATE_LEVEL]); the owner asked for them the same
 * day. Where a pin is taken out, whatever line crossed it is carried through.
 *
 * Prototyped and tuned on the frames of the drive of 2026-10-11 (docs/hud-projection-findings.md,
 * «Перекраска целиком»); every threshold is in pixels of this doubled image and in the 0..1 scale
 * of a channel, as there. Built for the night theme: [isNight] says when the ground is one.
 *
 * The scratch is as large as the image and kept from one frame to the next: the frame thread
 * repaints several frames a second. One instance per thread.
 */
class HudMapPalette {
    private val mx = FloatArray(SIZE)
    private val sat = FloatArray(SIZE)
    private val hue = FloatArray(SIZE)
    private val weight = FloatArray(SIZE)
    private val roles = ByteArray(SIZE)
    private val cursorWeight = FloatArray(SIZE)
    private val ink = FloatArray(SIZE)
    private val scratch = FloatArray(SIZE)
    private val spare = FloatArray(SIZE)

    private val cursor = BooleanArray(SIZE)
    private val blue = BooleanArray(SIZE)
    private val blueRoute = BooleanArray(SIZE)
    private val icon = BooleanArray(SIZE)
    private val kept = BooleanArray(SIZE)
    private val carried = BooleanArray(SIZE)
    private val neutral = BooleanArray(SIZE)
    private val mask = BooleanArray(SIZE)
    private val small = BooleanArray(SIZE)
    private val closed = BooleanArray(SIZE)

    private val blobs = HudMapBlobs(WIDTH, HEIGHT)
    private val bits = HudMapBits(WIDTH, HEIGHT)
    private val queue = IntArray(SIZE)
    private val depth = ByteArray(SIZE)
    private val sums = FloatArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT * 3)
    private val boxes = ArrayList<IntArray>()

    /**
     * Repaints [pixels], [WIDTH]×[HEIGHT] opaque ARGB already keyed to black ([HudMapKey]), into
     * [out], the map window's 300×180.
     */
    fun apply(pixels: IntArray, out: IntArray) {
        require(pixels.size >= SIZE) { "${pixels.size} pixels for $WIDTH×$HEIGHT" }
        measure(pixels)
        findCursor(pixels)
        findBlue()
        paintRoute()
        paintRoads()
        compose(pixels, out)
    }

    /** Value, saturation and hue in degrees, as the prototype's HSV. */
    private fun measure(pixels: IntArray) {
        for (i in 0 until SIZE) {
            val p = pixels[i]
            if (p and 0xFFFFFF == 0) {
                // Keyed ground, most of the frame.
                mx[i] = 0f
                sat[i] = 0f
                hue[i] = 0f
                continue
            }
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val d = max(hi - lo, 1e-6f)
            mx[i] = hi
            sat[i] = if (hi > 1e-3f) (hi - lo) / max(hi, 1e-3f) else 0f
            hue[i] = 60f * when (hi) {
                r -> ((g - b) / d).mod(6f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
        }
    }

    /**
     * The cursor: amber by [HudMapArrow.isCursorColour], the largest such blob in the bottom middle,
     * where the cut puts it ([HudMapGeometry.ARROW_ROW]); its antialiased rim with it.
     */
    private fun findCursor(pixels: IntArray) {
        for (i in 0 until SIZE) mask[i] = HudMapArrow.isCursorColour(pixels[i])
        val count = blobs.find(mask)
        var best = -1
        for (k in 0 until count) {
            val n = blobs.size[k]
            if (n <= CURSOR_MIN_PX) continue
            val cx = blobs.sumX[k].toFloat() / n
            val cy = blobs.sumY[k].toFloat() / n
            if (abs(cx - WIDTH / 2f) < 0.15f * WIDTH && cy > 0.55f * HEIGHT &&
                (best < 0 || n > blobs.size[best])
            ) {
                best = k
            }
        }
        mask.fill(false)
        if (best >= 0) blobs.forEach(best) { mask[it] = true }
        bits.load(mask)
        bits.dilate(1)
        bits.store(cursor)
        for (i in 0 until SIZE) cursorWeight[i] = if (cursor[i]) smooth(0.20f, 0.45f, mx[i]) else 0f
    }

    /**
     * Blue: a route when it is a long thick stroke; otherwise a lane balloon or a road sign, kept,
     * or a pin, whose box goes. Night roads are blue-grey up to saturation 0.47, (70,88,128);
     * Yandex's blue is about 0.9.
     */
    private fun findBlue() {
        for (i in 0 until SIZE) {
            val h = hue[i]
            blue[i] = h >= 190f && h < 260f && sat[i] > 0.62f && mx[i] > 0.5f
        }
        bits.load(blue)
        bits.erode(2)
        bits.dilate(2)
        bits.store(mask)
        small.fill(false)
        val strokes = blobs.find(mask)
        for (k in 0 until strokes) {
            val bh = blobs.bottom[k] - blobs.top[k] + 1
            val bw = blobs.right[k] - blobs.left[k] + 1
            val long = max(bh, bw)
            val fill = blobs.size[k].toFloat() / (bh * bw)
            if (long > 40 && (long.toFloat() / min(bh, bw) > 3.5f || fill < 0.35f)) {
                blobs.forEach(k) { small[it] = true }
            }
        }
        bits.load(small)
        bits.dilate(2)
        bits.store(small)
        for (i in 0 until SIZE) {
            blueRoute[i] = blue[i] && small[i]
            mask[i] = blue[i] && !blueRoute[i]
        }

        boxes.clear()
        kept.fill(false)
        icon.fill(false)
        val count = blobs.find(mask)
        for (k in 0 until count) {
            if (blobs.size[k] <= 12) continue
            val y0 = blobs.top[k]
            val y1 = blobs.bottom[k]
            val x0 = blobs.left[k]
            val x1 = blobs.right[k]
            val bh = y1 - y0 + 1
            val bw = x1 - x0 + 1
            val long = max(bh, bw)
            val sized = long in 20..40
            val edge = y0 <= 8 || x0 <= 8 || y1 >= HEIGHT - 9 || x1 >= WIDTH - 9
            val framed = sized && (edge || (bw.toFloat() / bh in 0.88f..1.14f && whiteFrame(y0, y1, x0, x1)))
            if (long > 60 || framed) {
                // Its plate, its glyphs - white, or light blue for a lane that does not go the way
                // of the route, (115,163,254) - and its frame; not the blue-grey road round it,
                // which is no brighter than 0.6.
                for (y in max(0, y0 - 4) until min(HEIGHT, y1 + 5)) {
                    for (x in max(0, x0 - 4) until min(WIDTH, x1 + 5)) {
                        val i = y * WIDTH + x
                        val bluish = hue[i] >= 190f && hue[i] < 260f
                        val plate = bluish && sat[i] > 0.62f && mx[i] > 0.3f
                        if (plate || (bluish && mx[i] > 0.8f) || (sat[i] < 0.25f && mx[i] > 0.5f)) kept[i] = true
                    }
                }
            } else {
                val box = intArrayOf(
                    max(0, y0 - ICON_MARGIN),
                    min(HEIGHT, y1 + ICON_MARGIN + 1),
                    max(0, x0 - ICON_MARGIN),
                    min(WIDTH, x1 + ICON_MARGIN + 1),
                )
                boxes += box
                for (y in box[0] until box[1]) for (x in box[2] until box[3]) icon[y * WIDTH + x] = true
            }
        }
        bits.load(kept)
        bits.dilate(1)
        bits.store(kept)
    }

    /** Whether more than half the 2-pixel ring round the box is white: a road sign's frame. */
    private fun whiteFrame(y0: Int, y1: Int, x0: Int, x1: Int): Boolean {
        val top = max(0, y0 - 2)
        val bottom = min(HEIGHT, y1 + 3)
        val left = max(0, x0 - 2)
        val right = min(WIDTH, x1 + 3)
        var white = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (y in y0..y1 && x in x0..x1) continue
                val i = y * WIDTH + x
                if (mx[i] > 0.85f && sat[i] < 0.15f) white++
            }
        }
        val ring = (bottom - top) * (right - left) - (y1 - y0 + 1) * (x1 - x0 + 1)
        return white > 0.5f * ring
    }

    /**
     * The route's weight and role per pixel: the traffic colours, closed over the street names
     * Yandex writes along it; the grey route and every maneuver arrow; then the pins taken out and
     * the route carried through them.
     */
    private fun paintRoute() {
        for (i in 0 until SIZE) {
            if (mx[i] <= 0.25f || sat[i] <= 0.40f) {
                weight[i] = 0f
                roles[i] = ROLE_FREE
                continue
            }
            val h = hue[i]
            val strong = smooth(0.40f, 0.60f, sat[i]) * smooth(0.25f, 0.45f, mx[i])
            val role = when {
                h >= 70f && h < 170f -> ROLE_FREE
                (h >= 50f && h < 70f) || (h >= 25f && h < 50f && !cursor[i]) -> ROLE_SLOW
                h < 25f || h >= 320f -> ROLE_JAM
                else -> null
            }
            weight[i] = if (role == null) 0f else strong
            roles[i] = role ?: ROLE_FREE
        }
        bits.load(blueRoute)
        bits.dilate(1)
        bits.store(scratch)
        for (i in 0 until SIZE) {
            if (scratch[i] == 0f) {
                mask[i] = weight[i] > 0.5f
                continue
            }
            val w = smooth(0.55f, 0.75f, sat[i]) * smooth(0.40f, 0.60f, mx[i]) * scratch[i]
            if (w > weight[i]) {
                weight[i] = w
                roles[i] = ROLE_BLUE
            }
            mask[i] = weight[i] > 0.5f
        }
        // Letters on a chip in a route colour (an arrival time in green) are islands; a route is long.
        islands(mask, ISLAND_PX, null, 0, small)
        for (i in 0 until SIZE) if (small[i]) weight[i] = 0f
        // Text written along the route: a closing wider than a letter; a closed gap takes the
        // colour round it.
        closeGaps(weight, roles, ROUTE_CLOSE)
        for (i in 0 until SIZE) weight[i] *= 1f - cursorWeight[i]

        // The grey route - exactly neutral, (160,160,160) - found where a neutral stroke is thicker
        // than a glyph, grown back to its whole shape so its arrows keep their tips. A highway's
        // median, (136,142,155), is not neutral enough to be grown into.
        for (i in 0 until SIZE) neutral[i] = sat[i] < 0.08f && mx[i] > 0.45f
        bits.load(neutral)
        bits.erode(2)
        bits.dilate(2)
        bits.store(small)
        reconstruct()
        for (i in 0 until SIZE) {
            if (!mask[i]) continue
            val grey = smooth(0.30f, 0.50f, mx[i])
            val white = smooth(0.70f, 0.85f, mx[i])
            val cyan = grey * (1f - white)
            if (cyan > weight[i]) {
                weight[i] = cyan
                roles[i] = ROLE_GREY
            }
            if (grey * white > weight[i]) {
                weight[i] = grey * white
                roles[i] = ROLE_ARROW
            }
        }

        for (i in 0 until SIZE) if (icon[i]) weight[i] = 0f
        for (box in boxes) carry(weight, roles, box)
        carried.fill(false)
        for (box in boxes) {
            var any = false
            for (y in box[0] until box[1]) for (x in box[2] until box[3]) if (weight[y * WIDTH + x] > 0.5f) any = true
            if (any) for (y in box[0] until box[1]) for (x in box[2] until box[3]) carried[y * WIDTH + x] = true
        }
    }

    /** [mask] := the 8-connected growth, at most [GROW_STEPS] deep, of [small] inside the neutral. */
    private fun reconstruct() {
        var head = 0
        var tail = 0
        for (i in 0 until SIZE) {
            val inShape = sat[i] < 0.07f && mx[i] > 0.30f
            mask[i] = inShape && small[i]
            spare[i] = if (inShape) 1f else 0f
            if (mask[i]) {
                queue[tail++] = i
                depth[i] = 0
            }
        }
        while (head < tail) {
            val p = queue[head++]
            val d = depth[p]
            if (d >= GROW_STEPS) continue
            val x = p % WIDTH
            val y = p / WIDTH
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= HEIGHT) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= WIDTH) continue
                    val q = ny * WIDTH + nx
                    if (!mask[q] && spare[q] > 0f) {
                        mask[q] = true
                        depth[q] = (d + 1).toByte()
                        queue[tail++] = q
                    }
                }
            }
        }
    }

    /** The roads: a steep ramp on value, flat; labels out as islands, holes closed. */
    private fun paintRoads() {
        for (i in 0 until SIZE) ink[i] = smooth(INK_LO, INK_HI, mx[i])
        for (i in 0 until SIZE) if (icon[i]) ink[i] = 0f
        // Through a pin on the route the roads go on too; a pin off it stays a gap, which is better
        // than a line drawn to where no road was.
        for (box in boxes) if (carried[box[0] * WIDTH + box[2]]) carry(ink, null, box)

        for (i in 0 until SIZE) mask[i] = ink[i] > 0.75f
        islands(mask, ISLAND_PX, neutral, WORD_PX, small)
        for (i in 0 until SIZE) {
            small[i] = small[i] && weight[i] <= 0.3f && !cursor[i]
            mask[i] = mask[i] && !small[i]
        }
        bits.load(small)
        bits.dilate(2)
        bits.store(small)
        bits.load(mask)
        bits.dilate(1)
        bits.store(mask)
        for (i in 0 until SIZE) if (small[i] && !mask[i]) ink[i] = 0f
        closeGaps(ink, null, 2)
    }

    /** Route over roads, the cursor over both, kept signs over all; then each 2×2 block averaged. */
    private fun compose(pixels: IntArray, out: IntArray) {
        sums.fill(0f)
        val mapWidth = HudMapGeometry.MAP_WIDTH
        for (i in 0 until SIZE) {
            var r: Float
            var g: Float
            var b: Float
            if (kept[i]) {
                val p = pixels[i]
                val h = hue[i]
                val dim = if (h >= 190f && h < 260f && sat[i] > 0.62f) PLATE_LEVEL else 1f
                r = (p shr 16 and 0xFF) / 255f * dim
                g = (p shr 8 and 0xFF) / 255f * dim
                b = (p and 0xFF) / 255f * dim
            } else {
                val w = weight[i]
                val c = roles[i] * 3
                val road = (1f - w) * ink[i] * ROAD_LEVEL
                r = w * COLOURS[c] + road * ROAD[0]
                g = w * COLOURS[c + 1] + road * ROAD[1]
                b = w * COLOURS[c + 2] + road * ROAD[2]
                val cw = cursorWeight[i]
                if (cw > 0f) {
                    // The cursor keeps its two-tone facets.
                    val shade = (0.55f + 0.45f * mx[i] / 0.92f).coerceIn(0f, 1f)
                    r = r * (1f - cw) + cw * shade * CURSOR[0]
                    g = g * (1f - cw) + cw * shade * CURSOR[1]
                    b = b * (1f - cw) + cw * shade * CURSOR[2]
                }
            }
            val o = ((i / WIDTH / SCALE) * mapWidth + (i % WIDTH) / SCALE) * 3
            sums[o] += r.coerceIn(0f, 1f)
            sums[o + 1] += g.coerceIn(0f, 1f)
            sums[o + 2] += b.coerceIn(0f, 1f)
        }
        val quarter = 255f / (SCALE * SCALE)
        for (j in 0 until mapWidth * HudMapGeometry.MAP_HEIGHT) {
            val r = (sums[j * 3] * quarter + 0.5f).toInt()
            val g = (sums[j * 3 + 1] * quarter + 0.5f).toInt()
            val b = (sums[j * 3 + 2] * quarter + 0.5f).toInt()
            out[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Through [box] (top, bottom, left, right; the far edges outside it): every place a line
     * enters it, read just outside, is joined to the box's centre at that line's width - a route
     * straight through comes out straight, a turn under a pin a turn, roads meeting there a
     * crossing. With [roleOf], each joined line takes the role where it entered.
     */
    private fun carry(values: FloatArray, roleOf: ByteArray?, box: IntArray) {
        val y0 = box[0]
        val y1 = box[1]
        val x0 = box[2]
        val x1 = box[3]
        // The ring just outside the box, clockwise from its top left corner.
        val ring = ArrayList<Int>()
        for (x in x0 - 1..x1) ring += pack(y0 - 1, x)
        for (y in y0..y1) ring += pack(y, x1)
        for (x in x1 - 1 downTo x0 - 1) ring += pack(y1, x)
        for (y in y1 - 1 downTo y0) ring += pack(y, x0 - 1)
        val on = BooleanArray(ring.size) { k ->
            val y = ring[k] shr 16
            val x = ring[k] and 0xFFFF
            y in 0 until HEIGHT && x in 0 until WIDTH && values[y * WIDTH + x] > 0.5f
        }
        val first = on.indexOfFirst { !it }
        if (first < 0 || on.none { it }) return
        val cy = (y0 + y1) / 2f
        val cx = (x0 + x1) / 2f
        var k = 0
        while (k < ring.size) {
            val at = (first + k) % ring.size
            if (!on[at]) {
                k++
                continue
            }
            var length = 0
            while (k + length < ring.size && on[(first + k + length) % ring.size]) length++
            val middle = ring[(first + k + length / 2) % ring.size]
            val my = (middle shr 16).toFloat()
            val mxp = (middle and 0xFFFF).toFloat()
            val role = roleOf?.get((middle shr 16) * WIDTH + (middle and 0xFFFF)) ?: 0
            val half = max(2, length) / 2f
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    if (distanceSquared(x.toFloat(), y.toFloat(), mxp, my, cx, cy) <= half * half) {
                        val i = y * WIDTH + x
                        if (values[i] < 1f) {
                            values[i] = 1f
                            if (roleOf != null) roleOf[i] = role
                        }
                    }
                }
            }
            k += length
        }
    }

    /**
     * [into] := the components of [of] that are islands: whose box is under [limit] on both sides
     * or, given [words], mostly neutral and under [wordLimit] - a word on a chip is longer than a
     * house number.
     */
    private fun islands(of: BooleanArray, limit: Int, words: BooleanArray?, wordLimit: Int, into: BooleanArray) {
        into.fill(false)
        val count = blobs.find(of)
        for (k in 0 until count) {
            val height = blobs.bottom[k] - blobs.top[k]
            val width = blobs.right[k] - blobs.left[k]
            var island = height < limit && width < limit
            if (!island && words != null && max(height, width) < wordLimit) {
                var wordy = 0
                blobs.forEach(k) { if (words[it]) wordy++ }
                island = wordy > 0.5f * blobs.size[k]
            }
            if (island) blobs.forEach(k) { into[it] = true }
        }
    }

    /**
     * A closing by a (2r+1) square, made on the mask of [values] above one half: what it adds -
     * a speck in a fill, a letter on the route - takes the strongest value within [r] round it,
     * and with [roleOf] that value's role. The rest keeps its own value, antialiased edges and all.
     */
    private fun closeGaps(values: FloatArray, roleOf: ByteArray?, r: Int) {
        bits.load(values, 0.5f)
        bits.dilate(r)
        bits.erode(r)
        bits.store(closed)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val i = y * WIDTH + x
                if (!closed[i] || values[i] > 0.5f) continue
                var best = values[i]
                var from = i
                for (ny in max(0, y - r)..min(HEIGHT - 1, y + r)) {
                    for (nx in max(0, x - r)..min(WIDTH - 1, x + r)) {
                        val v = values[ny * WIDTH + nx]
                        if (v > best) {
                            best = v
                            from = ny * WIDTH + nx
                        }
                    }
                }
                values[i] = best
                if (roleOf != null) roleOf[i] = roleOf[from]
            }
        }
    }

    companion object {
        /** The cut is drawn at this many times the map window's size, repainted, and reduced. */
        const val SCALE = 2
        const val WIDTH = HudMapGeometry.MAP_WIDTH * SCALE
        const val HEIGHT = HudMapGeometry.MAP_HEIGHT * SCALE
        private const val SIZE = WIDTH * HEIGHT

        val GREEN = floatArrayOf(40 / 255f, 1f, 60 / 255f)
        val YELLOW = floatArrayOf(1f, 235 / 255f, 0f)

        /** A jam and a heavy jam alike: a dark colour is no light at all on the glass. */
        val RED = floatArrayOf(1f, 30 / 255f, 40 / 255f)

        /** A route with no traffic data, grey or blue in Yandex. */
        val CYAN = floatArrayOf(0f, 225 / 255f, 1f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
        val CURSOR = floatArrayOf(1f, 130 / 255f, 0f)
        val ROAD = floatArrayOf(235 / 255f, 240 / 255f, 1f)

        /** Roads under every route colour, the red included. */
        const val ROAD_LEVEL = 0.42f

        /** A kept sign's or lane balloon's blue plate, under its white glyph. */
        const val PLATE_LEVEL = 0.55f

        /** Value ramp from ground to road. */
        const val INK_LO = 0.10f
        const val INK_HI = 0.24f

        /** A blot whose box is under this on both sides is a label. */
        const val ISLAND_PX = 24

        /** A mostly neutral blot under this is a word. */
        const val WORD_PX = 100

        private const val ROUTE_CLOSE = 4
        private const val GROW_STEPS: Byte = 40
        private const val ICON_MARGIN = 7
        private const val CURSOR_MIN_PX = 40

        private const val ROLE_FREE: Byte = 0
        private const val ROLE_SLOW: Byte = 1
        private const val ROLE_JAM: Byte = 2
        private const val ROLE_BLUE: Byte = 3
        private const val ROLE_GREY: Byte = 4
        private const val ROLE_ARROW: Byte = 5

        private val COLOURS = GREEN + YELLOW + RED + CYAN + CYAN + WHITE

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

        /**
         * [pixels], [WIDTH]×[HEIGHT], reduced to the map window's 300×180 by 2×2 averages: the
         * keyed frame as it was before the palette, for a day map.
         */
        fun reduce(pixels: IntArray, out: IntArray) {
            val mapWidth = HudMapGeometry.MAP_WIDTH
            for (y in 0 until HudMapGeometry.MAP_HEIGHT) {
                for (x in 0 until mapWidth) {
                    var r = 0
                    var g = 0
                    var b = 0
                    for (dy in 0 until SCALE) {
                        for (dx in 0 until SCALE) {
                            val p = pixels[(y * SCALE + dy) * WIDTH + x * SCALE + dx]
                            r += p shr 16 and 0xFF
                            g += p shr 8 and 0xFF
                            b += p and 0xFF
                        }
                    }
                    val n = SCALE * SCALE
                    out[y * mapWidth + x] = (0xFF shl 24) or ((r + n / 2) / n shl 16) or ((g + n / 2) / n shl 8) or ((b + n / 2) / n)
                }
            }
        }

        private fun smooth(lo: Float, hi: Float, x: Float): Float {
            val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        private fun pack(y: Int, x: Int): Int = (y shl 16) or (x and 0xFFFF)

        /** From ([px], [py]) to the segment ([ax], [ay])-([bx], [by]), squared. */
        private fun distanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / length).coerceIn(0f, 1f)
            val ex = ax + t * dx - px
            val ey = ay + t * dy - py
            return ex * ex + ey * ey
        }
    }
}

/**
 * The 4-connected components of a mask: each one's pixels in [members] from its start, its size,
 * box and the sums of its coordinates. Scratch as large as the image, kept between calls.
 */
internal class HudMapBlobs(private val width: Int, private val height: Int) {
    private val seen = BooleanArray(width * height)
    private val stack = IntArray(width * height)
    private val members = IntArray(width * height)
    private var start = IntArray(64)
    var size = IntArray(64); private set
    var left = IntArray(64); private set
    var right = IntArray(64); private set
    var top = IntArray(64); private set
    var bottom = IntArray(64); private set
    var sumX = LongArray(64); private set
    var sumY = LongArray(64); private set

    /** Labels [mask]; the number of components. */
    fun find(mask: BooleanArray): Int {
        seen.fill(false)
        var count = 0
        var used = 0
        for (seed in 0 until width * height) {
            if (!mask[seed] || seen[seed]) continue
            if (count == size.size) grow()
            var sp = 0
            stack[sp++] = seed
            seen[seed] = true
            val first = used
            var l = width
            var r = -1
            var t = height
            var b = -1
            var sx = 0L
            var sy = 0L
            while (sp > 0) {
                val p = stack[--sp]
                members[used++] = p
                val x = p % width
                val y = p / width
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                sx += x
                sy += y
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < width - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - width] && !seen[p - width]) { seen[p - width] = true; stack[sp++] = p - width }
                if (y < height - 1 && mask[p + width] && !seen[p + width]) { seen[p + width] = true; stack[sp++] = p + width }
            }
            start[count] = first
            size[count] = used - first
            left[count] = l
            right[count] = r
            top[count] = t
            bottom[count] = b
            sumX[count] = sx
            sumY[count] = sy
            count++
        }
        return count
    }

    inline fun forEach(component: Int, action: (Int) -> Unit) {
        val from = startOf(component)
        for (k in from until from + size[component]) action(memberAt(k))
    }

    @PublishedApi internal fun startOf(component: Int): Int = start[component]

    @PublishedApi internal fun memberAt(k: Int): Int = members[k]

    private fun grow() {
        val n = size.size * 2
        start = start.copyOf(n)
        size = size.copyOf(n)
        left = left.copyOf(n)
        right = right.copyOf(n)
        top = top.copyOf(n)
        bottom = bottom.copyOf(n)
        sumX = sumX.copyOf(n)
        sumY = sumY.copyOf(n)
    }
}

/**
 * A mask packed 64 pixels to a word, for the binary steps: a dilation or erosion by a (2r+1)
 * square is r rounds of 3×3, each a few shifts, ORs or ANDs a word. Past the edge counts for
 * nothing - unset to a dilation, set to an erosion - as in [HudMapPalette]'s float steps.
 */
internal class HudMapBits(private val width: Int, private val height: Int) {
    private val words = (width + 63) / 64
    private val bits = LongArray(words * height)
    private val tmp = LongArray(words * height)
    private val lastMask = if (width % 64 == 0) -1L else (1L shl (width % 64)) - 1

    /** Set where [mask] is. */
    fun load(mask: BooleanArray) {
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until words) {
                val x0 = j shl 6
                val n = min(64, width - x0)
                var word = 0L
                for (b in 0 until n) if (mask[row + x0 + b]) word = word or (1L shl b)
                bits[y * words + j] = word
            }
        }
    }

    /** Set where [values] is above [threshold]. */
    fun load(values: FloatArray, threshold: Float) {
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until words) {
                val x0 = j shl 6
                val n = min(64, width - x0)
                var word = 0L
                for (b in 0 until n) if (values[row + x0 + b] > threshold) word = word or (1L shl b)
                bits[y * words + j] = word
            }
        }
    }

    fun store(mask: BooleanArray) {
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until words) {
                val x0 = j shl 6
                val n = min(64, width - x0)
                val word = bits[y * words + j]
                if (word == 0L) {
                    mask.fill(false, row + x0, row + x0 + n)
                    continue
                }
                for (b in 0 until n) mask[row + x0 + b] = (word ushr b) and 1L != 0L
            }
        }
    }

    /** 1 where set, 0 elsewhere. */
    fun store(values: FloatArray) {
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until words) {
                val x0 = j shl 6
                val n = min(64, width - x0)
                val word = bits[y * words + j]
                if (word == 0L) {
                    values.fill(0f, row + x0, row + x0 + n)
                    continue
                }
                for (b in 0 until n) values[row + x0 + b] = if ((word ushr b) and 1L != 0L) 1f else 0f
            }
        }
    }

    fun dilate(r: Int) = repeat(r) { step(true) }

    fun erode(r: Int) = repeat(r) { step(false) }

    private fun step(grow: Boolean) {
        val pad = if (grow) 0L else -1L
        if (!grow) for (y in 0 until height) bits[y * words + words - 1] = bits[y * words + words - 1] or lastMask.inv()
        for (y in 0 until height) {
            val base = y * words
            for (j in 0 until words) {
                val a = bits[base + j]
                val left = if (j > 0) bits[base + j - 1] else pad
                val right = if (j < words - 1) bits[base + j + 1] else pad
                val fromLeft = (a shl 1) or (left ushr 63)
                val fromRight = (a ushr 1) or (right shl 63)
                tmp[base + j] = if (grow) a or fromLeft or fromRight else a and fromLeft and fromRight
            }
        }
        for (y in 0 until height) {
            val base = y * words
            for (j in 0 until words) {
                val up = if (y > 0) tmp[base - words + j] else pad
                val down = if (y < height - 1) tmp[base + words + j] else pad
                val a = tmp[base + j]
                bits[base + j] = if (grow) a or up or down else a and up and down
            }
        }
        for (y in 0 until height) bits[y * words + words - 1] = bits[y * words + words - 1] and lastMask
    }
}
