package dev.denza.apps.feature.hud

import kotlin.math.roundToInt

/**
 * A rectangle in a frame's pixels, as plain numbers: `android.graphics.Rect` is a stub on the JVM,
 * and every decision the map window makes is held by plain tests.
 */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Whether the two share at least [depth] pixels along both axes; edges that touch do not. */
    fun overlaps(other: PxRect, depth: Int = 1): Boolean =
        minOf(right, other.right) - maxOf(left, other.left) >= depth &&
            minOf(bottom, other.bottom) - maxOf(top, other.top) >= depth
}

/**
 * Where the map window's picture is cut from Yandex's window, and at what sizes it is drawn.
 *
 * The cut is measured in Yandex's dp, not in pixels: Yandex draws its roads, its cursor and its
 * labels in dp, so one cut in dp is one picture on the glass at every density, and the density
 * changes only how sharp it is before the reduction (docs/hud-projection-findings.md, «Окно карты
 * как функция продукта»). The cut the owner saw on the glass on 2026-09-23 was 576×346 dp of a
 * 160 dpi frame reduced to 300×180.
 */
object HudMapGeometry {
    /** The map window's frame: it fits the window whole, a larger one is cropped by the HUD. */
    const val MAP_WIDTH = 300
    const val MAP_HEIGHT = 180

    /**
     * The cut is drawn at twice the frame and halved on the CPU: a 2×2 mean reduces a main-screen
     * cut (×0.26) with less shimmer on thin roads than one bilinear pass.
     */
    const val RENDER_WIDTH = MAP_WIDTH * 2
    const val RENDER_HEIGHT = MAP_HEIGHT * 2

    /** The cut's width in Yandex's dp; its height follows the frame's 5:3. */
    const val CROP_WIDTH_DP = 576f

    /** Where the car's cursor sits in the cut, from the top: the rest above it is the road ahead. */
    const val ARROW_ROW = 0.77f

    /** The width Yandex's window is reduced to for finding the cursor. */
    const val ANALYSIS_WIDTH = 320

    /**
     * The cut for a cursor at ([anchorX], [anchorY]) in [window], both in frame pixels, at
     * [density] pixels per dp.
     *
     * The cursor goes to the middle of the cut, [ARROW_ROW] of the way down. A window smaller than
     * the cut - a one-third split pane is about 425 dp wide - shrinks it, keeping 5:3; the cut is
     * then moved inside the window, so near an edge the cursor is off its place rather than the cut
     * taking in what lies outside Yandex.
     */
    fun crop(window: PxRect, density: Float, anchorX: Int, anchorY: Int): PxRect {
        if (window.isEmpty) return PxRect(window.left, window.top, window.left, window.top)
        val width = minOf(
            (CROP_WIDTH_DP * density).roundToInt(),
            window.width,
            window.height * MAP_WIDTH / MAP_HEIGHT,
        ).coerceAtLeast(1)
        val height = (width * MAP_HEIGHT / MAP_WIDTH.toFloat()).roundToInt()
            .coerceIn(1, window.height)
        val left = (anchorX - width / 2).coerceIn(window.left, window.right - width)
        val top = (anchorY - (height * ARROW_ROW).roundToInt()).coerceIn(window.top, window.bottom - height)
        return PxRect(left, top, left + width, top + height)
    }

    /** The analysis image's size for [window]: [ANALYSIS_WIDTH] wide, or the window's own if narrower. */
    fun analysisWidth(window: PxRect): Int = minOf(ANALYSIS_WIDTH, window.width).coerceAtLeast(1)

    fun analysisHeight(window: PxRect): Int =
        (window.height.toLong() * analysisWidth(window) / window.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
}
