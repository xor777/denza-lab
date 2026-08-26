package dev.denza.apps.feature.vehicle

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import dev.denza.apps.feature.panel.PanelCanvas
import dev.denza.apps.feature.panel.PanelPalette
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The engine page: the combustion half of a series-parallel hybrid.
 *
 * Three blocks — what the engine is doing, what it is generating, and the state
 * of the fluids it needs. Same two virtual layouts and the same type-scale rule
 * as the vehicle page: about 0.6 dp to the unit at full width, exactly a dp in
 * the narrow pane.
 *
 * **This page is asleep most of the time and is designed for that.** The car
 * drives on electricity by default, so the honest resting state is zero
 * revolutions, zero generation and eight quiet lamps. That state gets the same
 * care as the running one: the figures go muted rather than bright, and the page
 * says "заглушен" rather than showing a dash that would read as a broken read.
 *
 * Two numbers here are readable but not yet calibrated. The revolution scale and
 * the unit behind generation were both read as `0` with the engine stopped, so
 * the gauge ranges below are the plausible ones, not the measured ones. A single
 * sweep with the engine running fixes them; nothing else on the page depends on
 * it, and every lamp is a state rather than a scale.
 *
 * There is no coolant temperature. Three candidate feature ids were tried on six
 * devices and every one returned a sentinel — this firmware appears not to carry
 * the number, only the overheat lamp, which is why the lamp is here and the
 * gauge a driver would expect is not.
 */
internal class EnginePanelRenderer : PanelCanvas() {

    private val path = Path()

    /** Set by the view; shared with the vehicle page. */
    var icons: VehicleIcons? = null

