package dev.denza.apps.feature.trip

import android.graphics.BlendMode
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The analyser, drawn the way the car draws its charging bars: fine vertical lines, one colour
 * field, a haze.
 *
 * `luminofor.js`, `spectrumField()`, line for line. Each column is a few two-dp lines rather than a
 * block, under one gradient that runs from the halo's blue at a third of its strength on the floor
 * to the core's blue at full strength at the top of the field - so colour means height across the
 * whole analyser, and a short column is drawn from the dim end. Behind each column a faint rect of
 * glow three dp proud of it; above it a crown, two dp of pale blue hung six dp over where the band
 * last peaked. Behind all of it one elliptical haze. Everything is added (BlendMode.PLUS), exactly
 * as the board composites `lighter`.
 *
 * **No blur anywhere.** The board gets its glow from plain rects and one radial gradient, and so
 * does this: a mask filter per column per frame is what the old analyser's bloom cost, and the new
 * one does not need it. Everything geometry-dependent - the field's gradient and the haze - is
 * built when the field moves, not per frame.
 *
 * What went with the old analyser: the dot-matrix ticker, the scanlines, the reflection, the bloom
 * and the idle ripple. A silent car shows the board's floor - four-dp stubs under their crowns.
 */
class SpectrumRenderer {

    private val haze = plus()
    private val glow = plus().apply { color = LightPen.alpha(HeadInk.BLUE.halo, Head.Spectrum.GLOW_ALPHA) }
    private val lines = plus()
    private val crown = plus().apply { color = HeadInk.CROWN }
    private val hazeMatrix = Matrix()

    private var laidX0 = Float.NaN
    private var laidTop = Float.NaN
    private var laidW = Float.NaN
    private var laidFloor = Float.NaN
    private var hazeLeft = 0f
    private var hazeTop = 0f
    private var hazeRight = 0f
    private var hazeBottom = 0f

    /**
     * The field, [n] columns across [w] from [x0], standing on [floor] with [top] as the ceiling.
     *
     * [levels] and [crowns] may carry more bands than there are columns: a pane samples them with
     * the board's own index map, `si = round(i × (NN − 1) / (n − 1))`, so the first and the last
     * column are always the first and the last band and the ones between are spread evenly.
     */
    fun draw(
        pen: LightPen,
        x0: Float,
        top: Float,
        w: Float,
        floor: Float,
        n: Int,
        levels: FloatArray,
        crowns: FloatArray,
    ) {
        if (n <= 0 || levels.isEmpty()) return
        val sp = Head.Spectrum
        val bw = sp.BAR_WIDTH
        val gap = if (n > 1) (w - n * bw) / (n - 1) else 0f
        val fh = floor - top - sp.HEADROOM
        prepare(pen, x0, top, w, floor)
        val canvas = pen.canvas

        canvas.drawRect(hazeLeft, hazeTop, hazeRight, hazeBottom, haze)

        val count = max(2, (bw / sp.LINE_PITCH).roundToInt())
        val lw = sp.LINE_WIDTH
        val lgap = (bw - count * lw) / (count - 1)
        val bands = levels.size
        val floorPx = pen.y(floor)
        for (i in 0 until n) {
            val si = sample(i, n, bands)
            val x = x0 + i * (bw + gap)
            val h = max(MIN_HEIGHT, levels[si] * fh)
            canvas.drawRect(
                pen.x(x - sp.GLOW_PAD), pen.y(floor - h - sp.GLOW_PAD),
                pen.x(x + bw + sp.GLOW_PAD), floorPx,
                glow,
            )
            val lineTop = pen.y(floor - h)
            for (k in 0 until count) {
                val lx = x + k * (lw + lgap)
                canvas.drawRect(pen.x(lx), lineTop, pen.x(lx + lw), floorPx, lines)
            }
            val peak = if (si < crowns.size) crowns[si] else 0f
            val cy = floor - min(1f, peak) * fh - sp.CROWN_LIFT
            canvas.drawRect(pen.x(x), pen.y(cy), pen.x(x + bw), pen.y(cy + sp.CROWN_HEIGHT), crown)
        }
    }

    /** The field's gradient and its haze, rebuilt when the field moves on the screen. */
    private fun prepare(pen: LightPen, x0: Float, top: Float, w: Float, floor: Float) {
        val px0 = pen.x(x0)
        val pTop = pen.y(top)
        val pW = pen.px(w)
        val pFloor = pen.y(floor)
        if (px0 == laidX0 && pTop == laidTop && pW == laidW && pFloor == laidFloor) return
        laidX0 = px0
        laidTop = pTop
        laidW = pW
        laidFloor = pFloor

        val sp = Head.Spectrum
        val blue = HeadInk.BLUE
        lines.shader = LinearGradient(
            0f, pFloor, 0f, pTop,
            LightPen.alpha(blue.halo, sp.GRADIENT_FOOT),
            LightPen.alpha(blue.core, sp.GRADIENT_TOP),
            Shader.TileMode.CLAMP,
        )

        // The board's haze: a radial gradient of radius rx squashed vertically to ry, centred at
        // the field's own 62 per cent, filled over its square - which, squashed, is the ellipse's
        // bounding box. Past the radius the gradient is its last stop, which is nothing.
        val field = floor - top
        val rx = pen.px(w * sp.HAZE_RX)
        val ry = pen.px(field * sp.HAZE_RY)
        val cx = pen.x(x0 + w / 2f)
        val cy = pen.y(top + field * sp.HAZE_CY)
        val stops = sp.HAZE_STOPS
        val shader = RadialGradient(
            cx, cy, rx,
            IntArray(stops.size) { LightPen.alpha(blue.halo, stops[it].second) },
            FloatArray(stops.size) { stops[it].first },
            Shader.TileMode.CLAMP,
        )
        hazeMatrix.setScale(1f, ry / rx, cx, cy)
        shader.setLocalMatrix(hazeMatrix)
        haze.shader = shader
        hazeLeft = cx - rx
        hazeRight = cx + rx
        hazeTop = cy - ry
        hazeBottom = cy + ry
    }

    internal companion object {
        /** The least a column is drawn at, silent or not: the board's `Math.max(4, …)`. */
        const val MIN_HEIGHT = 4f

        /**
         * Which band a column shows: `Math.round(i × (NN − 1) / max(1, n − 1))`, in doubles and
         * rounding a half up, as JavaScript does it.
         */
        fun sample(i: Int, n: Int, bands: Int): Int {
            if (n <= 1) return 0
            val v = i.toDouble() * (bands - 1) / (n - 1)
            return floor(v + 0.5).toInt().coerceIn(0, bands - 1)
        }

        private fun plus() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            blendMode = BlendMode.PLUS
        }
    }
}
