package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The single trip panel screen.
 *
 * At normal widths the left of the panel is the spectrum analyser
 * ([SpectrumRenderer]) and the right column holds the trip figures. In the
 * narrow split layout the analyser spans the full width and those figures stack
 * underneath it so neither half is horizontally compressed.
 *
 * The panel's original sensor instruments — the hanging mirror toy
 * ([MirrorToyRenderer]), the compass tape and the journey thread
 * ([JourneyThreadDrawer]) — occupied that same space and are now hidden behind
 * [TripPanelFlag.LEGACY_INSTRUMENTS]. Their code and the engine state driving
 * them are untouched; only the draw calls are gated.
 */
class TripPanelRenderer : BaseTripRenderer() {

    /**
     * The column's own two faces, because the shared helpers have neither.
     *
     * [PanelCanvas.value] draws monospace, which is right for a figure that ticks every frame and
     * wrong here: the board sets these in Roboto Light, and a proportional face is what makes
     * "0:00" and "209" read as typography rather than as a readout. The label is the board's
     * medium weight with its 1.6-over-15 tracking expressed as an em value, which is what a text
     * paint can actually do - the first attempt padded the string with thin spaces.
     */
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val capsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = CAPS_TRACKING
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    private val clip = Path()
    private val pointerPath = Path()

    private val toy = MirrorToyRenderer()
    private val thread = JourneyThreadDrawer()
    private val spectrumRenderer = SpectrumRenderer()

