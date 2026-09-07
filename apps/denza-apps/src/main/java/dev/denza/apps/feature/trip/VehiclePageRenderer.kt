package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.design.instrument.InstrumentPen
import dev.denza.apps.feature.cluster.dashboard.ContourFlow
import dev.denza.apps.feature.cluster.dashboard.ContourGlyphs
import dev.denza.apps.feature.cluster.dashboard.ContourPlan
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.cluster.dashboard.ContourRuns
import dev.denza.apps.feature.cluster.dashboard.GlyphSurface
import dev.denza.apps.feature.panel.PanelPalette
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The strip's second page: what the pack is doing, what it has been doing over the last ten
 * kilometres, and how warm five components are.
 *
 * `tools/design-canvas/StripPages.dc.html` is the board, `StripPagesBoardContractTest` joins the
 * two, and the rules the page is built on are the Contour's own - it went through nine passes to
 * learn them and there is no reason for this screen to learn them again:
 *
 *  - **one quantity, one sentence.** The headline is words - `ИЗ БАТАРЕИ`, `● В БАТАРЕЮ ОТ ДВС` -
 *    and the figure under it says how much, unsigned, in the colour of its own direction. The word
 *    and the colour are decided in one place with the cluster's (`EnergyReadouts`), which is what
 *    the page printing «В БАТАРЕЮ» over «−25 кВт» cost;
 *  - **a figure names the window it is true over**: `ЗА 10 КМ` after the consumption, and the
 *    shape above it *is* those ten kilometres - the same twenty bins on the same ladder the
 *    cluster's petal draws (`docs/energy-display-contract.md` §2.3);
 *  - **a zero is never drawn, and a quantity that did not happen has no cell.** The engine's cell
 *    is absent until the engine has run, the consumption is absent while the car stands;
 *  - **colour marks an exception.** A temperature that crosses its own band takes the figure, the
 *    fill and the glyph's own component to amber together, so a hot cell lights as one object.
 *
 * ### What is not on it
 *
 * The current, because this firmware cannot stand behind it - one reading, parked, on a charger,
 * named *charge* current, sign unproven - and because on a pack whose voltage barely moves amps
 * are kilowatts drawn twice. The 12 V rail, the charge, the range and the fuel, because the car's
 * own displays carry all four and this page exists for what they do not show.
 *
 * ### Drawn like the analyser, not like a panel of its own
 *
 * The caller owns the box and the scale, exactly as it does for [SpectrumRenderer]: everything
 * below is in the strip's own units and multiplied by [unit]. Nothing is allocated in a frame.
 */
internal class VehiclePageRenderer {

