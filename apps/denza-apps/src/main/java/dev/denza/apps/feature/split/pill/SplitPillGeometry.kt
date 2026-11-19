package dev.denza.apps.feature.split.pill

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A rectangle in dp on the 1280 x 800 stage: left, top, right, bottom. */
internal data class PillRect(val l: Double, val t: Double, val r: Double, val b: Double) {
    val w: Double get() = r - l
    val h: Double get() = b - t
    val cx: Double get() = (l + r) / 2
    val cy: Double get() = (t + b) / 2

    fun contains(x: Double, y: Double): Boolean = x >= l && x <= r && y >= t && y <= b

    fun union(o: PillRect): PillRect = PillRect(min(l, o.l), min(t, o.t), max(r, o.r), max(b, o.b))

    fun grow(dl: Double, dt: Double, dr: Double, db: Double): PillRect = PillRect(l - dl, t - dt, r + dr, b + db)
}

/**
 * One row laid out: each item's left edge relative to the row's centre, the row's width, and
 * [dx], how far the pill stands off the divider so that the apps row's mark stays on it (the
 * page's `layoutSpec` and `dxT`).
 */
internal class PillLayout(val items: List<PillItem>, val x: DoubleArray, val w: Double, val dx: Double) {
    fun widthOf(i: Int): Double = PillGeometry.itemWidth(items[i])

    fun indexOf(key: String): Int = items.indexOfFirst { it.key == key }
}

/**
 * Where the handle, the pill, its items and its window stand, from the spec alone (the page's
 * `render`, `layoutSpec`, the hit areas of README "The pill", and the window of decision 2).
 */
internal object PillGeometry {

    fun itemWidth(item: PillItem): Double = when (item) {
        is PillItem.Chip -> if (item.missing != null) SplitPillSpec.Chip.OPEN else SplitPillSpec.Chip.STACKED
        is PillItem.Single -> SplitPillSpec.Icon.SIZE
        PillItem.Separator -> SplitPillSpec.Sep.W
        PillItem.Plus -> SplitPillSpec.Btn.SIZE
        PillItem.DividerMark -> SplitPillSpec.Apps.MARK_W
    }

    fun itemHeight(item: PillItem): Double = when (item) {
        is PillItem.Chip -> SplitPillSpec.Row.H_CHIP
        is PillItem.Single -> SplitPillSpec.Row.H_SINGLE
        PillItem.Separator -> SplitPillSpec.Row.H_SEP
        PillItem.Plus -> SplitPillSpec.Row.H_BTN
        PillItem.DividerMark -> SplitPillSpec.Apps.MARK_H
    }

    fun gapBetween(a: PillItem, b: PillItem): Double = when {
        a == PillItem.DividerMark || b == PillItem.DividerMark -> SplitPillSpec.Apps.GAP
        a == PillItem.Separator || b == PillItem.Separator -> SplitPillSpec.Row.GAP_SEP
        a is PillItem.Chip && b is PillItem.Chip -> SplitPillSpec.Row.GAP_CHIP
        a == PillItem.Plus && b == PillItem.Plus -> SplitPillSpec.Row.GAP_BTN
        else -> SplitPillSpec.Row.GAP_OTHER
    }

    fun layout(items: List<PillItem>): PillLayout {
        val pad = SplitPillSpec.Row.PAD
        val x = DoubleArray(items.size)
        var at = pad
        items.forEachIndexed { i, item ->
            if (i > 0) at += gapBetween(items[i - 1], item)
            x[i] = at
            at += itemWidth(item)
        }
        val w = at + pad
        for (i in x.indices) x[i] -= w / 2
        val mark = items.indexOf(PillItem.DividerMark)
        val dx = if (mark >= 0) -(x[mark] + itemWidth(items[mark]) / 2) else 0.0
        return PillLayout(items, x, w, dx)
    }

    /** The capsule's centre: the divider plus the row's offset, kept `pill.margin` from the sides. */
    fun centreX(divX: Double, dx: Double, w: Double): Double {
        val lo = SplitPillSpec.Pill.MARGIN + w / 2
        val hi = SplitPillSpec.Stage.W - SplitPillSpec.Pill.MARGIN - w / 2
        return (divX + dx).coerceIn(lo, max(lo, hi))
    }

    /**
     * The capsule at width [w] and height [h]: its bottom rises from the handle's to the open
     * pill's as the height grows, its centre follows the divider (the page's `render`).
     */
    fun capsule(divX: Double, dx: Double, w0: Double, h0: Double): PillRect {
        val out = DoubleArray(4)
        capsuleInto(divX, dx, w0, h0, out)
        return PillRect(out[0], out[1], out[2], out[3])
    }

