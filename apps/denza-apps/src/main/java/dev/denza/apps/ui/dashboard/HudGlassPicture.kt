package dev.denza.apps.ui.dashboard

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet.Glass as G

/**
 * The HUD panel's picture of the projection during a route: the board's `glass` block
 * (`luminofor.js`, `spec.json` → `sheet.glass`), drawn the way the board draws it - black like the
 * glass at night, BYD's own layout scaled to the panel's width, each part named by a thin blue line
 * above or below, a part that is off an empty dashed outline. The car's own speed is always there.
 *
 * Drawn on the platform canvas in dp, operation by operation as the board's 2D canvas does it, so
 * `luminofor/compare.py` can lay one over the other.
 */
@Composable
internal fun HudGlassPicture(glass: HudGlass, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth.value
        val height = 2 * G.Label.ROOM + G.VIEW[3] * width / G.VIEW[2]
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(density, density)
                GlassDrawing(native).draw(glass, 0f, 0f, width)
                native.restore()
            }
        }
    }
}

/** One picture: the board's `glass(c, b, x, y, w)`, in dp. */
private class GlassDrawing(private val c: Canvas) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val type = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    private class Part(val text: String, val at: FloatArray, val up: Boolean, val on: Boolean)

    fun draw(glass: HudGlass, x: Float, y: Float, w: Float) {
        val v = G.VIEW
        val k = w / v[2]
        val h = v[3] * k
        val gy = y + G.Label.ROOM
        fun r(rect: FloatArray) = floatArrayOf(x + (rect[0] - v[0]) * k, gy + (rect[1] - v[1]) * k, rect[2] * k, rect[3] * k)
        roundRect(x, gy, w, h, G.RADIUS, BLACK)

        val sp = r(G.SPEED)
        val un = r(G.UNIT)
        words("64", sp[0] + sp[2] / 2, sp[1] + sp[3] * 0.84f, sp[3] * 0.78f, 1f, 500, WHITE, center = true)
        words("км/ч", un[0] + un[2] / 2, un[1] + un[3] * 0.72f, un[3] * 0.72f, 0.7f, 400, WHITE, center = true)
        val parts = mutableListOf<Part>()

        val sg = r(G.SIGN)
        if (glass.sign) {
            val cx = sg[0] + sg[2] / 2
            val cy = sg[1] + sg[3] / 2
            val rr = sg[2] * 0.42f
            // As the glass draws its own sign: a thin red ring on nothing, white digits.
            ring(cx, cy, rr * 0.95f, rr * 0.1f, G.Ink.RED)
            words("60", cx, centred(cy, rr * 0.95f), rr * 0.95f, 1f, 500, WHITE, center = true)
        } else {
            dashed(sg)
        }
        parts += Part("Ограничение", sg, up = true, on = glass.sign)

        val tn = r(G.TURN)
        val ds = r(G.DISTANCE)
        val turnBox = floatArrayOf(tn[0], tn[1], ds[0] + ds[2] - tn[0], ds[3])
        if (glass.turn) {
            stroke.apply {
                color = WHITE
                strokeWidth = tn[2] * 0.14f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = null
            }
            c.drawPath(Path().apply {
                moveTo(tn[0] + tn[2] * 0.3f, tn[1] + tn[3])
                lineTo(tn[0] + tn[2] * 0.3f, tn[1] + tn[3] * 0.38f)
                lineTo(tn[0] + tn[2] * 0.82f, tn[1] + tn[3] * 0.38f)
            }, stroke)
            c.drawPath(Path().apply {
                moveTo(tn[0] + tn[2] * 0.62f, tn[1] + tn[3] * 0.16f)
                lineTo(tn[0] + tn[2] * 0.86f, tn[1] + tn[3] * 0.38f)
                lineTo(tn[0] + tn[2] * 0.62f, tn[1] + tn[3] * 0.6f)
            }, stroke)
            words("350 м", ds[0], centred(ds[1] + ds[3] * 0.42f, ds[3] * 0.62f), ds[3] * 0.62f, 1f, 500, WHITE)
        } else {
            dashed(turnBox)
        }
        parts += Part("Манёвр", turnBox, up = true, on = glass.turn)

        val mp = r(G.MAP)
        val m = G.Map
        if (glass.map) {
            fun px(value: Float) = mp[0] + value * k
            fun py(value: Float) = mp[1] + value * k
            c.save()
            c.clipPath(Path().apply {
                addRoundRect(RectF(mp[0], mp[1], mp[0] + mp[2], mp[1] + mp[3]), G.Dash.RADIUS, G.Dash.RADIUS, Path.Direction.CW)
            })
            stroke.apply {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.MITER
                pathEffect = null
                color = withAlpha(G.Ink.ROAD, G.Ink.ROAD_ALPHA)
                strokeWidth = m.ROAD_WIDTH * k
            }
            m.ROADS.forEach { road -> c.drawLine(px(road[0]), py(road[1]), px(road[2]), py(road[3]), stroke) }
            val q = m.ROUTE
            stroke.color = G.Ink.ROUTE
            stroke.strokeWidth = m.ROUTE_WIDTH * k
            c.drawPath(Path().apply {
                moveTo(px(q[0]), py(q[1]))
                lineTo(px(q[2]), py(q[3]))
                quadTo(px(q[4]), py(q[5]), px(q[6]), py(q[7]))
                lineTo(px(q[8]), py(q[9]))
            }, stroke)
            val mark = m.MARK
            circle(px(mark[0]), py(mark[1]), mark[2] * k, WHITE)
            ring(px(mark[0]), py(mark[1]), mark[2] * 0.87f * k, mark[2] * 0.23f * k, G.Ink.RED)
            val u = m.CURSOR
            fill.color = G.Ink.CURSOR
            c.drawPath(Path().apply {
                moveTo(px(u[0]), py(u[1]))
                for (i in 2 until u.size step 2) lineTo(px(u[i]), py(u[i + 1]))
                close()
            }, fill)
            c.restore()
            words("60", px(mark[0]), centred(py(mark[1]), mark[2] * 0.87f * k), mark[2] * 0.87f * k, 1f, 500, G.Ink.DARK, center = true)
        } else {
            dashed(mp)
        }
        parts += Part("Карта", mp, up = true, on = glass.map)

        // HUD Speed's picture of the lane window, at the lane rect's height from its left edge; on
        // the map only its GPS bars.
        val warn = G.Warning
        val ln = r(G.LANES)
        val q = ln[3] / warn.HEIGHT
        val strip = floatArrayOf(ln[0], ln[1], warn.WIDTH * q, ln[3])
        if (glass.cameras) {
            val t = warn.TILE * q
            val g = warn.GAP * q
            val y0 = strip[1] + (strip[3] - t) / 2
            for (i in 0..1) {
                val x0 = strip[0] + i * (t + g)
                roundRect(x0, y0, t, t, t * 0.18f, WHITE)
                val lw = t * 0.09f
                stroke.apply {
                    color = G.Ink.RED
                    strokeWidth = lw
                    pathEffect = null
                    strokeJoin = Paint.Join.MITER
                }
                c.drawRoundRect(RectF(x0 + lw / 2, y0 + lw / 2, x0 + t - lw / 2, y0 + t - lw / 2), t * 0.18f, t * 0.18f, stroke)
                if (i == 0) {
                    fill.color = G.Ink.DARK
                    c.drawRect(x0 + t * 0.22f, y0 + t * 0.36f, x0 + t * 0.78f, y0 + t * 0.72f, fill)
                    c.drawRect(x0 + t * 0.38f, y0 + t * 0.27f, x0 + t * 0.6f, y0 + t * 0.37f, fill)
                    circle(x0 + t * 0.5f, y0 + t * 0.54f, t * 0.11f, WHITE)
                } else {
                    words("60", x0 + t / 2, centred(y0 + t * 0.54f, t * 0.5f), t * 0.5f, 1f, 500, G.Ink.DARK, center = true)
                }
            }
            val d = warn.DIGITS * q
            val x1 = strip[0] + 2 * (t + g)
            val base = centred(strip[1] + strip[3] / 2, d)
            words("350", x1, base, d, 1f, 500, WHITE)
            words("м", x1 + width("350", d, 500) + g / 2, base, d * warn.UNIT, 1f, 400, WHITE)
            val s = G.Gps.SIZE * k
            fill.color = G.Ink.GPS
            for (i in 0 until 4) {
                val bh = s * (0.3f + 0.7f * (i + 1) / 4f)
                val bx = mp[0] + G.Gps.X * k + i * s * 0.26f
                val by = mp[1] + G.Gps.Y * k + s - bh
                c.drawRect(bx, by, bx + s * 0.18f, by + bh, fill)
            }
        } else {
            dashed(strip)
        }
        parts += Part("Камеры и опасности", strip, up = false, on = glass.cameras)

        val label = G.Label
        val line = G.Line
        parts.forEach { p ->
            val cx = p.at[0] + p.at[2] / 2
            val from = if (p.up) y + label.SIZE + 4 else gy + h + 2
            val to = if (p.up) p.at[1] - 2 else p.at[1] + p.at[3] + 2
            val ink = withAlpha(line.COLOR, if (p.on) line.ALPHA else line.OFF_ALPHA)
            stroke.apply {
                color = ink
                strokeWidth = 1f
                strokeCap = Paint.Cap.BUTT
                pathEffect = null
            }
            c.drawLine(cx + 0.5f, from, cx + 0.5f, to, stroke)
            circle(cx + 0.5f, to, line.DOT, ink)
            val base = if (p.up) y + label.SIZE * Sheet.Roboto.ASCENT else gy + h + label.ROOM - label.SIZE * Sheet.Roboto.DESCENT
            words(p.text, cx, base, label.SIZE, if (p.on) label.ALPHA else label.OFF_ALPHA, 500, WHITE, center = true)
        }
    }

    private fun dashed(rect: FloatArray) {
        stroke.apply {
            color = withAlpha(WHITE, G.Dash.ALPHA)
            strokeWidth = 1f
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            pathEffect = DashPathEffect(floatArrayOf(G.Dash.LENGTH, G.Dash.LENGTH), 0f)
        }
        c.drawRoundRect(
            RectF(rect[0] + 0.5f, rect[1] + 0.5f, rect[0] + rect[2] - 0.5f, rect[1] + rect[3] - 0.5f),
            G.Dash.RADIUS, G.Dash.RADIUS, stroke,
        )
        stroke.pathEffect = null
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(RectF(x, y, x + w, y + h), radius, radius, fill)
    }

    private fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, radius, fill)
    }

    private fun ring(cx: Float, cy: Float, radius: Float, width: Float, color: Int) {
        stroke.apply {
            this.color = color
            strokeWidth = width
            pathEffect = null
        }
        c.drawCircle(cx, cy, radius, stroke)
    }

    /** The board's `words`: Roboto on a baseline, left or centred on [x]. */
    private fun words(
        str: String, x: Float, baseline: Float, size: Float, alpha: Float, weight: Int, color: Int,
        center: Boolean = false,
    ) {
        val width = width(str, size, weight)
        type.color = withAlpha(color, alpha)
        c.drawText(str, if (center) x - width / 2 else x, baseline, type)
    }

    /** The board's `wordsW`: how wide [words] lays [str] down. */
    private fun width(str: String, size: Float, weight: Int): Float {
        type.typeface = if (weight >= 500) MEDIUM else REGULAR
        type.textSize = size
        return type.measureText(str)
    }

    /** The baseline that centres a line of [size] on [cy], as the board's `centred` puts it. */
    private fun centred(cy: Float, size: Float) = cy + Sheet.Roboto.CENTRE * size

    private fun withAlpha(color: Int, alpha: Float): Int =
        ((((color ushr 24) * alpha) + 0.5f).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)
        val MEDIUM: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    }
}
