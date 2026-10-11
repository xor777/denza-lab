package dev.denza.apps.feature.hud

import kotlin.math.roundToInt

/**
 * The main screen as the map's mirror sees it: half its size, and everything the window list says
 * in display pixels brought into the mirror's.
 *
 * Half is enough: the cut is 490×294 dp ([HudMapGeometry.CROP_WIDTH_DP]), which at the main
 * screen's density 2 is 980×588 display pixels and in the mirror 490×294, still more than the
 * 300×180 sent. Mirroring at full size would only make the GPU throw three quarters of it away.
 */
object HudMapMirror {
    /** The mirror's size for a [width]×[height] screen: half, at least one pixel. */
    fun width(width: Int): Int = (width / 2).coerceAtLeast(1)

    fun height(height: Int): Int = (height / 2).coerceAtLeast(1)

    /** [rect] in display pixels, in the mirror's pixels at [factor] (mirror width over display width). */
    fun scale(rect: PxRect, factor: Float): PxRect = PxRect(
        (rect.left * factor).roundToInt(),
        (rect.top * factor).roundToInt(),
        (rect.right * factor).roundToInt(),
        (rect.bottom * factor).roundToInt(),
    )

    /** Yandex's window and the windows over it, in the mirror's pixels. */
    fun scale(placement: YandexMapPlacement.MainScreen, factor: Float): YandexMapPlacement.MainScreen =
        YandexMapPlacement.MainScreen(
            window = scale(placement.window, factor),
            above = placement.above.map { it.copy(bounds = scale(it.bounds, factor)) },
        )
}

/**
 * When the next mirrored frame is worth making: [intervalMs] after the last one began, whatever
 * arrived in between.
 *
 * The mirror delivers as fast as the screen changes, up to its refresh rate; the pipeline takes
 * what is due and lets the rest go at once, so a slow frame is followed by the newest picture,
 * never by the ones that queued behind it.
 */
class HudMapPacer(private val intervalMs: Long) {
    private var startedAt = Long.MIN_VALUE

    /** Whether a frame arriving at [nowMs] should be made. */
    fun due(nowMs: Long): Boolean = startedAt == Long.MIN_VALUE || nowMs - startedAt >= intervalMs

    /** A frame began at [nowMs]. */
    fun started(nowMs: Long) {
        startedAt = nowMs
    }

    fun reset() {
        startedAt = Long.MIN_VALUE
    }
}
