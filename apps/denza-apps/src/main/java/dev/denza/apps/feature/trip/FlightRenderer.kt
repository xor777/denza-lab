package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mode 1 «Рейс» — the aviation panel.
 *
 * All motion comes from real sensors: the left slot is the comfort field, an
 * elastic membrane showing what passengers actually feel (it replaced an
 * artificial horizon, which tells a driver nothing in a car); the compass tape
 * follows the GNSS bearing with an offline sun marker; the centre strip is the
 * WHOLE trip's smoothed GNSS altitude, decimated to the strip width, with a head
 * halo that swells with IMU vertical energy.
 *
 * The field carries no caption of its own — it is read, not measured.
 */
class FlightRenderer : BaseTripRenderer() {

    private val clip = Path()
    private val linePath = Path()
    private val fillPath = Path()
    private val elevBuf = FloatArray(320)

    private val field = ComfortFieldPhysics()
    private val fieldX = Array(FIELD_N) { FloatArray(FIELD_N) }
    private val fieldY = Array(FIELD_N) { FloatArray(FIELD_N) }
    private val fieldLoad = Array(FIELD_N) { FloatArray(FIELD_N) }

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
        drawComfortField(canvas, engine, dtSec)
        drawCompass(canvas, engine)
        drawElevation(canvas, engine)
        drawColumn(canvas, engine)
        if (showLocationHint) {
            // Right data column: its GNSS rows (Высота/Закат) are hidden without a
            // fix, so the lower part of that column is empty — and far from the
            // captionless field in the left slot.
            label(canvas, LOCATION_HINT, vx(1436f), vy(320f), 16f, TripPalette.alpha(TripPalette.MUTED, 0.85f))
        }
    }

    /**
     * The comfort field: a lattice of thin lines pinned at its edges, pulled by
     * the acceleration the cabin is under, rotated slightly by the rate of turn,
     * and rippled by vertical impacts. On a smooth road it settles back into a
     * plain grid on its own.
     */
    private fun drawComfortField(canvas: Canvas, engine: TripEngine, dtSec: Double) {
        field.update(
            dtSec,
            lateral = engine.lateralAccel,
            longitudinal = engine.longitudinalAccel,
            vertical = engine.verticalAccel,
            yawRate = engine.yawRate,
        )

        val cx = vx(175f)
        val cy = vy(186f)
        val r = vs(118f)
        val pull = r * FIELD_PULL
        val cosR = cos(field.rotationRad).toFloat()
        val sinR = sin(field.rotationRad).toFloat()
        val half = (FIELD_N - 1) / 2f

        for (row in 0 until FIELD_N) {
            for (col in 0 until FIELD_N) {
                val u = (col - half) / half
                val v = (row - half) / half
                // Rotate the rest lattice, then displace it.
                val rx = u * r
                val ry = v * r
                val bx = cx + rx * cosR - ry * sinR
                val by = cy + rx * sinR + ry * cosR

                // Edges are pinned, so the middle travels furthest — a membrane,
                // not a sliding picture.
                val pin = max(0f, 1f - (u * u + v * v) * FIELD_EDGE_PIN)
                var dx = field.offsetX.toFloat() * pull * pin
                var dy = field.offsetY.toFloat() * pull * pin

                val dist = hypot(bx - cx, by - cy)
                if (dist > 0.001f) {
                    val ripple = field.rippleDisplacementAt((dist / r).toDouble()).toFloat() * r
                    dx += (bx - cx) / dist * ripple
                    dy += (by - cy) / dist * ripple
                }
                fieldX[row][col] = bx + dx
                fieldY[row][col] = by + dy
                fieldLoad[row][col] = min(1f, hypot(dx, dy) / (r * FIELD_LOAD_SCALE))
            }
        }

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = vs(1f)
        for (row in 0 until FIELD_N) {
            var load = 0f
            linePath.rewind()
            for (col in 0 until FIELD_N) {
                if (col == 0) linePath.moveTo(fieldX[row][col], fieldY[row][col])
                else linePath.lineTo(fieldX[row][col], fieldY[row][col])
                load += fieldLoad[row][col]
            }
            stroke.color = TripPalette.alpha(TripPalette.MINT, FIELD_BASE_ALPHA + FIELD_LOAD_ALPHA * load / FIELD_N)
            canvas.drawPath(linePath, stroke)
        }
        for (col in 0 until FIELD_N) {
            var load = 0f
            linePath.rewind()
            for (row in 0 until FIELD_N) {
                if (row == 0) linePath.moveTo(fieldX[row][col], fieldY[row][col])
                else linePath.lineTo(fieldX[row][col], fieldY[row][col])
                load += fieldLoad[row][col]
            }
            stroke.color = TripPalette.alpha(TripPalette.MINT, FIELD_BASE_ALPHA + FIELD_LOAD_ALPHA * load / FIELD_N)
            canvas.drawPath(linePath, stroke)
        }

        // Where the cabin itself sits inside the field.
        val mx = cx + field.offsetX.toFloat() * pull
        val my = cy + field.offsetY.toFloat() * pull
        stroke.color = TripPalette.alpha(TripPalette.AMBER, 0.85f)
        stroke.strokeWidth = vs(1.5f)
        canvas.drawLine(mx - vs(9f), my, mx - vs(3f), my, stroke)
        canvas.drawLine(mx + vs(3f), my, mx + vs(9f), my, stroke)
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = TripPalette.alpha(TripPalette.AMBER, 0.9f)
        canvas.drawCircle(mx, my, vs(2f), fill)
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

    private fun drawCompass(canvas: Canvas, engine: TripEngine) {
        val x0 = vx(395f)
        val x1 = vx(1360f)
        val tcx = (x0 + x1) / 2f
        val tcy = vy(46f)
        val ppd = (x1 - x0) / 130f // ~130 deg visible across the tape
        val hasHeading = engine.hasHeading()
        // Held (stopped) course dims the tape; no course at all draws no ticks.
        val dim = if (engine.courseDimmed()) 0.55f else 1.0f

        canvas.save()
        clip.rewind()
        clip.addRect(x0, vy(16f), x1, vy(74f), Path.Direction.CW)
        canvas.clipPath(clip)
        if (hasHeading) {
            val hd = engine.headingDeg()
            stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.5f * dim)
            stroke.strokeWidth = vs(1.5f)
            val base = (hd / 10.0).roundToInt() * 10
            var k = -13
            while (k <= 13) {
                val deg = base + k * 10
                val x = tcx + ((deg - hd) * ppd).toFloat()
                canvas.drawLine(x, tcy + vs(10f), x, tcy + vs(19f), stroke)
                val dm = ((deg % 360) + 360) % 360
                val lbl = when {
                    dm == 0 -> "С"
                    dm == 90 -> "В"
                    dm == 180 -> "Ю"
                    dm == 270 -> "З"
                    dm % 30 == 0 -> dm.toString()
                    else -> null
                }
                if (lbl != null) {
                    label(canvas, lbl, x, tcy + vs(2f), 17f, TripPalette.alpha(TripPalette.MUTED, 0.85f * dim), Paint.Align.CENTER)
                }
                k++
            }
            // Sun marker sits at its azimuth relative to the known course.
            val sun = engine.sunInfo()
            if (sun.hasPosition) {
                val rel = ((sun.azimuthDeg - hd + 540.0) % 360.0) - 180.0
                var sxp = tcx + (rel * ppd).toFloat()
                sxp = sxp.coerceIn(x0 + vs(20f), x1 - vs(20f))
                drawSun(canvas, sxp, tcy + vs(34f), vs(4.5f), dim)
            }
        } else {
            // No trustworthy heading yet: a faint static baseline, no ticks, no
            // cardinal positions (they would imply a fake 0deg), and no sun.
            stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.16f)
            stroke.strokeWidth = vs(1.5f)
            canvas.drawLine(x0 + vs(20f), tcy + vs(16f), x1 - vs(20f), tcy + vs(16f), stroke)
        }
        canvas.restore()

        // Center pointer + course readout.
        fill.color = TripPalette.alpha(TripPalette.MINT, if (hasHeading) dim else 0.4f)
        val pointer = fillPath
        pointer.rewind()
        pointer.moveTo(tcx, tcy + vs(20f))
        pointer.lineTo(tcx - vs(6f), tcy + vs(31f))
        pointer.lineTo(tcx + vs(6f), tcy + vs(31f))
        pointer.close()
        canvas.drawPath(pointer, fill)
        val course = if (hasHeading) {
            "курс ${engine.headingDeg().roundToInt() % 360}°"
        } else {
            "курс —"
        }
        value(canvas, course, tcx, tcy + vs(58f), 21f, TripPalette.alpha(TripPalette.INK, if (hasHeading) dim else 0.55f), Paint.Align.CENTER)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float = 1f) {
        fill.color = TripPalette.alpha(TripPalette.AMBER, alpha)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = TripPalette.alpha(TripPalette.AMBER, alpha)
        stroke.strokeWidth = vs(1.5f)
        var k = 0
        while (k < 8) {
            val a = k * PI / 4.0
            canvas.drawLine(
                cx + (cos(a) * (r + vs(3f))).toFloat(), cy + (sin(a) * (r + vs(3f))).toFloat(),
                cx + (cos(a) * (r + vs(7f))).toFloat(), cy + (sin(a) * (r + vs(7f))).toFloat(),
                stroke,
            )
            k++
        }
    }

    private fun drawElevation(canvas: Canvas, engine: TripEngine) {
        val x0 = vx(395f)
        val x1 = vx(1360f)
        val top = vy(136f)
        val bot = vy(290f)
        // Whole trip, decimated to the strip width by copyElevationInto.
        val count = engine.copyElevationInto(elevBuf)
        if (count > 1) {
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (i in 0 until count) {
                mn = min(mn, elevBuf[i])
                mx = max(mx, elevBuf[i])
            }
            // +-10 m padding, then enforce a minimum displayed span so flat
            // terrain does not amplify GPS altitude noise into fake mountains.
            mn -= 10f
            mx += 10f
            if (mx - mn < MIN_ELEVATION_SPAN_M) {
                val mid = (mn + mx) / 2f
                mn = mid - MIN_ELEVATION_SPAN_M / 2f
                mx = mid + MIN_ELEVATION_SPAN_M / 2f
            }
            val span = max(1f, mx - mn)
            val step = (x1 - x0) / max(1, count - 1)
            linePath.rewind()
            for (i in 0 until count) {
                val px = x0 + step * i
                val py = bot - (elevBuf[i] - mn) / span * (bot - top)
                if (i == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
            }
            stroke.color = TripPalette.alpha(TripPalette.MINT, 0.9f)
            stroke.strokeWidth = vs(2f)
            canvas.drawPath(linePath, stroke)
            fillPath.rewind()
            fillPath.set(linePath)
            fillPath.lineTo(x1, bot + vs(12f))
            fillPath.lineTo(x0, bot + vs(12f))
            fillPath.close()
            fill.color = TripPalette.alpha(TripPalette.MINT, 0.07f)
            canvas.drawPath(fillPath, fill)

            val hy = bot - (elevBuf[count - 1] - mn) / span * (bot - top)
            val energy = engine.verticalEnergy()
            stroke.color = TripPalette.alpha(TripPalette.AMBER, (0.25f + min(0.4f, energy.toFloat() * 0.2f)))
            stroke.strokeWidth = vs(2f)
            canvas.drawCircle(x1, hy, vs(10f) + vs((energy * 6.0).toFloat()), stroke)
            fill.color = TripPalette.AMBER
            canvas.drawCircle(x1, hy, vs(4f), fill)
        }
        drawEvents(canvas, engine, x0, x1)
    }

    private fun drawEvents(canvas: Canvas, engine: TripEngine, x0: Float, x1: Float) {
        val now = engine.elapsedSeconds
        val span = (x1 - x0) - vs(40f)
        val n = engine.eventCount()
        for (idx in 0 until n) {
            val e = engine.eventAt(idx)
            if (e.kind == TripEventKind.STOP || e.kind == TripEventKind.SERPENTINE) continue
            val age = now - e.bornElapsedSeconds
            if (age < 0 || age > EVENT_LIFETIME) continue
            val fadeIn = min(1.0, age * 2.0)
            val fadeOut = min(1.0, (EVENT_LIFETIME - age) / 4.0)
            val a = (fadeIn * fadeOut * 0.85).toFloat()
            val x = (x1 - vs(16f)) - (age / EVENT_LIFETIME * span).toFloat()
            val y = if (e.lane == 0) vy(128f) else vy(326f)
            label(canvas, eventText(e), x, y, 17f, TripPalette.alpha(TripPalette.MUTED, a), Paint.Align.CENTER)
        }
    }

    private fun eventText(e: TripEvent): String = when (e.kind) {
        TripEventKind.CLIMB -> "набор +${e.value.roundToInt()} м"
        TripEventKind.DESCENT -> "плавный спуск"
        TripEventKind.TURN -> "вираж"
        TripEventKind.CALM -> "ровный участок"
        TripEventKind.CREST -> "гребень ${e.value.roundToInt()} м"
        else -> ""
    }

    private fun drawColumn(canvas: Canvas, engine: TripEngine) {
        // Faint dividers (thin lines, not frames).
        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.14f)
        stroke.strokeWidth = vs(1f)
        canvas.drawLine(vx(352f), vy(24f), vx(352f), vy(336f), stroke)
        canvas.drawLine(vx(1398f), vy(24f), vx(1398f), vy(336f), stroke)

        val rx = vx(1436f)
        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            row(canvas, rx, 46f, "Осталось · навигация", parts.joinToString(" · "), TripPalette.INK)
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            row(canvas, rx, 46f, "В пути", "$timePart · ${distanceLabel(engine.distanceMeters())}", TripPalette.INK)
        }

        val hasAlt = engine.hasAltitude()
        row(
            canvas, rx, 124f, "Высота",
            if (hasAlt) "${engine.smoothedAltitude().roundToInt()} м" else "—", TripPalette.INK,
        )
        if (hasAlt) {
            val v = engine.variometer()
            val up = v >= 0
            value(
                canvas,
                (if (up) "↗ +" else "↘ −") + fmt1(abs(v)) + " м/с",
                rx + vs(150f), vy(158f), 20f, if (up) TripPalette.MINT else TripPalette.AMBER,
            )
        }

        row(canvas, rx, 202f, "Набор за поездку", "+${engine.tripClimbMeters().roundToInt()} м", TripPalette.INK)

        val sun = engine.sunInfo()
        if (sun.hasPosition && sun.nextEventLabel.isNotEmpty()) {
            val head = if (sun.nextIsSunset) "Закат" else "Рассвет"
            val countdown = if (sun.countdownSeconds >= 0) "через ${clockHm(sun.countdownSeconds)}" else "—"
            row(canvas, rx, 280f, "$head · ${sun.nextEventLabel}", countdown, TripPalette.AMBER)
        }
    }

    private fun row(canvas: Canvas, rx: Float, y: Float, lab: String, text: String, color: Int) {
        label(canvas, lab, rx, vy(y), 16f, TripPalette.alpha(TripPalette.MUTED, 0.85f))
        value(canvas, text, rx, vy(y) + vs(34f), 27f, color)
    }

    private fun distanceLabel(meters: Double): String =
        if (meters >= 1000) "${fmt1(meters / 1000.0)} км" else "${meters.roundToInt()} м"

    private fun fmt1(v: Double): String = "%.1f".format(v).replace('.', ',')

    private companion object {
        /** Lattice resolution of the comfort field. */
        const val FIELD_N = 11

        /** How far the membrane's centre travels at full acceleration, in radii. */
        const val FIELD_PULL = 0.46f

        /** How hard the rim holds: 1 pins the edges, 0 slides the whole lattice. */
        const val FIELD_EDGE_PIN = 0.62f

        /** Displacement (in radii) at which a line reaches full brightness. */
        const val FIELD_LOAD_SCALE = 0.28f
        const val FIELD_BASE_ALPHA = 0.17f
        const val FIELD_LOAD_ALPHA = 0.5f

        const val EVENT_LIFETIME = 70.0

        /** Minimum altitude span the strip may display, meters. */
        const val MIN_ELEVATION_SPAN_M = 40f
    }
}
