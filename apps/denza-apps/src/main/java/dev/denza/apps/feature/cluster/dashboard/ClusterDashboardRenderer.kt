package dev.denza.apps.feature.cluster.dashboard

import android.graphics.Canvas
import android.graphics.Paint
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.design.instrument.EnergyGauge
import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.design.instrument.InstrumentPen
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.EngineLamp
import dev.denza.apps.feature.vehicle.LampState
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The instrument dashboard the driver sees.
 *
 * Four things about this renderer are deliberate and worth knowing before changing it.
 *
 * **It paints its whole box black.** It did not until 2026-08-25: the first live run showed the
 * stock graphics through every gap, and a dashboard read over somebody else's needle is not a
 * dashboard. So the surface is opaque now, and the radial scrims that used to buy each island just
 * enough legibility are gone with the thing they were compensating for. The keep-outs in
 * [ClusterDashboardLayout] still matter, but for the other reason: the vehicle draws *above* this
 * window as well, so a block placed under stock graphics is hidden by them rather than covering
 * them.
 *
 * **It shows what the car does not.** State of charge and remaining range are already on the stock
 * cluster a few centimetres away, so drawing them here would spend the best real estate on a
 * duplicate. The pack's voltage, its cell spread, its insulation and its health are nowhere else.
 *
 * **It reports exceptions rather than inventories.** The fluid lamps become one line that is quiet
 * while they are healthy and names only faults or incomplete reads.
 *
 * **Density follows the room, not the placement.** A block in a corner reveal is small wherever it
 * sits, so it takes [InstrumentDensity.COMPACT] even inside the full-width layout. That is the whole
 * rule; there is no second one.
 *
 * ### Where the rows come from
 *
 * No baseline in this file is placed by hand. Each block declares a plan in [ClusterBlockPlan], and
 * [Column] turns that plan into anchors: the block's height is known before its first line is drawn,
 * so it is centred in its box rather than hung from the top, and a unit test can add the plan up
 * against the box instead of the car doing it for us.
 *
 * That is a direct answer to what an audit found on the design boards - four sibling layouts whose
 * first label started at four different heights, so the headers jumped when you turned the page -
 * and the same drift had already started here, with gaps of 6, 7, 8 and 18 chosen per method.
 */
internal class ClusterDashboardRenderer {

