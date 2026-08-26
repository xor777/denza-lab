package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Color
import dev.denza.apps.feature.panel.PanelCanvas
import dev.denza.apps.feature.panel.PanelPalette

/**
 * Palette for the trip panel.
 *
 * The shared hues live in [PanelPalette] — every canvas panel in the app draws
 * with the same dark background, mint accent, amber warning, ink text and muted
 * labels. What is specific to this feature is the journey thread's time-of-day
 * ramp (dawn blue -> day mint -> golden amber -> evening coral -> night violet),
 * which the feature spec defines for that one mapping.
 */
object TripPalette {
    val LIVE = PanelPalette.LIVE
    val AMBER = PanelPalette.AMBER
    val INK = PanelPalette.INK
    val MUTED = PanelPalette.MUTED

    // Time-of-day stops for the journey thread.
    private val STOPS = arrayOf(
        intArrayOf(110, 168, 255), // dawn blue
        intArrayOf(126, 227, 174), // day mint
        intArrayOf(255, 217, 138), // golden amber
        intArrayOf(255, 158, 122), // evening coral
        intArrayOf(185, 151, 255), // night violet
    )

    fun colorAt(u: Double): Int {
        val clamped = u.coerceIn(0.0, 1.0)
        val s = clamped * (STOPS.size - 1)
        val i = s.toInt().coerceIn(0, STOPS.size - 2)
        val f = s - i
        val a = STOPS[i]
        val b = STOPS[i + 1]
        val r = (a[0] + (b[0] - a[0]) * f).toInt()
        val g = (a[1] + (b[1] - a[1]) * f).toInt()
        val bl = (a[2] + (b[2] - a[2]) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    fun alpha(color: Int, a: Float): Int = PanelPalette.alpha(color, a)
}

/**
 * The trip panel's renderer contract on top of the shared [PanelCanvas] drawing
 * kit. The fullscreen panel uses its original virtual 1850x360 space; a renderer
 * may select another virtual space when its content genuinely reflows (the
 * narrow split layout does this).
 */
abstract class BaseTripRenderer : PanelCanvas() {

    /**
     * @param frameTimeSec monotonic seconds since the panel started (for phase)
     * @param dtSec seconds since the previous drawn frame (for physics integration)
     * @param spectrum the live output-mix capture feeding the analyser
     * @param nowPlaying the active media session, for the track strip
     * @param showLocationHint when true, draw the muted "no location access" hint
     *   in an area that stays clear of the panel's own captions and figures in
     *   both the GNSS and no-GNSS states.
     * @param narrowLayout stacks the analyser and trip values for a narrow split pane.
     */
    abstract fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
        narrowLayout: Boolean = false,
    )

    protected fun setSize(width: Float, height: Float) =
        setSize(width, height, VIRTUAL_W, VIRTUAL_H)

    companion object {
        const val VIRTUAL_W = 1850f
        const val VIRTUAL_H = 360f
        const val LOCATION_HINT = "нет доступа к геолокации"

        /**
         * The height this panel's own layout asks for at [width].
         *
         * [PanelCanvas] scales x and y independently, which is what lets a renderer fill whatever
         * box it is given - and also what silently distorts it when the box is a different shape
         * from the layout. The full-width dashboard used to hand this strip everything left over
         * below the tiles, about twice the height its own space asks for, and every circle in it
         * came out an ellipse. Callers that can choose a height should ask here for it.
         */
        fun heightFor(width: Float): Float = width * VIRTUAL_H / VIRTUAL_W

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
}
