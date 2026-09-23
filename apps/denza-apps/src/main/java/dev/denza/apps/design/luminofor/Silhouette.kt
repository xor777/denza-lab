package dev.denza.apps.design.luminofor

import android.graphics.BlendMode
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import kotlin.math.min

/**
 * The ten-kilometre chart as the energy contract draws it (§2.3), on both screens: `silhouette()`
 * in `luminofor.js`, line for line.
 *
 * One line through the points - the newest on the right edge, a full window edge to edge and a
 * filling one growing leftward at the same pitch - and the field between the line and zero. The
 * field and the line are drawn twice, clipped once above the zero in the [upLight] and once below
 * it in the [downLight], so the silhouette changes colour exactly where it crosses the zero and
 * nothing is stroked along the zero itself. A run of points past a ceiling lies along the ceiling,
 * with one [tick] standing just outside the box at the run's centre.
 *
 * The line is lit in runs: the window is cut into [runLevels]`.size` equal runs of x and each is
 * drawn at its own level - the beam's persistence on the cluster, one level on the car page. One
 * stroke under a stepped gradient along x, not a path per run, whose round caps would meet and add
 * up into a bright bead at every seam.
 *
 * The fields are horizontal gradients from the oldest point to the newest, of the lights' halos:
 * [upFill] and [downFill] are each an old and a new alpha, equal for a flat field.
 *
 * Nothing is allocated per frame but the gradients, and they only when the window moves on the
 * screen. After [draw], [endX], [endY] and [endValue] say where the newest point is, for the
 * caller's own dot.
 */
