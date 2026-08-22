package dev.denza.apps.feature.panel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The colours every canvas panel draws with.
 *
 * These mirror the Compose theme constants in ui/DenzaAppsScreen.kt rather than
 * inventing new hues: dark background, mint accent, amber warning, coral danger,
 * ink text, muted labels. [BLUE] is the cool end the journey thread already used
 * for dawn; the vehicle panel reuses it for everything that flows the other way
 * (regeneration, charging, "too cold").
 *
 * feature.trip keeps its own [dev.denza.apps.feature.trip.TripPalette], which
 * delegates here for the shared colours and adds the journey thread's
 * time-of-day ramp on top.
 */
object PanelPalette {
    val MINT = 0xFF73E0BD.toInt()
    val AMBER = 0xFFF2C46D.toInt()
    val DANGER = 0xFFFF8B91.toInt()
    val BLUE = 0xFF6EA8FF.toInt()
    val INK = 0xFFF3F7F8.toInt()
    val MUTED = 0xFF9AA7AD.toInt()

    fun alpha(color: Int, a: Float): Int {
        val clamped = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }

    /** Linear blend in sRGB; good enough for the short ramps the panels use. */
    fun mix(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt(),
        )
    }
}

/**
 * Coordinate and text plumbing shared by the panel renderers.
 *
 * A renderer lays itself out in a fixed virtual space and this class maps that
 * space onto whatever rectangle the view actually got, so one set of constants
 * describes the layout at every window width. All Paint objects are preallocated
 * — nothing is allocated in the hot draw path, which runs on the main thread.
 */
abstract class PanelCanvas {

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

    private val boldMono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    protected fun setSize(
        width: Float,
        height: Float,
        virtualWidth: Float,
        virtualHeight: Float,
    ) {
        w = width
        h = height
        sx = width / virtualWidth
        sy = height / virtualHeight
    }

    /** Map an x from the active virtual layout to the canvas. */
    protected fun vx(x: Float): Float = x * sx

    /** Map a y from the active virtual layout to the canvas. */
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
        bold: Boolean = false,
    ) {
        valuePaint.typeface = if (bold) boldMono else Typeface.MONOSPACE
        valuePaint.textSize = vs(sizeV)
        valuePaint.color = color
        valuePaint.textAlign = align
        canvas.drawText(text, x, y, valuePaint)
    }

    protected fun labelWidth(text: String, sizeV: Float): Float {
        labelPaint.textSize = vs(sizeV)
        return labelPaint.measureText(text)
    }

    protected fun valueWidth(text: String, sizeV: Float, bold: Boolean = false): Float {
        valuePaint.typeface = if (bold) boldMono else Typeface.MONOSPACE
        valuePaint.textSize = vs(sizeV)
        return valuePaint.measureText(text)
    }

    /** The panels' only divider: a one-unit line, never a frame or a box. */
    protected fun hairline(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        alpha: Float = 0.14f,
    ) {
        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, alpha)
        stroke.strokeWidth = vs(1f)
        canvas.drawLine(x0, y0, x1, y1, stroke)
    }
}
