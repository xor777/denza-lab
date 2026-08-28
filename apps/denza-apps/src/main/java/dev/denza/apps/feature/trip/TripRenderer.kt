package dev.denza.apps.feature.trip

import android.graphics.Canvas
import dev.denza.apps.feature.panel.PanelCanvas
import dev.denza.apps.feature.panel.PanelPalette

/**
 * Palette for the trip panel.
 *
 * The shared hues live in [PanelPalette] — every canvas panel in the app draws
 * with the same dark background, mint accent, amber warning, ink text and muted
 * labels.
 */
object TripPalette {
    val LIVE = PanelPalette.LIVE
    val AMBER = PanelPalette.AMBER
    val INK = PanelPalette.INK
    val MUTED = PanelPalette.MUTED

    fun alpha(color: Int, a: Float): Int = PanelPalette.alpha(color, a)
}

/**
 * The trip panel's renderer contract on top of the shared [PanelCanvas] drawing
 * kit. The concrete renderer selects the virtual space for its wide or narrow
 * layout.
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
     * @param layout which of the three compositions to draw; see [TripPanelLayout].
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
        layout: TripPanelLayout = TripPanelLayout.WIDE,
    )

    companion object {
        const val LOCATION_HINT = "нет доступа к геолокации"

        /**
         * The height the *wide* panel's layout asks for at [width]; the panes are told a number.
         *
         * [PanelCanvas] scales x and y independently, which is what lets a renderer fill whatever
         * box it is given - and also what silently distorts it when the box is a different shape
         * from the layout. The dashboard used to hand this strip whatever height was left below
         * the tiles, and the strip was laid out in a 5:1 band, so every stroke in it came out
         * stretched about twofold. It is laid out in the board's own 1184x296 now - 296 because the
         * car gives the app 680 dp of height and the tiles and the page margins take 384 - and this
         * is how
         * a caller asks for a box of that shape.
         */
        fun heightFor(width: Float): Float =
            width * TripPanelRenderer.WIDE_VIRTUAL_H / TripPanelRenderer.WIDE_VIRTUAL_W

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
