package dev.denza.apps.feature.trip

import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.Path
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import dev.denza.apps.design.luminofor.ThermalGlyphs
import kotlin.math.max
import kotlin.math.min

/**
 * The strip's second page: what the pack is doing, how warm five components are, and what the
 * last ten kilometres cost.
 *
 * `luminofor.js`, `drawHead()`, the car page, line for line: the Luminofor board is the design and
 * `tools/design-canvas/luminofor/spec.json` its numbers. One row of readings over one chart:
 *
 *  - **the pack's flow is the page's hero** - its direction in words over its magnitude, blue when
 *    energy is coming back and behind the blue mark when a source is named. Centred on the full
 *    screen, first in the row on the two-thirds pane, alone on its line on the one-third;
 *  - **the voltage and the five temperatures** - the temperatures captioned by the Contour's own
 *    glyphs ([ThermalGlyphs]), the part of a glyph that means something lit in the reading's colour,
 *    which is white until a cell leaves its band;
 *  - **the engine's cell and the trip's** - the engine only while it has something to say, the trip
 *    flush right on the full screen;
 *  - **the chart**, under «Расход 16,9 кВт·ч/100 км · за 10 км»: a white step line over a faint
 *    field, the returns as blue shapes under the zero, a dot at the newest point.
 *
 * Every word and figure comes in through the [StripModel], decided by `EnergyReadouts` for both
 * screens (`docs/energy-display-contract.md`); this class owns the geometry and nothing else. What
 * the older page drew and this one does not - the axis gutter's «60» and «−20», the shelf's tracks
 * and zones, the cell spread's row - is not on the board.
 *
 * Nothing is allocated in a frame: the chart's paths are rebuilt when its points or its box move,
 * which is four times a second at most, and reused in between.
 */
internal class VehiclePageRenderer {

    private val placement = StripGeometry.CarPlacement()
    private val wrapped = StripInk.Wrapped()

    private val step = Path()
    private val fillUp = Path()
    private val fillDown = Path()
    private val zeroLine = Path()
    private val endDot = Path()
    private val upPaint = plus(LightPen.alpha(HeadInk.WHITE.halo, Head.Chart.FILL_UP))
    private val downPaint = plus(LightPen.alpha(HeadInk.BLUE.halo, Head.Chart.FILL_DOWN))

    private var chartPoints: FloatArray? = null
    private var chartCount = -1
    private var chartX = Float.NaN
    private var chartY = Float.NaN
    private var chartW = Float.NaN
    private var chartH = Float.NaN
    private var endLight: Light = HeadInk.WHITE

    fun draw(ink: StripInk, layout: TripPanelLayout, model: StripModel, left: Float, right: Float) {
        if (model.closed) {
            closed(ink, layout, model, left, right)
            return
        }
        when (layout) {
            TripPanelLayout.WIDE -> full(ink, model, left, right)
            TripPanelLayout.MEDIUM -> two(ink, model, left, right)
            TripPanelLayout.NARROW -> one(ink, model, left, right)
        }
    }

    private fun full(ink: StripInk, model: StripModel, left: Float, right: Float) {
        val s = Head.Full.Strip
        val c = Head.Full.Car
        StripGeometry.fullCar(model, left, right, ink.measure, placement)
        ink.reading(model.power, placement.power, s.CAPTION, s.VALUE, c.HERO_SIZE, s.LABEL_SIZE)
        ink.reading(model.volts, placement.volts, s.CAPTION, s.VALUE, s.VALUE_SIZE, s.LABEL_SIZE)
        temperatures(ink.pen, model, placement.temps, s.CAPTION, s.VALUE, c.TEMP_PITCH, c.TEMP_SIZE)
        ink.reading(model.engine, placement.engine, s.CAPTION, s.VALUE, s.VALUE_SIZE, s.LABEL_SIZE)
        ink.reading(model.tripCell, placement.tripCell, s.CAPTION, s.VALUE, s.VALUE_SIZE, s.LABEL_SIZE)
        spend(ink, model, left, c.CHART_CAPTION, c.CHART_CAPTION_SIZE)
        chart(ink.pen, model, left, c.CHART_TOP, right - left, c.CHART_HEIGHT)
    }

    private fun two(ink: StripInk, model: StripModel, left: Float, right: Float) {
        val c = Head.Two.Car
        val label = Head.Two.Sound.LABEL_SIZE
        StripGeometry.twoCar(model, left, ink.measure, placement)
        ink.reading(model.power, placement.power, c.CAPTION, c.VALUE, c.HERO_SIZE, label)
        ink.reading(model.engine, placement.engine, c.CAPTION, c.VALUE, c.VALUE_SIZE, label)
        ink.reading(model.tripCell, placement.tripCell, c.CAPTION, c.VALUE, c.VALUE_SIZE, label)
        ink.reading(model.volts, placement.volts, c.ROW2_CAPTION, c.ROW2_VALUE, c.VOLT_SIZE, label)
        temperatures(ink.pen, model, placement.temps, c.ROW2_CAPTION, c.ROW2_VALUE, c.TEMP_PITCH, c.TEMP_SIZE)
        // The pane's caption is the full screen's size: the board writes the literal 15 here.
        spend(ink, model, left, c.CHART_CAPTION, Head.Full.Car.CHART_CAPTION_SIZE)
        chart(ink.pen, model, left, c.CHART_TOP, right - left, c.CHART_HEIGHT)
    }

