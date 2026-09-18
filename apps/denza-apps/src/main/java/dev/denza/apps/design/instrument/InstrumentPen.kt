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
     * A step rather than a [curve] because each value is a closed bucket rather than a sample of
     * something continuous, and a stepped line says so. This is the engine box's shape: its slots
     * are closed five-second buckets. The consumption chart's points are a trailing kilometre and
     * are drawn with [curve]. Thirty bars 0.65 mm wide were 0.9′ at 750 mm - under the eye's own
     * resolution - which is why either of these is a line at all (M15).
     *
     * **The steps are stated as edges rather than as a pitch.** [xs] holds `count + 1` edges, so a
     * run that does not fill its box states where it starts and where it stops; a uniform box
     * simply hands in a uniform run of them.
     *
     * [zeroY] is where the field closes, which is not necessarily the box's floor: a descent gives
     * energy back, so a consumption history needs a zero line rather than a floor.
     */
    fun history(
        canvas: Canvas,
        xs: FloatArray,
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
        stepContour(xs, ys, count)
        path.lineTo(xs[count], zeroY)
        path.lineTo(xs[0], zeroY)
        path.close()
        fill.color = fieldColor
        fill.alpha = (fieldAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        canvas.drawPath(path, fill)
        fill.alpha = FULL_ALPHA

        // And the same outline again, open this time, so the line is drawn on top of its own field
        // and along neither the floor nor the two ends.
        stepContour(xs, ys, count)
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
    private fun stepContour(xs: FloatArray, ys: FloatArray, count: Int) {
        path.rewind()
        path.moveTo(xs[0], ys[0])
        for (index in 0 until count) {
            path.lineTo(xs[index + 1], ys[index])
            if (index + 1 < count) path.lineTo(xs[index + 1], ys[index + 1])
        }
    }

    /**
     * A history as one line through its points, with the field under it, crossing the zero.
     *
     * `docs/energy-display-contract.md` §2.3. [history]'s steps were chosen when a step was a
     * closed bucket and a step said so; the consumption chart's points are a trailing kilometre -
     * a continuous function of the road - and a line is what says that. The engine's box keeps its
     * steps, because its slots really are closed five-second buckets.
     *
     * **One silhouette, two colours, decided by the zero and not by the data.** [ys] is the real
     * height of every point, above the zero where the road cost and below it where it gave energy
     * back, so the shape crosses the zero wherever a kilometre gave back more than it took. It is
     * drawn twice under a clip - the half above the zero in [fieldColor] under [lineColor], the
     * half below in [returnColor] under [returnInkColor] - rather than split into two runs, because
     * a run is a statement about the data and this is a statement about the zero. Nothing is
     * stroked along the zero itself: the field closes there and the line does not.
     *
     * A run the caller hands in is a stretch with no holes in it ([ys] holds no `NaN`); the holes
     * are what break one history into several calls.
     *
     * [top] and [bottom] bound the box, so a line drawn along a ceiling keeps its whole stroke.
     * Nothing is allocated here: the path and the paints are the pen's own.
     */
    fun curve(
        canvas: Canvas,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        zeroY: Float,
        top: Float,
        bottom: Float,
        lineColor: Int,
        lineAlpha: Float,
        lineWidthV: Float,
        fieldColor: Int,
        fieldAlpha: Float,
        returnColor: Int,
        returnAlpha: Float,
        returnInkColor: Int,
    ) {
        if (count <= 0) return
        val width = v(lineWidthV)
        if (count == 1) {
            // One reading between two holes. A polyline of one point draws nothing at all, and a
            // kilometre the log does know should not vanish because its neighbours are missing.
            val y = ys[0]
            fill.color = if (y > zeroY) returnInkColor else lineColor
            fill.alpha = FULL_ALPHA
            canvas.drawCircle(xs[0], y, width / 2f, fill)
            return
        }
        val left = xs[0] - width
        val right = xs[count - 1] + width
        half(
            canvas, xs, ys, count, zeroY,
            left, top - width, right, zeroY,
            fieldColor, fieldAlpha, lineColor, lineAlpha, width,
        )
        half(
            canvas, xs, ys, count, zeroY,
            left, zeroY, right, bottom + width,
            returnColor, returnAlpha, returnInkColor, 1f, width,
        )
    }

    /** One side of the zero: the field closed down to it, and the same outline stroked over it. */
    private fun half(
        canvas: Canvas,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        zeroY: Float,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
        fieldColor: Int,
        fieldAlpha: Float,
        lineColor: Int,
        lineAlpha: Float,
        widthPx: Float,
    ) {
        if (clipBottom <= clipTop || clipRight <= clipLeft) return
        val save = canvas.save()
        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

        curveContour(xs, ys, count)
        path.lineTo(xs[count - 1], zeroY)
        path.lineTo(xs[0], zeroY)
        path.close()
        fill.color = fieldColor
        fill.alpha = (fieldAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        canvas.drawPath(path, fill)
        fill.alpha = FULL_ALPHA

        curveContour(xs, ys, count)
        stroke.color = lineColor
        stroke.alpha = (lineAlpha.coerceIn(0f, 1f) * FULL_ALPHA).toInt()
        stroke.strokeWidth = widthPx
        canvas.drawPath(path, stroke)
        canvas.restoreToCount(save)
    }

    /**
     * The polyline itself, into [path], left open at both ends.
     *
     * The field and the line are the same run of points drawn twice - once closed down to the zero
     * and filled, once open and stroked - and two copies of the walk would be two chances for a
     * history whose fill and whose line describe different data.
     */
    private fun curveContour(xs: FloatArray, ys: FloatArray, count: Int) {
        path.rewind()
        path.moveTo(xs[0], ys[0])
        for (index in 1 until count) path.lineTo(xs[index], ys[index])
    }

    /**
     * The marks over the stretches a ladder could not hold, so a cut is seen to be a cut.
     *
     * **One mark per run of clamped points, at the run's centre.** A mark per point was a mark per
     * closed bucket when a bucket was what got drawn; a line drawn along a ceiling for a kilometre
     * is one cut, and twenty ticks over it are a comb saying so twenty times. Three units standing
     * just outside the edge the run hit, on either ceiling.
     *
     * Here rather than in either renderer because both consumption charts have to draw the same
     * mark: they had two copies of the walk, and one of them had put the return's tick in the
     * spending's ink.
     *
     * @param xs the points, in pixels - one per value, not an edge
     * @param aboveY where a mark over the ceiling starts, gap already taken
     * @param belowY and where one under the floor does
     */
    fun clampTicks(
        canvas: Canvas,
        values: FloatArray,
        first: Int,
        count: Int,
        xs: FloatArray,
        aboveY: Float,
        belowY: Float,
        tickV: Float,
        widthV: Float,
        ceiling: Float,
        returnCeiling: Float,
        aboveColor: Int,
        belowColor: Int,
    ) {
        val tick = v(tickV)
        runTicks(canvas, values, first, count, xs, aboveY, -tick, widthV, aboveColor) {
            it >= ceiling
        }
        runTicks(canvas, values, first, count, xs, belowY, tick, widthV, belowColor) {
            it <= -returnCeiling
        }
    }

    /** One tick per maximal run of points [clamped] accepts, centred on the run. */
    private inline fun runTicks(
        canvas: Canvas,
        values: FloatArray,
        first: Int,
        count: Int,
        xs: FloatArray,
        fromY: Float,
        reach: Float,
        widthV: Float,
        color: Int,
        clamped: (Float) -> Boolean,
    ) {
        ClampMarks.forEach(values, first, count, clamped) { start, length ->
            val centre = ClampMarks.centre(xs, start, length)
            line(canvas, centre, fromY, centre, fromY + reach, color, widthV)
        }
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

/**
 * Where a cut is marked: the runs of clamped points, and the middle of each.
 *
 * Out here rather than private to [InstrumentPen] for the reason `ContourRuns` is out of its
 * renderer - a `Canvas` call is unverifiable by construction in this module, and "a cut is marked
 * once, in the middle of the stretch that was cut" is a statement about runs rather than pixels.
 *
 * **A hole ends a run.** A stretch held at the ceiling, a kilometre nobody knows, and then another
 * stretch at the ceiling are two cuts, and the reader is owed two marks.
 */
internal object ClampMarks {

    /** Calls [block] once per maximal run of clamped points, with its first index and length. */
    inline fun forEach(
        values: FloatArray,
        first: Int,
        count: Int,
        clamped: (Float) -> Boolean,
        block: (start: Int, length: Int) -> Unit,
    ) {
        var index = 0
        while (index < count) {
            val value = values[first + index]
            if (value.isNaN() || !clamped(value)) {
                index++
                continue
            }
            val start = index
            while (index < count) {
                val next = values[first + index]
                if (next.isNaN() || !clamped(next)) break
                index++
            }
            block(start, index - start)
        }
    }

    /** The same walk, collected - for a test, and for nothing that draws. */
    fun of(values: FloatArray, first: Int, count: Int, clamped: (Float) -> Boolean): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        forEach(values, first, count, clamped) { start, length -> out += start to length }
        return out
    }

    /**
     * And where the mark stands: the middle of the run, not its first point.
     *
     * A run of one point is that point, which is what makes the arithmetic one expression rather
     * than a case.
     */
    fun centre(xs: FloatArray, start: Int, length: Int): Float =
        (xs[start] + xs[start + length - 1]) / 2f
}
