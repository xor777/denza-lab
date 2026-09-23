package dev.denza.apps.design.luminofor

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.denza.apps.R
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The board's drawing verbs on an Android [Canvas], in the board's own units.
 *
 * `luminofor.js` draws with four verbs - a beam (a stroke, optionally with a halo), a glowing
 * fill, a line of text and the wide figures - and composites every one of them additively
 * (`globalCompositeOperation = 'lighter'`). This is the same four verbs, so a renderer ported from
 * the board reads line for line like the board, and the same additive compositing
 * ([BlendMode.PLUS], which is `lighter`). On black the two are ordinary alpha; over a card, a haze
 * or another stroke they are not, and the approved design is the additive one.
 *
 * Coordinates are board units. [begin] says how many pixels a unit is and where the unit origin
 * sits, so a renderer can use the spec's window-dp positions directly: the strip passes its box's
 * top-left as the origin, the cluster passes zero.
 *
 * Blur. The board's `shadowBlur` is in device pixels and, like every browser, Chrome turns it into
 * a Gaussian of sigma = blur / 2. Android's [BlurMaskFilter] radius becomes sigma = 0.57735 r + 0.5
 * in Skia, so [blurRadius] inverts that; a blur too small to reach half a pixel is drawn unblurred,
 * which is what the board's near-zero blurs look like.
 *
 * Tracking. Chrome adds `letterSpacing` after every glyph and the board subtracts the trailing one
 * from the width; Android spreads the same spacing half before and half after each glyph. The
 * width is the same once the trailing spacing is removed; the start is not, and [text] moves the
 * run back by half a spacing so the first glyph lands where the board puts it.
 */
