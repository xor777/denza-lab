package dev.denza.apps.feature.vehicle

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import dev.denza.apps.feature.panel.PanelCanvas
import dev.denza.apps.feature.panel.PanelPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The vehicle page: the car read as instruments rather than as advice.
 *
 * Four blocks, in the order a driver asks about them: how much charge is left,
 * what the electrics are doing, how hot everything is, and what the last few
 * kilometres cost. At full width they sit side by side behind hairline
 * dividers; in the narrow split pane they stack.
 *
 * Each block has exactly one figure at full size and everything else beneath
 * it. The big figure is chosen for movement, not for importance in the
 * abstract: charge, traction voltage (which sags under load), the hottest
 * thing on board, and consumption over the last few hundred metres. A number
 * that never moves — the 12 V rail, the cell window, insulation — is a
 * supporting line, and its colour, not its size, is what raises a hand.
 *
 * Type size is the first constraint, not an afterthought. At full width this
 * virtual space maps onto roughly 1280 x 211 dp, so one virtual unit is about
 * 0.6 dp — a "16" caption would render at 9 dp, half the size of the smallest
 * text in the cards above. In the narrow pane the panel is a fixed 660 dp tall
 * against a 660-unit layout, so a unit is a dp. The two layouts therefore do
 * not share a single number, and the shared elements read the fields set in
 * [draw] rather than either set of constants.
 *
 * The narrow pane drops all four block headings. Space there is the scarce
 * resource, the hairlines already separate the blocks, and the headings were
 * what pushed the consumption chart off the bottom of the pane.
 *
 * Every reading is nullable. A value that did not answer, or that could not be
 * true for its unit, is drawn as a dash — the panel never fills a gap with zero,
 * and when the shell channel itself is closed the page says so and shows nothing
 * else.
 *
 * What the electrical block does and does not tell you: this is an LFP pack, so
 * traction voltage barely moves with charge (550 V at 43 %, 551 V at 62 %). It
 * does move with current, which is why it is the block's live figure and why
 * its gauge spans a deliberately narrow band — sag under load is the whole
 * signal. The 12 V rail is held by the DC-DC and says nothing by its value,
 * everything by leaving its band.
 */
internal class VehiclePanelRenderer : PanelCanvas() {

    /**
     * How far back the chart reaches, chosen by the driver and set per frame.
     *
     * The log keeps one resolution and every window is a view of it, so switching
     * costs nothing and loses nothing - see [ConsumptionWindow].
     */
    var window: ConsumptionWindow = ConsumptionWindow.DEFAULT

    /**
     * Where the chart landed on this canvas, in pixels, so the view above can
     * tell whether a tap was aimed at it.
     *
     * A renderer publishing a hit rectangle is not elegant, but the alternatives
     * are worse: duplicating the layout arithmetic in the view, or giving the
     * chart a real widget and with it a second layout system on the same panel.
     */
    val chartBounds = RectF()

    private val rect = RectF()
    private val path = Path()
    private var dashUnit = 0f
    private var dash: DashPathEffect? = null

    /** Set by the view once it has a context; the renderer itself has none. */
    var icons: VehicleIcons? = null

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
        batteryGlyph(
            canvas,
            x = vx(240f), y = vy(78f), boxWidth = vx(180f), boxHeight = vs(74f),
            fraction = (soc ?: 0.0) / 100.0, filled = soc != null,
            color = socColor, charging = t.charging, frameTimeSec = frameTimeSec,
        )
        percentFigure(canvas, rightX = vx(214f), centreY = vy(115f), value = soc, sizeV = 72f, color = socColor)
        label(canvas, rangeLine(t), vx(0f), vy(210f), sizeValue, PanelPalette.alpha(PanelPalette.INK, 0.92f))
        label(canvas, packSummaryLine(t), vx(0f), vy(252f), sizeLabel, muted())
        if (t.charging) {
            icon(canvas, icons?.charging, vx(0f), vy(296f) - vs(22f), vs(27f), PanelPalette.BLUE)
            label(canvas, chargeLine(t), vx(34f), vy(296f), sizeLabel, PanelPalette.BLUE)
        }