    private val caps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = TripPanelRenderer.CAPS_TRACKING
    }
    private val figures = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val units = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.BUTT
    }
    private val box = RectF()
    private val glyphs = ContourGlyphs()

    /**
     * The cluster's pen, for the one shape both screens draw.
     *
     * Its virtual unit is set to this page's own dp on every draw, so `ContourPlan`'s strokes,
     * ticks and gaps are stated here in the numbers the cluster states them in and the two charts
     * cannot be two drawings.
     */
    private val pen = InstrumentPen()

    /**
     * Every energy string and shape this page draws, decided once for both screens.
     *
     * The renderer owns geometry and nothing else. Held rather than built per draw, because it
     * memoises its strings and it carries this screen's own neutral-zone hysteresis.
     */
    private val readouts = EnergyReadouts()

    /** The chart's bin edges and heights, fields so a draw allocates nothing. */
    private val chartXs = FloatArray(ConsumptionChart.BINS + 1)
    private val chartYs = FloatArray(ConsumptionChart.BINS)
    private val returnYs = FloatArray(ConsumptionChart.BINS)
    private val spanXs = FloatArray(ConsumptionChart.BINS + 1)
    private val spanYs = FloatArray(ConsumptionChart.BINS)
    private val surface = CanvasGlyphSurface()
    private val shelf: Array<Row> = Array(SENSORS.size + 1) { index ->
        if (index < SENSORS.size) {
            val sensor = SENSORS[index]
            Row(
                glyph = sensor.glyph,
                watch = sensor.band,
                top = sensor.band + HOT_MARGIN,
            )
        } else {
            // The spread's own window, built the way a temperature's is: the alert, and as much
            // again past it as separates the alert from the watch.
            Row(
                glyph = null,
                watch = ContourReadout.SPREAD_WATCH_MV.toFloat(),
                top = ContourReadout.SPREAD_ALERT_MV.toFloat(),
                unit = VehiclePageWords.UNIT_MV,
            )
        }
    }

    /**
     * @param left the field's own left edge in pixels; the dots below it are the caller's
     * @param unit pixels per strip unit, which is one dp in every window this app is given
     * @param narrow the 416 pane's composition: the marks stand over their figures and the
     *   temperatures take a row of their own under the shape
     */
    fun draw(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        unit: Float,
        narrow: Boolean,
    ) {
        val width = (right - left) / unit
        val height = (bottom - top) / unit
        if (width <= 0f || height <= 0f) return

        // One read per draw: the word, the figure, the window and the twenty bins, decided where
        // the cluster decides them.
        readouts.read(telemetry, telemetry.parked == true, narrow = narrow)

        if (narrow) {
            drawNarrow(canvas, telemetry, left, top, width, height, unit)
            return
        }

        val leftWidth = leftColumnWidth(width)
        val ruleX = left + (leftWidth + RULE_MARGIN) * unit
        val rightLeft = ruleX + RULE_MARGIN * unit
        val centre = top + (height - COLUMN) * unit / 2f

        drawHead(canvas, telemetry, left, centre, leftWidth, unit)
        drawChart(canvas, left, centre + (BLOCK + GAP) * unit, leftWidth, CHART, unit)
        drawFoot(
            canvas, left, centre + (COLUMN - FOOT_BASELINE_UP) * unit,
            leftWidth * unit, unit, false,
        )

        val shelfHeight = SHELF_ROWS * ROW + (SHELF_ROWS - 1) * ROW_GAP
        val shelfTop = top + (height - shelfHeight) * unit / 2f
        drawShelf(canvas, telemetry, rightLeft, shelfTop, (right - rightLeft) / unit, unit)

        fill.color = PanelPalette.alpha(PanelPalette.MUTED, HAIRLINE_ALPHA)
        canvas.drawRect(ruleX, top, ruleX + unit, bottom, fill)
    }

    /**
     * The 416 pane, where there is no second column.
     *
     * Not the wide composition scaled down: the marks stand over their figures and the temperatures
     * take one row under the shape, which is the same rule the rest of this app follows at 416. A
     * column of two beside a 62 figure at 392 dp is two columns of about 180, and a five-cell shelf
     * does not fit in one of them.
     *
     * **The consumption stays.** It used to be the thing that went, and the contract's §5 says the
     * opposite: the figure and its window are what the shape above cannot be read without, so what
     * goes is the word «РАСХОД» and nothing else. If the stack still does not fit the pane's own
     * height, the layout's unit shrinks until it does - nothing is ever drawn past the pane.
     */
    private fun drawNarrow(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        unit: Float,
    ) {
        val stack = BLOCK + GAP + CHART_NARROW + GAP + CAP_LINE + GAP + STACK
        val scale = if (stack > height && stack > 0f) height / stack else 1f
        val u = unit * scale
        // The same pixels, restated in the smaller unit: a shrunk layout has more room across, not
        // less.
        val across = width * unit / u
        val down = height * unit / u
        val start = top + max(0f, (down - stack) * u / 2f)

        drawHead(canvas, telemetry, left, start, across, u)
        drawChart(canvas, left, start + (BLOCK + GAP) * u, across, CHART_NARROW, u)
        val footBaseline = start + (BLOCK + GAP + CHART_NARROW + GAP + LABEL_BASELINE) * u
        drawFoot(canvas, left, footBaseline, across * u, u, true)

        val rowTop = start + (BLOCK + GAP + CHART_NARROW + GAP + CAP_LINE + GAP) * u
        val cell = across / SHELF_ROWS
        readings(telemetry).forEachIndexed { index, reading ->
            val x = left + index * cell * u
            drawGlyph(canvas, reading, x, rowTop + STACK_GLYPH * u, u)
            figure(
                canvas, reading.text, x, rowTop + (STACK_GLYPH + STACK_GAP + STACK_FIGURE_UP) * u,
                STACK_FIGURE * u, reading.ink,
            )
            drawTrack(
                canvas, reading,
                x, rowTop + (STACK - STACK_TRACK) * u,
                STACK_TRACK_WIDTH * u, STACK_TRACK * u,
            )
        }
    }

    // ------------------------------------------------------------------ the sentence and the hero

    private fun drawHead(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        top: Float,
        width: Float,
        unit: Float,
    ) {
        val closed = telemetry.access == VehicleAccess.UNAVAILABLE

        if (closed) {
            caption(canvas, TITLE_CLOSED, left, top + LABEL_BASELINE * unit, unit)
            units.textSize = INSTRUCTION * unit
            units.color = PanelPalette.INK
            canvas.drawText(
                telemetry.message,
                left,
                top + (CAP_LINE + GAP + INSTRUCTION_BASELINE) * unit,
                units,
            )
            return
        }

        // The word, the mark and the colour all come from one place with the cluster's. The page
        // printed «В БАТАРЕЮ» over «−25 кВт» because the sentence and the sign were decided
        // separately; there is one decision now (`docs/energy-display-contract.md` §2.1).
        var x = left
        if (readouts.mark) {
            fill.color = DenzaPalette.RETURN
            canvas.drawCircle(
                left + MARK_RADIUS * unit,
                top + (LABEL_BASELINE - MARK_RISE) * unit,
                MARK_RADIUS * unit,
                fill,
            )
            x += (MARK_RADIUS * 2f + MARK_GAP) * unit
        }
        caption(canvas, readouts.word, x, top + LABEL_BASELINE * unit, unit)
        val headWidth = x - left + capsWidth(readouts.word, unit)

        // **The magnitude, never the sign.** The direction is the word above the figure and the
        // colour of the figure itself - two cues that cannot disagree, because one function decides
        // both. A minus in front of a number is not a direction anybody reads at a glance, and the
        // one place it survives on either screen is the consumption, which is signed because it is
        // an exception.
        val ink = when (readouts.flow) {
            ContourFlow.OUT -> PanelPalette.INK
            ContourFlow.BACK -> DenzaPalette.RETURN_INK
            ContourFlow.NEUTRAL -> PanelPalette.MUTED
        }
        val figureWidth = figure(
            canvas, readouts.powerFigure ?: DASH, left, top + HERO_BASELINE * unit, HERO * unit, ink,
        )
        units.textSize = UNIT_SIZE * unit
        units.color = PanelPalette.MUTED
        canvas.drawText(
            UNIT_KW,
            left + figureWidth + UNIT_GAP * unit,
            top + HERO_BASELINE * unit,
            units,
        )

        units.textSize = UNIT_SIZE * unit
        // The cell is as wide as the wider of its two lines, and its sentence is usually the wider
        // one: «● В БАТАРЕЮ ОТ ЗАРЯДКИ» is half again «-2,4 кВт». Measured off the figure alone,
        // the voltage beside it was placed under the tail of that sentence and the two captions
        // ran into each other on the car. The board never showed it because a flex row sizes a
        // cell by its widest child, which is exactly the rule this line was missing.
        val heroRight = left + maxOf(headWidth, figureWidth + UNIT_GAP * unit + units.measureText(UNIT_KW))

        // Right to left after that, and each cell is drawn only if it can stand clear of the one
        // before it. A cell drawn over a figure is worse than an absent one, and this row carries
        // three things whose widths all depend on what the car is doing.
        //
        // Which of the engine's three cells this is - revolutions, minutes, or nothing at all - is
        // `EnergyReadouts`', because the cluster's own corner draws the same cell and the two used
        // to decide it from different things.
        var free = left + width * unit
        val engineFigure = readouts.engineCellFigure
        if (engineFigure != null) {
            val engineTitle = readouts.engineCellTitleCaps
            val engineX = free - capsWidth(engineTitle, unit)
            if (engineX > heroRight + GROUP * unit) {
                caption(canvas, engineTitle, engineX, top + LABEL_BASELINE * unit, unit)
                figure(
                    canvas, engineFigure, engineX, top + SECOND_BASELINE * unit,
                    SECOND * unit, PanelPalette.INK,
                )
                free = engineX - GROUP * unit
            }
        }

        // The pack's voltage stands beside the kilowatts because on this pack it is the kilowatts
        // that move it - the resting voltage is flat across the whole charge window, and what
        // changes is the sag under load. It used to hang off the end of the shape's caption, where
        // the owner read it as «пришпилили куда-то вниз, непонятно к чему относится».
        val voltsFigure = readouts.voltsFigure ?: return
        val voltsX = heroRight + GROUP * unit
        if (voltsX + capsWidth(VehiclePageWords.TITLE_VOLTS, unit) > free) return
        caption(canvas, VehiclePageWords.TITLE_VOLTS, voltsX, top + LABEL_BASELINE * unit, unit)
        val voltsWidth = figure(
            canvas, voltsFigure, voltsX, top + SECOND_BASELINE * unit,
            SECOND * unit, PanelPalette.INK,
        )
        units.textSize = SECOND_UNIT * unit
        units.color = PanelPalette.MUTED
        canvas.drawText(
            VehiclePageWords.UNIT_V,
            voltsX + voltsWidth + LEAD * unit,
            top + SECOND_BASELINE * unit,
            units,
        )
    }

    // ------------------------------------------------------------------------------- the shape

    /**
     * The last ten kilometres, as the twenty steps the cluster's petal draws.
     *
     * `docs/energy-display-contract.md` §2.3: one chart on both screens, the same bins on the same
     * ladder, and the pixel height is the only thing that differs. It replaced two minutes of pack
     * power - a second history of the quantity the headline already shows, and the reason the two
     * screens' graphs could not be the same graph.
     *
     * Above the zero is what the road cost, below it is what it gave back, on a fixed linear ladder
     * of 0…40 up and 0…20 down. A bin the log has no energy for is a **hole**: nothing is drawn, the
     * zero rule continues under it, and the road it covers is still counted. A bin past a ceiling is
     * drawn to the ceiling with a tick standing outside the box, so a cut is seen to be a cut. The
     * newest bin is as wide as the road it has, and the run is anchored at the right edge where new
     * road arrives.
     *
     * **Drawn with the cluster's own pen.** The steps, the field under them, the blue patches on
     * their posts and the marks over a cut bin were all written out a second time here, and the
     * second copy had already drifted - its return patch was edged in `RETURN` where the cluster
     * edges it in `RETURN_INK`. [InstrumentPen] speaks a virtual unit, and this page's virtual unit
     * is the strip's own dp, so one call draws the same shape at a different size.
     */
    private fun drawChart(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        unit: Float,
    ) {
        val bottom = top + height * unit
        val zero = top + height * unit * ContourPlan.PETAL_FULL /
            (ContourPlan.PETAL_FULL + ContourPlan.PETAL_RETURN_FULL)
        val plot = (width - CHART_AXIS) * unit
        val pitch = plot / ConsumptionChart.BINS

        fill.color = DenzaPalette.TRACK_MARK
        canvas.drawRect(left, zero, left + plot, zero + unit, fill)
        drawAxis(canvas, left + width * unit, top, bottom, unit)

        val chart = readouts.chart
        val values = chart.values
        val count = min(values.size, ConsumptionChart.BINS)
        if (count <= 0) return
        val first = values.size - count

        // One virtual unit is one strip dp, which is what lets the cluster's own strokes and ticks
        // be stated here in the numbers `ContourPlan` states them in.
        pen.size(plot, bottom - top, height)
        // The run is anchored at the box's right edge, so a chart that is still filling grows
        // leftward into its box instead of stretching across it.
        var x = left + (ConsumptionChart.BINS - chart.span) * pitch
        for (index in 0 until count) {
            chartXs[index] = x
            x += chart.widths[first + index] * pitch
            val value = values[first + index]
            chartYs[index] = if (value.isNaN()) {
                Float.NaN
            } else {
                zero - min(max(value, 0f) / ContourPlan.PETAL_FULL, 1f) * (zero - top)
            }
            returnYs[index] = if (value.isNaN()) {
                Float.NaN
            } else {
                zero + min(max(-value, 0f) / ContourPlan.PETAL_RETURN_FULL, 1f) * (bottom - zero)
            }
        }
        chartXs[count] = x

        ContourRuns.forEach(count, { !values[first + it].isNaN() }) { start, length ->
            pen.history(
                canvas,
                xSpan(start, length),
                ySpan(chartYs, start, length),
                length,
                zero,
                PanelPalette.INK,
                1f,
                CHART_EDGE,
                PanelPalette.INK,
                AREA_OUT_ALPHA,
            )
        }
        // The return is a patch per stretch of returning bins, standing on the zero on its own
        // posts: blue is only where energy actually came back.
        ContourRuns.forEach(count, { values[first + it] < 0f }) { start, length ->
            pen.steps(
                canvas,
                xSpan(start, length),
                ySpan(returnYs, start, length),
                length,
                zero,
                DenzaPalette.RETURN,
                AREA_BACK_ALPHA,
                DenzaPalette.RETURN_INK,
                CHART_EDGE,
            )
        }
        pen.clampTicks(
            canvas,
            values,
            first,
            count,
            chartXs,
            top - ContourPlan.PETAL_TICK_GAP * unit,
            bottom + ContourPlan.PETAL_TICK_GAP * unit,
            ContourPlan.PETAL_TICK,
            CHART_EDGE,
            ContourPlan.PETAL_FULL,
            ContourPlan.PETAL_RETURN_FULL,
            PanelPalette.INK,
            DenzaPalette.RETURN_INK,
        )
    }

    /**
     * One run's heights, packed to the front of the scratch buffer the pen reads.
     *
     * The cluster's own arrangement: it moves the values rather than allocating a view of them,
     * and the buffers are fields, so a frame allocates nothing here either.
     */
    private fun ySpan(ys: FloatArray, start: Int, length: Int): FloatArray {
        if (start == 0) return ys
        for (index in 0 until length) spanYs[index] = ys[start + index]
        return spanYs
    }

    /** And its edges, which are one longer than its heights. */
    private fun xSpan(start: Int, length: Int): FloatArray {
        if (start == 0) return chartXs
        for (index in 0..length) spanXs[index] = chartXs[start + index]
        return spanXs
    }

    /**
     * What the box holds, written where a chart writes it: «40» and «−20» in its own gutter.
     *
     * The span used to be a phrase on the line under the box - `ШКАЛА 5 ↑ 10 ↓ кВт` - and the
     * owner's verdict was «тоже не интуитивно, либо убрать либо починить». It was a legend, and a
     * legend is what this page spent four drawings getting rid of. Two numbers against the edges
     * they belong to are not a legend, and they are the cluster's own two ceilings.
     */
    private fun drawAxis(canvas: Canvas, right: Float, top: Float, bottom: Float, unit: Float) {
        caps.textSize = LABEL * unit
        caps.color = DenzaPalette.MUTED_DEEP
        caps.textAlign = Paint.Align.RIGHT
        canvas.drawText(AXIS_CEILING, right, top + CHART_AXIS_BASELINE * unit, caps)
        canvas.drawText(AXIS_FLOOR, right, bottom, caps)
        caps.textAlign = Paint.Align.LEFT
    }

    /**
     * The line under the shape: what those ten kilometres cost, and the road they were.
     *
     * «Как водитель, не очень интересен… ему больше места где-то под графиком» - so it is here
     * rather than on the shelf, where it was the one row that had nothing to do with how warm
     * anything is getting. Named, because a figure with no name and no place is exactly what the
     * voltage was.
     *
     * **The window rides on the unit**, which is the cluster's own arrangement for this very figure
     * - «кВт·ч/100 км · за 10 км» under the petal - and it is never a whole-number rounding of a
     * filling window. In a pane the word «РАСХОД» goes and nothing else: the figure and «10 КМ» are
     * what the shape above cannot be read without (`docs/energy-display-contract.md` §5).
     */
    private fun drawFoot(
        canvas: Canvas,
        left: Float,
        baseline: Float,
        width: Float,
        unit: Float,
        narrow: Boolean,
    ) {
        val figureText = readouts.consumptionFigure ?: return
        units.textSize = LABEL * unit
        figures.textSize = SPEND * unit
        // One string, memoised on the distance behind it, rather than a unit and a window joined
        // in the frame that measures them: this line is built and measured to decide whether the
        // word in front of it fits, sixty times a second, over a road that moves every 100 m.
        val unitText = readouts.windowFoot
        val figureWidth = figures.measureText(figureText)
        val unitWidth = units.measureText(unitText)
        val wordWidth = capsWidth(VehiclePageWords.TITLE_SPEND, unit) + LEAD * unit
        val run = figureWidth + LEAD * unit + unitWidth
        var x = left
        if (!narrow && wordWidth + run <= width) {
            caption(canvas, VehiclePageWords.TITLE_SPEND, x, baseline, unit)
            x += wordWidth
        }
        figures.color =
            if (readouts.consumptionNegative) DenzaPalette.RETURN_INK else PanelPalette.INK
        canvas.drawText(figureText, x, baseline, figures)
        units.color = DenzaPalette.MUTED_DEEP
        canvas.drawText(unitText, x + figureWidth + LEAD * unit, baseline, units)
    }

    // -------------------------------------------------------------------------- the temperatures

    private fun drawShelf(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        top: Float,
        width: Float,
        unit: Float,
    ) {
        readings(telemetry).forEachIndexed { index, reading ->
            val rowTop = top + index * (ROW + ROW_GAP) * unit
            drawGlyph(canvas, reading, left, rowTop + ROW * unit, unit)
            val figureWidth = figure(
                canvas, reading.text,
                left + (GLYPH + GAP) * unit, rowTop + ROW_BASELINE * unit,
                READING * unit, reading.ink,
            )
            // A degree belongs to its number and millivolts do not, so the one row that has a unit
            // of its own gets it drawn - the board has been showing «5 мВ» while the car showed a
            // bare «5» since this row was added this evening.
            if (reading.unit.isNotEmpty()) {
                units.textSize = SECOND_UNIT * unit
                units.color = PanelPalette.MUTED
                canvas.drawText(
                    reading.unit,
                    left + (GLYPH + GAP) * unit + figureWidth + LEAD * unit,
                    rowTop + ROW_BASELINE * unit,
                    units,
                )
            }
            val trackLeft = left + (GLYPH + GAP + READING_FIELD + GAP) * unit
            drawTrack(
                canvas, reading,
                trackLeft, rowTop + (ROW - TRACK) * unit / 2f,
                left + width * unit - trackLeft, TRACK * unit,
            )
        }
    }

    /**
     * The track under a figure, and the zones that say where ordinary stops.
     *
     * The first drawing was a plain fill with a one-unit tick on it and the owner read it as
     * «просто какая-то полосочка», which it was: a fill against a range nobody can see is
     * decoration.
     *
     * **The window ends at the alert and carries one zone.** It ran to the alert plus another
     * margin and drew two - amber then red - and a screenshot of a cold car settled it: 15 °C on
     * the pack and 16 on the motors, and every one of the six rows had a wide brown-and-red band
     * on the right. Five rows saying "almost" about things that are stone cold. Now the end of the
     * track *is* the alert, so a reading that fills it is one, and the single amber band starts at
     * the watch - the last quarter of the track on a motor, the last third on the pack. What
     * separates watch from alert is what it has always been: the colour of the figure, the mark
     * and the fill, together.
     *
     * The thresholds are `ContourReadout`'s, so the two screens in this car cannot hold two ideas
     * of hot.
     */
    private fun drawTrack(
        canvas: Canvas,
        reading: Row,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val radius = height / 2f
        box.set(left, top, left + width, top + height)
        fill.color = DenzaPalette.TRACK
        canvas.drawRoundRect(box, radius, radius, fill)
        val value = reading.value ?: return

        val watch = (reading.watch / reading.top).coerceIn(0f, 1f)
        box.set(left + width * watch, top, left + width, top + height)
        fill.color = PanelPalette.alpha(PanelPalette.AMBER, ZONE_ALPHA)
        canvas.drawRoundRect(box, radius, radius, fill)

        val filled = (value / reading.top).coerceIn(0f, 1f)
        box.set(left, top, left + width * filled, top + height)
        fill.color = when (reading.level) {
            ContourReadout.Level.ALERT -> PanelPalette.DANGER
            ContourReadout.Level.WATCH -> PanelPalette.AMBER
            else -> PanelPalette.alpha(PanelPalette.INK, FILL_ALPHA)
        }
        canvas.drawRoundRect(box, radius, radius, fill)
    }

    private fun drawGlyph(canvas: Canvas, reading: Row, left: Float, baseline: Float, unit: Float) {
        val glyph = reading.glyph ?: return drawCells(canvas, reading, left, baseline, unit)
        surface.bind(
            canvas, fill, line, left, baseline,
            scale = GLYPH * unit / ContourGlyphs.HEIGHT,
            weight = GLYPH_WEIGHT,
        )
        glyphs.draw(
            surface,
            glyph,
            x = 0f,
            baseline = ContourGlyphs.HEIGHT,
            outline = PanelPalette.MUTED,
            component = reading.ink,
        )
    }

    /**
     * Two cells at two levels, which is the reading this row carries.
     *
     * The Contour's family has no mark for the cell spread - the cluster names it with the one
     * word left in its row - so this screen draws one, in the family's own idiom: a case in the
     * caption's ink, and the part that means something in the data's. What differs between the two
     * cases is what the number is.
     */
    private fun drawCells(canvas: Canvas, reading: Row, left: Float, baseline: Float, unit: Float) {
        val scale = GLYPH * unit / ContourGlyphs.HEIGHT
        val width = CELL_WIDTH * scale
        val height = CELL_HEIGHT * scale
        val top = baseline - (ContourGlyphs.HEIGHT + CELL_HEIGHT) / 2f * scale
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.0f * unit
        line.color = PanelPalette.MUTED
        listOf(CELL_LOW, CELL_HIGH).forEachIndexed { index, fillFraction ->
            val x = left + (CELL_INSET_X + index * (CELL_WIDTH + CELL_GAP)) * scale
            box.set(x, top, x + width, top + height)
            canvas.drawRoundRect(box, CELL_RADIUS * scale, CELL_RADIUS * scale, line)
            val inset = CELL_INSET * scale
            box.set(
                x + inset,
                top + height - inset - (height - inset * 2f) * fillFraction,
                x + width - inset,
                top + height - inset,
            )
            fill.color = reading.ink
            canvas.drawRoundRect(box, CELL_RADIUS * scale / 2f, CELL_RADIUS * scale / 2f, fill)
        }
    }

    /**
     * The shelf: five temperatures and the cell spread, all rows of one kind.
     *
     * The spread joined them on the owner's own reasoning - *«они же шкала, которая показывает
     * цветовую кодировку… должна двигаться туда-сюда и уходить в оранжевую зону, если разброс
     * повышается»* - and he is right that it is the same kind of reading: a number with a window
     * it is ordinary inside of and two zones past it. It sat under the marks as a line of text,
     * which said nothing about how close to trouble it was.
     *
     * The temperatures are named by the Contour's marks, and that is the owner's verdict on the
     * cluster rather than a preference of this screen: naming the three motor positions in Russian
     * was tried there and thrown out on the sound of it. The spread has no mark in that family -
     * the cluster names it with the one word left in its row, because it has no room for anything
     * else - so this screen draws it: two cells at two levels, which is what the reading is.
     */
    private fun readings(telemetry: VehicleTelemetry): Array<Row> {
        for (index in SENSORS.indices) {
            val sensor = SENSORS[index]
            val celsius = telemetry[sensor.signal]?.toFloat()
            shelf[index].set(
                celsius,
                celsius?.let {
                    ContourReadout.thermalState(it.toDouble(), sensor.band.toDouble())
                } ?: ContourReadout.Level.NORMAL,
            )
        }
        val spread = telemetry.cellSpreadMv?.toFloat()
        shelf[SENSORS.size].set(
            spread,
            spread?.let { ContourReadout.spreadState(it.toDouble()) } ?: ContourReadout.Level.NORMAL,
        )
        return shelf
    }

    // -------------------------------------------------------------------------------- the type

    /** Draws a caption and returns how wide it came out, so a line can be built out of runs. */
    private fun caption(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        unit: Float,
        colour: Int = PanelPalette.MUTED,
    ): Float {
        caps.textSize = LABEL * unit
        caps.color = colour
        canvas.drawText(text, x, baseline, caps)
        return caps.measureText(text)
    }

    private fun capsWidth(text: String, unit: Float): Float {
        caps.textSize = LABEL * unit
        return caps.measureText(text)
    }

    /** Draws a figure and returns how wide it came out, so a unit can sit against it. */
    private fun figure(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        sizePx: Float,
        colour: Int,
    ): Float {
        figures.textSize = sizePx
        figures.color = colour
        canvas.drawText(text, x, baseline, figures)
        return figures.measureText(text)
    }

    private class Sensor(
        val signal: VehicleSignal,
        val glyph: ContourGlyphs.Glyph,
        val band: Float,
    )

    /**
     * One row of the shelf, refreshed rather than rebuilt.
     *
     * Six of these are made once and written on every frame. A list built per frame is 180 objects
     * a second thrown away inside the frames that made them, over a quantity the car answers four
     * times a second.
     *
     * [glyph] is null for the one row the Contour's family has no mark for: the cell spread, drawn
     * here as two cells at two levels. [unit] is empty where the figure carries its own sign - a
     * degree belongs to the number, millivolts do not.
     */
    private class Row(
        val glyph: ContourGlyphs.Glyph?,
        val watch: Float,
        val top: Float,
        val unit: String = "",
    ) {
        var value: Float? = null
            private set
        var level: ContourReadout.Level = ContourReadout.Level.NORMAL
            private set

        fun set(value: Float?, level: ContourReadout.Level) {
            this.value = value
            this.level = level
        }

        val text: String
            get() = value?.let { "${it.roundToInt()}${if (unit.isEmpty()) DEGREE else ""}" } ?: DASH


        val ink: Int
            get() = when {
                value == null -> PanelPalette.MUTED
                level == ContourReadout.Level.ALERT -> PanelPalette.DANGER
                level == ContourReadout.Level.WATCH -> PanelPalette.AMBER
                else -> PanelPalette.INK
            }
    }

    /**
     * The glyph family on a plain [Canvas].
     *
     * `ContourGlyphs` draws in its own units through [GlyphSurface] so that a test can read back
     * what was drawn; the cluster satisfies it with an `InstrumentPen`, and this satisfies it with
     * two paints and a scale. One family, two screens, no second copy of a car seen from above.
     */
    private class CanvasGlyphSurface : GlyphSurface {
        private var canvas: Canvas? = null
        private var fill: Paint? = null
        private var stroke: Paint? = null
        private var originX = 0f
        private var baseline = 0f
        private var scale = 1f
        private var weight = 1f
        private val box = RectF()

        fun bind(
            canvas: Canvas,
            fill: Paint,
            stroke: Paint,
            x: Float,
            baseline: Float,
            scale: Float,
            weight: Float,
        ) {
            this.canvas = canvas
            this.fill = fill
            this.stroke = stroke
            this.originX = x
            this.baseline = baseline
            this.scale = scale
            this.weight = weight
        }

        override fun frame(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
            stroke: Float,
        ) {
            val paint = this.stroke ?: return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke * scale * weight
            paint.color = colour
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            place(left, top, right, bottom)
            canvas?.drawRoundRect(box, radius * scale, radius * scale, paint)
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeCap = Paint.Cap.BUTT
        }

        override fun plate(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
        ) {
            val paint = fill ?: return
            paint.color = colour
            place(left, top, right, bottom)
            canvas?.drawRoundRect(box, radius * scale, radius * scale, paint)
        }

        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, colour: Int, stroke: Float) {
            val paint = this.stroke ?: return
            val target = canvas ?: return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke * scale * weight
            paint.color = colour
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            for (index in 0 until count - 1) {
                target.drawLine(
                    originX + xs[index] * scale,
                    baseline - (ContourGlyphs.HEIGHT - ys[index]) * scale,
                    originX + xs[index + 1] * scale,
                    baseline - (ContourGlyphs.HEIGHT - ys[index + 1]) * scale,
                    paint,
                )
            }
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeCap = Paint.Cap.BUTT
        }

        private fun place(left: Float, top: Float, right: Float, bottom: Float) {
            box.set(
                originX + left * scale,
                baseline - (ContourGlyphs.HEIGHT - top) * scale,
                originX + right * scale,
                baseline - (ContourGlyphs.HEIGHT - bottom) * scale,
            )
        }
    }

    companion object {

        /** How wide the left column is: the shape and its sentence against the shelf, 1.7 to 1. */
        const val LEFT_SHARE = 1.7f

        /** `Space.XL / 2` either side of the hairline, which is what a rule gets everywhere here. */
        const val RULE_MARGIN = 16f

        const val GROUP = 32f
        const val GAP = 20f
        const val LEAD = 8f

        // The head: a caption, `Space.S`, and the ramp's top rung. The cap top of a 62 Roboto Light
        // lands `0.71 * 62` above its baseline, which is what puts the hero's baseline at 70 and
        // its cap exactly 8 under the caption's own line.
        const val CAP_LINE = 18f
        const val LABEL = 15f
        const val LABEL_BASELINE = 14f
        const val HERO = 62f
        const val HERO_BASELINE = 70f
        const val BLOCK = 88f

        /** Everything beside the hero is the shelf's rung, so no two cells read as equals. */
        const val SECOND = 34f
        const val SECOND_BASELINE = 50f

        // The two named readings under the marks: a 15 caption and a 24 figure on one baseline,
        // which is the row this app sets a label-and-value in everywhere else.
        /** A 34 figure takes the rung under it for its unit, the way 62 takes 24. */
        const val SECOND_UNIT = 19f

        /** The consumption under the shape: a figure one rung over its own caption. */
        const val SPEND = 19f


        const val UNIT_SIZE = 24f
        const val UNIT_GAP = 12f

        const val INSTRUCTION = 19f
        const val INSTRUCTION_BASELINE = 15f

        /** The mark that means «into the pack», at the caption's own height. */
        const val MARK_RADIUS = 4f
        const val MARK_GAP = 8f
        const val MARK_RISE = 5f

        /** The chart's own box, and what is left of it at 392 dp. */
        const val CHART = 130f
        const val CHART_NARROW = 60f
        const val CHART_EDGE = 2f

        /**
         * The chart's own field and the return's patch, as the alphas the pen takes.
         *
         * The bin count, the two ceilings, the tick and its gap are **not** restated here: they are
         * [ConsumptionChart.BINS] and [ContourPlan]'s, read where they are needed. Five aliases
         * stood here and a test asserted each equalled its own initialiser, which proves nothing
         * about the board and hides the one thing that matters - that neither screen can move a
         * number the other draws.
         */
        const val AREA_OUT_ALPHA = 0.16f
        const val AREA_BACK_ALPHA = 0.26f

        /** The whole left column, which is what the field centres. */
        const val COLUMN = BLOCK + GAP + CHART + GAP + CAP_LINE
        const val FOOT_BASELINE_UP = CAP_LINE - LABEL_BASELINE

        // The shelf: five rows of 30 a neighbour's gap apart, the consumption under them.
        const val SHELF_ROWS = 6
        const val ROW = 30f
        const val ROW_GAP = 8f
        const val ROW_BASELINE = 27f
        const val GLYPH = 30f

        // The spread's own mark, in the glyph family's units: two cells, one fuller than the other.
        const val CELL_INSET_X = 2.5f
        const val CELL_WIDTH = 8f
        const val CELL_HEIGHT = 15f
        const val CELL_GAP = 3f
        const val CELL_RADIUS = 2f
        const val CELL_INSET = 2.4f
        const val CELL_LOW = 0.45f
        const val CELL_HIGH = 0.95f

        /**
         * The marks are the cluster's shapes at this screen's own weight, and that is what makes
         * them legible here.
         *
         * `ContourGlyphs` carries a cluster stroke - `ContourPlan.DATA_LINE`, 2.5 of its units -
         * which at a 30 dp mark comes out over three pixels. The owner saw the result as «иконки
         * какие-то размытые, как будто искусственно растянуты», and that is what a mark drawn at
         * half again the weight of every other icon on the screen looks like: mush at the corners.
         *
         * The head unit paints every icon at 2.0 dp whatever its size (`DenzaMetrics`: a stroke is
         * `2.0 × 24 ÷ rendered size`, so the painted width is always the same), so this is the
         * factor that lands the family's own case stroke there - and because it multiplies every
         * stroke, the family's two-weight contrast between a case and a wheel survives it.
         */
        val GLYPH_WEIGHT: Float =
            2.0f / (ContourGlyphs.STROKE * GLYPH / ContourGlyphs.HEIGHT)
        const val READING = 34f
        const val READING_FIELD = 64f
        const val TRACK = 6f

        // And the same shelf at 416, where a mark stands over its figure.
        const val STACK_GLYPH = 30f
        const val STACK_GAP = 4f
        const val STACK_FIGURE = 24f
        const val STACK_FIGURE_UP = 24f
        const val STACK_TRACK = 4f
        const val STACK_TRACK_WIDTH = 56f
        const val STACK = STACK_GLYPH + STACK_GAP + STACK_FIGURE + STACK_GAP + STACK_TRACK

        /** `ContourReadout`'s own margin, in the float the tracks are measured in. */
        val HOT_MARGIN = ContourReadout.HOT_MARGIN_C.toFloat()

        const val ZONE_ALPHA = 0.30f
        const val FILL_ALPHA = 0.55f
        const val HAIRLINE_ALPHA = 0.14f


        const val DASH = "—"
        const val DEGREE = "°"
        const val UNIT_KW = "кВт"

        const val TITLE_CLOSED = "ПИТАНИЕ ОТ МАШИНЫ"

        /** The gutter on the right of the box where the two ceilings stand. */
        const val CHART_AXIS = 44f
        const val CHART_AXIS_BASELINE = 13f

        /** What the gutter says, which is the ladder's own two labels. */
        val AXIS_CEILING: String = ContourPlan.PETAL_FULL_LABEL
        val AXIS_FLOOR: String = ContourPlan.PETAL_RETURN_FULL_LABEL

        private val SENSORS = listOf(
            Sensor(VehicleSignal.PACK_TEMP_AVG, ContourGlyphs.Glyph.PACK, ContourReadout.PACK_BAND_HIGH_C.toFloat()),
            Sensor(VehicleSignal.MOTOR_FRONT_C, ContourGlyphs.Glyph.MOTOR_FRONT, ContourReadout.DRIVE_BAND_HIGH_C.toFloat()),
            Sensor(VehicleSignal.MOTOR_REAR_LEFT_C, ContourGlyphs.Glyph.MOTOR_REAR_LEFT, ContourReadout.DRIVE_BAND_HIGH_C.toFloat()),
            Sensor(VehicleSignal.MOTOR_REAR_RIGHT_C, ContourGlyphs.Glyph.MOTOR_REAR_RIGHT, ContourReadout.DRIVE_BAND_HIGH_C.toFloat()),
            Sensor(VehicleSignal.INVERTER_C, ContourGlyphs.Glyph.INVERTER, ContourReadout.INVERTER_WATCH_C.toFloat()),
        )

        /** What the left column gets once the hairline and its two margins are taken out. */
        fun leftColumnWidth(width: Float): Float =
            (width - RULE_MARGIN * 2f - 1f) * LEFT_SHARE / (LEFT_SHARE + 1f)
    }
}