    /** [capsule] into [out] (left, top, right, bottom): the frame's form, which allocates nothing. */
    fun capsuleInto(divX: Double, dx: Double, w0: Double, h0: Double, out: DoubleArray) {
        val w = max(SplitPillSpec.Pill.MIN_W, w0)
        val h = max(SplitPillSpec.Pill.MIN_H, h0)
        val k = ((h - SplitPillSpec.Handle.H) / (SplitPillSpec.Pill.OPEN_H - SplitPillSpec.Handle.H)).coerceIn(0.0, 1.0)
        val yb = SplitPillSpec.Handle.BOTTOM - (SplitPillSpec.Handle.BOTTOM - SplitPillSpec.Pill.OPEN_BOTTOM) * k
        val cx = centreX(divX, dx, w)
        out[0] = cx - w / 2
        out[1] = yb - h
        out[2] = cx + w / 2
        out[3] = yb
    }

    /** The handle at rest. */
    fun handle(divX: Double): PillRect = capsule(divX, 0.0, SplitPillSpec.Handle.W, SplitPillSpec.Handle.H)

    /** The handle's touch target: `handle.hit`, centred on the disc. */
    fun handleHit(divX: Double): PillRect {
        val disc = handle(divX)
        val hw = SplitPillSpec.Handle.HIT_W / 2
        val hh = SplitPillSpec.Handle.HIT_H / 2
        return PillRect(disc.cx - hw, disc.cy - hh, disc.cx + hw, disc.cy + hh)
    }

    /** The open pill showing [layout]. */
    fun open(divX: Double, layout: PillLayout): PillRect =
        capsule(divX, layout.dx, layout.w, SplitPillSpec.Pill.OPEN_H)

    /**
     * Room round a capsule for its lift shadows and a press bloom, dp: left, top, right, bottom
     * (the larger of the shadows' reach and the bloom's half at its widest), plus the pull's band
     * above.
     */
    val margins: PillRect by lazy {
        val reachL = max(
            SplitPillSpec.Pill.LIFT_BLUR - SplitPillSpec.Pill.LIFT_X,
            SplitPillSpec.Pill.LIFT_TIGHT_BLUR - SplitPillSpec.Pill.LIFT_TIGHT_X,
        )
        val reachR = max(
            SplitPillSpec.Pill.LIFT_BLUR + SplitPillSpec.Pill.LIFT_X,
            SplitPillSpec.Pill.LIFT_TIGHT_BLUR + SplitPillSpec.Pill.LIFT_TIGHT_X,
        )
        val reachT = max(
            SplitPillSpec.Pill.LIFT_BLUR - SplitPillSpec.Pill.LIFT_Y,
            SplitPillSpec.Pill.LIFT_TIGHT_BLUR - SplitPillSpec.Pill.LIFT_TIGHT_Y,
        )
        val reachB = max(
            SplitPillSpec.Pill.LIFT_BLUR + SplitPillSpec.Pill.LIFT_Y,
            SplitPillSpec.Pill.LIFT_TIGHT_BLUR + SplitPillSpec.Pill.LIFT_TIGHT_Y,
        )
        val bloom = SplitPillSpec.Bloom.SIZE / 2 * SplitPillSpec.Bloom.SCALE_TO
        PillRect(max(reachL, bloom), max(reachT, bloom) + SplitPillSpec.Pull.BAND_CAP, max(reachR, bloom), max(reachB, bloom))
    }

    /**
     * The window, once for the whole life of the pill (decision 2): as wide as the stage, so the
     * pill never needs it moved - whatever the divider's detent, whichever row, however far the apps
     * row stands off the divider - and as tall as the tallest pill with its band, shadows and bloom
     * above the handle's bottom. It is never moved or resized while the pill moves; touches outside
     * the shape pass through ([touchBands]).
     */
    val window: PillRect by lazy {
        val top = SplitPillSpec.Pill.OPEN_BOTTOM - SplitPillSpec.Pill.OPEN_H - margins.t
        val bottom = max(SplitPillSpec.Handle.BOTTOM, handleHitBottom()) + margins.b
        PillRect(0.0, max(0.0, top), SplitPillSpec.Stage.W, min(SplitPillSpec.Stage.H, bottom))
    }

    private fun handleHitBottom(): Double =
        SplitPillSpec.Handle.BOTTOM - SplitPillSpec.Handle.H / 2 + SplitPillSpec.Handle.HIT_H / 2

    /** dp to whole px, outward: a window or a band never cuts into what it must hold. */
    fun toPx(r: PillRect, scale: Double): IntArray = intArrayOf(
        floor(r.l * scale + 1e-6).toInt(),
        floor(r.t * scale + 1e-6).toInt(),
        ceil(r.r * scale - 1e-6).toInt(),
        ceil(r.b * scale - 1e-6).toInt(),
    )