        hairline(canvas, vx(462f), vy(24f), vx(462f), vy(312f))

        // ---- electrics ----
        label(canvas, "Электрика", vx(500f), vy(46f), sizeHeader, muted())
        val traction = t[VehicleSignal.PACK_VOLT]
        label(canvas, "тяга", vx(500f), vy(118f), sizeLabel, muted())
        value(canvas, volts(traction, 0), vx(890f), vy(126f), 46f, tractionColor(traction), Paint.Align.RIGHT, bold = true)
        tractionGauge(canvas, vx(500f), vx(890f), vy(142f), vs(20f), traction)

        val rail = t[VehicleSignal.RAIL_12V]
        label(canvas, "бортовая сеть", vx(500f), vy(218f), sizeLabel, muted())
        value(canvas, volts(rail, 1), vx(890f), vy(218f), sizeValue, railColor(rail), Paint.Align.RIGHT)
        label(canvas, "ячейки ${cellWindowText(t)}", vx(500f), vy(262f), sizeLabel, muted())
        value(canvas, spreadText(t), vx(890f), vy(262f), 28f, spreadColor(t), Paint.Align.RIGHT)
        label(canvas, packDetailLine(t), vx(500f), vy(300f), sizeTiny, muted(0.75f))

        hairline(canvas, vx(922f), vy(24f), vx(922f), vy(312f))

        // ---- temperatures ----
        label(canvas, "Температуры", vx(960f), vy(46f), sizeHeader, muted())
        temperatureRows(canvas, nameX = vx(960f), trackStart = vx(1120f), trackEnd = vx(1200f), valueX = vx(1382f), t = t,
            rowY = floatArrayOf(vy(132f), vy(200f), vy(268f)))

        hairline(canvas, vx(1382f), vy(24f), vx(1382f), vy(312f))

