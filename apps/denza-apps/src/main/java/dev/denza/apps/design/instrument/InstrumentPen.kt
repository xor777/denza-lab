package dev.denza.apps.design.instrument

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The shapes the cluster is built from, and the surface they are drawn on.
 *
 * This is a pen an assembly *holds*, not a base class it extends. The difference matters: the three
 * older panel renderers each inherited their drawing surface and each grew its own arcs, tracks and
 * needles inline as private methods, because inheritance gave them nowhere else to put them.
 *
 * ### Nothing is allocated in a frame
 *
 * This runs on the main thread at the cluster's cadence, so every `Paint`, `Path`, `Matrix` and
 * `Shader` here is built once and reused. Two consequences are worth knowing before adding
 * anything:
 *
 *  - the band's gradient is built once over the span `0…1` and placed each frame with a local
 *    matrix, rather than rebuilt at its new length. A `LinearGradient` per frame is an allocation
 *    per frame, and the pedal moves every frame;
 *  - the glow's gradients are built once at full alpha and dimmed with `Paint.alpha`, which
 *    modulates a shader's output. Rebuilding one per brightness is the same defect one level along.
 *
 * ### Virtual units
 *
 * Everything a caller states is in the panel's own 424-unit space; [v] is the only conversion, and
 * [size] is the only place the factor is decided. One factor rather than two: the cluster's window
 * is the same shape as the space drawn for it, so an ellipse drawn as a circle would be a bug rather
 * than a stretch.
 */
class InstrumentPen {

    /** The canvas rectangle in pixels, after [size]. */
    var width: Float = 0f
        private set

    var height: Float = 0f
        private set