    private fun one(ink: StripInk, model: StripModel, left: Float, right: Float) {
        val c = Head.One.Car
        val label = Head.One.Sound.LABEL_SIZE
        StripGeometry.oneCar(model, left, ink.measure, placement)
        ink.reading(model.power, placement.power, c.CAPTION, c.VALUE, c.HERO_SIZE, label)
        ink.reading(model.volts, placement.volts, c.ROW2_CAPTION, c.ROW2_VALUE, c.ROW2_SIZE, label)
        ink.reading(model.engine, placement.engine, c.ROW2_CAPTION, c.ROW2_VALUE, c.ROW2_SIZE, label)
        temperatures(ink.pen, model, placement.temps, c.TEMPS_CAPTION, c.TEMPS_VALUE, c.TEMP_PITCH, c.TEMP_SIZE)
        spend(ink, model, left, c.CHART_CAPTION, c.CHART_CAPTION_SIZE)
        chart(ink.pen, model, left, c.CHART_TOP, right - left, c.CHART_HEIGHT)
    }

    /**
     * The board's `tempsRow`: five glyphs as the five captions, five figures under them.
     *
     * A cell out of its band lights its glyph's part and its figure in the cluster's own orange or
     * red, blurred, and nothing else changes: the thresholds are `ContourReadout`'s, so the two
     * screens cannot disagree about hot. A cell that has not answered keeps its glyph and loses its
     * figure, as every caption on this page does.
     */
    private fun temperatures(
        pen: LightPen,
        model: StripModel,
        x0: Float,
        capY: Float,
        valY: Float,
        pitch: Float,
        size: Float,
    ) {
        for (index in 0 until StripModel.TEMPERATURES) {
            val cell = model.temps[index]
            val x = x0 + index * pitch
            val hot = cell.heat != StripHeat.NORMAL
            val light = when (cell.heat) {
                StripHeat.DANGER -> ClusterInk.RED
                StripHeat.WARNING -> ClusterInk.ORANGE
                StripHeat.NORMAL -> HeadInk.WHITE
            }
            ThermalGlyphs.draw(pen, CELLS[index], x, capY + GLYPH_DROP, light, if (hot) 1f else GLYPH_LEVEL, hot)
            cell.figure?.let { pen.figures(it, x, valY, size, light, 1f) }
        }
    }

    /**
     * «Расход 16,9 кВт·ч/100 км · за 10 км», at 0.8 of white - in three runs, because the figure
     * is the one signed number on either screen and a negative one is printed blue (contract §2.2).
     * No figure, no line: a car that has not driven has no consumption to name.
     */
    private fun spend(ink: StripInk, model: StripModel, left: Float, baseline: Float, size: Float) {
        val figure = model.spendFigure ?: return
        val alpha = Head.Chart.CAPTION_ALPHA
        val space = ink.measure.sans(" ", size, false)
        var x = left + ink.label(model.spendWord, left, baseline, size, alpha) + space
        val light = if (model.spendNegative) HeadInk.BLUE else HeadInk.WHITE
        x += ink.pen.text(figure, x, baseline, size, light, alpha, LightPen.Face.SANS) + space
        ink.label(model.spendWindow, x, baseline, size, alpha)
    }

    /**
     * The board's `carChart`: the last ten kilometres, a hundred points on the contract's own
     * ladder - zero three quarters of the way down, sixty up to the top, twenty down to the foot,
     * clamped at both.
     *
     * One pitch for the hundred points and a window still filling anchored at the right edge, as
     * the cluster's trace draws it and as the contract says (§2.3, «за 3,7 км» is 37 % of the box);
     * the board, whose scenes are all full, spreads whatever it has over the width, and for a
     * hundred points the two are the same drawing. The zero spans the box either way.
     */
    private fun chart(pen: LightPen, model: StripModel, x0: Float, y0: Float, w: Float, h: Float) {
        val c = Head.Chart
        val zero = y0 + h * c.ZERO_AT
        val count = min(model.chartCount, model.chart.size)
        if (count > 0) {
            buildChart(model.chart, count, x0, y0, w, h)
            val canvas = pen.canvas
            canvas.drawPath(pen.toPx(fillUp), upPaint)
            canvas.drawPath(pen.toPx(fillDown), downPaint)
            pen.beam(step, c.STROKE, HeadInk.WHITE, LINE_INTENSITY)
        }
        zeroLine.reset()
        zeroLine.moveTo(x0, zero)
        zeroLine.lineTo(x0 + w, zero)
        pen.beam(zeroLine, c.ZERO_STROKE, HeadInk.WHITE, c.ZERO_ALPHA)
        if (count > 0) pen.glowFill(endDot, endLight, 1f, END_BLUR)
    }