    override fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
        narrowLayout: Boolean,
    ) {
        if (narrowLayout) {
            drawNarrow(
                canvas = canvas,
                width = w,
                height = h,
                engine = engine,
                spectrum = spectrum,
                nowPlaying = nowPlaying,
                frameTimeSec = frameTimeSec,
                dtSec = dtSec,
                showLocationHint = showLocationHint,
            )
            return
        }
        // Two spaces, because there are two layouts. The legacy instruments were laid out in the
        // old 1850x360 band and are kept working in it; the analyser and its figures are laid out
        // in the board's own 1184x416, so every number below is a number that can be read straight
        // off Main.dc.html instead of converted in somebody's head.
        if (TripPanelFlag.LEGACY_INSTRUMENTS) {
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
            drawColumnLegacy(canvas, engine)
        } else {
            setSize(w, h, WIDE_VIRTUAL_W, WIDE_VIRTUAL_H)
            spectrumRenderer.draw(
                canvas, spectrum, nowPlaying, frameTimeSec, dtSec,
                left = vx(SPECTRUM_LEFT), right = vx(SPECTRUM_RIGHT),
                top = vy(SPECTRUM_TOP), bottom = vy(SPECTRUM_BOTTOM), unit = vs(1f),
            )
            drawColumn(canvas, engine)
        }
        if (showLocationHint) {
            // The column's GNSS blocks are absent without a fix, so its lower half is empty and
            // the hint has somewhere to go that collides with nothing.
            label(
                canvas, LOCATION_HINT, vx(COLUMN_X), vy(HINT_Y), HINT_SIZE,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }
    }

    private fun drawNarrow(
        canvas: Canvas,
        width: Float,
        height: Float,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
    ) {
        setSize(width, height, NARROW_VIRTUAL_W, NARROW_VIRTUAL_H)
        spectrumRenderer.draw(
            canvas, spectrum, nowPlaying, frameTimeSec, dtSec,
            left = vx(NARROW_SPECTRUM_LEFT),
            right = vx(NARROW_SPECTRUM_RIGHT),
            top = vy(NARROW_SPECTRUM_TOP),
            bottom = vy(NARROW_SPECTRUM_BOTTOM),
            unit = vs(1f),
        )

        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.14f)
        stroke.strokeWidth = vs(1f)
        canvas.drawLine(
            vx(NARROW_SPECTRUM_LEFT),
            vy(NARROW_DIVIDER_Y),
            vx(NARROW_SPECTRUM_RIGHT),
            vy(NARROW_DIVIDER_Y),
            stroke,
        )
        drawNarrowColumn(canvas, engine)
        if (showLocationHint) {
            label(
                canvas,
                LOCATION_HINT,
                vx(0f),
                vy(618f),
                16f,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }
    }

    private fun drawNarrowColumn(canvas: Canvas, engine: TripEngine) {
        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            row(
                canvas,
                0f,
                318f,
                "Осталось · навигация",
                parts.joinToString(" · ").ifBlank { "—" },
                TripPalette.INK,
            )
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            row(
                canvas,
                0f,
                318f,
                "В пути",
                "$timePart · ${distanceLabel(engine.distanceMeters())}",
                TripPalette.INK,
            )
        }

        val hasAltitude = engine.hasAltitude()
        val altitude = if (hasAltitude) "${engine.smoothedAltitude().roundToInt()} м" else "—"
        val altitudeAndRate = if (hasAltitude) {
            val rate = engine.variometer()
            val arrow = if (rate >= 0) "↗ +" else "↘ −"
            "$altitude · $arrow${fmt1(abs(rate))} м/с"
        } else {
            altitude
        }
        row(canvas, 0f, 394f, "Высота · вариометр", altitudeAndRate, TripPalette.INK)
        row(
            canvas,
            0f,
            470f,
            "Набор за поездку",
            "+${engine.tripClimbMeters().roundToInt()} м",
            TripPalette.INK,
        )

        val sun = engine.sunInfo()
        if (sun.hasPosition && sun.nextEventLabel.isNotEmpty()) {
            val event = if (sun.nextIsSunset) "Закат" else "Рассвет"
            val countdown = if (sun.countdownSeconds >= 0) {
                "${sun.nextEventLabel} · через ${clockHm(sun.countdownSeconds)}"
            } else {
                sun.nextEventLabel
            }
            row(canvas, 0f, 546f, event, countdown, TripPalette.AMBER)
        } else {
            row(canvas, 0f, 546f, "Рассвет · закат", "—", TripPalette.AMBER)
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
        fill.color = TripPalette.alpha(TripPalette.LIVE, if (hasHeading) dim else 0.4f)
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

    /**
     * The figures beside the analyser, as `Main.dc.html` draws them.
     *
     * Three blocks, not four. The board hangs them apart down the full height with a hairline
     * above the second and the third, gives each a small tracked capital label and a large light
     * figure, and lets the unit sit beside the figure rather than under it. The version this
     * replaces had four blocks at four fixed heights in 16 and 27 point type - two sizes that are
     * on no ladder in this app - and packed them into the top two thirds with nothing below.
     *
     * The trip's total climb was the fourth block and is gone. The variometer beside the altitude
     * already says which way the road is going, which is the part that is worth a glance while
     * driving; a running total of metres climbed is a number for afterwards.
     */
    private fun drawColumn(canvas: Canvas, engine: TripEngine) {
        val x = vx(COLUMN_X)

        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            block(canvas, x, BLOCK_TOP_1, "ОСТАЛОСЬ", parts.joinToString(" · "), "")
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            block(canvas, x, BLOCK_TOP_1, "В ПУТИ", timePart, distanceLabel(engine.distanceMeters()))
        }

        if (engine.hasAltitude()) {
            hairline(canvas, x, vy(BLOCK_TOP_2), vx(COLUMN_RIGHT), vy(BLOCK_TOP_2))
            block(
                canvas, x, BLOCK_TOP_2 + RULE_GAP, "ВЫСОТА",
                "${engine.smoothedAltitude().roundToInt()}", "м",
            )
            drawVariometer(canvas, engine.variometer())
        }

        val sun = engine.sunInfo()
        if (sun.hasPosition && sun.nextEventLabel.isNotEmpty()) {
            hairline(canvas, x, vy(BLOCK_TOP_3), vx(COLUMN_RIGHT), vy(BLOCK_TOP_3))
            drawSun(canvas, x, sun.nextIsSunset)
            val textX = x + vs(SUN_ICON + SUN_GAP)
            val width = figure(
                canvas, sun.nextEventLabel, textX, vy(SUN_BASELINE), SUN_TIME, TripPalette.INK,
            )
            unit(
                canvas,
                if (sun.nextIsSunset) "закат" else "рассвет",
                textX + width + vs(SUN_GAP),
                vy(SUN_BASELINE),
                UNIT_SIZE_SMALL,
            )
        }
    }

    /** A tracked capital label with a large light figure and its unit beside it. */
    private fun block(canvas: Canvas, x: Float, top: Float, name: String, figure: String, unit: String) {
        caps(canvas, name, x, vy(top + LABEL_BASELINE))
        val width = figure(canvas, figure, x, vy(top + FIGURE_BASELINE), FIGURE_SIZE, TripPalette.INK)
        if (unit.isNotEmpty()) {
            unit(canvas, unit, x + width + vs(UNIT_GAP), vy(top + FIGURE_BASELINE), UNIT_SIZE)
        }
    }

    private fun caps(canvas: Canvas, text: String, x: Float, y: Float) {
        capsPaint.textSize = vs(LABEL_SIZE)
        capsPaint.color = TripPalette.MUTED
        canvas.drawText(text, x, y, capsPaint)
    }

    /** Draws a figure and returns how wide it came out, so its unit can sit against it. */
    private fun figure(canvas: Canvas, text: String, x: Float, y: Float, sizeV: Float, colour: Int): Float {
        figurePaint.textSize = vs(sizeV)
        figurePaint.color = colour
        canvas.drawText(text, x, y, figurePaint)
        return figurePaint.measureText(text)
    }

    private fun unit(canvas: Canvas, text: String, x: Float, y: Float, sizeV: Float) {
        unitPaint.textSize = vs(sizeV)
        unitPaint.color = TripPalette.MUTED
        canvas.drawText(text, x, y, unitPaint)
    }

    /** Which way the road is going, at the right edge of the altitude block. */
    private fun drawVariometer(canvas: Canvas, metresPerSecond: Double) {
        val up = metresPerSecond >= 0
        val colour = if (up) TripPalette.LIVE else TripPalette.AMBER
        val text = fmt1(abs(metresPerSecond))
        val right = vx(COLUMN_RIGHT)
        val baseline = vy(BLOCK_TOP_2 + RULE_GAP + FIGURE_BASELINE)
        unitPaint.textSize = vs(ARROW_TEXT)
        unitPaint.color = colour
        unitPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, right, baseline, unitPaint)
        val textWidth = unitPaint.measureText(text)
        unitPaint.textAlign = Paint.Align.LEFT
        val ax = right - textWidth - vs(SUN_GAP) - vs(ARROW_SIZE) / 2f
        val half = vs(ARROW_SIZE) / 2f
        stroke.color = colour
        stroke.strokeWidth = vs(ARROW_WEIGHT)
        val tip = if (up) baseline - half * 2f else baseline
        val tail = if (up) baseline else baseline - half * 2f
        canvas.drawLine(ax, tail, ax, tip, stroke)
        val wing = if (up) tip + half else tip - half
        canvas.drawLine(ax - half * 0.6f, wing, ax, tip, stroke)
        canvas.drawLine(ax + half * 0.6f, wing, ax, tip, stroke)
    }

    /** The board's sun: a disc with five rays and the horizon under it. */
    private fun drawSun(canvas: Canvas, x: Float, sunset: Boolean) {
        val cx = x + vs(SUN_ICON) / 2f
        val cy = vy(BLOCK_TOP_3 + RULE_GAP + SUN_ICON / 2f)
        val r = vs(SUN_ICON) * 0.17f
        stroke.color = if (sunset) TripPalette.AMBER else TripPalette.LIVE
        stroke.strokeWidth = vs(SUN_WEIGHT)
        canvas.drawCircle(cx, cy, r, stroke)
        val ray = vs(SUN_ICON) * 0.09f
        canvas.drawLine(cx, cy - r - ray * 1.6f, cx, cy - r - ray * 0.4f, stroke)
        val d = (r + ray) * 0.72f
        canvas.drawLine(cx - d - ray * 0.4f, cy - d - ray * 0.4f, cx - d, cy - d, stroke)
        canvas.drawLine(cx + d + ray * 0.4f, cy - d - ray * 0.4f, cx + d, cy - d, stroke)
        canvas.drawLine(x, cy + r + ray * 1.4f, x + vs(SUN_ICON), cy + r + ray * 1.4f, stroke)
    }

    private fun drawColumnLegacy(canvas: Canvas, engine: TripEngine) {
        // Faint dividers (thin lines, not frames). The left divider only made
        // sense as the mirror toy's slot edge; the analyser spans that space.
        stroke.color = TripPalette.alpha(TripPalette.MUTED, 0.14f)
        stroke.strokeWidth = vs(1f)
        if (TripPanelFlag.LEGACY_INSTRUMENTS) {
            canvas.drawLine(vx(352f), vy(24f), vx(352f), vy(324f), stroke)
        }
        canvas.drawLine(vx(1398f), vy(24f), vx(1398f), vy(324f), stroke)

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
                rx + vs(150f), vy(158f), 20f, if (up) TripPalette.LIVE else TripPalette.AMBER,
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

    companion object {
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

        // The analyser takes the whole area the three instruments used to share,
        // stopping short of the data column's divider at 1398.
        //
        // Flush against the panel's own left edge: the view is laid out with
        // fillMaxWidth() in the same column as the feature cards, so x=0 is
        // already the card edge and any inset here reads as the analyser sitting
        // crooked against everything above it.
        /**
         * The wide panel's own space: the board's, one unit to one of its pixels.
         *
         * The base class declares 1850x360, a 5.1:1 band from when this strip was thin, and
         * [PanelCanvas] scales x and y independently - so drawing that layout into the board's
         * 2.85:1 region stretched every stroke in it vertically by about two. Rather than choose
         * between a distorted panel and a box the board does not draw, the panel is laid out in
         * the board's own numbers and asks for a box of the board's own shape.
         */
        const val WIDE_VIRTUAL_W = 1184f
        const val WIDE_VIRTUAL_H = 416f

        // The analyser takes the left, the figures the right, with the board's 30 between them.
        const val SPECTRUM_LEFT = 0f
        const val SPECTRUM_RIGHT = 834f
        const val SPECTRUM_TOP = 0f
        const val SPECTRUM_BOTTOM = 416f
        const val COLUMN_X = 864f
        const val COLUMN_RIGHT = 1184f

        // Three blocks hung apart down the full height, as `justify-content: space-between`.
        const val BLOCK_TOP_1 = 0f
        const val BLOCK_TOP_2 = 177f
        const val BLOCK_TOP_3 = 368f
        const val RULE_GAP = 14f

        /** The board's `letter-spacing:1.6px` at 15 px, as the em value a paint takes. */
        const val CAPS_TRACKING = 0.107f

        const val LABEL_SIZE = 15f
        const val LABEL_BASELINE = 12f
        const val FIGURE_SIZE = 46f
        const val FIGURE_BASELINE = 58f
        const val UNIT_SIZE = 24f
        const val UNIT_SIZE_SMALL = 19f
        const val UNIT_GAP = 14f

        const val ARROW_SIZE = 20f
        const val ARROW_WEIGHT = 2.4f
        const val ARROW_TEXT = 19f

        const val SUN_ICON = 26f
        const val SUN_WEIGHT = 1.846f
        const val SUN_GAP = 12f
        const val SUN_TIME = 34f
        const val SUN_BASELINE = 408f

        const val HINT_Y = 300f
        const val HINT_SIZE = 15f

        const val NARROW_VIRTUAL_W = 368f
        const val NARROW_VIRTUAL_H = 660f
        const val NARROW_SPECTRUM_LEFT = 0f
        const val NARROW_SPECTRUM_RIGHT = 368f
        const val NARROW_SPECTRUM_TOP = 20f
        const val NARROW_SPECTRUM_BOTTOM = 258f
        const val NARROW_DIVIDER_Y = 286f
    }
}
