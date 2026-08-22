package dev.denza.apps.feature.vehicle

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import dev.denza.apps.feature.panel.PanelCanvas
import dev.denza.apps.feature.panel.PanelPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The vehicle page: the car read as instruments rather than as advice.
 *
 * Four blocks, in the order a driver asks about them: how much charge is left,
 * what the electrics are doing, how hot everything is, and what the last few
 * kilometres cost. At full width they sit side by side behind hairline
 * dividers; in the narrow split pane they stack, and the least urgent rows drop
 * out rather than compress.
 *
 * Type size is the first constraint, not an afterthought. At full width this
 * virtual space maps onto roughly 1280 x 211 dp, so one virtual unit is about
 * 0.6 dp — a "16" caption would render at 9 dp, half the size of the smallest
 * text in the cards above. Sizes here are chosen against that ratio, which is
 * why the full-width layout carries fewer lines than the narrow one, where a
 * unit happens to be a dp.
 *
 * Every reading is nullable. A value that did not answer, or that could not be
 * true for its unit, is drawn as a dash — the panel never fills a gap with zero,
 * and when the shell channel itself is closed the page says so and shows nothing
 * else.
 *
 * What the electrical block does and does not tell you: this is an LFP pack, so
 * traction voltage barely moves with charge (550 V at 43 %, 551 V at 62 %) and
 * the 12 V rail is held by the DC-DC. Both are quiet indicators — they matter
 * when they leave their band, not while they sit in it. The live number in that
 * block is the cell spread, which widens under load and with age. Traction
 * voltage does move with current, but that is a moving-car reading; the sag
 * figure waits for the drive capture in docs/vehicle-data-findings.md.
 */
internal class VehiclePanelRenderer : PanelCanvas() {

    private val rect = RectF()
    private var dashUnit = 0f
    private var dash: DashPathEffect? = null

