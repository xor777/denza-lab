package dev.denza.apps.design.instrument

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.denza.apps.design.DenzaPalette

/**
 * The shapes every instrument in this app is built from, and the surface they are drawn on.
 *
 * This is a pen an assembly *holds*, not a base class it extends. The difference matters: the three
 * older panel renderers each inherited their drawing surface and each grew its own arcs, tracks and
 * needles inline as private methods, because inheritance gave them nowhere else to put them. A
 * component like [EnergyGauge] can only exist - and only be reused across the cluster and the two
 * projected panel widths - if the surface is something it can be handed.
 *
 * Angles are the ordinary mathematical kind, degrees counter-clockwise from east, because that is
 * what [EnergyScale] speaks. The conversion to the platform's clockwise convention happens here and
 * nowhere else.
 *
 * Nothing is allocated per frame: this runs on the main thread at the cluster's draw cadence.
 */
class InstrumentPen {

    /** The canvas rectangle in pixels, after [size]. */
    var width: Float = 0f
        private set

    var height: Float = 0f
        private set

    private var unit: Float = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private val oval = RectF()
    private val bounds = Rect()
    private var dashUnit = 0f
    private var dashEffect: DashPathEffect? = null

    private var glowShader: RadialGradient? = null
    private var glowCentreX = Float.NaN
    private var glowCentreY = Float.NaN
    private var glowRadius = Float.NaN
    private var glowArgb = 0

    /**
     * Fit a virtual layout onto the rectangle the view actually got.
     *
     * One factor rather than two: the cluster's placements are the same shape as the spaces drawn
     * for them, so an ellipse drawn as a circle would be a bug rather than a stretch.
     */
    fun size(canvasWidth: Float, canvasHeight: Float, virtualHeight: Float) {
        width = canvasWidth
        height = canvasHeight
        unit = if (virtualHeight <= 0f) 1f else canvasHeight / virtualHeight
    }

    /** A size, stroke or radius stated in the virtual space, in pixels. */
    fun v(size: Float): Float = size * unit

    // ---- text

    fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeV: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        labelPaint.textSize = v(sizeV)
        labelPaint.color = color
        labelPaint.textAlign = align
        canvas.drawText(text, x, y, labelPaint)
    }

    /**
     * A section name, set in capitals with the ramp's tracking.
     *
     * It has a method of its own rather than a parameter on [label] so the tracking cannot be
     * forgotten at one call site and applied at the next - which is precisely how the design boards
     * ended up with the same title set two different ways on adjacent artboards.
     */
    fun title(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        density: InstrumentDensity,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        labelPaint.letterSpacing = density.titleTracking
        label(canvas, text, x, baseline, density.title, color, align)
        labelPaint.letterSpacing = 0f
    }

    fun value(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeV: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        valuePaint.textSize = v(sizeV)
        valuePaint.color = color
        valuePaint.textAlign = align
        canvas.drawText(text, x, y, valuePaint)
    }

    fun labelWidth(text: String, sizeV: Float): Float {
        labelPaint.textSize = v(sizeV)
        return labelPaint.measureText(text)
    }

    fun valueWidth(text: String, sizeV: Float): Float {
        valuePaint.textSize = v(sizeV)
        return valuePaint.measureText(text)
    }

    /**
     * How tall a digit actually comes out, from its baseline up.
     *
     * Not the type size: a font's size includes the room above the caps and below the baseline that
     * accents and descenders live in, and a shape asked to be "as tall as this number" and given the
     * type size stands visibly taller than the digits beside it. Measured from a real glyph so the
     * answer stays right if the face ever changes.
     */
    fun digitHeight(sizeV: Float): Float {
        valuePaint.textSize = v(sizeV)
        valuePaint.getTextBounds(DIGIT, 0, DIGIT.length, bounds)
        return bounds.height().toFloat()
    }

    /**
     * A number with its unit after it, as one thing.
     *
     * Every block needs this and every block was measuring it by hand, which is how a gap of 6 in
     * one method and 8 in the next got in. The pair is placed by [align] as a whole, and the width
     * it came out at is returned so a caller can put something beside it.
     */
    fun figure(
        canvas: Canvas,
        text: String,
        unitText: String,
        x: Float,
        baseline: Float,
        density: InstrumentDensity,
        sizeV: Float,
        color: Int,
        unitColor: Int = DenzaPalette.MUTED,
        align: Paint.Align = Paint.Align.LEFT,
    ): Float {
        val numberWidth = valueWidth(text, sizeV)
        val unitWidth = if (unitText.isEmpty()) 0f else labelWidth(unitText, density.body)
        val gap = if (unitText.isEmpty()) 0f else density.rhythm(1f)
        val total = numberWidth + gap + unitWidth
        val left = when (align) {
            Paint.Align.LEFT -> x
            Paint.Align.RIGHT -> x - total
            Paint.Align.CENTER -> x - total / 2f
        }
        value(canvas, text, left, baseline, sizeV, color)
        if (unitText.isNotEmpty()) {
            label(canvas, unitText, left + numberWidth + gap, baseline, density.body, unitColor)
        }
        return total
    }

    // ---- primitives

    /**
     * A stroked arc between two angles, taking the short way round from [fromDegrees].
     *
     * A zero-length sweep draws nothing rather than a stray round cap, which is what a gauge at rest
     * needs: no reading is not the same as a reading of zero.
     */
    fun arc(
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
        stroke.strokeWidth = v(widthV)
        stroke.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        // The platform measures angles clockwise; ours run the other way.
        canvas.drawArc(oval, -fromDegrees, -sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    /**
     * The soft light a dial pools on the panel around itself.
     *
     * Found by accident. Before the dashboard had a ground of its own, the dark scrim that kept each
     * island legible ran over the vehicle's own lit background and left a halo around the gauge; the
     * owner saw it on the car and asked for it on purpose. So this is the same shape that scrim was
     * and the opposite colour: a wide, low-alpha radial lift, strongest at the centre and gone by the
     * rim.
     *
     * It is the one thing on the dashboard that carries no reading. On a black ground a gauge drawn
     * in strokes alone sits flat, and a low pool of light under it gives the instrument a centre of
     * gravity without adding anything the driver has to look at.
     *
     * The gradient is rebuilt only when its geometry or colour moves - which, on a fixed panel, is
     * once - because this is drawn on the main thread at the cluster's cadence.
     */
    fun glow(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radiusX: Float,
        radiusY: Float,
        color: Int,
        strength: Float,
    ) {
        if (radiusX <= 0f || radiusY <= 0f) return
        val alpha = (strength.coerceIn(0f, 1f) * 255f).toInt()
        if (alpha == 0) return
        val argb = (alpha shl 24) or (color and 0x00FFFFFF)
        if (
            glowShader == null ||
            centreX != glowCentreX ||
            centreY != glowCentreY ||
            radiusX != glowRadius ||
            argb != glowArgb
        ) {
            glowCentreX = centreX
            glowCentreY = centreY
            glowRadius = radiusX
            glowArgb = argb
            val tint = argb and 0x00FFFFFF
            glowShader = RadialGradient(
                centreX,
                centreY,
                radiusX,
                intArrayOf(argb, ((alpha * GLOW_MID_PERCENT / 100) shl 24) or tint, tint),
                floatArrayOf(0f, GLOW_MID_STOP, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        fill.shader = glowShader
        val save = canvas.save()
        canvas.scale(1f, radiusY / radiusX, centreX, centreY)
        canvas.drawCircle(centreX, centreY, radiusX, fill)
        canvas.restoreToCount(save)
        fill.shader = null
    }

    /**
     * One run of readings over time, oldest at the left, with a dot on the newest.
     *
     * The run is always the full width of its axis: [values] carries one entry per slot of that
     * axis, `null` where nothing was recorded, and a null breaks the line rather than being drawn
     * through. That is the whole point of it - a straight segment across a gap would claim the
     * engine held a steady speed through a minute nobody was watching.
     *
     * [ceiling] is the top of the box. A reading past it is clamped rather than drawn outside, and
     * [squareRoot] gives the low end of a wide span room, the way the energy dial does.
     */
    fun trace(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        values: List<Double?>,
        ceiling: Double,
        color: Int,
        widthV: Float,
        dotRadiusV: Float,
        squareRoot: Boolean = false,
    ) {
        if (values.size < 2 || ceiling <= 0.0 || right <= left || bottom <= top) return
        val step = (right - left) / (values.size - 1)
        val height = bottom - top

        fun yOf(value: Double): Float {
            val share = (value / ceiling).coerceIn(0.0, 1.0)
            return bottom - height * (if (squareRoot) kotlin.math.sqrt(share) else share).toFloat()
        }

        stroke.color = color
        stroke.strokeWidth = v(widthV)
        var open = false
        var lastX = 0f
        var lastY = 0f
        values.forEachIndexed { index, value ->
            if (value == null) {
                open = false
                return@forEachIndexed
            }
            val x = left + index * step
            val y = yOf(value)
            if (open) canvas.drawLine(lastX, lastY, x, y, stroke)
            lastX = x
            lastY = y
            open = true
        }
        // A single reading with nothing either side draws no segment at all, so the dot is what
        // says it arrived.
        val newest = values.indexOfLast { it != null }
        if (newest >= 0 && dotRadiusV > 0f) {
            dot(canvas, left + newest * step, yOf(values[newest]!!), dotRadiusV, color)
        }
    }

    /** A filled dot. */
    fun dot(canvas: Canvas, x: Float, y: Float, radiusV: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, v(radiusV), fill)
    }

    /** A hollow dot: a reading that never answered, which is a weaker claim than a good one. */
    fun hollowDot(canvas: Canvas, x: Float, y: Float, radiusV: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = v(1.4f)
        canvas.drawCircle(x, y, v(radiusV), stroke)
    }

    /**
     * A mark on a dial, drawn outward along the radius through [degrees].
     *
     * Both ends are computed from the same angle, which is the whole of it: the hand-placed ticks on
     * the design boards ended up on three different radii and one of them lay along the arc instead
     * of across it, because each end was typed rather than derived.
     */
    fun tick(
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
        val cosine = kotlin.math.cos(radians).toFloat()
        val sine = kotlin.math.sin(radians).toFloat()
        val inner = radius + v(gapV)
        val outer = inner + v(lengthV)
        stroke.color = color
        stroke.strokeWidth = v(1.8f)
        canvas.drawLine(
            centreX + cosine * inner,
            centreY - sine * inner,
            centreX + cosine * outer,
            centreY - sine * outer,
            stroke,
        )
    }

    /** Where a point on a dial falls, so a caller can label or cap it. */
    fun onArc(centreX: Float, centreY: Float, radius: Float, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        return centreX + kotlin.math.cos(radians).toFloat() * radius to
            centreY - kotlin.math.sin(radians).toFloat() * radius
    }

    /**
     * A linear gauge: one unlit track with a lit run along it.
     *
     * [fraction] outside `0f..1f` clamps rather than overflowing, for the same reason a dial clamps:
     * a reading we cannot bound must not be drawn as if we could. A null [trackColor] leaves the
     * unlit part alone, which is how a second reading is laid over the first on one line - painting
     * the track twice would erase whatever ran underneath.
     */
    fun track(
        canvas: Canvas,
        left: Float,
        right: Float,
        centreY: Float,
        heightV: Float,
        fraction: Float?,
        fillColor: Int,
        trackColor: Int? = DenzaPalette.TRACK,
    ) {
        val half = v(heightV) / 2f
        if (trackColor != null) {
            fill.color = trackColor
            canvas.drawRoundRect(left, centreY - half, right, centreY + half, half, half, fill)
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
            half,
            half,
            fill,
        )
    }

    /**
     * A run of consumption bars about a zero line, newest at the right.
     *
     * Nothing is drawn for an empty run - not even the zero line. A bare rule with no bars on it
     * inside a dial reads as a chart that failed rather than as a chart with nothing to say yet, and
     * the assembly has a sentence for that case.
     *
     * Bars below the line are energy the car got back, drawn in [DenzaPalette.RETURN] rather than a
     * dimmer version of the spending colour: recovery is a different event, not less of the same
     * one.
     */
    fun consumptionChart(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        values: List<Double>,
        density: InstrumentDensity,
        average: Double?,
        averageColor: Int,
    ) {
        if (values.isEmpty()) return
        val chartHeight = bottom - top
        val zeroY = top + ChartScale.zeroLine(chartHeight)

        stroke.color = DenzaPalette.ink(0.24f)
        stroke.strokeWidth = v(density.hairline)
        canvas.drawLine(left, zeroY, right, zeroY, stroke)

        val floats = values.map(Double::toFloat)
        val ceilings = ChartScale.ceilings(floats)
        val step = (right - left) / values.size
        val barWidth = minOf(v(density.barWidth), step * 0.78f)

        floats.forEachIndexed { index, value ->
            val barHeight = ChartScale.barHeight(value, chartHeight, ceilings)
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
        val averageY = zeroY - ChartScale.barHeight(average.toFloat(), chartHeight, ceilings)
        stroke.color = averageColor
        stroke.strokeWidth = v(density.hairline)
        stroke.pathEffect = dashes()
        canvas.drawLine(left, averageY, right, averageY, stroke)
        stroke.pathEffect = null
    }

    private fun dashes(): DashPathEffect {
        val length = v(5f)
        val cached = dashEffect
        if (cached != null && length == dashUnit) return cached
        dashUnit = length
        return DashPathEffect(floatArrayOf(length, length), 0f).also { dashEffect = it }
    }

    private companion object {
        /**
         * The middle stop of a glow, and how much of its strength is left there.
         *
         * Two stops would fall off in a straight line and read as a disc with an edge. Holding most
         * of the light through the first half and then letting it go is what makes it a pool of
         * light rather than a shape.
         */
        const val GLOW_MID_STOP = 0.5f
        const val GLOW_MID_PERCENT = 45

        /** Measured rather than assumed; any digit of a monospaced face would do. */
        const val DIGIT = "0"
    }
}
