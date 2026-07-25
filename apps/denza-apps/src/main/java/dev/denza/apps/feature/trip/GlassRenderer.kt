package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Mode 2 «Стакан» — the comfort glass.
 *
 * The liquid is driven by the real physics channels (v*w lateral, GNSS-derived
 * longitudinal, gravity-projected vertical) and is deliberately MORE liquid and
 * MORE active than the prototype: the surface-tilt spring is under-damped, two
 * wave modes run at once (a fundamental slosh plus a faster ripple), corners AND
 * braking visibly slosh it, vertical impulses raise ripples, there is a slight
 * meniscus at the walls, and steam wisps appear when calm for >3 s — while it
 * still settles when the car is smooth. No spilled-drop particles, no "пролито".
 *
 * The right side is the body-motion ribbon: agitation aggregated into ~0.45 s RMS
 * buckets, a smooth mirrored envelope through the bucket values, advancing one
 * bucket at a time (never per-frame shifting).
 */
class GlassRenderer : BaseTripRenderer() {

    // Surface-tilt spring.
    private var tilt = 0.0
    private var tiltVel = 0.0
    private var sloshAmp = 0.0
    private var rippleAmp = 0.0
    private var phaseSlosh = 0.0
    private var phaseRipple = 0.0
    private var steamPhase = 0.0
    private var prevVertical = 0.0

    private val clip = Path()
    private val liquid = Path()
    private val ribbon = Path()
    private val xs = FloatArray(160)
    private val hts = FloatArray(160)
    private val bucketBuf = FloatArray(160)