    /**
     * Zero until [size] is called, which is what makes the first call always a change.
     *
     * Every text size on this pen is set from that call, so a first frame that happened to land on
     * a factor of exactly one would otherwise measure and draw at a `Paint`'s default 12 px - and
     * the plan is *measured*, so it would be wrong rather than merely small.
     */
    private var unit: Float = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * One paint per face, so a size, a weight and a tracking are set together or not at all.
     *
     * `tnum` is asked for on every one of them. Roboto's own figures are already fixed-width, so on
     * this car it changes nothing; on a car whose framework resolves a different face it is what
     * keeps a reserve field a contract.
     */
    private val faces: Map<InstrumentFace, Paint> = InstrumentFace.entries.associateWith { face ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaceFor(face.weight)
            letterSpacing = face.tracking
            fontFeatureSettings = TABULAR
        }
    }

    private val path = Path()
    private val matrix = Matrix()

    private var bandShader: LinearGradient? = null
    private var bandFrom = 0
    private var bandTo = 0

    private var glowShader: RadialGradient? = null
    private var glowColor = 0
    private var glowRadius = 0f

    /**
     * Fit the virtual layout onto the rectangle the view actually got.
     *
     * Returns whether the factor moved, so a caller can rebuild whatever it measured in pixels.
     */
    fun size(canvasWidth: Float, canvasHeight: Float, virtualHeight: Float): Boolean {
        val next = if (virtualHeight <= 0f) 1f else canvasHeight / virtualHeight
        val moved = next != unit
        width = canvasWidth
        height = canvasHeight
        unit = next
        if (moved) {
            faces.forEach { (face, paint) -> paint.textSize = v(face.size) }
            glowShader = null
        }
        return moved
    }

    /** A size, stroke or radius stated in the virtual space, in pixels. */
    fun v(size: Float): Float = size * unit

    /** A pixel measurement back in the virtual space. */
    fun u(pixels: Float): Float = if (unit <= 0f) pixels else pixels / unit

    // ---- text

    /** How wide [text] comes out in [face], in **virtual units**, which is what a plan speaks. */
    fun widthOf(text: String, face: InstrumentFace): Float =
        u(requireFace(face).measureText(text))

    fun text(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        face: InstrumentFace,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val paint = requireFace(face)
        paint.color = color
        paint.textAlign = align
        canvas.drawText(text, x, baseline, paint)
    }

    private fun requireFace(face: InstrumentFace): Paint =
        requireNotNull(faces[face]) { "no paint for $face" }

    // ---- primitives

    fun rect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        if (right <= left || bottom <= top) return
        fill.color = color
        fill.alpha = FULL_ALPHA
        canvas.drawRect(left, top, right, bottom, fill)
    }

    fun line(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Int,
        widthV: Float,
        alpha: Float = 1f,
    ) {
        stroke.color = color
        stroke.alpha = (alpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        stroke.strokeWidth = v(widthV)
        canvas.drawLine(x0, y0, x1, y1, stroke)
    }

    fun dot(canvas: Canvas, x: Float, y: Float, radiusV: Float, color: Int) {
        fill.color = color
        fill.alpha = FULL_ALPHA
        canvas.drawCircle(x, y, v(radiusV), fill)
    }

    /**
     * A rounded rectangle, filled - the lit part of a glyph.
     *
     * A radius of zero is a plain rectangle and `drawRoundRect` draws one, so the pack's terminal
     * and its cell go through the same call as the motor's block.
     */
    fun plate(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusV: Float,
        color: Int,
    ) {
        if (right <= left || bottom <= top) return
        fill.color = color
        fill.alpha = FULL_ALPHA
        val r = v(radiusV)
        canvas.drawRoundRect(left, top, right, bottom, r, r, fill)
    }

    /**
     * And the same rectangle as an outline, which is what a glyph is mostly made of.
     *
     * The stroke straddles the path, so a caller stating the case's own edges gets a mark half a
     * stroke wider than it asked for in every direction - which is what both records do, so the
     * boards and the panel agree on it.
     */
    fun frame(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusV: Float,
        color: Int,
        widthV: Float,
    ) {
        if (right <= left || bottom <= top) return
        stroke.color = color
        stroke.alpha = FULL_ALPHA
        stroke.strokeWidth = v(widthV)
        val r = v(radiusV)
        canvas.drawRoundRect(left, top, right, bottom, r, r, stroke)
    }

    /**
     * A run of points joined straight, stroked - the inverter's own alternating current.
     *
     * One lived here until the eighth pass and was deleted with its only caller, the engine box's
     * revolutions, on the rule that a primitive nothing draws with is a promise rather than a tool.
     * The ninth pass gave it a caller again: a sine is twenty-one points and a `Path`, and the path
     * belongs to whoever builds the points rather than to the pen.
     */
    fun polyline(canvas: Canvas, xs: FloatArray, ys: FloatArray, count: Int, color: Int, widthV: Float) {
        if (count < 2) return
        path.rewind()
        path.moveTo(xs[0], ys[0])
        for (index in 1 until count) path.lineTo(xs[index], ys[index])
        stroke.color = color
        stroke.alpha = FULL_ALPHA
        stroke.strokeWidth = v(widthV)
        canvas.drawPath(path, stroke)
    }

    /**
     * The band's body: one rectangle from the zero mark to the tip, lit along its own length.
     *
     * The gradient is the panel's one moving light and it is built once. [from] sits at the zero
     * mark at [FILL_ALPHA] of itself and [to] at the tip at full, so the tip is the live edge of the
     * data and the rest of the bar is the same reading, quieter.
     */
    fun band(
        canvas: Canvas,
        zeroX: Float,
        tipX: Float,
        top: Float,
        bottom: Float,
        from: Int,
        to: Int,
    ) {
        if (tipX == zeroX) return
        if (bandShader == null || from != bandFrom || to != bandTo) {
            bandFrom = from
            bandTo = to
            bandShader = LinearGradient(
                0f,
                0f,
                1f,
                0f,
                (from and 0x00FFFFFF) or (FILL_ALPHA shl 24),
                to,
                Shader.TileMode.CLAMP,
            )
        }
        matrix.reset()
        matrix.setTranslate(zeroX, 0f)
        matrix.preScale(tipX - zeroX, 1f)
        bandShader?.setLocalMatrix(matrix)
        fill.shader = bandShader
        fill.alpha = FULL_ALPHA
        canvas.drawRect(minOf(zeroX, tipX), top, maxOf(zeroX, tipX), bottom, fill)
        fill.shader = null
    }

    /**
     * The one pool of light on the panel, and it does not move.
     *
     * Centred on zero, hue by sign, brightness by magnitude. The fourth board had it riding the
     * band's tip, which put a 73 mm pool through 50-100 mm of travel every time the pedal moved in
     * a jam - which is precisely what peripheral vision is built to catch, and the last thing a
     * driver's display should do with it.
     */
    fun glow(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radiusXV: Float,
        radiusYV: Float,
        color: Int,
        strength: Float,
    ) {
        val alpha = (strength.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        if (alpha == 0) return
        val radiusX = v(radiusXV)
        val radiusY = v(radiusYV)
        if (radiusX <= 0f || radiusY <= 0f) return
        if (glowShader == null || color != glowColor || radiusX != glowRadius) {
            glowColor = color
            glowRadius = radiusX
            val tint = color and 0x00FFFFFF
            glowShader = RadialGradient(
                centreX,
                centreY,
                radiusX,
                intArrayOf(
                    tint or (FULL_ALPHA shl 24),
                    tint or ((FULL_ALPHA * GLOW_MID_PERCENT / 100) shl 24),
                    tint,
                ),
                floatArrayOf(0f, GLOW_MID_STOP, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        fill.shader = glowShader
        // The strength is the paint's alpha rather than the gradient's, so a brightness that
        // changes every frame does not rebuild a shader every frame.
        fill.alpha = alpha
        val save = canvas.save()
        canvas.scale(1f, radiusY / radiusX, centreX, centreY)
        canvas.drawCircle(centreX, centreY, radiusX, fill)
        canvas.restoreToCount(save)
        fill.shader = null
        fill.alpha = FULL_ALPHA
    }

    /**
     * A history, as a stepped line over a field, both from one outline.
     *
     * A step rather than a curve because each value is a closed bucket rather than a sample of
     * something continuous, and a stepped line says so. Thirty bars 0.65 mm wide were 0.9′ at 750 mm
     * - under the eye's own resolution - which is why this is a line at all (M15).
     *
     * [zeroY] is where the field closes, which is not necessarily the box's floor: a descent gives
     * energy back, so a consumption history needs a zero line rather than a floor.
     */
    fun history(
        canvas: Canvas,
        left: Float,
        pitch: Float,
        ys: FloatArray,
        count: Int,
        zeroY: Float,
        lineColor: Int,
        lineAlpha: Float,
        lineWidthV: Float,
        fieldColor: Int,
        fieldAlpha: Float,
    ) {
        if (count <= 0) return
        // The field first, sharing the line's own outline, closed down to the zero line.
        stepContour(left, pitch, ys, count)
        path.lineTo(left + pitch * count, zeroY)
        path.lineTo(left, zeroY)
        path.close()
        fill.color = fieldColor
        fill.alpha = (fieldAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        canvas.drawPath(path, fill)
        fill.alpha = FULL_ALPHA

        // And the same outline again, open this time, so the line is drawn on top of its own field
        // and along neither the floor nor the two ends.
        stepContour(left, pitch, ys, count)
        stroke.color = lineColor
        stroke.alpha = (lineAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        stroke.strokeWidth = v(lineWidthV)
        canvas.drawPath(path, stroke)
    }

    /**
     * The steps themselves, into [path], which is left open at both ends.
     *
     * The field and the line are the same run of steps drawn twice - once closed down to a floor
     * and filled, once open and stroked - and the walk was written out twice with it. Two copies of
     * a contour is two chances for a history whose fill and whose line describe different data.
     */
    private fun stepContour(left: Float, pitch: Float, ys: FloatArray, count: Int) {
        path.rewind()
        path.moveTo(left, ys[0])
        for (index in 0 until count) {
            path.lineTo(left + pitch * (index + 1), ys[index])
            if (index + 1 < count) path.lineTo(left + pitch * (index + 1), ys[index + 1])
        }
    }

    /**
     * One stepped shape standing on [zeroY], posts and all, filled and edged from one path.
     *
     * Not [history]: that closes its field along a floor and leaves the outline open at both ends,
     * which is what a *continuous* history wants. This draws a stretch - something that starts and
     * stops inside the box - so the posts up from the zero line are part of the drawing rather than
     * the edge of a fill. The path is never closed, so nothing is stroked along the zero line
     * itself: the panel's own zero rule is drawn once, by whoever owns it.
     */
    fun steps(
        canvas: Canvas,
        left: Float,
        pitch: Float,
        ys: FloatArray,
        count: Int,
        zeroY: Float,
        fieldColor: Int,
        fieldAlpha: Float,
        edgeColor: Int,
        edgeWidthV: Float,
    ) {
        if (count <= 0) return
        path.rewind()
        path.moveTo(left, zeroY)
        for (index in 0 until count) {
            path.lineTo(left + pitch * index, ys[index])
            path.lineTo(left + pitch * (index + 1), ys[index])
        }
        path.lineTo(left + pitch * count, zeroY)
        fill.color = fieldColor
        fill.alpha = (fieldAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        canvas.drawPath(path, fill)
        fill.alpha = FULL_ALPHA
        stroke.color = edgeColor
        stroke.alpha = FULL_ALPHA
        stroke.strokeWidth = v(edgeWidthV)
        canvas.drawPath(path, stroke)
    }

    private fun typefaceFor(weight: InstrumentWeight): Typeface = when (weight) {
        InstrumentWeight.LIGHT -> LIGHT
        InstrumentWeight.REGULAR -> Typeface.SANS_SERIF
        InstrumentWeight.MEDIUM -> MEDIUM
    }

    private companion object {
        const val FULL_ALPHA = 255

        /** What the band's body is at the zero mark, against its lit tip. */
        const val FILL_ALPHA = 140

        /**
         * The middle stop of a glow, and how much of its strength is left there.
         *
         * Two stops would fall off in a straight line and read as a disc with an edge. Holding most
         * of the light through the first half and then letting it go is what makes it a pool of
         * light rather than a shape.
         */
        const val GLOW_MID_STOP = 0.5f
        const val GLOW_MID_PERCENT = 45

        const val TABULAR = "'tnum'"

        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
