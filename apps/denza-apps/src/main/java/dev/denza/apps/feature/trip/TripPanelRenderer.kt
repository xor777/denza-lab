package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The single trip panel screen.
 *
 * Everything moves from real sensors: the left slot is the hanging mirror toy
 * ([MirrorToyRenderer]) swung by the cabin's apparent gravity and made queasy by
 * real manoeuvres; the compass tape follows the GNSS bearing with an offline sun
 * marker; the centre is the whole trip as one journey thread
 * ([JourneyThreadDrawer]); the right column holds the remaining-route/elapsed,
 * altitude, climb and daylight figures.
 */
class TripPanelRenderer : BaseTripRenderer() {

    private val clip = Path()
    private val pointerPath = Path()

    private val toy = MirrorToyRenderer()
    private val thread = JourneyThreadDrawer()

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
        toy.draw(
            canvas, engine, frameTimeSec, dtSec,
            cx = vx(TOY_CX), cy = vy(TOY_CY), s = vs(TOY_SCALE), unit = vs(1f), panelHeight = h,
        )
        drawCompass(canvas, engine)
        thread.draw(
            canvas, engine, frameTimeSec, dtSec,
            left = vx(THREAD_LEFT), right = vx(THREAD_RIGHT),
            top = vy(THREAD_TOP), bottom = vy(THREAD_BOTTOM), unit = vs(1f),
        )
        drawColumn(canvas, engine)
        if (showLocationHint) {
            // Right data column: its GNSS rows (Высота/Закат) are hidden without a
            // fix, so the lower part of that column is empty — and far from the
            // captionless toy in the left slot.
            label(canvas, LOCATION_HINT, vx(1436f), vy(320f), 16f, TripPalette.alpha(TripPalette.MUTED, 0.85f))
        }
    }

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
        pointerPath.rewind()
        pointerPath.moveTo(tcx, tcy + vs(20f))
        pointerPath.lineTo(tcx - vs(6f), tcy + vs(31f))
        pointerPath.lineTo(tcx + vs(6f), tcy + vs(31f))
        pointerPath.close()
        canvas.drawPath(pointerPath, fill)
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
        // Toy placement: sized so the settled (spring-sagged) figure fits the
        // 360-tall slot with the mirror at the top and the feet just above the
        // bottom — the measured equilibrium sag is ~0.16 units, so the feet
        // hang at cy + ~0.78 * scale plus the foot flick.
        const val TOY_CX = 176f
        const val TOY_CY = 235f
        const val TOY_SCALE = 146f

        // The journey thread's rect between the toy slot and the data column.
        const val THREAD_LEFT = 400f
        const val THREAD_RIGHT = 1356f
        const val THREAD_TOP = 120f
        const val THREAD_BOTTOM = 300f
    }
}
