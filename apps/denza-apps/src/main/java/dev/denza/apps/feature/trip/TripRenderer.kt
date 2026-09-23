package dev.denza.apps.feature.trip

import dev.denza.apps.design.luminofor.LuminoforSpec.Head

/**
 * The strip's arithmetic that is not a drawing: its shape on the full screen, and its clocks.
 *
 * Kept under the name the dashboard's layout policy already calls it by. It was the base class of
 * the strip's renderers when they drew on `PanelCanvas`, a virtual space scaled onto whatever box
 * they were given; the Luminofor strip draws in window dp on its own box and has no base class.
 */
object BaseTripRenderer {

    /**
     * What the strip says where the altitude would be when it has no location access at all.
     *
     * In sentence case, like every caption on the Luminofor strip.
     */
    const val LOCATION_HINT = "Нет доступа к геолокации"

    /**
     * The height the full screen's strip asks for at [width]: the board's own box, 1184 x 296.
     *
     * [Head.Full.STRIP_BOX] is where the strip stands on the 1280 x 680 window, and asking for a box
     * of that shape is how the dashboard keeps it from being handed a stretched one. The panes'
     * strips take the remainder instead and hang from their tops (see [TripPanelRenderer]).
     */
    fun heightFor(width: Float): Float {
        val box = Head.Full.STRIP_BOX
        return width * (box.bottom - box.top) / (box.right - box.left)
    }

    fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    /** m:ss */
    fun clockMs(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        return "${s / 60}:${pad2(s % 60)}"
    }

    /** h:mm for durations (used for remaining time / countdown). */
    fun clockHm(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return "$h:${pad2(m.toInt())}"
    }
}
