package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hanging rag-doll toy in the panel's left slot: [MirrorToyPhysics] drawn
 * as a stick figure under a schematic mirror, its face driven by
 * [MotionSickness] — a wide grin when the drive is calm, flattening, then an
 * open mouth, crossed eyes and (at the very top) spit particles as the doses
 * pile up.
 *
 * A direct port of the approved prototype's rendering; prototype pixel values
 * are virtual (1850x360) units here, mapped through the [unit] factor the
 * caller derives from the panel size. Nothing is allocated in the draw path.
 */
class MirrorToyRenderer {

    private val physics = MirrorToyPhysics()
    private val sickness = MotionSickness()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val inkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK
    }
    private val path = Path()
    private val mouthOval = RectF()

    // Projected screen coordinates of every physics point.
    private val px = FloatArray(MirrorToyPhysics.POINT_COUNT)
    private val py = FloatArray(MirrorToyPhysics.POINT_COUNT)

    // Figure colour ladder, recomputed once per frame.
    private var colR = 0
    private var colG = 0
    private var colB = 0

    // Head screen velocity (throws the spit the way the toy is swinging).
    private var prevHeadX = Float.NaN
    private var prevHeadY = 0f
    private var headVX = 0f
    private var headVY = 0f

    // Spit particles, screen-space, structure of arrays.
    private val spitX = FloatArray(MAX_SPIT)
    private val spitY = FloatArray(MAX_SPIT)
    private val spitVX = FloatArray(MAX_SPIT)
    private val spitVY = FloatArray(MAX_SPIT)
    private val spitR = FloatArray(MAX_SPIT)
    private var spitCount = 0

    /**
     * @param cx toy anchor x on the canvas
     * @param cy toy anchor y on the canvas
     * @param s toy scale: canvas pixels per physics unit
     * @param unit virtual-unit scale (the panel's vs(1f)) for widths/speeds
     * @param panelHeight canvas height, for dropping spit off the bottom
     */
    fun draw(
        canvas: Canvas,
        engine: TripEngine,
        frameTimeSec: Double,
        dtSec: Double,
        cx: Float,
        cy: Float,
        s: Float,
        unit: Float,
        panelHeight: Float,
    ) {
        physics.step(dtSec, engine.lateralAccel, engine.longitudinalAccel, engine.verticalAccel)
        sickness.update(dtSec, engine.lateralAccel, engine.longitudinalAccel, engine.verticalAccel)

        for (i in 0 until MirrorToyPhysics.POINT_COUNT) {
            val z = physics.z(i).toFloat()
            val scale = 1f + z * DEPTH_SCALE // swinging toward you looks bigger
            px[i] = cx + physics.x(i).toFloat() * s * scale
            py[i] = cy + (physics.y(i).toFloat() + z * DEPTH_DROP) * s * (0.86f + 0.14f * scale)
        }
        val nausea = sickness.nausea
        figureColor(nausea)
        val lw = 3.2f * unit

        drawMirror(canvas, cx, cy, s, unit)
        drawSpring(canvas, s, unit)

        // Limbs: straight lines, nothing else.
        chain(canvas, MirrorToyPhysics.SHL, MirrorToyPhysics.ELL, MirrorToyPhysics.HAL, lw * 0.85f, 0.9f)
        chain(canvas, MirrorToyPhysics.SHR, MirrorToyPhysics.ELR, MirrorToyPhysics.HAR, lw * 0.85f, 0.9f)
        hand(canvas, MirrorToyPhysics.HAL, MirrorToyPhysics.ELL, 0.075f * s, unit)
        hand(canvas, MirrorToyPhysics.HAR, MirrorToyPhysics.ELR, 0.075f * s, unit)
        chain(canvas, MirrorToyPhysics.HIP, MirrorToyPhysics.KNL, MirrorToyPhysics.FTL, lw * 0.9f, 0.9f)
        chain(canvas, MirrorToyPhysics.HIP, MirrorToyPhysics.KNR, MirrorToyPhysics.FTR, lw * 0.9f, 0.9f)
        foot(canvas, MirrorToyPhysics.FTL, MirrorToyPhysics.KNL, 0.075f * s, unit, flip = true)
        foot(canvas, MirrorToyPhysics.FTR, MirrorToyPhysics.KNR, 0.075f * s, unit, flip = false)
        chain(canvas, MirrorToyPhysics.NECK, MirrorToyPhysics.HIP, null, lw, 0.95f)
        chain(canvas, MirrorToyPhysics.SHL, MirrorToyPhysics.SHR, null, lw * 0.9f, 0.95f)
        chain(canvas, MirrorToyPhysics.HEAD, MirrorToyPhysics.NECK, null, lw, 0.95f)

        drawHead(canvas, nausea, dtSec, frameTimeSec, s, unit, panelHeight)
    }

    /** Calm ink -> strained amber (0.5) -> queasy green (1.0). */
    private fun figureColor(nausea: Double) {
        if (nausea < 0.5) {
            val u = (nausea / 0.5).toFloat()
            colR = lerp(233, 255, u)
            colG = lerp(244, 217, u)
            colB = lerp(238, 138, u)
        } else {
            val u = ((nausea - 0.5) / 0.5).toFloat()
            colR = lerp(255, 122, u)
            colG = lerp(217, 186, u)
            colB = lerp(138, 74, u)
        }
    }

    private fun col(alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), colR, colG, colB)

    /** The mirror the toy hangs from: a small muted trapezoid above the hook. */
    private fun drawMirror(canvas: Canvas, cx: Float, cy: Float, s: Float, unit: Float) {
        val my = cy + physics.y(MirrorToyPhysics.HOOK).toFloat() * s
        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.28f)
        stroke.strokeWidth = 2f * unit
        path.rewind()
        path.moveTo(cx - 0.42f * s, my - 0.16f * s)
        path.lineTo(cx + 0.42f * s, my - 0.16f * s)
        path.lineTo(cx + 0.36f * s, my - 0.02f * s)
        path.lineTo(cx - 0.36f * s, my - 0.02f * s)
        path.close()
        canvas.drawPath(path, stroke)
    }

    /**
     * Walk the HOOK-STR-HEAD chain and swing perpendicular to it — a schematic
     * coil, tapered at both ends so it reads as a spring rather than a zigzag.
     */
    private fun drawSpring(canvas: Canvas, s: Float, unit: Float) {
        val x0 = px[MirrorToyPhysics.HOOK]
        val y0 = py[MirrorToyPhysics.HOOK]
        val x1 = px[MirrorToyPhysics.STR]
        val y1 = py[MirrorToyPhysics.STR]
        val x2 = px[MirrorToyPhysics.HEAD]
        val y2 = py[MirrorToyPhysics.HEAD]
        val seg0 = hypot(x1 - x0, y1 - y0)
        val seg1 = hypot(x2 - x1, y2 - y1)
        val total = seg0 + seg1
        if (total < unit) return
        val amp = max(4f * unit, 0.085f * s)
        stroke.color = col(0.55f)
        stroke.strokeWidth = 2f * unit
        path.rewind()
        for (k in 0..SPRING_SAMPLES) {
            val t = k.toFloat() / SPRING_SAMPLES
            val s0 = t * total
            // Which segment of the two-link chain this sample falls on.
            val onSecond = s0 >= seg0 && seg1 > 0f
            val ax = if (onSecond) x1 else x0
            val ay = if (onSecond) y1 else y0
            val bx = if (onSecond) x2 else x1
            val by = if (onSecond) y2 else y1
            val segLen = if (onSecond) seg1 else seg0
            val u = if (segLen > 0f) (if (onSecond) s0 - seg0 else s0) / segLen else 0f
            val x = ax + (bx - ax) * u
            val y = ay + (by - ay) * u
            val dl = max(segLen, 1f)
            val off = sin(t * TWO_PI * SPRING_COILS) * amp * sin(t * PI_F)
            val ox = x + (-(by - ay) / dl) * off
            val oy = y + ((bx - ax) / dl) * off
            if (k == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
        }
        canvas.drawPath(path, stroke)
    }

    /** A polyline through two or three projected points. */
    private fun chain(canvas: Canvas, a: Int, b: Int, c: Int?, width: Float, alpha: Float) {
        stroke.color = col(alpha)
        stroke.strokeWidth = width
        path.rewind()
        path.moveTo(px[a], py[a])
        path.lineTo(px[b], py[b])
        if (c != null) path.lineTo(px[c], py[c])
        canvas.drawPath(path, stroke)
    }

    /** Three little fingers fanned along the forearm's direction. */
    private fun hand(canvas: Canvas, wr: Int, el: Int, size: Float, unit: Float) {
        val dx = px[wr] - px[el]
        val dy = py[wr] - py[el]
        val d = max(hypot(dx, dy), 1f)
        val ux = dx / d
        val uy = dy / d
        stroke.color = col(0.9f)
        stroke.strokeWidth = 2f * unit
        for (i in -1..1) {
            val a = i * FINGER_FAN_RAD
            val fx = ux * cos(a) - uy * sin(a)
            val fy = ux * sin(a) + uy * cos(a)
            canvas.drawLine(px[wr], py[wr], px[wr] + fx * size, py[wr] + fy * size, stroke)
        }
    }

    /** A short flick forward from the ankle. */
    private fun foot(canvas: Canvas, ft: Int, kn: Int, size: Float, unit: Float, flip: Boolean) {
        val dx = px[ft] - px[kn]
        val dy = py[ft] - py[kn]
        val d = max(hypot(dx, dy), 1f)
        val ux = dx / d
        val uy = dy / d
        val side = if (flip) -1f else 1f
        stroke.color = col(0.9f)
        stroke.strokeWidth = 2.6f * unit
        canvas.drawLine(
            px[ft], py[ft],
            px[ft] + (-uy) * size * side * 0.2f + ux * size * 0.1f,
            py[ft] + size * 0.55f,
            stroke,
        )
    }

    private fun drawHead(
        canvas: Canvas,
        nausea: Double,
        dtSec: Double,
        frameTimeSec: Double,
        s: Float,
        unit: Float,
        panelHeight: Float,
    ) {
        val hx = px[MirrorToyPhysics.HEAD]
        val hy = py[MirrorToyPhysics.HEAD]
        val hr = 0.20f * s * (1f + physics.z(MirrorToyPhysics.HEAD).toFloat() * DEPTH_SCALE)
        if (!prevHeadX.isNaN()) {
            val invDt = 1f / max(dtSec.toFloat(), 1e-3f)
            headVX = (hx - prevHeadX) * invDt
            headVY = (hy - prevHeadY) * invDt
        }
        prevHeadX = hx
        prevHeadY = hy

        fill.color = col(0.95f)
        canvas.drawCircle(hx, hy, hr, fill)

        // The face turns with the string, so it reads as one wobbling piece.
        val upx = px[MirrorToyPhysics.STR] - hx
        val upy = py[MirrorToyPhysics.STR] - hy
        val ang = atan2(upx, -upy)
        val save = canvas.save()
        canvas.translate(hx, hy)
        canvas.rotate(ang * RAD_TO_DEG)
        drawFace(canvas, nausea, hr, unit)
        canvas.restoreToCount(save)

        drawSpit(canvas, nausea, dtSec, hx, hy, hr, ang, unit, panelHeight)

        // A sweat drop once it is properly unwell.
        if (nausea > 0.6) {
            val a = ((nausea - 0.6) * 2.0).coerceIn(0.0, 1.0).toFloat()
            fill.color = Color.argb((a * 255f).toInt(), 160, 214, 255)
            canvas.drawCircle(
                hx + hr * 0.85f,
                hy - hr * 0.4f + sin(frameTimeSec * 3.0).toFloat() * hr * 0.1f,
                hr * 0.11f,
                fill,
            )
        }
    }

    /** Face features in the head's rotated frame, knocked out in [INK]. */
    private fun drawFace(canvas: Canvas, nausea: Double, hr: Float, unit: Float) {
        val ex = hr * 0.34f
        val ey = -hr * 0.14f
        // Round eyes; they squeeze shut only when it is really bad.
        if (nausea > 0.85) {
            inkStroke.strokeWidth = 2.6f * unit
            canvas.drawLine(-ex - hr * 0.16f, ey - hr * 0.05f, -ex + hr * 0.16f, ey + hr * 0.05f, inkStroke)
            canvas.drawLine(ex - hr * 0.16f, ey + hr * 0.05f, ex + hr * 0.16f, ey - hr * 0.05f, inkStroke)
        } else {
            canvas.drawCircle(-ex, ey, hr * 0.135f, inkFill)
            canvas.drawCircle(ex, ey, hr * 0.135f, inkFill)
        }
        // Worried brows tent up over the nose as the nausea rises.
        if (nausea > 0.3) {
            val worry = ((nausea - 0.3) / 0.7).coerceIn(0.0, 1.0).toFloat()
            val a = ((nausea - 0.3) * 5.0).coerceIn(0.0, 1.0).toFloat()
            inkStroke.strokeWidth = 2.4f * unit
            inkStroke.alpha = (a * 255f).toInt()
            val browY = ey - hr * 0.34f
            val innerY = browY - hr * 0.12f * worry
            val outerY = browY + hr * 0.06f * worry
            canvas.drawLine(-hr * 0.46f, outerY, -hr * 0.10f, innerY, inkStroke)
            canvas.drawLine(hr * 0.10f, innerY, hr * 0.46f, outerY, inkStroke)
            inkStroke.alpha = 255
        }
        // Nose.
        canvas.drawCircle(0f, hr * 0.10f, hr * 0.075f, inkFill)
        // Mouth: a smile that flattens out, then opens up when it is about to
        // lose it.
        val mouthY = hr * 0.44f
        if (nausea < 0.72) {
            val smile = (1.0 - nausea / 0.72).toFloat() // 1 = wide grin, 0 = flat
            val halfW = hr * (0.22f + 0.16f * smile)
            inkStroke.strokeWidth = 2.8f * unit
            path.rewind()
            path.moveTo(-halfW, mouthY - smile * hr * 0.06f)
            path.quadTo(0f, mouthY + smile * hr * 0.52f, halfW, mouthY - smile * hr * 0.06f)
            canvas.drawPath(path, inkStroke)
        } else {
            val open = ((nausea - 0.72) / 0.28).toFloat()
            mouthOval.set(
                -hr * (0.14f + 0.08f * open),
                mouthY + hr * 0.04f - hr * (0.10f + 0.16f * open),
                hr * (0.14f + 0.08f * open),
                mouthY + hr * 0.04f + hr * (0.10f + 0.16f * open),
            )
            canvas.drawOval(mouthOval, inkFill)
        }
    }

    /**
     * Spit: only when it has really had enough, thrown the way the toy is
     * swinging, then gravity takes it off the bottom of the panel.
     */
    private fun drawSpit(
        canvas: Canvas,
        nausea: Double,
        dtSec: Double,
        hx: Float,
        hy: Float,
        hr: Float,
        ang: Float,
        unit: Float,
        panelHeight: Float,
    ) {
        if (nausea > SPIT_NAUSEA && Math.random() < dtSec * SPIT_RATE && spitCount < MAX_SPIT) {
            val mouthX = hx - sin(ang) * hr * 0.5f
            val mouthY = hy + cos(ang) * hr * 0.5f
            val sv = hypot(headVX, headVY)
            val a2 = atan2(headVY, headVX) + ((Math.random() - 0.5) * 1.0).toFloat()
            val sp = (50.0 + Math.random() * 90.0).toFloat() * unit + sv * 0.35f
            spitX[spitCount] = mouthX
            spitY[spitCount] = mouthY
            spitVX[spitCount] = cos(a2) * sp
            spitVY[spitCount] = sin(a2) * sp - 50f * unit
            spitR[spitCount] = (1.8 + Math.random() * 2.4).toFloat() * unit
            spitCount++
        }
        val dt = dtSec.coerceIn(0.0, 0.08).toFloat()
        fill.color = SPIT_COLOR
        var i = 0
        while (i < spitCount) {
            spitVY[i] += SPIT_GRAVITY * unit * dt
            spitX[i] += spitVX[i] * dt
            spitY[i] += spitVY[i] * dt
            if (spitY[i] > panelHeight + 24f * unit) {
                spitCount--
                spitX[i] = spitX[spitCount]
                spitY[i] = spitY[spitCount]
                spitVX[i] = spitVX[spitCount]
                spitVY[i] = spitVY[spitCount]
                spitR[i] = spitR[spitCount]
            } else {
                canvas.drawCircle(spitX[i], spitY[i], spitR[i], fill)
                i++
            }
        }
    }

    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)

    private companion object {
        /** The panel background the face is knocked out in. */
        const val INK = 0xFF080B0D.toInt()
        const val SPIT_COLOR = 0xD996C454.toInt() // rgba(150,196,84,0.85)

        const val DEPTH_SCALE = 0.26f
        const val DEPTH_DROP = 0.10f
        const val SPRING_COILS = 6f
        const val SPRING_SAMPLES = 120
        const val FINGER_FAN_RAD = 0.44f

        const val MAX_SPIT = 18
        const val SPIT_NAUSEA = 0.94
        const val SPIT_RATE = 3.5
        const val SPIT_GRAVITY = 880f

        const val PI_F = Math.PI.toFloat()
        const val TWO_PI = (2.0 * Math.PI).toFloat()
        const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()
    }
}
