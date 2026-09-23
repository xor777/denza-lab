package dev.denza.apps.feature.split

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.AnimationUtils
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CX
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CY
import dev.denza.apps.feature.split.SplitCrewScene.Companion.H
import dev.denza.apps.feature.split.SplitCrewScene.Companion.W
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The split shield's wait on the screen: [SplitCrewScene] drawn on an Android canvas, one frame
 * per vsync while the shield is up.
 *
 * The scene is in the page's units, 1280 x 800; the view scales them by the smaller of its two
 * ratios and centres them, so on the car (2560 x 1600) a unit is exactly two pixels and nothing is
 * left over. The whole view is black first: the shield is opaque wherever it is, not only inside
 * the picture (contract 1.13.2).
 *
 * The page composites `lighter` and so does this - every line, the haze and the crown's sparks are
 * [BlendMode.PLUS], as in `LightPen` and `SpectrumRenderer`. The caption is the exception, as on
 * the page: it is laid on, white at 0.9. The field every line is stroked with is one radial
 * gradient of four opaque stops, so Chrome's unpremultiplied and Skia's premultiplied
 * interpolation agree; the haze varies only in alpha over one colour, so they agree there too.
 * Both shaders are in scene units under the canvas's own scale, built once.
 *
 * The clock is the shield's: [SplitCrewScene.drawShield] takes the milliseconds since this view
 * last became visible. The overlay re-shows an attached window by making it visible again, and that
 * restarts the scene from its reveal; a hidden or detached view schedules no frames. With animations
 * off in the system settings the view draws one still frame, [SplitCrewScene.STILL_T], and stops.
 * The debug fixture pins any moment with [pin].
 *
 * The view is the shield's accessibility node, the one the card used to be: its description is
 * the caption.
 */
internal class SplitCrewView(context: Context, caption: CharSequence) : View(context) {

    private val pen = CanvasPen(Typeface.create("sans-serif", Typeface.NORMAL))
    private val scene = SplitCrewScene(pen, caption.toString())

    private var pinnedMs = Double.NaN
    private var startMs = NOT_STARTED
    private var running = false