    /**
     * True when (x, y) lies in the capsule [c] as it is drawn: a rounded rectangle whose corner is
     * half its shorter side (Skia scales a too-large corner down so), so a wide capsule has round
     * ends left and right and a tall one round ends top and bottom.
     */
    fun inCapsule(c: PillRect, x: Double, y: Double): Boolean {
        if (!c.contains(x, y)) return false
        val rad = min(c.w, c.h) / 2
        val dx = x - x.coerceIn(c.l + rad, max(c.l + rad, c.r - rad))
        val dy = y - y.coerceIn(c.t + rad, max(c.t + rad, c.b - rad))
        return dx * dx + dy * dy <= rad * rad + 1e-9
    }

    /**
     * The touchable region's arithmetic. A window's touchable region is a union of rectangles, so
     * a capsule (px, window coordinates) is cut into horizontal bands [step] px tall, each as wide
     * as the drawn capsule ([inCapsule]) is at the band's row nearest its straight middle: they
     * cover the whole capsule and stray past it by less than one band's worth of curve at the round
     * ends - a tall capsule's top and bottom as much as a wide one's sides. Each band is left, top,
     * right, bottom.
     */
    fun capsuleBands(c: DoubleArray, step: Int): List<IntArray> {
        val w = c[2] - c[0]
        val h = c[3] - c[1]
        if (w <= 0 || h <= 0) return emptyList()
        val rad = min(w, h) / 2
        // The straight middle of the shape, where it is as wide as it gets: a point for a disc, a
        // vertical run for a tall capsule, the centre line for a wide one.
        val midT = c[1] + rad
        val midB = max(midT, c[3] - rad)
        val top = floor(c[1]).toInt()
        val bottom = ceil(c[3]).toInt()
        val out = mutableListOf<IntArray>()
        var y0 = top
        while (y0 < bottom) {
            val y1 = min(bottom, y0 + step)
            val dy = when {
                y1 < midT -> midT - y1
                y0 > midB -> y0 - midB
                else -> 0.0
            }
            val half = if (dy >= rad) 0.0 else sqrt(rad * rad - dy * dy)
            val inset = rad - half
            val l = floor(c[0] + inset).toInt()
            val r = ceil(c[2] - inset).toInt()
            if (r > l) out += intArrayOf(l, y0, r, y1)
            y0 += step
        }
        return out
    }

    /**
     * The touchable region of the window ([window] at [scale] px per dp): the bands of each capsule
     * in [shapes] and, while the handle can be pressed, the handle's target as one rectangle. Set
     * when a motion starts and when it ends, never per frame (decision 2).
     */
    fun touchBands(shapes: List<PillRect>, handleHit: PillRect?, scale: Double): List<IntArray> {
        val origin = toPx(window, scale)
        val bands = mutableListOf<IntArray>()
        shapes.forEach { s ->
            val c = doubleArrayOf(
                s.l * scale - origin[0],
                s.t * scale - origin[1],
                s.r * scale - origin[0],
                s.b * scale - origin[1],
            )
            bands += capsuleBands(c, BAND_PX)
        }
        if (handleHit != null) {
            val px = toPx(handleHit, scale)
            bands += intArrayOf(px[0] - origin[0], px[1] - origin[1], px[2] - origin[0], px[3] - origin[1])
        }
        return bands
    }

    /**
     * What the window may take a touch on (decision 2): the shapes as capsules and the handle's
     * target, for a pill [open] or not, its shape [moving] or at rest, showing [layout] with its
     * width now [w] and its offset now [dx]. Nothing while [shown] is false - the moment hiding
     * starts. At rest: the handle's target, or exactly the open capsule as drawn. While the shape
     * moves: the open capsule at its widest, past the pull's bounds, and the handle's target, so a
     * touch anywhere on it in flight catches it.
     */
    fun region(shown: Boolean, open: Boolean, moving: Boolean, divX: Double, layout: PillLayout, w: Double, dx: Double): Pair<List<PillRect>, PillRect?> {
        if (!shown) return emptyList<PillRect>() to null
        if (!open && !moving) return emptyList<PillRect>() to handleHit(divX)
        if (!moving) return listOf(open(divX, layout)) to null
        val wide = max(layout.w, w) * (1 + SplitPillSpec.Pull.BOUND_WIDTH)
        val tall = SplitPillSpec.Pill.OPEN_H + SplitPillSpec.Pull.BOUND_HEIGHT
        val ox = (min(layout.dx, dx) + max(layout.dx, dx)) / 2
        val span = if (layout.dx > dx) layout.dx - dx else dx - layout.dx
        return listOf(capsule(divX, ox, wide + span, tall)) to handleHit(divX)
    }