    private fun buildChart(points: FloatArray, count: Int, x0: Float, y0: Float, w: Float, h: Float) {
        if (points === chartPoints && count == chartCount && x0 == chartX && y0 == chartY &&
            w == chartW && h == chartH
        ) {
            return
        }
        chartPoints = points
        chartCount = count
        chartX = x0
        chartY = y0
        chartW = w
        chartH = h

        val c = Head.Chart
        val bottom = y0 + h
        val zero = y0 + h * c.ZERO_AT
        val pitch = w / POINTS
        val start = x0 + w - count * pitch
        val first = points.size - count
        step.reset()
        fillUp.reset()
        fillDown.reset()
        fillUp.moveTo(start, zero)
        fillDown.moveTo(start, zero)
        for (i in 0 until count) {
            val v = points[first + i]
            val a = start + i * pitch
            val b = a + pitch
            val yu = up(max(0f, v), zero, y0)
            val yd = if (v < 0f) down(v, zero, bottom) else zero
            if (i == 0) step.moveTo(a, yu)
            step.lineTo(a, yu)
            step.lineTo(b, yu)
            fillUp.lineTo(a, yu)
            fillUp.lineTo(b, yu)
            fillDown.lineTo(a, yd)
            fillDown.lineTo(b, yd)
        }
        fillUp.lineTo(x0 + w, zero)
        fillUp.close()
        fillDown.lineTo(x0 + w, zero)
        fillDown.close()

        val last = points[points.size - 1]
        endDot.reset()
        endDot.addCircle(
            x0 + w,
            if (last >= 0f) up(last, zero, y0) else down(last, zero, bottom),
            c.DOT,
            Path.Direction.CW,
        )
        endLight = if (last < 0f) HeadInk.BLUE else HeadInk.WHITE
    }

    private fun up(v: Float, zero: Float, top: Float): Float =
        zero - (zero - top) * min(1f, v / Head.Chart.UP_TO)

    private fun down(v: Float, zero: Float, bottom: Float): Float =
        zero + (bottom - zero) * min(1f, -v / Head.Chart.DOWN_TO)

    /**
     * The car closed to us: «Питание от машины» on the caption line and the instruction under it,
     * where the readings would be, in Roboto at the page's title size, broken at its spaces to the
     * page's width. Nothing else - the cluster's own unavailable scene is its skeleton and the
     * message, and a row of empty captions under an instruction is a page pretending to be up.
     */
    private fun closed(ink: StripInk, layout: TripPanelLayout, model: StripModel, left: Float, right: Float) {
        val capY: Float
        val valY: Float
        val label: Float
        val size: Float
        when (layout) {
            TripPanelLayout.WIDE -> {
                capY = Head.Full.Strip.CAPTION
                valY = Head.Full.Strip.VALUE
                label = Head.Full.Strip.LABEL_SIZE
                size = Head.Full.Strip.TITLE_SIZE
            }
            TripPanelLayout.MEDIUM -> {
                capY = Head.Two.Car.CAPTION
                valY = Head.Two.Car.VALUE
                label = Head.Two.Sound.LABEL_SIZE
                size = Head.Two.Sound.TITLE_SIZE
            }
            TripPanelLayout.NARROW -> {
                capY = Head.One.Car.CAPTION
                valY = Head.One.Car.VALUE
                label = Head.One.Sound.LABEL_SIZE
                size = Head.One.Sound.TITLE_SIZE
            }
        }
        ink.label(VehiclePageWords.CLOSED, left, capY, label)
        val lines = ink.wrap(wrapped, model.message, size, right - left)
        for (index in lines.indices) {
            ink.label(lines[index], left, valY + index * size * MESSAGE_LEADING, size)
        }
    }

    internal companion object {
        /** The chart's pitch is a hundredth of its box: the contract's hundred points. */
        const val POINTS = 100

        const val LINE_INTENSITY = 0.95f
        const val END_BLUR = 8f

        /** A glyph stands five dp under its caption line, lit at 0.9 until it is hot. */
        const val GLYPH_DROP = 5f
        const val GLYPH_LEVEL = 0.9f

        /** Between two lines of the closed page's instruction. */
        const val MESSAGE_LEADING = 1.25f

        private val CELLS = arrayOf(
            ThermalGlyphs.Cell.PACK,
            ThermalGlyphs.Cell.FRONT,
            ThermalGlyphs.Cell.REAR_LEFT,
            ThermalGlyphs.Cell.REAR_RIGHT,
            ThermalGlyphs.Cell.INVERTER,
        )

        private fun plus(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            blendMode = BlendMode.PLUS
            this.color = color
        }
    }
}