    // The two layouts do not share a type scale. At full width one virtual unit
    // is about 0.6 dp, in the narrow pane it is a dp, so the same number means
    // two very different sizes on screen. The shared elements below read these
    // instead of the full-width constants.
    private var sizeHeader = WIDE_HEADER
    private var sizeLabel = WIDE_LABEL
    private var sizeValue = WIDE_VALUE
    private var sizeTiny = WIDE_TINY

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        telemetry: VehicleTelemetry,
        frameTimeSec: Double,
        narrowLayout: Boolean,
    ) {
        if (narrowLayout) {
            setSize(width, height, NARROW_W, NARROW_H)
            sizeHeader = NARROW_HEADER
            sizeLabel = NARROW_LABEL
            sizeValue = NARROW_VALUE
            sizeTiny = NARROW_TINY
        } else {
            setSize(width, height, WIDE_W, WIDE_H)
            sizeHeader = WIDE_HEADER
            sizeLabel = WIDE_LABEL
            sizeValue = WIDE_VALUE
            sizeTiny = WIDE_TINY
        }
        when (telemetry.access) {
            VehicleAccess.STARTING -> {
                centred(canvas, READING, "", narrowLayout)
                return
            }

            VehicleAccess.UNAVAILABLE -> {
                centred(canvas, NO_DATA, telemetry.message, narrowLayout)
                return
            }

            VehicleAccess.READY -> Unit
        }
        if (narrowLayout) drawNarrow(canvas, telemetry, frameTimeSec) else drawWide(canvas, telemetry, frameTimeSec)
    }

    // ---------------------------------------------------------------- layouts

    private fun drawWide(canvas: Canvas, t: VehicleTelemetry, frameTimeSec: Double) {
        val soc = t[VehicleSignal.SOC_PERCENT]
        val socColor = socColor(soc, t.charging)

        // ---- battery ----
        label(canvas, "Батарея", vx(0f), vy(46f), sizeHeader, muted())
        value(
            canvas,
            if (soc == null) "—" else "${soc.roundToInt()} %",
            vx(0f), vy(142f), 72f, socColor, bold = true,
        )
        batteryGlyph(
            canvas,
            x = vx(240f), y = vy(78f), boxWidth = vx(180f), boxHeight = vs(74f),
            fraction = (soc ?: 0.0) / 100.0, filled = soc != null,
            color = socColor, charging = t.charging, frameTimeSec = frameTimeSec,
        )
        label(canvas, packSummaryLine(t), vx(0f), vy(196f), sizeLabel, muted())
        label(canvas, rangeLine(t), vx(0f), vy(242f), sizeValue, PanelPalette.alpha(PanelPalette.INK, 0.92f))
        label(canvas, flowLine(t), vx(0f), vy(290f), sizeLabel, flowColor(t))

        hairline(canvas, vx(462f), vy(24f), vx(462f), vy(312f))

        // ---- electrics ----
        label(canvas, "Электрика", vx(500f), vy(46f), sizeHeader, muted())
        label(canvas, "бортовая сеть", vx(500f), vy(110f), sizeLabel, muted())
        val rail = t[VehicleSignal.RAIL_12V]
        value(canvas, volts(rail, 1), vx(890f), vy(112f), 40f, railColor(rail), Paint.Align.RIGHT)
        voltGauge(canvas, vx(500f), vx(890f), vy(130f), vs(18f), rail)

        label(canvas, "ячейки", vx(500f), vy(210f), sizeLabel, muted())
        value(canvas, cellWindowText(t), vx(890f), vy(210f), 26f, PanelPalette.INK, Paint.Align.RIGHT)
        label(canvas, "разброс", vx(500f), vy(254f), sizeLabel, muted())
        value(canvas, spreadText(t), vx(890f), vy(254f), 28f, spreadColor(t), Paint.Align.RIGHT)
        label(canvas, tractionLine(t), vx(500f), vy(300f), sizeTiny, muted(0.75f))

        hairline(canvas, vx(922f), vy(24f), vx(922f), vy(312f))

        // ---- temperatures ----
        label(canvas, "Температуры", vx(960f), vy(46f), sizeHeader, muted())
        val nameX = vx(960f)
        val trackStart = vx(1130f)
        val trackEnd = vx(1270f)
        val valueX = vx(1350f)
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(120f), "батарея",
            t[VehicleSignal.PACK_TEMP_AVG], PACK_BAND_LO, PACK_BAND_HI,
            t[VehicleSignal.PACK_TEMP_MIN], t[VehicleSignal.PACK_TEMP_MAX], sizeValue,
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(176f), "моторы",
            warmerMotor(t), DRIVE_BAND_LO, DRIVE_BAND_HI, null, null, sizeValue, motorPairText(t),
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(232f), "за бортом",
            t[VehicleSignal.OUTSIDE_TEMP_C], OUTSIDE_BAND_LO, OUTSIDE_BAND_HI, null, null, sizeValue,
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(288f), "в салоне",
            t[VehicleSignal.CABIN_TEMP_C], CABIN_BAND_LO, CABIN_BAND_HI, null, null, sizeValue,
        )

        hairline(canvas, vx(1382f), vy(24f), vx(1382f), vy(312f))

        // ---- consumption ----
        label(canvas, "Расход, кВт·ч/100", vx(1420f), vy(46f), sizeLabel, muted())
        value(canvas, currentConsumption(t), vx(1850f), vy(46f), sizeLabel, PanelPalette.INK, Paint.Align.RIGHT)
        kmChart(canvas, vx(1420f), vx(1850f), vy(86f), vy(250f), t.consumption, compact = false)
        label(canvas, tyreLine(t), vx(1420f), vy(300f), sizeLabel, tyreColor(t))
    }

    /**
     * The narrow pane is a dp-for-unit layout, so it can carry more lines than
     * the full-width one — but only if each line owns its own row. The figures
     * that share a row here are checked against each other's width: the state of
     * charge sits to the right of the battery, and the pack summary and the
     * charge/load line are separate rows rather than one long caption.
     */
    private fun drawNarrow(canvas: Canvas, t: VehicleTelemetry, frameTimeSec: Double) {
        val soc = t[VehicleSignal.SOC_PERCENT]
        val socColor = socColor(soc, t.charging)

        // ---- battery ----
        label(canvas, "Батарея", vx(0f), vy(34f), sizeHeader, muted())
        batteryGlyph(
            canvas,
            x = vx(0f), y = vy(50f), boxWidth = vx(236f), boxHeight = vs(70f),
            fraction = (soc ?: 0.0) / 100.0, filled = soc != null,
            color = socColor, charging = t.charging, frameTimeSec = frameTimeSec,
        )
        value(
            canvas,
            if (soc == null) "—" else "${soc.roundToInt()} %",
            vx(368f), vy(108f), 40f, socColor, Paint.Align.RIGHT, bold = true,
        )
        label(canvas, rangeLine(t), vx(0f), vy(154f), sizeLabel, PanelPalette.alpha(PanelPalette.INK, 0.92f))
        label(canvas, packSummaryLine(t), vx(0f), vy(180f), NARROW_SECONDARY, muted())
        label(canvas, flowLine(t), vx(0f), vy(204f), NARROW_SECONDARY, flowColor(t))

        hairline(canvas, vx(0f), vy(220f), vx(368f), vy(220f))

        // ---- electrics ----
        label(canvas, "Электрика", vx(0f), vy(246f), sizeHeader, muted())
        label(canvas, "бортовая сеть", vx(0f), vy(278f), sizeLabel, muted())
        val rail = t[VehicleSignal.RAIL_12V]
        value(canvas, volts(rail, 1), vx(368f), vy(280f), 26f, railColor(rail), Paint.Align.RIGHT)
        voltGauge(canvas, vx(0f), vx(368f), vy(292f), vs(12f), rail)
        label(canvas, "ячейки ${cellWindowText(t)}", vx(0f), vy(346f), sizeLabel, muted())
        value(canvas, spreadText(t), vx(368f), vy(346f), sizeValue, spreadColor(t), Paint.Align.RIGHT)
        label(canvas, tractionLine(t), vx(0f), vy(374f), NARROW_SECONDARY, muted(0.75f))

        hairline(canvas, vx(0f), vy(392f), vx(368f), vy(392f))

        // ---- temperatures ----
        label(canvas, "Температуры", vx(0f), vy(418f), sizeHeader, muted())
        val nameX = vx(0f)
        val trackStart = vx(104f)
        val trackEnd = vx(244f)
        val valueX = vx(368f)
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(454f), "батарея",
            t[VehicleSignal.PACK_TEMP_AVG], PACK_BAND_LO, PACK_BAND_HI,
            t[VehicleSignal.PACK_TEMP_MIN], t[VehicleSignal.PACK_TEMP_MAX], sizeValue,
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(494f), "моторы",
            warmerMotor(t), DRIVE_BAND_LO, DRIVE_BAND_HI, null, null, sizeValue, motorPairText(t),
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, vy(534f), "за бортом",
            t[VehicleSignal.OUTSIDE_TEMP_C], OUTSIDE_BAND_LO, OUTSIDE_BAND_HI, null, null, sizeValue,
        )

        hairline(canvas, vx(0f), vy(556f), vx(368f), vy(556f))

        // ---- consumption ----
        label(canvas, "Расход, кВт·ч/100", vx(0f), vy(582f), sizeHeader, muted())
        value(canvas, currentConsumption(t), vx(368f), vy(582f), sizeValue, PanelPalette.INK, Paint.Align.RIGHT)
        kmChart(canvas, vx(0f), vx(368f), vy(596f), vy(632f), t.consumption, compact = true)
    }

    // --------------------------------------------------------------- elements

    /**
     * The battery: an outline with a terminal nub and twelve lit segments. The
     * segment count is what makes a glance readable — a smooth bar reads as
     * "somewhere in the middle", twelve blocks read as a number.
     */
    private fun batteryGlyph(
        canvas: Canvas,
        x: Float,
        y: Float,
        boxWidth: Float,
        boxHeight: Float,
        fraction: Double,
        filled: Boolean,
        color: Int,
        charging: Boolean,
        frameTimeSec: Double,
    ) {
        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.45f)
        val nubHeight = boxHeight * 0.42f
        rect.set(x + boxWidth, y + (boxHeight - nubHeight) / 2f, x + boxWidth + vs(9f), y + (boxHeight + nubHeight) / 2f)
        canvas.drawRoundRect(rect, vs(3f), vs(3f), fill)

        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.5f)
        stroke.strokeWidth = vs(2.4f)
        rect.set(x, y, x + boxWidth, y + boxHeight)
        canvas.drawRoundRect(rect, vs(8f), vs(8f), stroke)

        val pad = vs(7f)
        val innerX = x + pad
        val innerY = y + pad
        val innerW = boxWidth - pad * 2f
        val innerH = boxHeight - pad * 2f
        val gap = vs(3.5f)
        val segment = (innerW - gap * (SEGMENTS - 1)) / SEGMENTS
        val lit = if (filled) fraction.coerceIn(0.0, 1.0) else 0.0
        for (i in 0 until SEGMENTS) {
            val left = innerX + i * (segment + gap)
            fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.09f)
            canvas.drawRect(left, innerY, left + segment, innerY + innerH, fill)
            val share = (lit * SEGMENTS - i).coerceIn(0.0, 1.0)
            if (share > 0.0) {
                fill.color = PanelPalette.alpha(color, (0.4f + 0.6f * min(1.0, share * 2.5).toFloat()))
                canvas.drawRect(left, innerY, left + segment * share.toFloat(), innerY + innerH, fill)
            }
        }
        if (charging && filled) {
            val index = min(SEGMENTS - 1, floor(lit * SEGMENTS).toInt())
            val left = innerX + index * (segment + gap)
            val pulse = 0.2f + 0.3f * (0.5f + 0.5f * sin(frameTimeSec * 2.2).toFloat())
            fill.color = PanelPalette.alpha(PanelPalette.BLUE, pulse)
            canvas.drawRect(left, innerY, left + segment, innerY + innerH, fill)
        }
    }

    /**
     * The 12 V rail against its healthy band. The band is the point: the DC-DC
     * holds this number steady while the car is on, so it says nothing by its
     * value and everything by leaving the green stretch.
     */
    private fun voltGauge(canvas: Canvas, x0: Float, x1: Float, y: Float, height: Float, volts: Double?) {
        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.10f)
        canvas.drawRect(x0, y, x1, y + height, fill)

        fun position(v: Double): Float =
            x0 + (x1 - x0) * ((v - RAIL_LO) / (RAIL_HI - RAIL_LO)).coerceIn(0.0, 1.0).toFloat()

        fill.color = PanelPalette.alpha(PanelPalette.MINT, 0.16f)
        canvas.drawRect(position(RAIL_GOOD_LO), y, position(RAIL_GOOD_HI), y + height, fill)

        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.35f)
        stroke.strokeWidth = vs(1f)
        var tick = RAIL_LO
        while (tick <= RAIL_HI) {
            canvas.drawLine(position(tick), y + height, position(tick), y + height + vs(5f), stroke)
            tick += 1.0
        }
        val scaleY = y + height + vs(sizeTiny * 0.8f)
        label(canvas, "${RAIL_LO.roundToInt()}", x0, scaleY, sizeTiny, muted(0.5f))
        label(canvas, "${RAIL_HI.roundToInt()}", x1, scaleY, sizeTiny, muted(0.5f), Paint.Align.RIGHT)

        if (volts == null) return
        fill.color = railColor(volts)
        canvas.drawRect(position(volts) - vs(1.4f), y - vs(5f), position(volts) + vs(1.4f), y + height + vs(5f), fill)
    }

    /**
     * One temperature: name, a track carrying the range this reading is supposed
     * to live in, a bar to the value, and the number. Pack rows also mark the
     * coldest and hottest cell group, which is where a failing pack shows first.
     */
    private fun tempRow(
        canvas: Canvas,
        nameX: Float,
        trackStart: Float,
        trackEnd: Float,
        valueX: Float,
        y: Float,
        name: String,
        celsius: Double?,
        bandLo: Double,
        bandHi: Double,
        spreadLo: Double?,
        spreadHi: Double?,
        textSize: Float,
        text: String? = null,
    ) {
        label(canvas, name, nameX, y + vs(5f), sizeLabel, muted())
        val height = vs(9f)
        fun position(v: Double): Float =
            trackStart + (trackEnd - trackStart) *
                ((v - TEMP_LO) / (TEMP_HI - TEMP_LO)).coerceIn(0.0, 1.0).toFloat()

        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.10f)
        canvas.drawRect(trackStart, y - height / 2f, trackEnd, y + height / 2f, fill)
        fill.color = PanelPalette.alpha(PanelPalette.MINT, 0.13f)
        canvas.drawRect(position(bandLo), y - height / 2f, position(bandHi), y + height / 2f, fill)

        val color = if (celsius == null) muted(0.6f) else tempColor(celsius, bandLo, bandHi)
        if (celsius != null) {
            fill.color = color
            canvas.drawRect(trackStart, y - height / 2f, position(celsius), y + height / 2f, fill)
            if (spreadLo != null && spreadHi != null) {
                fill.color = PanelPalette.alpha(PanelPalette.INK, 0.5f)
                canvas.drawRect(position(spreadLo) - vs(1f), y - height, position(spreadLo) + vs(1f), y + height, fill)
                canvas.drawRect(position(spreadHi) - vs(1f), y - height, position(spreadHi) + vs(1f), y + height, fill)
            }
        }
        // Zero mark, so a reading below freezing is obvious without reading it.
        fill.color = PanelPalette.alpha(PanelPalette.INK, 0.28f)
        canvas.drawRect(position(0.0) - vs(0.5f), y - height, position(0.0) + vs(0.5f), y + height, fill)

        val readout = text ?: celsius?.let { "${it.roundToInt()} °C" } ?: "—"
        value(canvas, readout, valueX, y + vs(7f), textSize, color, Paint.Align.RIGHT)
    }

    /**
     * Consumption per half-kilometre, newest on the right, coloured against the
     * window's own average: a hill and the descent after it read as a shape
     * rather than as a column of numbers.
     */
    private fun kmChart(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        values: List<Double>,
        compact: Boolean,
    ) {
        hairline(canvas, x0, y1, x1, y1, 0.22f)
        hairline(canvas, x0, y0, x1, y0, 0.07f)
        if (values.isEmpty()) {
            label(canvas, EMPTY_CHART, x0, (y0 + y1) / 2f, sizeLabel, muted(0.6f))
            return
        }

        val top = max(CHART_MIN_TOP, values.max())
        val scale = ceilToStep(top, 10.0)
        val average = values.average()
        if (!compact) {
            label(canvas, "${scale.roundToInt()}", x0 + vs(3f), y0 + vs(18f), sizeTiny, muted(0.45f))
        }

        val slot = (x1 - x0) / values.size
        val barWidth = slot * 0.66f
        values.forEachIndexed { index, value ->
            // A slice that gave more back than it took has no bar to draw; it
            // gets a stub in the regeneration colour so it reads as "returned",
            // not as "missing".
            val height = max(vs(3f), (y1 - y0) * (value / scale).coerceIn(0.0, 1.0).toFloat())
            val left = x0 + index * slot + (slot - barWidth) / 2f
            if (value < 0.0) {
                fill.color = PanelPalette.alpha(PanelPalette.BLUE, if (index == values.lastIndex) 1f else 0.72f)
                canvas.drawRect(left, y1 - vs(3f), left + barWidth, y1, fill)
                return@forEachIndexed
            }
            val color = when {
                value <= average * 0.85 -> PanelPalette.MINT
                value <= average * 1.15 -> PanelPalette.mix(PanelPalette.MINT, PanelPalette.AMBER, 0.55f)
                value <= average * 1.5 -> PanelPalette.AMBER
                else -> PanelPalette.DANGER
            }
            fill.color = if (index == values.lastIndex) color else PanelPalette.alpha(color, 0.72f)
            canvas.drawRect(left, y1 - height, left + barWidth, y1, fill)
        }

        val averageY = y1 - (y1 - y0) * (average / scale).coerceIn(0.0, 1.0).toFloat()
        stroke.color = PanelPalette.alpha(PanelPalette.INK, 0.45f)
        stroke.strokeWidth = vs(1f)
        stroke.pathEffect = dashEffect()
        canvas.drawLine(x0, averageY, x1, averageY, stroke)
        stroke.pathEffect = null

        if (!compact) {
            label(
                canvas, "средний ${fmt(average, 1)}", x1, averageY - vs(8f), sizeTiny,
                PanelPalette.alpha(PanelPalette.INK, 0.6f), Paint.Align.RIGHT,
            )
            label(canvas, "−${fmt(values.size * ConsumptionLog.DEFAULT_BUCKET_KM, 1)} км", x0, y1 + vs(24f), sizeTiny, muted(0.5f))
            label(canvas, "сейчас", x1, y1 + vs(24f), sizeTiny, muted(0.5f), Paint.Align.RIGHT)
        }
    }

    private fun centred(canvas: Canvas, title: String, detail: String, narrowLayout: Boolean) {
        val cx = w / 2f
        val titleSize = if (narrowLayout) 22f else 34f
        label(canvas, title, cx, h * 0.46f, titleSize, PanelPalette.alpha(PanelPalette.INK, 0.8f), Paint.Align.CENTER)
        if (detail.isNotEmpty()) {
            label(canvas, detail, cx, h * 0.46f + vs(40f), if (narrowLayout) 16f else 24f, muted(0.75f), Paint.Align.CENTER)
        }
    }

    // ------------------------------------------------------------------ lines

    /**
     * The BMS's own state of charge next to pack health. The BMS figure is
     * deliberately kept: it is the same quantity the dashboard shows, read from
     * the other side, and a growing gap between them is worth seeing.
     */
    private fun packSummaryLine(t: VehicleTelemetry): String {
        val bms = t[VehicleSignal.BMS_SOC_PERCENT]?.let { "BMS ${fmt(it, 1)} %" } ?: "BMS —"
        val soh = t[VehicleSignal.SOH_PERCENT]?.let { "ресурс ${it.roundToInt()} %" } ?: "ресурс —"
        return "$bms · $soh"
    }

    private fun rangeLine(t: VehicleTelemetry): String =
        t[VehicleSignal.RANGE_KM]?.let { "запас хода ${it.roundToInt()} км" } ?: "запас хода —"

    private fun flowLine(t: VehicleTelemetry): String {
        if (t.charging) {
            val power = t[VehicleSignal.CHARGE_KW]?.let { "заряд ${fmt(it, 1)} кВт" } ?: "заряд —"
            val hours = t[VehicleSignal.CHARGE_HOURS]?.roundToInt()
            val minutes = t[VehicleSignal.CHARGE_MINUTES]?.roundToInt()
            if (hours == null && minutes == null) return power
            return "$power · полный ${hours ?: 0}:${pad2(minutes ?: 0)}"
        }
        val load = t.loadKw ?: return "мощность —"
        return when {
            load < -0.5 -> "рекуперация ${(-load).roundToInt()} кВт"
            else -> "нагрузка ${load.roundToInt()} кВт"
        }
    }

    private fun cellWindowText(t: VehicleTelemetry): String {
        val window = t.cellWindowVolt ?: return "—"
        return "${fmt(window.first, 3)}–${fmt(window.second, 3)} В"
    }

    private fun spreadText(t: VehicleTelemetry): String =
        t.cellSpreadMv?.let { "${it.roundToInt()} мВ" } ?: "—"

    /**
     * Traction voltage and insulation, both quiet by nature: an LFP pack holds
     * its voltage flat across the middle of the charge window, and insulation
     * only means something when it collapses.
     */
    private fun tractionLine(t: VehicleTelemetry): String {
        val pack = t[VehicleSignal.PACK_VOLT]?.let { "тяга ${it.roundToInt()} В" } ?: "тяга —"
        val cells = t[VehicleSignal.CELL_COUNT]?.let { "${it.roundToInt()} ячеек" }
        val insulation = t.insulationMohm?.let { "изоляция ${fmt(it, 1)} МОм" }
        return listOfNotNull(pack, cells, insulation).joinToString(" · ")
    }

    private fun tyreLine(t: VehicleTelemetry): String {
        val pressures = t.tyrePressures.joinToString(" · ") { it?.let { bar -> fmt(bar, 2) } ?: "—" }
        val hottest = t.tyreTemperatures.filterNotNull().maxOrNull()
        val suffix = hottest?.let { " · до ${it.roundToInt()} °C" } ?: ""
        return "шины $pressures бар$suffix"
    }

    private fun currentConsumption(t: VehicleTelemetry): String =
        t.currentConsumption?.let { "сейчас ${fmt(it, 1)}" } ?: "сейчас —"

    private fun motorPairText(t: VehicleTelemetry): String {
        val front = t[VehicleSignal.MOTOR_FRONT_C]?.roundToInt()?.toString() ?: "—"
        val rear = t.motorRearC?.roundToInt()?.toString() ?: "—"
        return "$front/$rear"
    }

    private fun warmerMotor(t: VehicleTelemetry): Double? {
        val front = t[VehicleSignal.MOTOR_FRONT_C]
        val rear = t.motorRearC
        return when {
            front != null && rear != null -> max(front, rear)
            else -> front ?: rear
        }
    }

    // ----------------------------------------------------------------- colour

    private fun socColor(soc: Double?, charging: Boolean): Int = when {
        charging -> PanelPalette.BLUE
        soc == null -> muted(0.6f)
        soc < 12.0 -> PanelPalette.DANGER
        soc < 25.0 -> PanelPalette.AMBER
        else -> PanelPalette.MINT
    }

    private fun tempColor(celsius: Double, bandLo: Double, bandHi: Double): Int = when {
        celsius < bandLo -> PanelPalette.BLUE
        celsius > bandHi + HOT_MARGIN -> PanelPalette.DANGER
        celsius > bandHi -> PanelPalette.AMBER
        else -> PanelPalette.MINT
    }

    private fun railColor(volts: Double?): Int = when {
        volts == null -> muted(0.6f)
        volts < RAIL_GOOD_LO -> PanelPalette.DANGER
        volts > RAIL_GOOD_HI -> PanelPalette.AMBER
        else -> PanelPalette.MINT
    }

    private fun spreadColor(t: VehicleTelemetry): Int {
        val spread = t.cellSpreadMv ?: return muted(0.6f)
        return when {
            spread > CELL_SPREAD_ALERT_MV -> PanelPalette.DANGER
            spread > CELL_SPREAD_WATCH_MV -> PanelPalette.AMBER
            else -> PanelPalette.MINT
        }
    }

    private fun flowColor(t: VehicleTelemetry): Int {
        if (t.charging) return PanelPalette.BLUE
        val load = t.loadKw ?: return muted()
        return if (load < -0.5) PanelPalette.BLUE else muted()
    }

    private fun tyreColor(t: VehicleTelemetry): Int {
        val known = t.tyrePressures.filterNotNull()
        if (known.size < 4) return muted()
        val mean = known.average()
        val worst = known.maxOf { abs(it - mean) }
        return if (worst >= TYRE_SPREAD_ALERT_BAR || known.min() < TYRE_LOW_BAR) {
            PanelPalette.DANGER
        } else {
            muted()
        }
    }

    private fun muted(alpha: Float = 0.85f): Int = PanelPalette.alpha(PanelPalette.MUTED, alpha)

    // ------------------------------------------------------------------ utils

    private fun dashEffect(): DashPathEffect {
        val unit = vs(5f)
        val current = dash
        if (current != null && unit == dashUnit) return current
        dashUnit = unit
        return DashPathEffect(floatArrayOf(unit, unit), 0f).also { dash = it }
    }

    private fun volts(value: Double?, digits: Int): String =
        value?.let { "${fmt(it, digits)} В" } ?: "—"

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value).replace('.', ',')

    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun ceilToStep(value: Double, step: Double): Double =
        kotlin.math.ceil(value / step) * step

    private companion object {
        const val WIDE_W = 1850f
        const val WIDE_H = 360f
        const val NARROW_W = 368f
        const val NARROW_H = 660f

        // Full-width type scale. One unit is about 0.6 dp here, so these land at
        // roughly 12, 15 and 17 dp — in the same range as the cards above.
        const val WIDE_TINY = 20f
        const val WIDE_LABEL = 26f
        const val WIDE_VALUE = 30f
        const val WIDE_HEADER = 30f

        // Narrow pane: the panel is a fixed 660 dp tall against a 660-unit
        // layout, so a unit is a dp and these are the sizes as drawn.
        const val NARROW_TINY = 12f
        const val NARROW_LABEL = 15f
        const val NARROW_VALUE = 18f
        const val NARROW_HEADER = 16f

        /** Supporting lines under a figure; small on purpose, still legible. */
        const val NARROW_SECONDARY = 14f

        const val SEGMENTS = 12

        const val TEMP_LO = -20.0
        const val TEMP_HI = 80.0
        const val HOT_MARGIN = 15.0

        const val PACK_BAND_LO = 15.0
        const val PACK_BAND_HI = 40.0
        const val DRIVE_BAND_LO = 0.0
        const val DRIVE_BAND_HI = 70.0
        const val OUTSIDE_BAND_LO = 3.0
        const val OUTSIDE_BAND_HI = 35.0
        const val CABIN_BAND_LO = 18.0
        const val CABIN_BAND_HI = 26.0

        const val RAIL_LO = 11.0
        const val RAIL_HI = 15.0
        const val RAIL_GOOD_LO = 12.8
        const val RAIL_GOOD_HI = 14.6

        const val CELL_SPREAD_WATCH_MV = 25.0
        const val CELL_SPREAD_ALERT_MV = 40.0
        const val TYRE_SPREAD_ALERT_BAR = 0.18
        const val TYRE_LOW_BAR = 2.3

        const val CHART_MIN_TOP = 10.0

        const val READING = "Читаю данные машины…"
        const val NO_DATA = "Нет доступа к данным машины"
        const val EMPTY_CHART = "накапливаю первые полкилометра"
    }
}