    /** The bands' height, px: four, the probe's measure (findings, "The divider is a DOCK_DIVIDER window"). */
    const val BAND_PX = 4

    /**
     * Whether (lx, ly) takes [item]: [lx] from the item's own left edge, [ly] from the row's centre
     * line, both in the item's own unscaled measure - the view maps a touch through the item's
     * rendered place and scale first. The face grown by its kind's hit margin; «+» is its disc; a
     * separator and the divider's mark take nothing.
     */
    fun hitsItem(item: PillItem, lx: Double, ly: Double): Boolean {
        val w = itemWidth(item)
        return when (item) {
            is PillItem.Chip -> {
                val m = SplitPillSpec.Chip.HIT
                lx >= -m && lx <= w + m && ly >= -SplitPillSpec.Chip.H / 2 - m && ly <= SplitPillSpec.Chip.H / 2 + m
            }
            is PillItem.Single -> {
                val m = SplitPillSpec.Single.HIT
                val s = SplitPillSpec.Icon.SIZE / 2
                lx >= -m && lx <= w + m && ly >= -s - m && ly <= s + m
            }
            PillItem.Plus -> {
                val r = SplitPillSpec.Btn.SIZE / 2
                val dx = lx - w / 2
                dx * dx + ly * ly <= r * r
            }
            PillItem.Separator, PillItem.DividerMark -> false
        }
    }

    /**
     * Whether (lx, ly), in a chip's own measure as for [hitsItem], takes its badge: the disc grown
     * by `badge.hit`, cut halfway across the gap to the next chip, so it never takes that chip's taps.
     */
    fun hitsBadge(lx: Double, ly: Double): Boolean {
        val m = SplitPillSpec.Badge.HIT
        val l = SplitPillSpec.Badge.X
        val t = -SplitPillSpec.Row.H_CHIP / 2 + SplitPillSpec.Badge.Y
        val cut = SplitPillSpec.Chip.STACKED + SplitPillSpec.Row.GAP_CHIP / 2
        return lx >= l - m && lx <= min(l + SplitPillSpec.Badge.SIZE + m, cut) &&
            ly >= t - m && ly <= t + SplitPillSpec.Badge.SIZE + m
    }

    /** The item under (rx, ry), relative to the row's centre, with every item at rest; or -1. */
    fun itemAt(layout: PillLayout, rx: Double, ry: Double): Int {
        for (i in layout.items.indices) {
            if (hitsItem(layout.items[i], rx - layout.x[i], ry)) return i
        }
        return -1
    }

    /** The badge disc of the chip at item [i]: `badge.size` at (`badge.x`, `badge.y`) of the item. */
    fun badgeDisc(layout: PillLayout, i: Int): PillRect {
        val top = -SplitPillSpec.Row.H_CHIP / 2
        val l = layout.x[i] + SplitPillSpec.Badge.X
        val t = top + SplitPillSpec.Badge.Y
        return PillRect(l, t, l + SplitPillSpec.Badge.SIZE, t + SplitPillSpec.Badge.SIZE)
    }

    /** Whether (rx, ry), relative to the row's centre with every item at rest, takes item [i]'s badge. */
    fun inBadge(layout: PillLayout, i: Int, rx: Double, ry: Double): Boolean = hitsBadge(rx - layout.x[i], ry)
}

internal object PillDivider {
    const val DIVIDER_TITLE = "multi-divider"

    /** One window of the list: its kind, its title and its bounds in px. */
    data class Box(
        val system: Boolean,
        val application: Boolean,
        val title: String?,
        val l: Int,
        val t: Int,
        val r: Int,
        val b: Int,
    )

    /** The divider's centre in px, or `null`. */
    fun centrePx(boxes: List<Box>, screenH: Int): Double? {
        boxes.firstOrNull { it.system && it.title == DIVIDER_TITLE && it.r > it.l }
            ?.let { return (it.l + it.r) / 2.0 }
        val tall = boxes.filter { it.application && (it.b - it.t) > screenH * TALL_SHARE }
            .sortedBy { it.l }
        for (i in 0 until tall.size - 1) {
            val a = tall[i]
            val b = tall[i + 1]
            val gap = b.l - a.r
            if (gap in 0..MAX_GAP_PX && shareTheBand(a, b)) return (a.r + b.l) / 2.0
        }
        return null
    }

    /** Two panes side by side stand in one band: each covers most of the other's height. */
    private fun shareTheBand(a: Box, b: Box): Boolean {
        val overlap = min(a.b, b.b) - max(a.t, b.t)
        return overlap >= BAND_SHARE * min(a.b - a.t, b.b - b.t)
    }

    private const val TALL_SHARE = 0.4
    private const val MAX_GAP_PX = 120
    private const val BAND_SHARE = 0.8
}