    private var sizeLabel = WIDE_LABEL
    private var sizeTiny = WIDE_TINY
    private var sizeState = WIDE_STATE
    private var sizeFigure = WIDE_FIGURE

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        telemetry: VehicleTelemetry,
        narrowLayout: Boolean,
    ) {
        if (narrowLayout) {
            setSize(width, height, NARROW_W, NARROW_H)
            sizeLabel = NARROW_LABEL
            sizeTiny = NARROW_TINY
            sizeState = NARROW_STATE
            sizeFigure = NARROW_FIGURE
        } else {
            setSize(width, height, WIDE_W, WIDE_H)
            sizeLabel = WIDE_LABEL
            sizeTiny = WIDE_TINY
            sizeState = WIDE_STATE
            sizeFigure = WIDE_FIGURE
        }
        when (telemetry.access) {
            VehicleAccess.STARTING -> return centred(canvas, READING, "", narrowLayout)
            VehicleAccess.UNAVAILABLE -> return centred(canvas, NO_DATA, telemetry.message, narrowLayout)
            VehicleAccess.READY -> Unit
        }
        if (narrowLayout) drawNarrow(canvas, telemetry) else drawWide(canvas, telemetry)
    }

    // ---------------------------------------------------------------- layouts

    private fun drawWide(canvas: Canvas, t: VehicleTelemetry) {
        val running = t.engineRunning == true

        // ---- engine ----
        label(canvas, "Двигатель", vx(0f), vy(46f), WIDE_HEADER, muted())
        t[VehicleSignal.ENGINE_LITRES]?.let {
            label(canvas, "${fmt(it, 1)} л", vx(600f), vy(46f), sizeTiny, muted(0.6f), Paint.Align.RIGHT)
        }
        figure(canvas, vx(0f), vy(152f), t.engineRpm, "об/мин", running)
        label(canvas, engineState(t), vx(0f), vy(206f), sizeState, if (running) PanelPalette.LIVE else muted(0.7f))
        dialGauge(canvas, vx(0f), vx(600f), vy(236f), vs(24f), t.engineRpm, RPM_MAX, RPM_STEP, running)

        hairline(canvas, vx(620f), vy(24f), vx(620f), vy(312f))

        // ---- generation ----
        label(canvas, "Генерация", vx(660f), vy(46f), WIDE_HEADER, muted())
        figure(canvas, vx(660f), vy(152f), t.generationKw, "кВт", t.generating)
        icon(canvas, icons?.flow, vx(660f), vy(206f) - vs(22f), vs(26f), generationColor(t))
        label(canvas, generationState(t), vx(694f), vy(206f), sizeState, generationColor(t))
        label(canvas, inverterLine(t), vx(1140f), vy(206f), sizeState, inverterColor(t), Paint.Align.RIGHT)
        dialGauge(
            canvas, vx(660f), vx(1140f), vy(236f), vs(24f), t.generationKw, GENERATION_MAX, GENERATION_STEP,
            t.generating, ticks = GENERATION_TICKS, squareRoot = true,
        )

        hairline(canvas, vx(1160f), vy(24f), vx(1160f), vy(312f))

        // ---- fluids ----
        label(canvas, "Жидкости", vx(1200f), vy(46f), WIDE_HEADER, muted())
        EngineLamp.entries.forEachIndexed { index, lamp ->
            val x = if (index < 4) vx(1200f) else vx(1540f)
            val y = vy(112f + (index % 4) * 58f)
            lampRow(canvas, x, y, lamp, t.lamp(lamp), sizeLabel)
        }
    }

    private fun drawNarrow(canvas: Canvas, t: VehicleTelemetry) {
        val running = t.engineRunning == true

        label(canvas, "обороты", vx(0f), vy(58f), sizeLabel, muted())
        t[VehicleSignal.ENGINE_LITRES]?.let {
            label(canvas, "${fmt(it, 1)} л", vx(120f), vy(58f), sizeTiny, muted(0.55f))
        }
        figure(canvas, vx(368f), vy(64f), t.engineRpm, "об/мин", running, Paint.Align.RIGHT)
        dialGauge(canvas, vx(0f), vx(368f), vy(80f), vs(16f), t.engineRpm, RPM_MAX, RPM_STEP, running)
        label(canvas, engineState(t), vx(0f), vy(140f), sizeState, if (running) PanelPalette.LIVE else muted(0.7f))

        hairline(canvas, vx(0f), vy(160f), vx(368f), vy(160f))

        label(canvas, "генерация", vx(0f), vy(196f), sizeLabel, muted())
        figure(canvas, vx(368f), vy(202f), t.generationKw, "кВт", t.generating, Paint.Align.RIGHT)
        dialGauge(
            canvas, vx(0f), vx(368f), vy(218f), vs(16f), t.generationKw, GENERATION_MAX, GENERATION_STEP,
            t.generating, ticks = GENERATION_TICKS, squareRoot = true,
        )
        icon(canvas, icons?.flow, vx(0f), vy(278f) - vs(13f), vs(16f), generationColor(t))
        label(canvas, generationState(t), vx(21f), vy(278f), sizeState, generationColor(t))
        label(canvas, inverterLine(t), vx(368f), vy(278f), sizeState, inverterColor(t), Paint.Align.RIGHT)

        hairline(canvas, vx(0f), vy(298f), vx(368f), vy(298f))

        label(canvas, "жидкости", vx(0f), vy(322f), sizeLabel, muted())
        EngineLamp.entries.forEachIndexed { index, lamp ->
            lampRow(canvas, vx(0f), vy(354f + index * 36f), lamp, t.lamp(lamp), sizeLabel)
        }
    }

    // --------------------------------------------------------------- elements

    /**
     * A reading and its unit as one composed figure. The number goes bright only
     * when the thing behind it is actually doing something — a page full of
     * brightly lit zeroes would claim attention it has not earned.
     */
    private fun figure(
        canvas: Canvas,
        x: Float,
        baseline: Float,
        reading: Double?,
        unit: String,
        live: Boolean,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val big = sizeFigure
        val color = when {
            reading == null -> muted(0.5f)
            live -> PanelPalette.INK
            else -> muted(0.65f)
        }
        val text = reading?.roundToInt()?.toString() ?: "—"
        val unitSize = big * 0.34f
        val gap = vs(big * 0.14f)
        if (align == Paint.Align.RIGHT) {
            label(canvas, unit, x, baseline, unitSize, muted(0.6f), Paint.Align.RIGHT)
            val unitWidth = labelWidth(unit, unitSize)
            value(canvas, text, x - unitWidth - gap, baseline, big, color, Paint.Align.RIGHT, bold = true)
        } else {
            value(canvas, text, x, baseline, big, color, bold = true)
            label(canvas, unit, x + valueWidth(text, big, bold = true) + gap, baseline, unitSize, muted(0.6f))
        }
    }

    /**
     * A linear dial with a pointer. Ranges here are plausible rather than
     * measured — see the class comment — so the pointer clamps at the ends
     * instead of running off, and the end labels always say what the span is.
     */
    private fun dialGauge(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        height: Float,
        reading: Double?,
        max: Double,
        step: Double,
        live: Boolean,
        ticks: DoubleArray? = null,
        squareRoot: Boolean = false,
    ) {
        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.10f)
        canvas.drawRect(x0, y, x1, y + height, fill)

        fun position(v: Double): Float {
            val share = (v / max).coerceIn(0.0, 1.0)
            return x0 + (x1 - x0) * (if (squareRoot) sqrt(share) else share).toFloat()
        }

        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.32f)
        stroke.strokeWidth = vs(1f)
        val marks = ticks ?: DoubleArray(((max - step) / step).toInt().coerceAtLeast(0)) { step * (it + 1) }
        marks.forEach { tick ->
            canvas.drawLine(position(tick), y + height, position(tick), y + height + vs(4f), stroke)
        }
        label(canvas, "0", x0, y + height + vs(sizeTiny * 1.35f), sizeTiny, muted(0.5f))
        label(
            canvas, "${max.roundToInt()}", x1, y + height + vs(sizeTiny * 1.35f), sizeTiny,
            muted(0.5f), Paint.Align.RIGHT,
        )

        if (reading == null) return
        val color = if (live) PanelPalette.LIVE else muted(0.55f)
        fill.color = PanelPalette.alpha(color, 0.75f)
        canvas.drawRect(x0, y, position(reading), y + height, fill)
        needle(canvas, position(reading), y, height, color)
    }

    private fun needle(canvas: Canvas, x: Float, y: Float, height: Float, color: Int) {
        val wing = vs(5f)
        path.reset()
        path.moveTo(x, y + height * 0.5f)
        path.lineTo(x - wing, y - vs(5f))
        path.lineTo(x + wing, y - vs(5f))
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.drawRect(x - vs(1.1f), y, x + vs(1.1f), y + height, fill)
    }

    /**
     * One lamp: a dot and a name. A healthy lamp is mint rather than grey — this
     * is an instrument panel, and "checked, fine" is information. A lamp nothing
     * answered for is a hollow ring, which is not the same claim as fine.
     */
    private fun lampRow(canvas: Canvas, x: Float, y: Float, lamp: EngineLamp, state: LampState, textSize: Float) {
        val radius = vs(6f)
        val centreX = x + radius
        when (state) {
            LampState.ALERT -> {
                fill.color = PanelPalette.DANGER
                canvas.drawCircle(centreX, y - vs(4f), radius, fill)
                fill.color = PanelPalette.alpha(PanelPalette.DANGER, 0.22f)
                canvas.drawCircle(centreX, y - vs(4f), radius * 1.9f, fill)
            }

            LampState.OK -> {
                fill.color = PanelPalette.alpha(PanelPalette.LIVE, 0.65f)
                canvas.drawCircle(centreX, y - vs(4f), radius, fill)
            }

            LampState.UNKNOWN -> {
                stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.5f)
                stroke.strokeWidth = vs(1.5f)
                canvas.drawCircle(centreX, y - vs(4f), radius, stroke)
            }
        }
        val color = when (state) {
            LampState.ALERT -> PanelPalette.DANGER
            LampState.OK -> muted()
            LampState.UNKNOWN -> muted(0.5f)
        }
        label(canvas, lamp.label, x + radius * 3.2f, y, textSize, color)
    }

    private fun icon(canvas: Canvas, drawable: Drawable?, x: Float, top: Float, size: Float, color: Int) {
        if (drawable == null) return
        drawable.setTint(color)
        drawable.setBounds(x.roundToInt(), top.roundToInt(), (x + size).roundToInt(), (top + size).roundToInt())
        drawable.draw(canvas)
    }

    private fun centred(canvas: Canvas, title: String, detail: String, narrowLayout: Boolean) {
        val cx = w / 2f
        label(
            canvas, title, cx, h * 0.46f, if (narrowLayout) 22f else 34f,
            PanelPalette.alpha(PanelPalette.INK, 0.8f), Paint.Align.CENTER,
        )
        if (detail.isNotEmpty()) {
            label(canvas, detail, cx, h * 0.46f + vs(40f), if (narrowLayout) 16f else 24f, muted(0.75f), Paint.Align.CENTER)
        }
    }

    // ------------------------------------------------------------------ lines

    private fun engineState(t: VehicleTelemetry): String = when (t.engineRunning) {
        null -> "нет данных"
        true -> "работает"
        false -> "заглушен"
    }

    private fun generationState(t: VehicleTelemetry): String = when {
        t.generationKw == null -> "нет данных"
        t.generating -> "отдаёт в батарею"
        else -> "нет генерации"
    }

    private fun generationColor(t: VehicleTelemetry): Int =
        if (t.generating) PanelPalette.BLUE else muted(0.7f)

    /**
     * The only temperature this page can honestly show. There is no engine
     * coolant reading on this firmware, but the inverter carries the generated
     * power and answers — it climbed 26 → 32 °C across a minute of generation on
     * 2026-08-23 and fell back afterwards, so it is real thermal feedback on the
     * generation path rather than a stand-in number.
     */
    private fun inverterLine(t: VehicleTelemetry): String =
        t[VehicleSignal.INVERTER_C]?.let { "инвертор ${it.roundToInt()} °C" } ?: "инвертор —"

    private fun inverterColor(t: VehicleTelemetry): Int {
        val celsius = t[VehicleSignal.INVERTER_C] ?: return muted(0.5f)
        return when {
            celsius > INVERTER_HOT_C -> PanelPalette.DANGER
            celsius > INVERTER_WARM_C -> PanelPalette.AMBER
            else -> muted(0.7f)
        }
    }

    private fun muted(alpha: Float = 0.85f): Int = PanelPalette.alpha(PanelPalette.MUTED, alpha)

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value).replace('.', ',')

    private companion object {
        const val WIDE_W = 1850f
        const val WIDE_H = 360f
        const val NARROW_W = 368f
        const val NARROW_H = 660f

        const val WIDE_TINY = 20f
        const val WIDE_LABEL = 26f
        const val WIDE_STATE = 28f
        const val WIDE_HEADER = 30f
        const val WIDE_FIGURE = 76f

        const val NARROW_TINY = 12f
        const val NARROW_LABEL = 15f
        const val NARROW_STATE = 14f
        const val NARROW_FIGURE = 34f

        /**
         * Revolutions are real rpm, confirmed against a start/stop cycle on the
         * car: 1619 at the start peak, a 1321 generation set-point, 242 spinning
         * down. The span is the engine's, not the observed range — a generating
         * engine should visibly sit at the bottom of it.
         */
        const val RPM_MAX = 6000.0
        const val RPM_STEP = 1000.0

        /**
         * Generation is in kilowatts, confirmed by pack power mirroring it
         * exactly (`8` against `-8`, `10` against `-10`). Idle generation is
         * around 10 kW while the generator's ceiling is several times that, so
         * the dial is square-root like the vehicle page's power bar: linear
         * would leave the needle pinned near zero for the common case.
         */
        const val GENERATION_MAX = 100.0
        const val GENERATION_STEP = 0.0
        val GENERATION_TICKS = doubleArrayOf(10.0, 25.0, 50.0)

        const val INVERTER_WARM_C = 70.0
        const val INVERTER_HOT_C = 85.0

        const val READING = "Читаю данные машины…"
        const val NO_DATA = "Нет доступа к данным машины"
    }
}