    init {
        contentDescription = caption
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Draws the moment [ms] and nothing else: no clock, no frames after it. For the debug fixture. */
    fun pin(ms: Long) {
        pinnedMs = ms.toDouble()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (isVisible) {
            startMs = NOT_STARTED
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val live = pinnedMs.isNaN() && ValueAnimator.areAnimatorsEnabled()
        val t = when {
            !pinnedMs.isNaN() -> pinnedMs
            !live -> SplitCrewScene.STILL_T
            else -> {
                val now = AnimationUtils.currentAnimationTimeMillis()
                if (startMs == NOT_STARTED) startMs = now
                (now - startMs).toDouble()
            }
        }
        canvas.drawColor(Color.BLACK)
        val scale = min(width / W, height / H).toFloat()
        canvas.save()
        canvas.translate((width - W.toFloat() * scale) / 2f, (height - H.toFloat() * scale) / 2f)
        canvas.scale(scale, scale)
        // The page's canvas is the screen and nothing outside it: the haze reaches past its sides.
        canvas.clipRect(0f, 0f, W.toFloat(), H.toFloat())
        pen.canvas = canvas
        scene.drawShield(t)
        canvas.restore()
        if (live && running) postInvalidateOnAnimation()
    }

    /** The page's canvas verbs on an Android [Canvas], in scene units; nothing is allocated per frame. */
    private class CanvasPen(sans: Typeface) : SplitCrewScene.Pen {
        lateinit var canvas: Canvas

        private val path = Path()
        private val clip = Path()
        private val oval = RectF()

        /** Whether the path has a current point: the canvas's `arc` joins it, or starts a subpath. */
        private var open = false
        private var lastX = 0f
        private var lastY = 0f

        private val field = strokePaint().apply {
            shader = RadialGradient(
                CX.toFloat(), CY.toFloat(), SplitCrewScene.HALF_DIAG.toFloat(),
                SplitCrewScene.FIELD_COLORS,
                FloatArray(SplitCrewScene.FIELD_STOPS.size) { SplitCrewScene.FIELD_STOPS[it].toFloat() },
                Shader.TileMode.CLAMP,
            )
        }
        private val crown = strokePaint().apply { color = rgb(SplitCrewScene.CROWN) }

        /**
         * The page fills the haze's square under `translate(W / 2, H / 2)` and `scale(1, 0.64)`;
         * the same gradient with that squash as its local matrix, filled over the squashed square,
         * is the same ellipse. Past the radius the gradient is its last stop, which is nothing.
         */
        private val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            blendMode = BlendMode.PLUS
            val halo = SplitCrewScene.HALO
            shader = RadialGradient(
                CX.toFloat(), CY.toFloat(), SplitCrewScene.HAZE_RADIUS.toFloat(),
                IntArray(SplitCrewScene.HAZE_ALPHAS.size) {
                    Color.argb(alpha255(SplitCrewScene.HAZE_ALPHAS[it]), halo[0], halo[1], halo[2])
                },
                FloatArray(SplitCrewScene.HAZE_STOPS.size) { SplitCrewScene.HAZE_STOPS[it].toFloat() },
                Shader.TileMode.CLAMP,
            ).apply {
                val squash = SplitCrewScene.HAZE_SQUASH.toFloat()
                setLocalMatrix(Matrix().apply { setScale(1f, squash, CX.toFloat(), CY.toFloat()) })
            }
        }
        private val hazeRect = RectF(
            (CX - SplitCrewScene.HAZE_RADIUS).toFloat(),
            (CY - SplitCrewScene.HAZE_RADIUS * SplitCrewScene.HAZE_SQUASH).toFloat(),
            (CX + SplitCrewScene.HAZE_RADIUS).toFloat(),
            (CY + SplitCrewScene.HAZE_RADIUS * SplitCrewScene.HAZE_SQUASH).toFloat(),
        )

        // Linear text, as LightPen draws it: advances at the size asked for, not hinted to whole
        // pixels, which is how Chrome lays a canvas's text out.
        private val type = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = sans
        }

        override fun ground() {
            canvas.drawColor(Color.BLACK, BlendMode.SRC)
        }

        override fun haze(alpha: Double) {
            val a = alpha255(alpha)
            if (a == 0) return
            haze.alpha = a
            canvas.drawRect(hazeRect, haze)
        }

        override fun beginPath() {
            path.rewind()
            open = false
        }

        override fun moveTo(x: Double, y: Double) {
            lastX = x.toFloat()
            lastY = y.toFloat()
            path.moveTo(lastX, lastY)
            open = true
        }

        /**
         * A line to where the path already is adds nothing, as the canvas's `lineTo` does: Chrome
         * never records it, so a subpath of nothing else is not drawn. Skia would draw it as a dot
         * under a round cap - which is what a spark of age zero, at the very moment of a blow, came
         * out as: eleven crown dots added on the hammer's head, burnt white.
         */
        override fun lineTo(x: Double, y: Double) {
            if (!open) return moveTo(x, y)
            val px = x.toFloat()
            val py = y.toFloat()
            if (px == lastX && py == lastY) return
            path.lineTo(px, py)
            lastX = px
            lastY = py
        }

        override fun rect(x: Double, y: Double, w: Double, h: Double) {
            path.addRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), Path.Direction.CW)
            // The canvas starts a new subpath at the rectangle's corner.
            lastX = x.toFloat()
            lastY = y.toFloat()
            open = true
        }

        /**
         * The canvas's `arc`, clockwise. A whole turn is drawn as two halves, as Chrome draws it:
         * Skia's arcTo takes its sweep modulo 360 degrees, so one call of 360 draws nothing.
         */
        override fun arc(cx: Double, cy: Double, r: Double, start: Double, end: Double) {
            oval.set((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat())
            val from = Math.toDegrees(start).toFloat()
            var sweep = end - start
            if (sweep >= 2 * PI) {
                path.arcTo(oval, from, 180f, !open)
                path.arcTo(oval, from + 180f, 180f, false)
            } else {
                sweep %= 2 * PI
                if (sweep < 0) sweep += 2 * PI
                path.arcTo(oval, from, Math.toDegrees(sweep).toFloat(), !open)
            }
            lastX = (cx + r * cos(start + sweep)).toFloat()
            lastY = (cy + r * sin(start + sweep)).toFloat()
            open = true
        }

        override fun stroke(alpha: Double, width: Double, ink: SplitCrewScene.Ink) {
            val a = alpha255(alpha)
            if (a == 0) return
            val paint = if (ink == SplitCrewScene.Ink.FIELD) field else crown
            paint.strokeWidth = width.toFloat()
            paint.alpha = a
            canvas.drawPath(path, paint)
        }

        override fun save() {
            canvas.save()
        }

        override fun clipCircle(cx: Double, cy: Double, r: Double) {
            clip.rewind()
            clip.addCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), Path.Direction.CW)
            canvas.clipPath(clip)
        }

        override fun restore() {
            canvas.restore()
        }

        /**
         * Chrome puts a `middle` baseline at the middle of the em box, normalised from the font's
         * typographic ascent and descent. Roboto's are 1536 and 512 of 2048 - three quarters of an
         * em above the baseline and one below - so the middle is a quarter em above the baseline,
         * and the baseline a quarter em below [y].
         */
        override fun caption(text: String, x: Double, y: Double, size: Double, alpha: Double) {
            val a = alpha255(alpha)
            if (a == 0 || text.isEmpty()) return
            type.textSize = size.toFloat()
            type.alpha = a
            canvas.drawText(text, x.toFloat(), (y + size * EM_MIDDLE).toFloat(), type)
        }

        private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            blendMode = BlendMode.PLUS
        }

        private fun rgb(c: IntArray) = Color.rgb(c[0], c[1], c[2])

        private fun alpha255(a: Double) = (a * 255).roundToInt().coerceIn(0, 255)
    }

    private companion object {
        const val NOT_STARTED = Long.MIN_VALUE

        /** The em box's middle above the alphabetic baseline, in ems (Roboto's typographic metrics). */
        const val EM_MIDDLE = 0.25
    }
}
