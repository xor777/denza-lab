package dev.denza.apps.feature.cluster.dashboard

import android.graphics.Canvas
import android.graphics.Paint
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.design.instrument.EnergyScale
import dev.denza.apps.design.instrument.InstrumentCanvas
import dev.denza.apps.feature.vehicle.ConsumptionLog
import dev.denza.apps.feature.vehicle.EngineLamp
import dev.denza.apps.feature.vehicle.LampState
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The instrument dashboard the driver sees, drawn as islands over the vehicle's own graphics.
 *
 * Three things about this renderer are deliberate and worth knowing before changing it.
 *
 * It paints no background. The cluster is not ours: everything outside a block stays the car's, and
 * each island gets only enough of a scrim under it to stay legible.
 *
 * It shows what the car does not. State of charge and remaining range are already on the stock
 * cluster a few centimetres away, so drawing them here would spend the best real estate on a
 * duplicate. The pack's voltage, its cell spread, its insulation and its health are nowhere else.
 *
 * It reports exceptions rather than inventories. The eight fluid lamps become one line that is
 * silent while they are healthy; their names live on the engine page, where there is room to read.
 */
internal class ClusterDashboardRenderer : InstrumentCanvas() {

    private var figure = WIDE_FIGURE
    private var secondary = WIDE_SECONDARY
    private var label = WIDE_LABEL
    private var caption = WIDE_CAPTION
    private var tickText = WIDE_TICK

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        layout: ClusterDashboardLayout,
        telemetry: VehicleTelemetry,
    ) {
        if (!layout.supported) return
        val compact = layout.compact
        if (compact) {
            setSize(width, height, COMPACT_W, COMPACT_H)
            figure = COMPACT_FIGURE
            secondary = COMPACT_SECONDARY
            label = COMPACT_LABEL
            caption = COMPACT_CAPTION
            tickText = COMPACT_TICK
        } else {
            setSize(width, height, WIDE_W, WIDE_H)
            figure = WIDE_FIGURE
            secondary = WIDE_SECONDARY
            label = WIDE_LABEL
            caption = WIDE_CAPTION
            tickText = WIDE_TICK
        }

        if (telemetry.access != VehicleAccess.READY) {
            unavailable(canvas, layout, telemetry)
            return
        }

        energyGauge(canvas, layout, telemetry)
        electric(canvas, layout, telemetry, compact)
        engine(canvas, layout, telemetry, compact)
        layout.temperatureBlock?.let { temperatures(canvas, it, telemetry) }
        layout.lampBlock?.let { lamps(canvas, it, telemetry) }
    }

    // ---- the centre: power now, and what it cost over the road behind

    private fun energyGauge(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        t: VehicleTelemetry,
    ) {
        val cx = layout.gaugeCentreX * w
        val cy = layout.gaugeCentreY * h
        val radius = layout.gaugeRadius * h

        scrim(canvas, cx, cy, radius * 1.28f, radius * 1.22f, SCRIM_CENTRE)

        arc(canvas, cx, cy, radius, ARC_FROM, ARC_TO, DenzaPalette.TRACK, ARC_WIDTH)

        EnergyScale.DISCHARGE_TICKS_KW.forEach { kilowatts ->
            val degrees = EnergyScale.angleDegrees(kilowatts, TOP_DEGREES, SIDE_SWEEP)
            tick(canvas, cx, cy, radius, degrees, TICK_LENGTH, DenzaPalette.TRACK_MARK)
        }
        EnergyScale.REGEN_TICKS_KW.forEach { kilowatts ->
            val degrees = EnergyScale.angleDegrees(-kilowatts, TOP_DEGREES, SIDE_SWEEP)
            tick(canvas, cx, cy, radius, degrees, TICK_LENGTH, DenzaPalette.returned(0.6f))
        }
        tick(canvas, cx, cy, radius, TOP_DEGREES, TICK_LENGTH, DenzaPalette.MUTED_DEEP)

        val (zeroX, zeroY) = onArc(cx, cy, radius, TOP_DEGREES)
        value(canvas, "0", zeroX, zeroY - vs(TICK_LENGTH + 10f), tickText, DenzaPalette.MUTED_DEEP, Paint.Align.CENTER)

        val flow = flowKw(t)
        if (flow != null) {
            val degrees = EnergyScale.angleDegrees(flow.toFloat(), TOP_DEGREES, SIDE_SWEEP)
            val regenerating = EnergyScale.isRegenerating(flow.toFloat())
            val color = if (regenerating) DenzaPalette.RETURN else DenzaPalette.INK
            arc(canvas, cx, cy, radius, TOP_DEGREES, degrees, color, ARC_WIDTH)
            if (EnergyScale.sweepFraction(flow.toFloat()) > 0f) {
                val (capX, capY) = onArc(cx, cy, radius, degrees)
                dot(canvas, capX, capY, ARC_WIDTH * 0.78f, DenzaPalette.DATA_PEAK)
            }
        }

        // One average, drawn as a line inside the chart and stated in words under it.
        val average = ClusterReadout.averageConsumption(t.consumption)
        val chartHalf = radius * CHART_HALF_WIDTH
        val zeroLineY = cy - radius * CHART_ZERO_ABOVE
        val chartHeight = radius * CHART_HEIGHT
        val chartTop = zeroLineY - chartHeight * dev.denza.apps.design.instrument.ChartScale.ABOVE_ZERO_SHARE
        consumptionChart(
            canvas,
            cx - chartHalf,
            cx + chartHalf,
            chartTop,
            chartTop + chartHeight,
            t.consumption,
            BAR_WIDTH,
            average,
            DenzaPalette.accent(0.34f),
        )

        val figureBaseline = cy - radius * FIGURE_ABOVE
        val reading = flow?.let { ClusterReadout.whole(it) } ?: ClusterReadout.DASH
        val unitWidth = labelWidth(UNIT_KW, caption)
        val readingWidth = valueWidth(reading, figure)
        val left = cx - (readingWidth + vs(8f) + unitWidth) / 2f
        value(canvas, reading, left, figureBaseline, figure, DenzaPalette.INK)
        label(
            canvas,
            UNIT_KW,
            left + readingWidth + vs(8f),
            figureBaseline,
            caption,
            DenzaPalette.MUTED,
        )

        val distance = ClusterReadout.chartDistanceKm(t.consumption, ConsumptionLog.DEFAULT_BUCKET_KM)
        val averageText = if (average == null) {
            CHART_EMPTY
        } else {
            "${ClusterReadout.fmt(average, 1)} $AVERAGE_OVER ${ClusterReadout.fmt(distance, 1)} км"
        }
        label(
            canvas,
            averageText,
            cx,
            cy + radius * AVERAGE_BELOW,
            caption,
            DenzaPalette.MUTED_DEEP,
            Paint.Align.CENTER,
        )
    }

    /** What the pack is doing: charging reads as energy arriving, not as a load of its own. */
    private fun flowKw(t: VehicleTelemetry): Double? {
        if (t.charging) {
            val charge = t[VehicleSignal.CHARGE_KW] ?: return t.loadKw
            return -kotlin.math.abs(charge)
        }
        return t.loadKw
    }

    // ---- left: the pack, in the terms the car itself never shows

    private fun electric(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        t: VehicleTelemetry,
        compact: Boolean,
    ) {
        val box = layout.electricBlock
        val left = box.left * w
        val right = box.right * w
        val top = box.top * h
        scrimBlock(canvas, box)

        var y = top + vs(label)
        label(canvas, TITLE_ELECTRIC, left, y, label, DenzaPalette.MUTED_DEEP)

        y += vs(figure) * 0.98f
        val volts = t[VehicleSignal.PACK_VOLT]
        val voltWidth = valueWidth(ClusterReadout.whole(volts), figure)
        value(canvas, ClusterReadout.whole(volts), left, y, figure, DenzaPalette.INK)
        label(canvas, UNIT_VOLT, left + voltWidth + vs(8f), y, caption, DenzaPalette.MUTED)

        if (!compact) {
            val spread = t.cellSpreadMv
            val spreadText = spread?.let { ClusterReadout.whole(it) } ?: ClusterReadout.DASH
            val unitWidth = labelWidth(SPREAD_UNIT, caption)
            val spreadWidth = valueWidth(spreadText, secondary)
            val spreadLeft = right - unitWidth - spreadWidth - vs(6f)
            value(canvas, spreadText, spreadLeft, y, secondary, spreadColor(spread))
            label(canvas, SPREAD_UNIT, spreadLeft + spreadWidth + vs(6f), y, caption, DenzaPalette.MUTED_DEEP)
        }

        y += vs(caption) * 1.5f
        track(canvas, left, right, y, TRACK_HEIGHT, ClusterReadout.voltFraction(volts), DenzaPalette.INK)

        y += vs(caption) * 2.0f
        label(canvas, packLine(t, compact), left, y, caption, DenzaPalette.MUTED_DEEP)
    }

    private fun packLine(t: VehicleTelemetry, compact: Boolean): String {
        val parts = mutableListOf<String>()
        if (compact) {
            t.cellSpreadMv?.let { parts += "разброс ${ClusterReadout.whole(it)} мВ" }
        }
        t[VehicleSignal.SOH_PERCENT]?.let { parts += "ресурс ${ClusterReadout.whole(it)} %" }
        t.insulationMohm?.let { parts += "изоляция ${ClusterReadout.fmt(it, 1)} МОм" }
        t[VehicleSignal.RAIL_12V]?.let { parts += "борт ${ClusterReadout.fmt(it, 1)} В" }
        return if (parts.isEmpty()) NO_PACK_DETAIL else parts.joinToString(" · ")
    }

    // ---- right: the combustion half, which is normally asleep

    private fun engine(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        t: VehicleTelemetry,
        compact: Boolean,
    ) {
        val box = layout.engineBlock
        val left = box.left * w
        val right = box.right * w
        val top = box.top * h
        scrimBlock(canvas, box)

        var y = top + vs(label)
        label(canvas, TITLE_ENGINE, right, y, label, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT)

        y += vs(figure) * 0.98f
        val rpm = t.engineRpm
        val unitWidth = labelWidth(UNIT_RPM, caption)
        val rpmText = ClusterReadout.whole(rpm)
        val rpmWidth = valueWidth(rpmText, figure)
        val rpmLeft = right - unitWidth - rpmWidth - vs(8f)
        val awake = t.engineRunning == true
        value(canvas, rpmText, rpmLeft, y, figure, if (awake) DenzaPalette.INK else DenzaPalette.ink(0.55f))
        label(canvas, UNIT_RPM, rpmLeft + rpmWidth + vs(8f), y, caption, DenzaPalette.MUTED)

        val generation = t.generationKw
        if (generation != null && t.generating) {
            val text = ClusterReadout.whole(generation)
            val textWidth = valueWidth(text, secondary)
            value(canvas, text, left, y, secondary, DenzaPalette.RETURN_INK)
            label(canvas, GENERATION_UNIT, left + textWidth + vs(6f), y, caption, DenzaPalette.MUTED_DEEP)
        }

        y += vs(caption) * 1.5f
        track(canvas, left, right, y, TRACK_HEIGHT, ClusterReadout.rpmFraction(rpm), DenzaPalette.ink(0.32f))
        // Laid over the revolutions on the same line, so the track is left as it was drawn.
        ClusterReadout.generationFraction(generation.takeIf { t.generating })?.let { fraction ->
            track(canvas, left, right, y, TRACK_HEIGHT, fraction, DenzaPalette.RETURN, trackColor = null)
        }

        y += vs(caption) * 2.0f
        if (compact) {
            label(canvas, lampLine(t), right, y, caption, lampColor(t), Paint.Align.RIGHT)
        } else {
            label(canvas, engineLine(t), right, y, caption, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT)
        }
    }

    private fun engineLine(t: VehicleTelemetry): String = when {
        t.generating -> "работает · заряжает батарею"
        t.engineRunning == true -> "работает"
        t.engineRunning == false -> "заглушен"
        else -> NO_ENGINE_ANSWER
    }

    // ---- the two reveals: temperatures on the left, fluids on the right

    private fun temperatures(canvas: Canvas, box: DashboardBox, t: VehicleTelemetry) {
        val left = box.left * w
        val top = box.top * h
        scrimBlock(canvas, box)

        var y = top + vs(label)
        label(canvas, TITLE_THERMAL, left, y, label, DenzaPalette.MUTED_DEEP)

        y += vs(secondary) * 1.25f
        val pack = t[VehicleSignal.PACK_TEMP_AVG]
        var x = left
        x += degrees(canvas, x, y, pack, PACK_WORD, ClusterReadout.PACK_BAND_HIGH_C)
        degrees(canvas, x + vs(18f), y, t[VehicleSignal.INVERTER_C], INVERTER_WORD, ClusterReadout.INVERTER_WATCH_C)

        y += vs(secondary) * 1.35f
        val motors = t.motorTemps.filterNotNull()
        val motorText = if (motors.isEmpty()) {
            ClusterReadout.DASH
        } else {
            motors.joinToString(" · ") { ClusterReadout.whole(it) } + DEGREE
        }
        val motorWidth = valueWidth(motorText, secondary)
        value(canvas, motorText, left, y, secondary, thermalColor(t.hottestMotorC, ClusterReadout.DRIVE_BAND_HIGH_C))
        label(canvas, MOTOR_WORD, left + motorWidth + vs(7f), y, caption, DenzaPalette.MUTED_DEEP)
    }

    /** One temperature with its name after it. Returns how wide the pair came out. */
    private fun degrees(
        canvas: Canvas,
        x: Float,
        y: Float,
        celsius: Double?,
        word: String,
        bandHigh: Double,
    ): Float {
        val text = if (celsius == null) ClusterReadout.DASH else ClusterReadout.whole(celsius) + DEGREE
        val textWidth = valueWidth(text, secondary)
        value(canvas, text, x, y, secondary, thermalColor(celsius, bandHigh))
        label(canvas, word, x + textWidth + vs(7f), y, caption, DenzaPalette.MUTED_DEEP)
        return textWidth + vs(7f) + labelWidth(word, caption)
    }

    private fun lamps(canvas: Canvas, box: DashboardBox, t: VehicleTelemetry) {
        val right = box.right * w
        val top = box.top * h
        scrimBlock(canvas, box)

        var y = top + vs(label)
        label(canvas, TITLE_FLUIDS, right, y, label, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT)

        y += vs(secondary) * 0.9f
        val step = vs(LAMP_STEP)
        EngineLamp.entries.forEachIndexed { index, lamp ->
            val column = index % LAMP_COLUMNS
            val row = index / LAMP_COLUMNS
            val x = right - step * (LAMP_COLUMNS - 1 - column)
            val cy = y + step * row
            when (t.lamp(lamp)) {
                LampState.ALERT -> dot(canvas, x, cy, LAMP_RADIUS, DenzaPalette.DANGER)
                LampState.OK -> dot(canvas, x, cy, LAMP_RADIUS, DenzaPalette.ink(0.55f))
                LampState.UNKNOWN -> hollowDot(canvas, x, cy, LAMP_RADIUS, DenzaPalette.ink(0.35f))
            }
        }

        y += step * ((EngineLamp.entries.size + LAMP_COLUMNS - 1) / LAMP_COLUMNS) + vs(caption) * 0.6f
        label(canvas, lampLine(t), right, y, caption, lampColor(t), Paint.Align.RIGHT)
    }

    private fun lampLine(t: VehicleTelemetry): String = ClusterReadout.lampLine(
        t.lampAlerts.map(EngineLamp::label),
        EngineLamp.entries.count { t.lamp(it) != LampState.UNKNOWN },
    )

    private fun lampColor(t: VehicleTelemetry): Int =
        if (t.lampAlerts.isEmpty()) DenzaPalette.MUTED_DEEP else DenzaPalette.DANGER

    // ---- states where there is nothing to draw

    private fun unavailable(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        t: VehicleTelemetry,
    ) {
        val cx = layout.gaugeCentreX * w
        val cy = layout.gaugeCentreY * h - layout.gaugeRadius * h * 0.3f
        scrim(canvas, cx, cy, w * 0.22f, h * 0.16f, SCRIM_BLOCK)
        val word = if (t.access == VehicleAccess.STARTING) READING else NO_DATA
        label(canvas, word, cx, cy, secondary, DenzaPalette.MUTED, Paint.Align.CENTER)
        if (t.message.isNotEmpty()) {
            label(
                canvas,
                t.message,
                cx,
                cy + vs(caption) * 1.7f,
                caption,
                DenzaPalette.MUTED_DEEP,
                Paint.Align.CENTER,
            )
        }
    }

    private fun scrimBlock(canvas: Canvas, box: DashboardBox) {
        val cx = (box.left + box.right) / 2f * w
        val cy = (box.top + box.bottom) / 2f * h
        val radiusX = (box.right - box.left) * w * 0.72f
        val radiusY = (box.bottom - box.top) * h * 0.95f
        scrim(canvas, cx, cy, radiusX, radiusY, SCRIM_BLOCK)
    }

    private fun thermalColor(celsius: Double?, bandHigh: Double): Int =
        when (ClusterReadout.thermalState(celsius, bandHigh, ClusterReadout.PACK_BAND_LOW_C)) {
            ClusterReadout.Thermal.UNKNOWN -> DenzaPalette.ink(0.4f)
            ClusterReadout.Thermal.COLD -> DenzaPalette.RETURN_INK
            ClusterReadout.Thermal.NORMAL -> DenzaPalette.INK
            ClusterReadout.Thermal.WARM -> DenzaPalette.WARNING
            ClusterReadout.Thermal.HOT -> DenzaPalette.DANGER
        }

    private fun spreadColor(millivolts: Double?): Int =
        when (ClusterReadout.spreadState(millivolts)) {
            ClusterReadout.Thermal.UNKNOWN -> DenzaPalette.ink(0.4f)
            ClusterReadout.Thermal.WARM -> DenzaPalette.WARNING
            ClusterReadout.Thermal.HOT -> DenzaPalette.DANGER
            else -> DenzaPalette.INK
        }

    private companion object {
        /**
         * The two virtual spaces, matching the design's own artboards one to one.
         *
         * At the verified 2560x720 panel the wide space scales by about 1.7 on both axes, which is
         * the same figure the projected-app placements use - so a size chosen in the design lands
         * at the size it was drawn at.
         */
        const val WIDE_W = 1506f
        const val WIDE_H = 424f
        const val COMPACT_W = 602f
        const val COMPACT_H = 308f

        const val WIDE_FIGURE = 52f
        const val WIDE_SECONDARY = 22f
        const val WIDE_LABEL = 14f
        const val WIDE_CAPTION = 15f
        const val WIDE_TICK = 13f

        const val COMPACT_FIGURE = 34f
        const val COMPACT_SECONDARY = 18f
        const val COMPACT_LABEL = 12f
        const val COMPACT_CAPTION = 13f
        const val COMPACT_TICK = 12f

        /** The dial runs from below the horizon on one side to below it on the other. */
        const val ARC_FROM = 200f
        const val ARC_TO = -20f
        const val TOP_DEGREES = 90f
        const val SIDE_SWEEP = 110f
        const val ARC_WIDTH = 10f
        const val TICK_LENGTH = 9f

        /** The chart nested inside the dial, in shares of its radius. */
        const val CHART_HALF_WIDTH = 0.80f
        const val CHART_ZERO_ABOVE = 0.06f
        const val CHART_HEIGHT = 0.52f
        const val BAR_WIDTH = 9f
        const val FIGURE_ABOVE = 0.60f
        const val AVERAGE_BELOW = 0.30f

        const val TRACK_HEIGHT = 7f
        const val LAMP_STEP = 21f
        const val LAMP_RADIUS = 5.5f
        const val LAMP_COLUMNS = 4

        /** Enough darkness to read against live vehicle graphics, not enough to blank them. */
        const val SCRIM_CENTRE = 0.78f
        const val SCRIM_BLOCK = 0.7f

        const val TITLE_ELECTRIC = "ЭЛЕКТРИКА"
        const val TITLE_ENGINE = "ДВИГАТЕЛЬ"
        const val TITLE_THERMAL = "ТЕМПЕРАТУРЫ"
        const val TITLE_FLUIDS = "ЖИДКОСТИ"
        const val UNIT_KW = "кВт"
        const val UNIT_VOLT = "В"
        const val UNIT_RPM = "об/мин"
        const val SPREAD_UNIT = "мВ разброс"
        const val GENERATION_UNIT = "кВт в батарею"
        const val AVERAGE_OVER = "средний за"
        const val CHART_EMPTY = "расход считается"
        const val PACK_WORD = "батарея"
        const val INVERTER_WORD = "инвертор"
        const val MOTOR_WORD = "моторы"
        const val DEGREE = "°"
        const val NO_PACK_DETAIL = "пакет не ответил"
        const val NO_ENGINE_ANSWER = "двигатель не ответил"
        const val READING = "Читаю машину"
        const val NO_DATA = "Нет данных"
    }
}