    private val pen = InstrumentPen()
    private val gauge = EnergyGauge(pen)

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        layout: ClusterDashboardLayout,
        telemetry: VehicleTelemetry,
    ) {
        if (!layout.supported) return
        val compact = layout.compact
        val density = if (compact) InstrumentDensity.COMPACT else InstrumentDensity.WIDE
        pen.size(width, height, layout.virtualHeight)

        // Black rather than the design system's near-black `BACKGROUND`: this sits on a panel whose
        // own ground is black, and three per cent of grey is a visible rectangle there while it is
        // invisible on the boards, which are viewed in a browser page.
        canvas.drawColor(BACKGROUND)

        if (telemetry.access != VehicleAccess.READY) {
            unavailable(canvas, layout, density, telemetry)
            return
        }

        energy(canvas, layout, density, telemetry)
        if (compact) {
            electricNarrow(canvas, layout.electricBlock, density, telemetry)
            engineNarrow(canvas, layout.engineBlock, density, telemetry)
        } else {
            electricWide(canvas, layout.electricBlock, density, telemetry)
            engineWide(canvas, layout.engineBlock, density, telemetry)
        }
        layout.temperatureBlock?.let { temperatures(canvas, it, telemetry) }
        layout.lampBlock?.let { lamps(canvas, it, telemetry) }
    }

    // ---- the centre: power now, and what it cost over the road behind

    /**
     * The dial over the fixed three-kilometre consumption window.
     *
     * The bars and average come from the same tail of hundred-metre buckets, so
     * the number describes exactly the road visible underneath it.
     */
    private fun energy(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val bars = ConsumptionWindow.raw(t.consumption)
        val average = ClusterReadout.averageConsumption(bars)
        gauge.draw(
            canvas = canvas,
            centreX = layout.gaugeCentreX * pen.width,
            centreY = layout.gaugeCentreY * pen.height,
            radius = layout.gaugeRadius * pen.height,
            density = density,
            kilowatts = flowKw(t),
            bars = bars,
            average = average,
            caption = ClusterReadout.chartCaption(
                average,
                ConsumptionWindow.coveredKm(t.consumption),
                t.stationary,
            ),
        )
    }

    /** What the pack is doing: charging reads as energy arriving, not as a load of its own. */
    private fun flowKw(t: VehicleTelemetry): Double? {
        if (t.charging) {
            val charge = t.chargeKw ?: return t.loadKw
            return -kotlin.math.abs(charge)
        }
        return t.loadKw
    }

    // ---- the pack, in the terms the car itself never shows

    private fun electricWide(
        canvas: Canvas,
        box: DashboardBox,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val column = Column(box, density, ClusterBlockPlan.electricWide(density))

        pen.title(canvas, TITLE_ELECTRIC, column.left, column.next(), density, DenzaPalette.MUTED_DEEP)

        val volts = t[VehicleSignal.PACK_VOLT]
        val spread = t.cellSpreadMv
        val figure = column.next()
        pen.figure(
            canvas, ClusterReadout.whole(volts), UNIT_VOLT,
            column.left, figure, density, density.figure, DenzaPalette.INK,
        )
        pen.figure(
            canvas, ClusterReadout.whole(spread), SPREAD_UNIT,
            column.right, figure, density, density.reading,
            levelColor(ClusterReadout.spreadState(spread)),
            DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT,
        )

        pen.track(
            canvas, column.left, column.right, column.next(),
            density.trackHeight, ClusterReadout.voltFraction(volts), DenzaPalette.INK,
        )

        pen.label(
            canvas, packLine(t, includeHealth = true), column.left, column.next(),
            density.body, DenzaPalette.MUTED_DEEP,
        )
    }

    private fun electricNarrow(
        canvas: Canvas,
        box: DashboardBox,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val column = Column(box, density, ClusterBlockPlan.electricNarrow(density))

        pen.title(canvas, TITLE_ELECTRIC, column.left, column.next(), density, DenzaPalette.MUTED_DEEP)

        val volts = t[VehicleSignal.PACK_VOLT]
        pen.figure(
            canvas, ClusterReadout.whole(volts), UNIT_VOLT,
            column.left, column.next(), density, density.figure, DenzaPalette.INK,
        )
        pen.track(
            canvas, column.left, column.right, column.next(),
            density.trackHeight, ClusterReadout.voltFraction(volts), DenzaPalette.INK,
        )

        val spread = t.cellSpreadMv
        pen.figure(
            canvas, ClusterReadout.whole(spread), SPREAD_UNIT,
            column.left, column.next(), density, density.reading,
            levelColor(ClusterReadout.spreadState(spread)), DenzaPalette.MUTED_DEEP,
        )
        pen.figure(
            canvas, ClusterReadout.whole(t[VehicleSignal.SOH_PERCENT]), HEALTH_UNIT,
            column.left, column.next(), density, density.reading,
            DenzaPalette.INK, DenzaPalette.MUTED_DEEP,
        )

        // The block's last row, in the block's own anatomy where it can be: the insulation is a
        // number with a unit and a name, exactly like the two rows above it, and writing it as a
        // grey sentence made the one row of this column that carries a reading look like the one
        // row that had failed to get one. A charge takes the row over and is a sentence, because
        // "2 ч 15 мин" is not a quantity with a unit.
        val insulation = t.insulationMohm.takeIf { !t.charging }
        val last = column.next()
        if (insulation != null) {
            pen.figure(
                canvas, ClusterReadout.fmt(insulation, 1), INSULATION_UNIT,
                column.left, last, density, density.reading,
                DenzaPalette.INK, DenzaPalette.MUTED_DEEP,
            )
        } else {
            pen.label(
                canvas, packLine(t, includeHealth = false), column.left, last,
                density.body, DenzaPalette.MUTED_DEEP,
            )
        }
    }

    /**
     * The pack's supporting sentence, which a charge takes over entirely.
     *
     * A driver watching a gun go in wants the rate and the wait; insulation resistance can go back
     * to being interesting when the cable comes out.
     *
     * [includeHealth] is false where the narrow layout has already given the pack's health a row of
     * its own, so the same fact never appears twice in one block.
     *
     * The 12 V rail used to end this line and no longer does. It is a diagnostic rather than a
     * driving fact, and at the wide body size the four facts together measured wider than the block
     * they sat in. Its poll left with the retired head-unit vehicle page.
     */
    private fun packLine(t: VehicleTelemetry, includeHealth: Boolean): String {
        if (t.charging) return ClusterReadout.chargeLine(t.chargeMinutesLeft, brief = !includeHealth)
        val parts = mutableListOf<String>()
        if (includeHealth) {
            t[VehicleSignal.SOH_PERCENT]?.let { parts += "ресурс ${ClusterReadout.whole(it)} %" }
        }
        t.insulationMohm?.let { parts += "изоляция ${ClusterReadout.fmt(it, 1)} МОм" }
        return if (parts.isEmpty()) NO_PACK_DETAIL else parts.joinToString(" · ")
    }

    // ---- the combustion half, which is normally asleep and still has something to say

    private fun engineWide(
        canvas: Canvas,
        box: DashboardBox,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val column = Column(box, density, ClusterBlockPlan.engineWide(density))

        pen.title(
            canvas, TITLE_ENGINE, column.right, column.next(),
            density, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT,
        )

        val row = column.next()
        val figureWidth = pen.figure(
            canvas, ClusterReadout.whole(t.engineRpm), UNIT_RPM,
            column.right, row, density, density.figure, rpmColor(t), align = Paint.Align.RIGHT,
        )
        engineTrace(canvas, column, density, row, figureWidth, t)

        revolutions(canvas, column, density, t)

        pen.label(
            canvas, engineLine(t), column.right, column.next(),
            density.body, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT,
        )
    }

    private fun engineNarrow(
        canvas: Canvas,
        box: DashboardBox,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val column = Column(box, density, ClusterBlockPlan.engineNarrow(density))

        pen.title(
            canvas, TITLE_ENGINE, column.right, column.next(),
            density, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT,
        )

        val row = column.next()
        val figureWidth = pen.figure(
            canvas, ClusterReadout.whole(t.engineRpm), UNIT_RPM,
            column.right, row, density, density.figure, rpmColor(t), align = Paint.Align.RIGHT,
        )
        engineTrace(canvas, column, density, row, figureWidth, t)

        revolutions(canvas, column, density, t)

        // The narrow layout has no reveal to put the lamps in, so they speak here instead.
        pen.label(
            canvas, lampLine(t, brief = true), column.right, column.next(),
            density.body, lampColor(t), Paint.Align.RIGHT,
        )
    }

    /** Revolutions, with generation laid over them on the same line rather than beside it. */
    private fun revolutions(
        canvas: Canvas,
        column: Column,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val centre = column.next()
        pen.track(
            canvas, column.left, column.right, centre, density.trackHeight,
            ClusterReadout.rpmFraction(t.engineRpm), DenzaPalette.ink(0.32f),
        )
        // The track is left as drawn, so the revolutions stay visible under the generation.
        ClusterReadout.generationFraction(t.generationKw.takeIf { t.generating })?.let { fraction ->
            pen.track(
                canvas, column.left, column.right, centre, density.trackHeight,
                fraction, DenzaPalette.RETURN, trackColor = null,
            )
        }
    }

    /**
     * Where the revolutions and the generation have just been, beside the figure they belong to.
     *
     * It takes the room the tank's percentage used to have and adds no row of its own: it stands on
     * the figure's baseline and is exactly as tall as the figure's digits, so it reads as part of
     * the number rather than as a second instrument. It runs from the gauge's left edge to where the
     * digits begin, which makes the row one object - history, then the reading it arrived at.
     *
     * Both traces share the box. Their ceilings differ and the box carries no scale, because what it
     * is for is the pair of shapes: whether the engine spun up before it started putting anything
     * back, and whether what it puts back is steady. The two current numbers are already on the row.
     */
    private fun engineTrace(
        canvas: Canvas,
        column: Column,
        density: InstrumentDensity,
        baseline: Float,
        figureWidth: Float,
        t: VehicleTelemetry,
    ) {
        val right = column.right - figureWidth - pen.v(density.rhythm(2f))
        val top = baseline - pen.digitHeight(density.figure)
        if (right - column.left < pen.v(TRACE_MIN_WIDTH)) return

        val trace = t.engineTrace
        pen.trace(
            canvas, column.left, right, top, baseline,
            trace.revolutions, ClusterReadout.RPM_FULL, DenzaPalette.ink(0.55f),
            density.hairline * TRACE_WIDTH, density.dotRadius * TRACE_DOT,
        )
        pen.trace(
            canvas, column.left, right, top, baseline,
            trace.generationKw, ClusterReadout.GENERATION_FULL_KW, DenzaPalette.RETURN,
            density.hairline * TRACE_WIDTH, density.dotRadius * TRACE_DOT,
            // The same square root the generation gauge uses: ordinary generation is around ten
            // kilowatts of a hundred, and linear would leave it flat on the floor of the box.
            squareRoot = true,
        )
    }

    private fun rpmColor(t: VehicleTelemetry): Int =
        if (t.engineRunning == true) DenzaPalette.INK else DenzaPalette.ink(0.55f)

    private fun engineLine(t: VehicleTelemetry): String =
        ClusterReadout.engineLine(t.generating, t.generationKw, t.engineRunning)

    // ---- the two reveals: temperatures on the left, fluids on the right

    private fun temperatures(canvas: Canvas, box: DashboardBox, t: VehicleTelemetry) {
        val density = InstrumentDensity.COMPACT
        val column = Column(box, density, ClusterBlockPlan.temperatures(density))

        pen.title(canvas, TITLE_THERMAL, column.left, column.next(), density, DenzaPalette.MUTED_DEEP)

        val pack = t[VehicleSignal.PACK_TEMP_AVG]
        val inverter = t[VehicleSignal.INVERTER_C]
        val row = column.next()
        val packWidth = pen.figure(
            canvas, degrees(pack), PACK_WORD, column.left, row, density, density.reading,
            levelColor(
                ClusterReadout.thermalState(
                    pack,
                    ClusterReadout.PACK_BAND_HIGH_C,
                    ClusterReadout.PACK_BAND_LOW_C,
                ),
            ),
            DenzaPalette.MUTED_DEEP,
        )
        // Two readings on one row, and the reader binds a word to the number *before* it only if
        // there is no closer number after it. At the pair rhythm the pack's word sat as near the
        // inverter's figure as its own, and "- батарея 26 инвертор" read as "battery 26" - which
        // is the wrong sensor and the wrong number at once. Between a value and its own word is
        // one step; between two pairs it is now four.
        pen.figure(
            canvas, degrees(inverter), INVERTER_WORD,
            column.left + packWidth + pen.v(density.rhythm(PAIR_GAP)),
            row, density, density.reading,
            levelColor(ClusterReadout.thermalState(inverter, ClusterReadout.INVERTER_WATCH_C)),
            DenzaPalette.MUTED_DEEP,
        )

        // Every temperature carries its own degree, in both rows. The three motors used to share
        // one at the end of the run, so the same row had values that were marked as temperatures
        // and values that were not.
        val motors = t.motorTemps.filterNotNull()
        val motorText = if (motors.isEmpty()) {
            ClusterReadout.DASH
        } else {
            motors.joinToString(" · ") { ClusterReadout.whole(it) + DEGREE }
        }
        pen.figure(
            canvas, motorText, MOTOR_WORD, column.left, column.next(), density, density.reading,
            levelColor(ClusterReadout.thermalState(t.hottestMotorC, ClusterReadout.DRIVE_BAND_HIGH_C)),
            DenzaPalette.MUTED_DEEP,
        )
    }

    private fun degrees(celsius: Double?): String =
        if (celsius == null) ClusterReadout.DASH else ClusterReadout.whole(celsius) + DEGREE

    private fun lamps(canvas: Canvas, box: DashboardBox, t: VehicleTelemetry) {
        val density = InstrumentDensity.COMPACT
        val plan = ClusterBlockPlan.lamps(density, EngineLamp.entries.size, LAMP_COLUMNS)
        val column = Column(box, density, plan)

        pen.title(
            canvas, TITLE_FLUIDS, column.right, column.next(),
            density, DenzaPalette.MUTED_DEEP, Paint.Align.RIGHT,
        )

        val step = pen.v(density.lampStep)
        val first = column.next()
        EngineLamp.entries.forEachIndexed { index, lamp ->
            val x = column.right - step * (LAMP_COLUMNS - 1 - index % LAMP_COLUMNS)
            val y = first + step * (index / LAMP_COLUMNS)
            when (t.lamp(lamp)) {
                LampState.ALERT -> pen.dot(canvas, x, y, density.lampRadius, DenzaPalette.DANGER)
                LampState.OK -> pen.dot(canvas, x, y, density.lampRadius, DenzaPalette.ink(0.55f))
                LampState.UNKNOWN -> pen.hollowDot(canvas, x, y, density.lampRadius, DenzaPalette.ink(0.35f))
            }
        }

        pen.label(
            canvas, lampLine(t), column.right, column.next(),
            density.body, lampColor(t), Paint.Align.RIGHT,
        )
    }

    private fun lampLine(t: VehicleTelemetry, brief: Boolean = false): String = ClusterReadout.lampLine(
        t.lampAlerts.map(EngineLamp::label),
        EngineLamp.entries.count { t.lamp(it) != LampState.UNKNOWN },
        EngineLamp.entries.size,
        brief,
    )

    private fun lampColor(t: VehicleTelemetry): Int =
        if (t.lampAlerts.isEmpty()) DenzaPalette.MUTED_DEEP else DenzaPalette.DANGER

    // ---- states where there is nothing to draw

    private fun unavailable(
        canvas: Canvas,
        layout: ClusterDashboardLayout,
        density: InstrumentDensity,
        t: VehicleTelemetry,
    ) {
        val cx = layout.gaugeCentreX * pen.width
        val cy = layout.gaugeCentreY * pen.height - layout.gaugeRadius * pen.height * WAITING_ABOVE
        val word = if (t.access == VehicleAccess.STARTING) READING else NO_DATA
        pen.label(canvas, word, cx, cy, density.reading, DenzaPalette.MUTED, Paint.Align.CENTER)
        if (t.message.isNotEmpty()) {
            pen.label(
                canvas,
                t.message,
                cx,
                cy + pen.v(density.rhythm(ClusterBlockPlan.LEAD_ROW) + density.body),
                density.body,
                DenzaPalette.MUTED_DEEP,
                Paint.Align.CENTER,
            )
        }
    }

    private fun levelColor(level: ClusterReadout.Level): Int = when (level) {
        ClusterReadout.Level.UNKNOWN -> DenzaPalette.ink(0.4f)
        ClusterReadout.Level.LOW -> DenzaPalette.RETURN_INK
        ClusterReadout.Level.NORMAL -> DenzaPalette.INK
        ClusterReadout.Level.WATCH -> DenzaPalette.WARNING
        ClusterReadout.Level.ALERT -> DenzaPalette.DANGER
    }

    /**
     * A plan turned into anchors, centred in the box it was given.
     *
     * Anchors come out in the order the plan declared them, which is why every block above reads its
     * rows top to bottom with no index in sight: the plan is the layout, and this is only arithmetic
     * on it.
     */
    private inner class Column(
        box: DashboardBox,
        density: InstrumentDensity,
        rows: List<DashboardRow>,
    ) {
        val left: Float = box.left * pen.width
        val right: Float = box.right * pen.width

        private val anchors = FloatArray(rows.size)
        private var taken = 0

        init {
            val boxHeight = (box.bottom - box.top) * pen.height
            val content = pen.v(ClusterBlockPlan.height(rows, density))
            var cursor = box.top * pen.height + maxOf(0f, (boxHeight - content) / 2f)
            rows.forEachIndexed { index, row ->
                cursor += pen.v(density.rhythm(row.lead))
                anchors[index] = when (row) {
                    is DashboardRow.Text -> {
                        cursor += pen.v(row.sizeV)
                        cursor
                    }
                    is DashboardRow.Rule -> {
                        val half = pen.v(density.trackHeight) / 2f
                        cursor += half
                        val centre = cursor
                        cursor += half
                        centre
                    }
                    is DashboardRow.Dots -> {
                        val step = pen.v(row.stepV)
                        cursor += step / 2f
                        val centre = cursor
                        cursor += step * (row.rows - 0.5f)
                        centre
                    }
                }
            }
        }

        /**
         * The next anchor the plan declared: a baseline, a track centre, or a first dot row.
         *
         * A block that asks for more anchors than it planned gets the last one again rather than an
         * exception. That is deliberate: this draws inside a `Presentation` over the vehicle's live
         * instruments, where two lines landing on one baseline is a visible mistake somebody
         * reports, and a throw out of `onDraw` takes the whole cluster window down.
         */
        fun next(): Float = anchors[taken++.coerceAtMost(anchors.lastIndex)]
    }

    private companion object {
        const val LAMP_COLUMNS = 4

        /** Rhythm steps between two value-and-word pairs sharing a row. See [temperatures]. */
        const val PAIR_GAP = 4f

        /** The dashboard's own ground: opaque, and the same black the panel around it is. */
        const val BACKGROUND = 0xFF000000.toInt()

        /** The waiting word sits above the dial's centre, where the figure would be. */
        const val WAITING_ABOVE = 0.3f

        const val TITLE_ELECTRIC = "ЭЛЕКТРИКА"
        const val TITLE_ENGINE = "ДВИГАТЕЛЬ"
        const val TITLE_THERMAL = "ТЕМПЕРАТУРЫ"
        const val TITLE_FLUIDS = "ЖИДКОСТИ"
        const val UNIT_VOLT = "В"
        const val UNIT_RPM = "об/мин"
        const val SPREAD_UNIT = "мВ разброс"
        const val HEALTH_UNIT = "% ресурс"
        const val INSULATION_UNIT = "МОм изоляция"
        /**
         * The trace beside the revolutions: a little heavier than a hairline so two of them can
         * cross without merging, and a dot on the newest reading a touch under the dial's own.
         *
         * It is not drawn at all below [TRACE_MIN_WIDTH] units of room. A four-digit reading in a
         * narrow block can leave almost nothing to its left, and a sparkline two centimetres wide is
         * a smudge rather than a history.
         */
        const val TRACE_WIDTH = 1.6f
        const val TRACE_DOT = 0.5f
        const val TRACE_MIN_WIDTH = 60f
        const val PACK_WORD = "батарея"
        const val INVERTER_WORD = "инвертор"
        const val MOTOR_WORD = "моторы"
        const val DEGREE = "°"
        const val NO_PACK_DETAIL = "пакет не ответил"
        const val READING = "Читаю машину"
        const val NO_DATA = "Нет данных"
    }
}