class Silhouette(
    private val upLight: Light,
    private val downLight: Light,
    private val stroke: Float,
    private val tick: Float,
    private val runLevels: FloatArray,
    private val upFill: FloatArray,
    private val downFill: FloatArray,
) {

    private val line = Path()
    private val field = Path()
    private val ticksUp = Path()
    private val ticksDown = Path()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
    }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        blendMode = BlendMode.PLUS
    }

    private var laidFrom = Float.NaN
    private var laidTo = Float.NaN
    private var fillUpShader: Shader? = null
    private var fillDownShader: Shader? = null
    private var strokeUpShader: Shader? = null
    private var strokeDownShader: Shader? = null

    /** Where the newest point was drawn, in units, and its value. */
    var endX: Float = 0f
        private set
    var endY: Float = 0f
        private set
    var endValue: Float = 0f
        private set

    /**
     * [count] points of [points] from [first], the newest standing on [right], one [pitch] apart,
     * zero at [zero] and the ceilings [upTo] at [top] and [downTo] at [bottom]. Units throughout;
     * false, and nothing drawn, when there is no point.
     */
    fun draw(
        pen: LightPen,
        points: FloatArray,
        first: Int,
        count: Int,
        right: Float,
        pitch: Float,
        zero: Float,
        top: Float,
        bottom: Float,
        upTo: Float,
        downTo: Float,
    ): Boolean {
        if (count <= 0) return false
        this.count = count
        this.right = right
        this.pitch = pitch
        this.zero = zero
        this.top = top
        this.bottom = bottom
        this.upTo = upTo
        this.downTo = downTo

        val x0 = xOf(0)
        val x1 = xOf(count - 1)
        endX = x1
        endValue = points[first + count - 1]
        endY = yOf(endValue)
        if (count < 2) return true

        line.rewind()
        field.rewind()
        for (i in 0 until count) {
            val x = pen.x(xOf(i))
            val y = pen.y(yOf(points[first + i]))
            if (i == 0) {
                line.moveTo(x, y)
                field.moveTo(x, y)
            } else {
                line.lineTo(x, y)
                field.lineTo(x, y)
            }
        }
        field.lineTo(pen.x(x1), pen.y(zero))
        field.lineTo(pen.x(x0), pen.y(zero))
        field.close()
        shaders(pen.x(x0), pen.x(x1))

        val canvas = pen.canvas
        val left = pen.x(x0 - CLIP_MARGIN)
        val rightPx = pen.x(x1 + CLIP_MARGIN)
        beam.strokeWidth = pen.px(stroke)
        beam.color = OPAQUE_WHITE
        fill.color = OPAQUE_WHITE

        var save = canvas.save()
        canvas.clipRect(left, pen.y(top - CLIP_MARGIN), rightPx, pen.y(zero))
        fill.shader = fillUpShader
        canvas.drawPath(field, fill)
        beam.shader = strokeUpShader
        canvas.drawPath(line, beam)
        canvas.restoreToCount(save)

        save = canvas.save()
        canvas.clipRect(left, pen.y(zero), rightPx, pen.y(bottom + CLIP_MARGIN))
        fill.shader = fillDownShader
        canvas.drawPath(field, fill)
        beam.shader = strokeDownShader
        canvas.drawPath(line, beam)
        canvas.restoreToCount(save)
        fill.shader = null
        beam.shader = null

        // One tick per run cut at a ceiling, at the run's centre, just outside the box.
        ticksUp.rewind()
        ticksDown.rewind()
        var i = 0
        while (i < count) {
            val v = points[first + i]
            val over = v > upTo
            val under = v < -downTo
            if (!over && !under) {
                i++
                continue
            }
            var j = i
            while (j + 1 < count && if (over) points[first + j + 1] > upTo else points[first + j + 1] < -downTo) j++
            val xm = pen.x((xOf(i) + xOf(j)) / 2f)
            if (over) {
                ticksUp.moveTo(xm, pen.y(top - 1f))
                ticksUp.lineTo(xm, pen.y(top - 1f - tick))
            } else {
                ticksDown.moveTo(xm, pen.y(bottom + 1f))
                ticksDown.lineTo(xm, pen.y(bottom + 1f + tick))
            }
            i = j + 1
        }
        if (!ticksUp.isEmpty) {
            beam.color = upLight.core
            canvas.drawPath(ticksUp, beam)
        }
        if (!ticksDown.isEmpty) {
            beam.color = downLight.core
            canvas.drawPath(ticksDown, beam)
        }
        return true
    }

    // The window being drawn, held in fields so the two below are plain methods.
    private var count = 0
    private var right = 0f
    private var pitch = 0f
    private var zero = 0f
    private var top = 0f
    private var bottom = 0f
    private var upTo = 1f
    private var downTo = 1f

    private fun xOf(i: Int): Float = x(i, count, right, pitch)

    private fun yOf(v: Float): Float = y(v, zero, top, bottom, upTo, downTo)

    private fun shaders(from: Float, to: Float) {
        if (from == laidFrom && to == laidTo) return
        laidFrom = from
        laidTo = to
        fillUpShader = gradient(from, to, upLight.halo, upFill)
        fillDownShader = gradient(from, to, downLight.halo, downFill)
        strokeUpShader = lit(from, to, upLight.core)
        strokeDownShader = lit(from, to, downLight.core)
    }

    /** A field's gradient: [alphas] are its oldest and newest ends. */
    private fun gradient(from: Float, to: Float, color: Int, alphas: FloatArray): Shader =
        LinearGradient(
            from, 0f, to, 0f,
            LightPen.alpha(color, alphas[0]), LightPen.alpha(color, alphas[1]),
            Shader.TileMode.CLAMP,
        )

    /** The line's stepped gradient: each run a flat level from its first x to its last. */
    private fun lit(from: Float, to: Float, color: Int): Shader {
        val runs = runLevels.size
        val colors = IntArray(runs * 2) { LightPen.alpha(color, runLevels[it / 2]) }
        val stops = FloatArray(runs * 2) { (it / 2 + it % 2).toFloat() / runs }
        return LinearGradient(from, 0f, to, 0f, colors, stops, Shader.TileMode.CLAMP)
    }

    companion object {
        /** Where point [i] of [count] stands: the newest on [right], the older ones a [pitch] apart leftward. */
        fun x(i: Int, count: Int, right: Float, pitch: Float): Float = right - (count - 1 - i) * pitch

        /**
         * A value on the contract's linear ladder: zero at [zero], [upTo] at [top] and −[downTo] at
         * [bottom], the same slope either side when the box is cut that way, clamped at both.
         */
        fun y(v: Float, zero: Float, top: Float, bottom: Float, upTo: Float, downTo: Float): Float =
            if (v >= 0f) zero - (zero - top) * min(1f, v / upTo) else zero + (bottom - zero) * min(1f, -v / downTo)

        /** The clip rects reach this far past the window on three sides, as the board's do. */
        private const val CLIP_MARGIN = 20f

        /** A shader's colour is multiplied by the paint's alpha, so the paint stays opaque. */
        private const val OPAQUE_WHITE = -0x1
    }
}