    override fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        engine: TripEngine,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
    ) {
        setSize(w, h)
        advancePhysics(engine, dtSec)
        drawGlass(canvas, engine)
        drawStats(canvas, engine)
        drawRibbon(canvas, engine)
        if (showLocationHint) {
            // Top-right corner: above the ribbon (which starts ~y192) and right of
            // the stats, so it never touches the glass caption, stats, or ribbon.
            label(canvas, LOCATION_HINT, w - vx(24f), vy(30f), 15f, TripPalette.alpha(TripPalette.MUTED, 0.6f), Paint.Align.RIGHT)
        }
    }

    private fun advancePhysics(engine: TripEngine, dtSec: Double) {
        val dt = dtSec.coerceIn(0.0, 0.08)
        val lateral = engine.lateralAccel
        val longitudinal = engine.longitudinalAccel
        val vertical = engine.verticalAccel

        // Under-damped tilt spring: lower stiffness + low damping so it keeps
        // living for a while after a corner instead of snapping back.
        // Tilt target blends both horizontal channels: engine lateral is positive
        // in a LEFT turn (v*w), and positive tilt piles the liquid LEFT, so the
        // lateral term is negated to slosh OUTWARD; braking (longitudinal < 0)
        // visibly surges the liquid the other way.
        val target = (-lateral * TILT_GAIN_LATERAL - longitudinal * TILT_GAIN_LONGITUDINAL)
            .coerceIn(-MAX_TILT, MAX_TILT)
        tiltVel += (-K_TILT * (tilt - target) - C_TILT * tiltVel) * dt
        tilt += tiltVel * dt

        // Fundamental slosh grows with horizontal (corner + brake) energy and
        // decays slowly.
        sloshAmp *= exp(-dt / SLOSH_TAU)
        sloshAmp += hypot(lateral, longitudinal) * SLOSH_GAIN * dt
        sloshAmp = min(sloshAmp, MAX_SLOSH)

        // Ripple is kicked by vertical impulses (change in vertical accel).
        rippleAmp *= exp(-dt / RIPPLE_TAU)
        rippleAmp += abs(vertical - prevVertical) * RIPPLE_GAIN * dt
        rippleAmp = min(rippleAmp, MAX_RIPPLE)
        prevVertical = vertical

        phaseSlosh += dt * FREQ_SLOSH
        phaseRipple += dt * FREQ_RIPPLE
        steamPhase += dt
    }

    private fun drawGlass(canvas: Canvas, engine: TripEngine) {
        val gx = vx(240f)
        val gcy = vy(196f)
        val gw = vs(168f)
        val gh = vs(172f)
        val top = gcy - gh / 2f
        val bot = gcy + gh / 2f
        val lt = gx - gw / 2f
        val rt = gx + gw / 2f
        val wall = vs(14f)
        val lb = lt + wall
        val rb = rt - wall

        val level = top + gh * 0.34f
        val u = (level - top) / gh
        val xL = lt + wall * u
        val xR = rt - wall * u

        canvas.save()
        clip.rewind()
        clip.moveTo(lt, top); clip.lineTo(lb, bot); clip.lineTo(rb, bot); clip.lineTo(rt, top); clip.close()
        canvas.clipPath(clip)

        // Liquid body.
        liquid.rewind()
        val steps = 26
        for (k in 0..steps) {
            val frac = k.toFloat() / steps
            val xx = xL + (xR - xL) * frac
            val yy = surfaceY(level, xx, gx, frac)
            if (k == 0) liquid.moveTo(xx, yy) else liquid.lineTo(xx, yy)
        }
        liquid.lineTo(rb, bot)
        liquid.lineTo(lb, bot)
        liquid.close()
        fill.style = Paint.Style.FILL
        fill.color = COFFEE
        canvas.drawPath(liquid, fill)

        // Surface highlight (meniscus edge).
        ribbon.rewind()
        for (k in 0..steps) {
            val frac = k.toFloat() / steps
            val xx = xL + (xR - xL) * frac
            val yy = surfaceY(level, xx, gx, frac)
            if (k == 0) ribbon.moveTo(xx, yy) else ribbon.lineTo(xx, yy)
        }
        stroke.color = COFFEE_FOAM
        stroke.strokeWidth = vs(2f)
        canvas.drawPath(ribbon, stroke)
        canvas.restore()

        // Glass outline (thin, no frame/fill).
        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.55f)
        stroke.strokeWidth = vs(2f)
        val outline = clip
        outline.rewind()
        outline.moveTo(lt, top); outline.lineTo(lb, bot); outline.lineTo(rb, bot); outline.lineTo(rt, top)
        canvas.drawPath(outline, stroke)

        // Steam wisps when calm for a while.
        val calm = engine.calmSeconds()
        if (calm > 3.0) {
            val sa = min(0.3, (calm - 3.0) * 0.06)
            stroke.color = TripPalette.alpha(TripPalette.INK, sa.toFloat())
            stroke.strokeWidth = vs(1.5f)
            for (j in 0 until 2) {
                ribbon.rewind()
                for (k in 0..10) {
                    val sy = top - vs(10f) - k * vs(4.6f)
                    val sxx = gx - vs(16f) + j * vs(32f) + (sin(k * 0.9 + steamPhase * 2.5 + j * 2) * vs(6f)).toFloat()
                    if (k == 0) ribbon.moveTo(sxx, sy) else ribbon.lineTo(sxx, sy)
                }
                canvas.drawPath(ribbon, stroke)
            }
        }

        val caption = if (engine.agitationScore() > 72) "кузов спокоен" else "кузов в движении"
        label(canvas, caption, gx, vy(342f), 18f, TripPalette.alpha(TripPalette.MUTED, 0.9f), Paint.Align.CENTER)
    }

    private fun surfaceY(level: Float, xx: Float, gx: Float, frac: Float): Float {
        val base = level + (xx - gx) * tan(tilt).toFloat()
        // Wave amplitudes are authored in virtual (360-tall) units, so scale them
        // to the real panel height like every other size.
        val slosh = vs(sloshAmp.toFloat()) * sin(frac * PI + phaseSlosh).toFloat()
        val ripple = vs(rippleAmp.toFloat()) * sin(frac * 3.0 * 2.0 * PI + phaseRipple).toFloat()
        // Slight meniscus: liquid rides a touch higher against the walls.
        val meniscus = -vs(3f) * (4f * (frac - 0.5f) * (frac - 0.5f))
        return base + slosh + ripple + meniscus
    }

    private fun drawStats(canvas: Canvas, engine: TripEngine) {
        val score = engine.agitationScore()
        stat(canvas, vx(500f), "Плавность", score.toString(), if (score > 85) TripPalette.MINT else TripPalette.AMBER)
        stat(canvas, vx(900f), "Без всплесков", clockMs(engine.calmSeconds()), TripPalette.INK)
    }

    private fun stat(canvas: Canvas, px: Float, lab: String, text: String, color: Int) {
        label(canvas, lab, px, vy(64f), 18f, TripPalette.alpha(TripPalette.MUTED, 0.9f))
        value(canvas, text, px, vy(116f), 42f, color)
    }

    private fun drawRibbon(canvas: Canvas, engine: TripEngine) {
        val rx0 = vx(500f)
        val rx1 = vx(1810f)
        val ry = vy(248f)
        val amp = vs(56f)

        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.15f)
        stroke.strokeWidth = vs(1f)
        canvas.drawLine(rx0, ry, rx1, ry, stroke)

        val closed = engine.copyBucketsInto(bucketBuf)
        // Live head = the still-open bucket, so the strip advances one bucket at a
        // time and never shifts per frame.
        val partial = engine.bucketPartial()
        val count = min(closed + 1, xs.size)
        if (count < 2) {
            caption(canvas, rx0, ry, amp)
            return
        }
        val slots = 150
        val step = (rx1 - rx0) / (slots - 1)
        for (j in 0 until count) {
            val raw = if (j < closed) bucketBuf[j].toDouble() else partial
            val norm = min(1.0, raw / RIBBON_SCALE)
            xs[j] = rx1 - (count - 1 - j) * step
            hts[j] = ((0.06 + norm * 0.94) * amp).toFloat()
        }

        ribbon.rewind()
        ribbon.moveTo(xs[0], ry - hts[0])
        for (j in 1 until count - 1) {
            ribbon.quadTo(xs[j], ry - hts[j], (xs[j] + xs[j + 1]) / 2f, ry - (hts[j] + hts[j + 1]) / 2f)
        }
        ribbon.lineTo(xs[count - 1], ry - hts[count - 1])
        ribbon.lineTo(xs[count - 1], ry + hts[count - 1])
        for (j in count - 2 downTo 1) {
            ribbon.quadTo(xs[j], ry + hts[j], (xs[j] + xs[j - 1]) / 2f, ry + (hts[j] + hts[j - 1]) / 2f)
        }
        ribbon.lineTo(xs[0], ry + hts[0])
        ribbon.close()
        fill.style = Paint.Style.FILL
        fill.color = TripPalette.alpha(TripPalette.MINT, 0.16f)
        canvas.drawPath(ribbon, fill)
        stroke.color = TripPalette.alpha(TripPalette.MINT, 0.55f)
        stroke.strokeWidth = vs(1.5f)
        canvas.drawPath(ribbon, stroke)

        // Amber dots only on strong local maxima.
        fill.color = TripPalette.alpha(TripPalette.AMBER, 0.9f)
        for (j in 1 until count - 1) {
            val raw = if (j < closed) bucketBuf[j].toDouble() else partial
            val norm = min(1.0, raw / RIBBON_SCALE)
            val prev = if (j - 1 < closed) bucketBuf[j - 1].toDouble() else partial
            val next = if (j + 1 < closed) bucketBuf[j + 1].toDouble() else partial
            if (norm > HIGH_DOT && raw >= prev && raw >= next) {
                canvas.drawCircle(xs[j], ry - hts[j] - vs(9f), vs(2.5f), fill)
            }
        }
        caption(canvas, rx0, ry, amp)
    }

    private fun caption(canvas: Canvas, rx0: Float, ry: Float, amp: Float) {
        label(canvas, "движение кузова · последняя минута", rx0, ry + amp + vs(30f), 17f, TripPalette.alpha(TripPalette.MUTED, 0.5f))
    }

    private companion object {
        val COFFEE = 0xD1B06A3A.toInt()
        val COFFEE_FOAM = 0xFFFFD9A8.toInt()

        // Tilt gains are rad per m/s^2 against real physics magnitudes: a
        // comfortable 2.5 m/s^2 corner tilts ~0.4 rad (visibly), a normal
        // 2 m/s^2 brake ~0.24 rad — both settle on a smooth highway.
        const val TILT_GAIN_LATERAL = 0.16
        const val TILT_GAIN_LONGITUDINAL = 0.12
        const val MAX_TILT = 0.5
        const val K_TILT = 34.0
        const val C_TILT = 4.2
        const val SLOSH_TAU = 1.4

        // Steady-state slosh = |a| * gain * tau: ~2 m/s^2 sustains a visible
        // wave without instantly pinning MAX_SLOSH the way the old gain did
        // against real magnitudes.
        const val SLOSH_GAIN = 8.0
        const val MAX_SLOSH = 22.0
        const val RIPPLE_TAU = 0.5
        const val RIPPLE_GAIN = 26.0
        const val MAX_RIPPLE = 12.0
        const val FREQ_SLOSH = 6.0
        const val FREQ_RIPPLE = 15.0

        // Ribbon full scale in m/s^2 RMS: normal driving sits mid-strip, only
        // hard events (>= HIGH_DOT * scale = 4 m/s^2) earn an amber dot.
        const val RIBBON_SCALE = 5.0
        const val HIGH_DOT = 0.8
    }
}