class LightPen(
    private val jura: Typeface,
    private val sans: Typeface,
    private val sansStrong: Typeface,
) {

    enum class Face { JURA, SANS, SANS_STRONG }

    enum class Align { LEFT, CENTER, RIGHT }

    private var target: Canvas? = null
    private val unitMatrix = Matrix()
    private val scratch = Path()
    private val glyphMatrix = Matrix()

    /** Pixels per unit. */
    var scale: Float = 1f
        private set
    private var originX = 0f
    private var originY = 0f

    val canvas: Canvas get() = requireNotNull(target) { "LightPen.begin was not called" }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        blendMode = BlendMode.PLUS
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
    }
    private val type = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
    }
    private val blurs = HashMap<Int, BlurMaskFilter>()

    fun begin(canvas: Canvas, pxPerUnit: Float, originX: Float = 0f, originY: Float = 0f) {
        target = canvas
        scale = pxPerUnit
        this.originX = originX
        this.originY = originY
        unitMatrix.setTranslate(-originX, -originY)
        unitMatrix.postScale(pxPerUnit, pxPerUnit)
    }

    fun x(u: Float): Float = (u - originX) * scale
    fun y(u: Float): Float = (u - originY) * scale
    fun px(u: Float): Float = u * scale

    /** A path built in units, in pixels. The result is reused: draw it before asking again. */
    fun toPx(path: Path): Path {
        path.transform(unitMatrix, scratch)
        return scratch
    }

    /** A fresh additive paint for a renderer's own gradients and rects. */
    fun plusFill(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
    }

    fun plusStroke(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        blendMode = BlendMode.PLUS
    }

    /** A stroke drawn by a beam: with [glow] above zero a faint wide halo and a blurred one first. */
    fun beam(path: Path, strokeUnits: Float, light: Light, intensity: Float, glow: Float = 0f) {
        if (intensity <= 0.01f) return
        beamPx(toPx(path), px(strokeUnits), light, intensity, glow)
    }

    private fun beamPx(p: Path, sw: Float, light: Light, intensity: Float, glow: Float) {
        val c = canvas
        stroke.maskFilter = null
        if (glow > 0f) {
            stroke.strokeWidth = sw * 4.5f
            stroke.color = alpha(light.halo, 0.03f * intensity * glow)
            c.drawPath(p, stroke)
            stroke.strokeWidth = sw * 1.8f
            shadow(p, stroke, light.halo, 0.7f * intensity * glow, min(30f, sw * 2.8f))
            stroke.color = alpha(light.halo, 0.14f * intensity * glow)
            c.drawPath(p, stroke)
        }
        stroke.strokeWidth = sw
        stroke.color = alpha(light.core, intensity)
        c.drawPath(p, stroke)
    }

    /** An emissive fill: its blurred halo, the halo itself, then the core. */
    fun glowFill(path: Path, light: Light, intensity: Float, blurUnits: Float) {
        if (intensity <= 0.01f) return
        val p = toPx(path)
        fill.maskFilter = null
        shadow(p, fill, light.halo, 0.8f * intensity, min(40f, blurUnits * scale))
        fill.color = alpha(light.halo, 0.55f * intensity)
        canvas.drawPath(p, fill)
        fill.color = alpha(light.core, 0.85f * intensity)
        canvas.drawPath(p, fill)
    }

    /** The board's `shadowColor` + `shadowBlur`: the same shape, blurred, under the real one. */
    private fun shadow(p: Path, paint: Paint, color: Int, a: Float, blurPx: Float) {
        val radius = blurRadius(blurPx)
        paint.color = alpha(color, a)
        paint.maskFilter = if (radius > 0f) blur(radius) else null
        canvas.drawPath(p, paint)
        paint.maskFilter = null
    }

    private fun blur(radius: Float): BlurMaskFilter {
        val key = (radius * 16f).roundToInt()
        return blurs.getOrPut(key) { BlurMaskFilter(key / 16f, BlurMaskFilter.Blur.NORMAL) }
    }

    /** Text on a baseline. Returns the width in units, without the trailing tracking. */
    fun text(
        str: String,
        x: Float,
        baseline: Float,
        size: Float,
        light: Light,
        intensity: Float,
        face: Face = Face.JURA,
        track: Float = 0f,
        align: Align = Align.LEFT,
    ): Float {
        if (intensity <= 0.01f || str.isEmpty()) return 0f
        setType(size, face, track)
        val spacing = track * size * scale
        val w = type.measureText(str) - spacing
        val left = when (align) {
            Align.LEFT -> x(x)
            Align.CENTER -> x(x) - w / 2f
            Align.RIGHT -> x(x) - w
        }
        type.color = alpha(light.core, intensity)
        canvas.drawText(str, left - spacing / 2f, y(baseline), type)
        return w / scale
    }

    fun textWidth(str: String, size: Float, face: Face = Face.JURA, track: Float = 0f): Float {
        if (str.isEmpty()) return 0f
        setType(size, face, track)
        return (type.measureText(str) - track * size * scale) / scale
    }

    private fun setType(size: Float, face: Face, track: Float) {
        type.typeface = when (face) {
            Face.JURA -> jura
            Face.SANS -> sans
            Face.SANS_STRONG -> sansStrong
        }
        type.textSize = size * scale
        type.letterSpacing = track
    }

    /** The wide figures; returns their width in units. */
    fun figures(
        str: String,
        x: Float,
        baseline: Float,
        size: Float,
        light: Light,
        intensity: Float,
        align: Align = Align.LEFT,
        strokeUnits: Float = WideDigits.stroke(size),
    ): Float {
        val k = WideDigits.k(size)
        val w = WideDigits.width(str, size)
        var cx = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2f
            Align.RIGHT -> x - w
        }
        val sw = px(strokeUnits)
        for (ch in str) {
            val glyph = LuminoforSpec.Digits.GLYPHS[ch] ?: continue
            val path = WideDigits.path(ch)
            if (path != null) {
                glyphMatrix.setScale(k, k)
                glyphMatrix.postTranslate(cx, baseline - LuminoforSpec.Digits.CAP * k)
                glyphMatrix.postConcat(unitMatrix)
                path.transform(glyphMatrix, scratch)
                if (intensity > 0.01f) beamPx(scratch, sw, light, intensity, 0f)
            }
            cx += (glyph.first + LuminoforSpec.Digits.TRACK) * k
        }
        return w
    }

    companion object {
        /** Chrome's shadowBlur (sigma = blur / 2) as an Android blur radius. */
        fun blurRadius(shadowBlurPx: Float): Float {
            val sigma = shadowBlurPx / 2f
            return if (sigma <= 0.5f) 0f else (sigma - 0.5f) / 0.57735f
        }

        fun alpha(color: Int, a: Float): Int {
            val base = (color ushr 24) / 255f
            val v = (base * a.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (v shl 24)
        }

        fun create(context: Context): LightPen {
            val jura = ResourcesCompat.getFont(context, R.font.jura_medium) ?: Typeface.DEFAULT
            val sans = Typeface.create(Typeface.SANS_SERIF, LuminoforSpec.Type.HEAD_WEIGHT, false)
            val strong = Typeface.create(Typeface.SANS_SERIF, LuminoforSpec.Type.HEAD_STRONG, false)
            return LightPen(jura, sans, strong)
        }
    }
}
