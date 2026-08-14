package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Palette for the canvas rendering. These mirror the app's existing Compose
 * theme constants (see ui/DenzaAppsScreen.kt) rather than inventing new hues:
 * dark background, mint accent, amber warning, ink text, muted labels.
 *
 * The journey-thread time-of-day stops (dawn blue -> day mint -> golden amber ->
 * evening coral -> night violet) are the palette the feature spec defines for
 * that specific mapping.
 */
object TripPalette {
    val MINT = 0xFF73E0BD.toInt() // Accent
    val AMBER = 0xFFF2C46D.toInt() // Warning
    val INK = 0xFFF3F7F8.toInt()
    val MUTED = 0xFF9AA7AD.toInt()

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

    fun alpha(color: Int, a: Float): Int {
        val clamped = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }
}

/**
 * Coordinate + text helpers for the panel renderer. The panel was designed in a
 * virtual 1850x360 space (the free zone's design ratio); we map that space onto
 * whatever width/height is actually free, keeping the vertical proportions and
 * stretching horizontally. All Paint/Path objects are preallocated — nothing is
 * allocated in the hot draw path. Rendering and animation happen on the main
 * thread.
 */
abstract class BaseTripRenderer {

    /**
     * @param frameTimeSec monotonic seconds since the panel started (for phase)
     * @param dtSec seconds since the previous drawn frame (for physics integration)
     * @param spectrum the live output-mix capture feeding the analyser
     * @param nowPlaying the active media session, for the track strip
     * @param showLocationHint when true, draw the muted "no location access" hint
     *   in an area that stays clear of the panel's own captions and figures in
     *   both the GNSS and no-GNSS states.
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
    )
    protected var w: Float = 0f
    protected var h: Float = 0f
    private var sx: Float = 1f
    private var sy: Float = 1f

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    protected val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    protected fun setSize(width: Float, height: Float) {
        w = width
        h = height
        sx = width / VIRTUAL_W
        sy = height / VIRTUAL_H
    }

    /** Map a virtual x (0..1850) to a canvas x. */
    protected fun vx(x: Float): Float = x * sx

    /** Map a virtual y (0..360) to a canvas y. */
    protected fun vy(y: Float): Float = y * sy

    /** Scale a size/radius/stroke by the vertical factor (keeps shapes round). */
    protected fun vs(size: Float): Float = size * sy

    protected fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeV: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        labelPaint.textSize = vs(sizeV)
        labelPaint.color = color
        labelPaint.textAlign = align
        canvas.drawText(text, x, y, labelPaint)
    }

    protected fun value(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeV: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        valuePaint.textSize = vs(sizeV)
        valuePaint.color = color
        valuePaint.textAlign = align
        canvas.drawText(text, x, y, valuePaint)
    }

    companion object {
        const val VIRTUAL_W = 1850f
        const val VIRTUAL_H = 360f
        const val LOCATION_HINT = "нет доступа к геолокации"

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