        // ---- consumption ----
        label(canvas, "Расход", vx(1420f), vy(46f), sizeHeader, muted())
        consumptionFigure(canvas, vx(1850f), vy(60f), 46f, t)
        powerBar(canvas, vx(1420f), vx(1850f), vy(92f), vs(22f), t)
        powerReadout(canvas, vx(1420f), vx(1850f), vy(146f), t, textSize = sizeValue)
        kmChart(canvas, vx(1420f), vx(1850f), vy(168f), vy(288f), t, compact = false)
    }

    /**
     * The narrow pane is a dp-for-unit layout with no block headings, so every
     * figure owns its own row and the rows are checked against each other's
     * width. The only two things that share a row are the battery glyph and the
     * state of charge, which is sized and centred against the glyph rather than
     * placed on a baseline of its own.
     */
    private fun drawNarrow(canvas: Canvas, t: VehicleTelemetry, frameTimeSec: Double) {
        val soc = t[VehicleSignal.SOC_PERCENT]
        val socColor = socColor(soc, t.charging)

        // ---- battery ----
        batteryGlyph(
            canvas,
            x = vx(0f), y = vy(14f), boxWidth = vx(196f), boxHeight = vs(62f),
            fraction = (soc ?: 0.0) / 100.0, filled = soc != null,
            color = socColor, charging = t.charging, frameTimeSec = frameTimeSec,
        )
        percentFigure(canvas, rightX = vx(368f), centreY = vy(45f), value = soc, sizeV = 46f, color = socColor)
        if (t.charging) {
            icon(canvas, icons?.charging, vx(0f), vy(100f) - vs(13f), vs(16f), PanelPalette.BLUE)
            label(canvas, chargeLine(t), vx(21f), vy(100f), NARROW_SECONDARY, PanelPalette.BLUE)
        }

        hairline(canvas, vx(0f), vy(116f), vx(368f), vy(116f))

        // ---- electrics ----
        val traction = t[VehicleSignal.PACK_VOLT]
        label(canvas, "тяга", vx(0f), vy(150f), sizeLabel, muted())
        value(canvas, volts(traction, 0), vx(368f), vy(156f), 32f, tractionColor(traction), Paint.Align.RIGHT, bold = true)
        tractionGauge(canvas, vx(0f), vx(368f), vy(170f), vs(16f), traction)

        val rail = t[VehicleSignal.RAIL_12V]
        label(canvas, "бортовая сеть", vx(0f), vy(228f), sizeLabel, muted())
        value(canvas, volts(rail, 1), vx(368f), vy(228f), 20f, railColor(rail), Paint.Align.RIGHT)
        label(canvas, "ячейки ${cellWindowText(t)}", vx(0f), vy(254f), NARROW_SECONDARY, muted())
        value(canvas, spreadText(t), vx(368f), vy(254f), 16f, spreadColor(t), Paint.Align.RIGHT)
        label(canvas, packDetailLine(t), vx(0f), vy(276f), sizeTiny, muted(0.75f))

        hairline(canvas, vx(0f), vy(292f), vx(368f), vy(292f))

        // ---- temperatures ----
        temperatureRows(canvas, nameX = vx(0f), trackStart = vx(104f), trackEnd = vx(244f), valueX = vx(368f), t = t,
            rowY = floatArrayOf(vy(322f), vy(364f), vy(406f)))

        hairline(canvas, vx(0f), vy(430f), vx(368f), vy(430f))

        // ---- consumption ----
        label(canvas, "расход", vx(0f), vy(464f), sizeLabel, muted())
        consumptionFigure(canvas, vx(368f), vy(470f), 32f, t)
        powerBar(canvas, vx(0f), vx(368f), vy(484f), vs(20f), t)
        powerReadout(canvas, vx(0f), vx(368f), vy(526f), t, textSize = 18f)
        kmChart(canvas, vx(0f), vx(368f), vy(542f), vy(634f), t, compact = true)
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
     * A percentage as one composed figure: the number, then a smaller sign a
     * fixed fraction of the type size away, the pair centred on [centreY]
     * rather than sitting on a baseline. A plain "72 %" string put the sign a
     * full monospace cell away and left the whole figure riding low against the
     * battery beside it.
     */
    private fun percentFigure(canvas: Canvas, rightX: Float, centreY: Float, value: Double?, sizeV: Float, color: Int) {
        val baseline = centreY + vs(sizeV * FIGURE_CENTRE)
        if (value == null) {
            value(canvas, "—", rightX, baseline, sizeV, color, Paint.Align.RIGHT, bold = true)
            return
        }
        val signSize = sizeV * 0.5f
        val gap = vs(sizeV * 0.12f)
        value(canvas, "%", rightX, baseline, signSize, PanelPalette.alpha(color, 0.75f), Paint.Align.RIGHT, bold = true)
        val signWidth = valueWidth("%", signSize, bold = true)
        value(canvas, "${value.roundToInt()}", rightX - signWidth - gap, baseline, sizeV, color, Paint.Align.RIGHT, bold = true)
    }

    /**
     * Traction voltage against a deliberately narrow span. The pack sits near
     * 550 V across most of the charge window, so a wide scale would show a
     * needle that never moves; over [TRACTION_LO]..[TRACTION_HI] the sag under
     * acceleration and the lift under regeneration are both visible.
     */
    private fun tractionGauge(canvas: Canvas, x0: Float, x1: Float, y: Float, height: Float, volts: Double?) {
        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.10f)
        canvas.drawRect(x0, y, x1, y + height, fill)

        fun position(v: Double): Float =
            x0 + (x1 - x0) * ((v - TRACTION_LO) / (TRACTION_HI - TRACTION_LO)).coerceIn(0.0, 1.0).toFloat()

        fill.color = PanelPalette.alpha(PanelPalette.MINT, 0.14f)
        canvas.drawRect(position(TRACTION_BAND_LO), y, position(TRACTION_BAND_HI), y + height, fill)

        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.35f)
        stroke.strokeWidth = vs(1f)
        var tick = TRACTION_LO
        while (tick <= TRACTION_HI) {
            canvas.drawLine(position(tick), y + height, position(tick), y + height + vs(5f), stroke)
            tick += TRACTION_STEP
        }
        val scaleY = y + height + vs(sizeTiny * 1.3f)
        label(canvas, "${TRACTION_LO.roundToInt()}", x0, scaleY, sizeTiny, muted(0.5f))
        label(canvas, "${TRACTION_HI.roundToInt()} В", x1, scaleY, sizeTiny, muted(0.5f), Paint.Align.RIGHT)

        if (volts == null) return
        needle(canvas, position(volts), y, height, tractionColor(volts))
    }

    /** A triangular pointer sitting on a track — the one dial flourish here. */
    private fun needle(canvas: Canvas, x: Float, y: Float, height: Float, color: Int) {
        val wing = vs(6f)
        path.reset()
        path.moveTo(x, y + height * 0.55f)
        path.lineTo(x - wing, y - vs(6f))
        path.lineTo(x + wing, y - vs(6f))
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.drawRect(x - vs(1.2f), y, x + vs(1.2f), y + height, fill)
    }

    /**
     * Pack power, centred on zero: right is energy leaving the battery, left is
     * energy coming back. This is the block's always-live element — unlike
     * kWh/100 km it is defined at a standstill, which is where the panel spends
     * a good part of its life.
     *
     * The scale is square-root, not linear. A linear bar over the few hundred
     * kilowatts this car can actually pull would leave cruising pinned to the
     * centre; square root spends most of the travel on the first 50 kW and
     * still never hits the end stop.
     */
    private fun powerBar(canvas: Canvas, x0: Float, x1: Float, y: Float, height: Float, t: VehicleTelemetry) {
        val centre = (x0 + x1) / 2f
        val halfWidth = (x1 - x0) / 2f

        fill.color = PanelPalette.alpha(PanelPalette.MUTED, 0.10f)
        canvas.drawRect(x0, y, x1, y + height, fill)

        stroke.color = PanelPalette.alpha(PanelPalette.MUTED, 0.3f)
        stroke.strokeWidth = vs(1f)
        POWER_TICKS_KW.forEach { kw ->
            val offset = halfWidth * powerPosition(kw)
            canvas.drawLine(centre + offset, y + height, centre + offset, y + height + vs(4f), stroke)
            canvas.drawLine(centre - offset, y + height, centre - offset, y + height + vs(4f), stroke)
        }

        val kw = flowKw(t)
        if (kw != null) {
            val offset = halfWidth * powerPosition(kw)
            fill.color = if (kw < 0.0) {
                PanelPalette.alpha(PanelPalette.BLUE, 0.9f)
            } else {
                PanelPalette.mix(PanelPalette.MINT, PanelPalette.AMBER, min(1.0, abs(kw) / POWER_WARM_KW).toFloat())
            }
            canvas.drawRect(min(centre, centre + offset), y, max(centre, centre + offset), y + height, fill)
        }

        fill.color = PanelPalette.alpha(PanelPalette.INK, 0.55f)
        canvas.drawRect(centre - vs(1f), y - vs(3f), centre + vs(1f), y + height + vs(3f), fill)
    }

    private fun powerPosition(kw: Double): Float =
        (sign(kw) * sqrt(min(1.0, abs(kw) / POWER_FULL_KW))).toFloat()

    /** The kilowatt figure under the bar, with the direction spelled out. */
    private fun powerReadout(canvas: Canvas, x0: Float, x1: Float, y: Float, t: VehicleTelemetry, textSize: Float) {
        val kw = flowKw(t)
        val glyph = vs(sizeTiny * 1.3f)
        icon(canvas, icons?.flow, x0, y - glyph * 0.82f, glyph, flowColor(t))
        label(canvas, flowWord(t), x0 + glyph * 1.2f, y, sizeTiny, muted(0.8f))
        val text = kw?.let { "${if (it < -0.05) "−" else ""}${fmt(abs(it), 1)} кВт" } ?: "— кВт"
        value(canvas, text, x1, y, textSize, flowColor(t), Paint.Align.RIGHT)
    }

    /**
     * A Material Symbol tinted and placed by its top-left corner. Everything the
     * panel draws is laid out in virtual units, so [size] is one too.
     */
    private fun icon(canvas: Canvas, drawable: Drawable?, x: Float, top: Float, size: Float, color: Int) {
        if (drawable == null) return
        drawable.setTint(color)
        drawable.setBounds(
            x.roundToInt(), top.roundToInt(),
            (x + size).roundToInt(), (top + size).roundToInt(),
        )
        drawable.draw(canvas)
    }

    private fun temperatureRows(
        canvas: Canvas,
        nameX: Float,
        trackStart: Float,
        trackEnd: Float,
        valueX: Float,
        t: VehicleTelemetry,
        rowY: FloatArray,
    ) {
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, rowY[0], "батарея",
            t[VehicleSignal.PACK_TEMP_AVG], PACK_BAND_LO, PACK_BAND_HI,
            marks = listOf(t[VehicleSignal.PACK_TEMP_MIN], t[VehicleSignal.PACK_TEMP_MAX]),
        )
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, rowY[1], "инверторы",
            t[VehicleSignal.INVERTER_C], DRIVE_BAND_LO, DRIVE_BAND_HI,
        )
        // Three motors, three marks, three numbers: this car drives one front
        // and two rear units, and one of them running away from the others is
        // exactly what the row exists to show. A single averaged figure would
        // hide it.
        tempRow(
            canvas, nameX, trackStart, trackEnd, valueX, rowY[2], "моторы",
            t.hottestMotorC, DRIVE_BAND_LO, DRIVE_BAND_HI,
            marks = t.motorTemps,
            text = t.motorTemps.joinToString("·") { it?.roundToInt()?.toString() ?: "—" },
        )
    }

    /**
     * One temperature: name, a track carrying the range this reading is supposed
     * to live in, a bar to the value, and the number. Marks on the track are the
     * readings the bar cannot show — the coldest and hottest cell group, or each
     * individual motor.
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
        marks: List<Double?> = emptyList(),
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
        }
        fill.color = PanelPalette.alpha(PanelPalette.INK, 0.5f)
        marks.filterNotNull().forEach { mark ->
            canvas.drawRect(position(mark) - vs(1f), y - height, position(mark) + vs(1f), y + height, fill)
        }
        // Zero mark, so a reading below freezing is obvious without reading it.
        fill.color = PanelPalette.alpha(PanelPalette.INK, 0.28f)
        canvas.drawRect(position(0.0) - vs(0.5f), y - height, position(0.0) + vs(0.5f), y + height, fill)

        val readout = text ?: celsius?.let { "${it.roundToInt()} °C" } ?: "—"
        value(canvas, readout, valueX, y + vs(7f), sizeValue, color, Paint.Align.RIGHT)
    }

    /**
     * Consumption per slice of road, newest on the right, growing up from a zero
     * line that is not at the bottom of the chart: a slice that gave more back
     * than it took hangs below it. Downhill and a hard regenerative stop are
     * shapes here, not missing bars.
     */
    private fun kmChart(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        t: VehicleTelemetry,
        compact: Boolean,
    ) {
        val values = window.fold(t.consumption)
        val covered = window.coveredKm(t.consumption)
        chartBounds.set(x0, y0, x1, y1)
        val zeroY = y0 + (y1 - y0) * ABOVE_ZERO_SHARE
        hairline(canvas, x0, zeroY, x1, zeroY, 0.28f)
        if (values.isEmpty()) {
            label(canvas, EMPTY_CHART, x0, zeroY - vs(sizeLabel * 0.6f), sizeLabel, muted(0.6f))
            return
        }

        val positives = values.filter { it > 0.0 }
        val posScale = ceilToStep(max(CHART_MIN_TOP, positives.maxOrNull() ?: 0.0), 10.0)
        val negScale = ceilToStep(max(CHART_MIN_BOTTOM, values.minOrNull()?.let { -it } ?: 0.0), 5.0)
        val average = values.average()
        val reference = if (positives.isEmpty()) posScale / 2.0 else positives.average()

        val slot = (x1 - x0) / values.size
        val barWidth = slot * BAR_FILL
        values.forEachIndexed { index, v ->
            val left = x0 + index * slot + (slot - barWidth) / 2f
            val newest = index == values.lastIndex
            if (v < 0.0) {
                val depth = (y1 - zeroY) * (-v / negScale).coerceIn(0.0, 1.0).toFloat()
                fill.color = PanelPalette.alpha(PanelPalette.BLUE, if (newest) 1f else 0.72f)
                canvas.drawRect(left, zeroY, left + barWidth, zeroY + max(vs(2f), depth), fill)
                return@forEachIndexed
            }
            val height = max(vs(2f), (zeroY - y0) * (v / posScale).coerceIn(0.0, 1.0).toFloat())
            val color = when {
                v <= reference * 0.85 -> PanelPalette.MINT
                v <= reference * 1.15 -> PanelPalette.mix(PanelPalette.MINT, PanelPalette.AMBER, 0.55f)
                v <= reference * 1.5 -> PanelPalette.AMBER
                else -> PanelPalette.DANGER
            }
            fill.color = if (newest) color else PanelPalette.alpha(color, 0.72f)
            canvas.drawRect(left, zeroY - height, left + barWidth, zeroY, fill)
        }

        val averageY = if (average >= 0.0) {
            zeroY - (zeroY - y0) * (average / posScale).coerceIn(0.0, 1.0).toFloat()
        } else {
            zeroY + (y1 - zeroY) * (-average / negScale).coerceIn(0.0, 1.0).toFloat()
        }
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
            label(
                canvas, "−${fmt(covered, 1)} км · окно ${window.label}",
                x0, y1 + vs(20f), sizeTiny, muted(0.5f),
            )
            label(canvas, "сейчас", x1, y1 + vs(20f), sizeTiny, muted(0.5f), Paint.Align.RIGHT)
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
     * deliberately kept at full width: it is the same quantity the dashboard
     * shows, read from the other side, and a growing gap between them is worth
     * seeing. The narrow pane has no room to spend on a second opinion.
     */
    private fun packSummaryLine(t: VehicleTelemetry): String {
        val bms = t[VehicleSignal.BMS_SOC_PERCENT]?.let { "BMS ${fmt(it, 1)} %" } ?: "BMS —"
        val soh = t[VehicleSignal.SOH_PERCENT]?.let { "ресурс ${it.roundToInt()} %" } ?: "ресурс —"
        return "$bms · $soh"
    }

    private fun rangeLine(t: VehicleTelemetry): String =
        t[VehicleSignal.RANGE_KM]?.let { "запас хода ${it.roundToInt()} км" } ?: "запас хода —"

    private fun chargeLine(t: VehicleTelemetry): String {
        val power = t[VehicleSignal.CHARGE_KW]?.let { "${fmt(it, 1)} кВт" } ?: "—"
        val hours = t[VehicleSignal.CHARGE_HOURS]?.roundToInt()
        val minutes = t[VehicleSignal.CHARGE_MINUTES]?.roundToInt()
        if (hours == null && minutes == null) return power
        return "$power · полный ${hours ?: 0}:${pad2(minutes ?: 0)}"
    }

    private fun cellWindowText(t: VehicleTelemetry): String {
        val window = t.cellWindowVolt ?: return "—"
        return "${fmt(window.first, 3)}–${fmt(window.second, 3)} В"
    }

    private fun spreadText(t: VehicleTelemetry): String =
        t.cellSpreadMv?.let { "${it.roundToInt()} мВ" } ?: "—"

    /** What the pack is made of, and the one number that only matters broken. */
    private fun packDetailLine(t: VehicleTelemetry): String {
        val cells = t[VehicleSignal.CELL_COUNT]?.let { "${it.roundToInt()} ячеек" }
        val insulation = t.insulationMohm?.let { "изоляция ${fmt(it, 1)} МОм" }
        return listOfNotNull(cells, insulation).joinToString(" · ").ifEmpty { "—" }
    }

    /**
     * The figure that moves with the road, and the word that replaces it when
     * there is no road: energy per kilometre has no value at zero speed, so a
     * standing car says so rather than showing a bare dash that reads as a
     * broken sensor. The power bar right below keeps reading either way.
     */
    private fun consumptionFigure(canvas: Canvas, rightX: Float, baseline: Float, sizeV: Float, t: VehicleTelemetry) {
        val reading = t.currentConsumption
        if (reading != null) {
            val color = if (reading < 0.0) PanelPalette.BLUE else PanelPalette.INK
            value(canvas, fmt(reading, 1), rightX, baseline, sizeV, color, Paint.Align.RIGHT, bold = true)
            return
        }
        val text = if (t.stationary) STANDING else "—"
        value(canvas, text, rightX, baseline, sizeV * 0.55f, muted(0.7f), Paint.Align.RIGHT)
    }

    /** Pack power as the panel talks about it: charging is negative flow too. */
    private fun flowKw(t: VehicleTelemetry): Double? {
        if (t.charging) t[VehicleSignal.CHARGE_KW]?.let { return -abs(it) }
        return t.loadKw
    }

    private fun flowWord(t: VehicleTelemetry): String {
        val kw = flowKw(t) ?: return "поток"
        return when {
            t.charging -> "заряд"
            kw < -REGEN_FLOOR_KW -> "рекуперация"
            else -> "нагрузка"
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

    private fun tractionColor(volts: Double?): Int = when {
        volts == null -> muted(0.6f)
        volts < TRACTION_BAND_LO || volts > TRACTION_BAND_HI -> PanelPalette.AMBER
        else -> PanelPalette.INK
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
        val kw = flowKw(t) ?: return muted()
        return when {
            t.charging -> PanelPalette.BLUE
            kw < -REGEN_FLOOR_KW -> PanelPalette.BLUE
            else -> PanelPalette.mix(PanelPalette.MINT, PanelPalette.AMBER, min(1.0, kw / POWER_WARM_KW).toFloat())
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
        max(step, kotlin.math.ceil(value / step) * step)

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

        // Narrow pane: a unit is a dp, so these are the sizes as drawn.
        const val NARROW_TINY = 12f
        const val NARROW_LABEL = 15f
        const val NARROW_VALUE = 18f
        const val NARROW_HEADER = 16f

        /** Supporting lines under a figure; small on purpose, still legible. */
        const val NARROW_SECONDARY = 14f

        /**
         * Baseline offset that puts a line of type's optical centre on a given
         * y. Roughly the cap-height half of the sans/mono faces in use.
         */
        const val FIGURE_CENTRE = 0.36f

        const val SEGMENTS = 12

        const val TEMP_LO = -20.0
        const val TEMP_HI = 80.0
        const val HOT_MARGIN = 15.0

        const val PACK_BAND_LO = 15.0
        const val PACK_BAND_HI = 40.0
        const val DRIVE_BAND_LO = 0.0
        const val DRIVE_BAND_HI = 70.0

        const val RAIL_GOOD_LO = 12.8
        const val RAIL_GOOD_HI = 14.6

        // 166 LFP cells sit near 550 V and rarely leave this stretch; the span is
        // narrow so that sag under load actually moves the needle.
        const val TRACTION_LO = 490.0
        const val TRACTION_HI = 610.0
        const val TRACTION_STEP = 20.0
        const val TRACTION_BAND_LO = 520.0
        const val TRACTION_BAND_HI = 590.0

        /** Full deflection of the power bar's square-root scale. */
        const val POWER_FULL_KW = 300.0

        /** Where the load colour has finished warming from mint to amber. */
        const val POWER_WARM_KW = 120.0

        /** Below this a negative reading is noise, not regeneration. */
        const val REGEN_FLOOR_KW = 0.5

        val POWER_TICKS_KW = doubleArrayOf(20.0, 60.0, 150.0)

        const val CELL_SPREAD_WATCH_MV = 25.0
        const val CELL_SPREAD_ALERT_MV = 40.0

        const val CHART_MIN_TOP = 10.0
        const val CHART_MIN_BOTTOM = 5.0

        /** Share of the chart above the zero line; the rest is regeneration. */
        const val ABOVE_ZERO_SHARE = 0.74f

        /** Bars nearly touch: the shape of the run matters more than each bar. */
        const val BAR_FILL = 0.82f

        const val READING = "Читаю данные машины…"
        const val NO_DATA = "Нет доступа к данным машины"
        const val EMPTY_CHART = "накапливаю первые 200 метров"
        const val STANDING = "стоим"
    }
}
