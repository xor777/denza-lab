package dev.denza.apps.design.instrument

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.feature.panel.PanelCanvas
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shapes every instrument in this app is built from.
 *
 * [PanelCanvas] gives coordinates, text and one hairline, and deliberately nothing else - each of
 * the three panel renderers grew its own arcs, tracks and needles inline, and no two of them agree
 * on how a gauge is drawn. This class is where that stops: a gauge, a track and a chart are drawn
 * once here and composed by the assemblies above.
 *
 * Angles are the ordinary mathematical kind - degrees counter-clockwise from east - because that is
 * what [EnergyScale] speaks. The conversion to the platform's clockwise-from-east convention
 * happens here and nowhere else.
 */
abstract class InstrumentCanvas : PanelCanvas() {

    private val oval = RectF()

    /**
     * A stroked arc between two angles, taking the short way round from [fromDegrees].
     *
     * A zero-length sweep draws nothing rather than a stray round cap, which is what a gauge at
     * rest needs: no reading is not the same as a reading of zero.
     */
    protected fun arc(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radius: Float,
        fromDegrees: Float,
        toDegrees: Float,
        color: Int,
        widthV: Float,
        round: Boolean = true,
    ) {
        val sweep = toDegrees - fromDegrees
        if (sweep == 0f) return
        oval.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius)
        stroke.color = color
        stroke.strokeWidth = vs(widthV)
        stroke.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        // The platform measures angles clockwise; ours run the other way.
        canvas.drawArc(oval, -fromDegrees, -sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    /** A filled dot, sized in virtual units so it stays round on a stretched space. */
    protected fun dot(canvas: Canvas, x: Float, y: Float, radiusV: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, vs(radiusV), fill)
    }

    /** A hollow dot: a reading that never answered, which is a weaker claim than a good one. */
    protected fun hollowDot(canvas: Canvas, x: Float, y: Float, radiusV: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = vs(1.4f)
        canvas.drawCircle(x, y, vs(radiusV), stroke)
    }

    /** A mark on a dial, drawn outward from the arc it belongs to. */
    protected fun tick(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radius: Float,
        degrees: Float,
        lengthV: Float,
        color: Int,
        gapV: Float = 3f,
    ) {
        val radians = Math.toRadians(degrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val inner = radius + vs(gapV)
        val outer = inner + vs(lengthV)
        stroke.color = color
        stroke.strokeWidth = vs(1.8f)
        canvas.drawLine(
            centreX + cosine * inner,
            centreY - sine * inner,
            centreX + cosine * outer,
            centreY - sine * outer,
            stroke,
        )
    }

    /** Where a point on a dial falls, so a caller can label or cap it. */
    protected fun onArc(
        centreX: Float,
        centreY: Float,
        radius: Float,
        degrees: Float,
    ): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        return centreX + cos(radians).toFloat() * radius to
            centreY - sin(radians).toFloat() * radius
    }

    /**
     * A linear gauge: one unlit track with a lit run along it.
     *
     * [fraction] outside `0f..1f` clamps rather than overflowing the track, for the same reason a
     * dial clamps - a reading we cannot bound must not be drawn as if we could.
     *
     * A null [trackColor] leaves the unlit part alone, which is how a second reading is laid over
     * the first on one line: painting the track twice would erase whatever ran underneath it.
     */
    protected fun track(
        canvas: Canvas,
        left: Float,
        right: Float,
        centreY: Float,
        heightV: Float,
        fraction: Float?,
        fillColor: Int,
        trackColor: Int? = DenzaPalette.TRACK,
    ) {
        val half = vs(heightV) / 2f
        val radius = half
        if (trackColor != null) {
            fill.color = trackColor
            canvas.drawRoundRect(left, centreY - half, right, centreY + half, radius, radius, fill)
        }
        if (fraction == null) return
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0f) return
        fill.color = fillColor
        canvas.drawRoundRect(
            left,
            centreY - half,
            left + (right - left) * clamped,
            centreY + half,
            radius,
            radius,
            fill,
        )
    }

    /**
     * The soft ground an island sits on.
     *
     * The cluster dashboard draws over live vehicle graphics rather than over a background of its
     * own, so each block needs enough darkness under it to stay legible without blanking the panel.
     * A radial fade does that and still reads as an island; a filled rectangle would read as a
     * window cut into the car's own instruments.
     */
    protected fun scrim(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radiusX: Float,
        radiusY: Float,
        strength: Float = 0.72f,
    ) {
        if (radiusX <= 0f || radiusY <= 0f) return
        val alpha = (strength.coerceIn(0f, 1f) * 255f).toInt()
        fill.shader = RadialGradient(
            centreX,
            centreY,
            radiusX,
            intArrayOf(alpha shl 24, (alpha * 3 / 4) shl 24, 0),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        val save = canvas.save()
        canvas.scale(1f, radiusY / radiusX, centreX, centreY)
        canvas.drawCircle(centreX, centreY, radiusX, fill)
        canvas.restoreToCount(save)
        fill.shader = null
    }

    /**
     * A run of consumption bars about a zero line, newest at the right.
     *
     * Bars below the line are energy the car got back, and they are drawn in [DenzaPalette.RETURN]
     * rather than a dimmer version of the spending colour: recovery is a different event, not less
     * of the same one.
     */
    protected fun consumptionChart(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        values: List<Double>,
        barWidthV: Float,
        average: Double?,
        averageColor: Int,
    ) {
        val height = bottom - top
        val zeroY = top + ChartScale.zeroLine(height)

        stroke.color = DenzaPalette.ink(0.24f)
        stroke.strokeWidth = vs(1.2f)
        canvas.drawLine(left, zeroY, right, zeroY, stroke)

        if (values.isEmpty()) return

        val floats = values.map(Double::toFloat)
        val ceilings = ChartScale.ceilings(floats)
        val step = (right - left) / values.size
        val barWidth = minOf(vs(barWidthV), step * 0.78f)

        floats.forEachIndexed { index, value ->
            val barHeight = ChartScale.barHeight(value, height, ceilings)
            if (barHeight <= 0f) return@forEachIndexed
            val x = left + step * index + (step - barWidth) / 2f
            val newest = index == floats.lastIndex
            fill.color = when {
                value < 0f -> DenzaPalette.returned(0.78f)
                newest -> DenzaPalette.DATA_PEAK
                else -> DenzaPalette.ink(0.55f)
            }
            val barTop = if (value >= 0f) zeroY - barHeight else zeroY
            canvas.drawRect(x, barTop, x + barWidth, barTop + barHeight, fill)
        }

        if (average == null || average <= 0.0) return
        val averageY = zeroY - ChartScale.barHeight(average.toFloat(), height, ceilings)
        stroke.color = averageColor
        stroke.strokeWidth = vs(1.2f)
        stroke.pathEffect = dashes()
        canvas.drawLine(left, averageY, right, averageY, stroke)
        stroke.pathEffect = null
    }

    private var dashUnit = 0f
    private var dashEffect: android.graphics.DashPathEffect? = null

    private fun dashes(): android.graphics.DashPathEffect {
        val unit = vs(5f)
        val cached = dashEffect
        if (cached != null && unit == dashUnit) return cached
        dashUnit = unit
        return android.graphics.DashPathEffect(floatArrayOf(unit, unit), 0f)
            .also { dashEffect = it }
    }
}
