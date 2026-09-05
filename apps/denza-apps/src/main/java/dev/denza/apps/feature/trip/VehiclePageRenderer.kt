package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.feature.cluster.dashboard.ContourGlyphs
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.cluster.dashboard.GlyphSurface
import dev.denza.apps.feature.panel.PanelPalette
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.PowerSpan
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The strip's second page: what the pack is doing, has been doing for two minutes, and how warm
 * five components are.
 *
 * `tools/design-canvas/StripPages.dc.html` is the board, `StripPagesBoardContractTest` joins the
 * two, and the rules the page is built on are the Contour's own - it went through nine passes to
 * learn them and there is no reason for this screen to learn them again:
 *
 *  - **one quantity, one sentence.** The headline is words - `ИЗ БАТАРЕИ`, `● В БАТАРЕЮ ОТ ДВС` -
 *    and the figure under it says how much. A minus in front of a number is not a direction
 *    anybody reads at a glance, and here the direction matters more than the sign;
 *  - **a figure names the window it is true over**: `ПОСЛЕДНИЕ 2 МИНУТЫ` under the shape,
 *    `ЗА 3 КМ` after the consumption;
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
    private val surface = CanvasGlyphSurface()
    private val shelf: Array<Reading> = Array(SENSORS.size) { Reading(SENSORS[it].glyph, SENSORS[it].band) }

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

        if (narrow) {
            drawNarrow(canvas, telemetry, left, top, width, height, unit)
            return
        }

        val leftWidth = leftColumnWidth(width)
        val ruleX = left + (leftWidth + RULE_MARGIN) * unit
        val rightLeft = ruleX + RULE_MARGIN * unit
        val centre = top + (height - COLUMN) * unit / 2f

        drawHead(canvas, telemetry, left, centre, leftWidth, unit)
        drawTrace(canvas, telemetry, left, centre + (BLOCK + GAP) * unit, leftWidth, GRAPH, unit)
        drawFoot(canvas, telemetry, left, centre + (COLUMN - FOOT_BASELINE_UP) * unit, unit, false)

        val shelfHeight = SHELF_ROWS * ROW + (SHELF_ROWS - 1) * ROW_GAP + ROW_GAP + CAP_LINE
        val shelfTop = top + (height - shelfHeight) * unit / 2f
        drawShelf(canvas, telemetry, rightLeft, shelfTop, (right - rightLeft) / unit, unit)

        fill.color = PanelPalette.alpha(PanelPalette.MUTED, HAIRLINE_ALPHA)
        canvas.drawRect(ruleX, top, ruleX + unit, bottom, fill)
    }

    /**
     * The 416 pane, where there is no second column.
     *
     * Not the wide composition scaled down: the marks stand over their figures, the temperatures
     * take one row under the shape, and the consumption goes - which is the same rule the rest of
     * this app follows at 416, and the same reason. A column of two beside a 62 figure at 392 dp
     * is two columns of about 180, and a five-cell shelf does not fit in one of them.
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
        val stack = BLOCK + GAP + GRAPH_NARROW + GAP + CAP_LINE + GAP + STACK
        val start = top + max(0f, (height - stack) * unit / 2f)

        drawHead(canvas, telemetry, left, start, width, unit)
        drawTrace(canvas, telemetry, left, start + (BLOCK + GAP) * unit, width, GRAPH_NARROW, unit)
        val footBaseline = start + (BLOCK + GAP + GRAPH_NARROW + GAP + LABEL_BASELINE) * unit
        drawFoot(canvas, telemetry, left, footBaseline, unit, true)

        val rowTop = start + (BLOCK + GAP + GRAPH_NARROW + GAP + CAP_LINE + GAP) * unit
        val cell = width / SHELF_ROWS
        readings(telemetry).forEachIndexed { index, reading ->
            val x = left + index * cell * unit
            drawGlyph(canvas, reading, x, rowTop + STACK_GLYPH * unit, unit)
            figure(
                canvas, reading.text, x, rowTop + (STACK_GLYPH + STACK_GAP + STACK_FIGURE_UP) * unit,
                STACK_FIGURE * unit, reading.ink,
            )
            drawTrack(
                canvas, reading,
                x, rowTop + (STACK - STACK_TRACK) * unit,
                STACK_TRACK_WIDTH * unit, STACK_TRACK * unit,
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
        val load = telemetry.loadKw
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

        val headline = VehiclePageWords.headline(telemetry)
        var x = left
        if (headline != null && headline.mark) {
            fill.color = DenzaPalette.RETURN
            canvas.drawCircle(
                left + MARK_RADIUS * unit,
                top + (LABEL_BASELINE - MARK_RISE) * unit,
                MARK_RADIUS * unit,
                fill,
            )
            x += (MARK_RADIUS * 2f + MARK_GAP) * unit
        }
        if (headline != null) caption(canvas, headline.text, x, top + LABEL_BASELINE * unit, unit)

        // The sign is back on the figure, and the words stay.
        //
        // It came off on the reasoning that a minus is not a direction anybody reads at a glance -
        // which is true, and was not the whole truth. On the car the owner worked the page out as
        // «белый разряд, синий заряд… но супер неинтуитивно»: he was decoding the *hue*, because
        // the sentence above the figure was wrong at that moment (the gun gate) and colour was the
        // only thing telling him the truth. Three cues that agree cost nothing and need no
        // learning; one clever one costs a glance.
        val into = load != null && load < 0.0
        val ink = if (into) DenzaPalette.RETURN_INK else PanelPalette.INK
        val text = load?.let { (if (into) MINUS else "") + ContourReadout.whole(abs(it)) } ?: DASH
        val figureWidth = figure(canvas, text, left, top + HERO_BASELINE * unit, HERO * unit, ink)
        units.textSize = UNIT_SIZE * unit
        units.color = PanelPalette.MUTED
        canvas.drawText(
            UNIT_KW,
            left + figureWidth + UNIT_GAP * unit,
            top + HERO_BASELINE * unit,
            units,
        )

        val engine = VehiclePageWords.engineCell(telemetry) ?: return
        val capsWidth = capsWidth(engine.first, unit)
        val engineX = left + width * unit - capsWidth
        // The engine never crowds the hero: if its caption cannot stand clear of the kilowatts
        // there is no room for the cell, and a cell drawn over a figure is worse than an absent one.
        if (engineX < left + figureWidth + (UNIT_GAP + GROUP) * unit) return
        caption(canvas, engine.first, engineX, top + LABEL_BASELINE * unit, unit)
        figure(canvas, engine.second, engineX, top + ENGINE_BASELINE * unit, ENGINE * unit, PanelPalette.INK)
    }

    // ------------------------------------------------------------------------------- the shape

    private fun drawTrace(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        unit: Float,
    ) {
        val steps = telemetry.powerTrace.steps
        val ceiling = PowerSpan.ceiling(steps)
        val floor = PowerSpan.floor(steps)
        val zero = top + height * unit * ceiling / (ceiling + floor).toFloat()
        val perKw = height * unit / (ceiling + floor).toFloat()
        val plot = (width - AXIS) * unit

        fill.color = DenzaPalette.TRACK_MARK
        canvas.drawRect(left, zero, left + plot, zero + unit, fill)
        drawAxis(canvas, ceiling, floor, left + width * unit, top, top + height * unit, unit)
        if (telemetry.powerTrace.isEmpty) return

        val step = plot / steps.size
        line.strokeWidth = EDGE * unit
        line.color = PanelPalette.INK
        var previous = Float.NaN
        for (index in steps.indices) {
            val raw = steps[index]
            if (raw.isNaN()) {
                previous = Float.NaN
                continue
            }
            // Held inside the box: past the ladder's last rung a step is drawn flat against the
            // edge, which is what "more than this holds" looks like. Unclamped it was drawn over
            // the figure above the box, which is the answer the owner's «что будет при 200 кВт»
            // would have got.
            val kw = telemetry.powerTrace.clamp(raw, ceiling, floor)
            val x = left + index * step
            val y = zero - kw * perKw
            fill.color = if (kw >= 0f) AREA_OUT else AREA_BACK
            canvas.drawRect(x, minOf(y, zero), x + step, maxOf(y, zero), fill)
            canvas.drawLine(x, y, x + step, y, line)
            // The riser between two steps, and nothing across a bin that never answered: a hole in
            // the window breaks the shape rather than being drawn through.
            if (!previous.isNaN()) canvas.drawLine(x, zero - previous * perKw, x, y, line)
            previous = kw
        }
    }

    /**
     * What the box holds, written where a chart writes it.
     *
     * The span used to be a phrase on the line under the box - `ШКАЛА 5 ↑ 10 ↓ кВт` - and the
     * owner's verdict was «тоже не интуитивно, либо убрать либо починить». It was a legend, and a
     * legend is what this page spent four drawings getting rid of. Two numbers against the edges
     * they belong to are not a legend: the top of the box is what leaves the pack, the bottom is
     * what comes back, and the unit is said once.
     */
    private fun drawAxis(
        canvas: Canvas,
        ceiling: Int,
        floor: Int,
        right: Float,
        top: Float,
        bottom: Float,
        unit: Float,
    ) {
        caps.textSize = LABEL * unit
        caps.color = DenzaPalette.MUTED_DEEP
        caps.textAlign = Paint.Align.RIGHT
        canvas.drawText("$ceiling $UNIT_KW", right, top + AXIS_BASELINE * unit, caps)
        canvas.drawText("$floor", right, bottom, caps)
        caps.textAlign = Paint.Align.LEFT
    }

    /** The line under the shape: how far back it reaches, and the volts behind it. */
    private fun drawFoot(
        canvas: Canvas,
        telemetry: VehicleTelemetry,
        left: Float,
        baseline: Float,
        unit: Float,
        narrow: Boolean,
    ) {
        val window = VehiclePageWords.window(telemetry.powerTrace.seconds, narrow)
        val volts = telemetry[VehicleSignal.PACK_VOLT]
        val tail = if (narrow || volts == null) "" else " · ${ContourReadout.whole(volts)} $UNIT_V"
        caption(canvas, "$window$tail", left, baseline, unit, DenzaPalette.MUTED_DEEP)
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
            figure(
                canvas, reading.text,
                left + (GLYPH + GAP) * unit, rowTop + ROW_BASELINE * unit,
                READING * unit, reading.ink,
            )
            val trackLeft = left + (GLYPH + GAP + READING_FIELD + GAP) * unit
            drawTrack(
                canvas, reading,
                trackLeft, rowTop + (ROW - TRACK) * unit / 2f,
                left + width * unit - trackLeft, TRACK * unit,
            )
        }

        val spend = VehiclePageWords.spend(telemetry) ?: return
        val baseline = top + (SHELF_ROWS * ROW + SHELF_ROWS * ROW_GAP + LABEL_BASELINE) * unit
        caption(canvas, spend, left, baseline, unit, DenzaPalette.MUTED_DEEP)
    }

    /**
     * The track under a figure, and the zones that say where ordinary stops.
     *
     * The first drawing was a plain fill with a one-unit tick on it and the owner read it as
     * «просто какая-то полосочка», which it was: a fill against a range nobody can see is
     * decoration. The window runs to the band plus twice the margin, so amber and red are the last
     * two fifths of it and an ordinary reading sits in the clear part with room to spare - and the
     * thresholds are `ContourReadout`'s, so the two screens in this car cannot hold two ideas of
     * hot.
     */
    private fun drawTrack(
        canvas: Canvas,
        reading: Reading,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val radius = height / 2f
        box.set(left, top, left + width, top + height)
        fill.color = DenzaPalette.TRACK
        canvas.drawRoundRect(box, radius, radius, fill)
        val celsius = reading.celsius ?: return

        val top0 = reading.band + HOT_MARGIN * 2f
        val watch = (reading.band / top0).coerceIn(0f, 1f)
        val alert = ((reading.band + HOT_MARGIN) / top0).coerceIn(0f, 1f)
        box.set(left + width * watch, top, left + width, top + height)
        fill.color = PanelPalette.alpha(PanelPalette.AMBER, ZONE_ALPHA)
        canvas.drawRoundRect(box, radius, radius, fill)
        box.set(left + width * alert, top, left + width, top + height)
        fill.color = PanelPalette.alpha(PanelPalette.DANGER, ZONE_ALPHA)
        canvas.drawRoundRect(box, radius, radius, fill)

        val value = (celsius / top0).coerceIn(0f, 1f)
        box.set(left, top, left + width * value, top + height)
        fill.color = when (reading.level) {
            ContourReadout.Level.ALERT -> PanelPalette.DANGER
            ContourReadout.Level.WATCH -> PanelPalette.AMBER
            else -> PanelPalette.alpha(PanelPalette.INK, FILL_ALPHA)
        }
        canvas.drawRoundRect(box, radius, radius, fill)
    }

    private fun drawGlyph(canvas: Canvas, reading: Reading, left: Float, baseline: Float, unit: Float) {
        surface.bind(
            canvas, fill, line, left, baseline,
            scale = GLYPH * unit / ContourGlyphs.HEIGHT,
            weight = GLYPH_WEIGHT,
        )
        glyphs.draw(
            surface,
            reading.glyph,
            x = 0f,
            baseline = ContourGlyphs.HEIGHT,
            outline = PanelPalette.MUTED,
            component = reading.ink,
        )
    }

    /**
     * The five, in the order the car is put together: the pack, then front to back, then what
     * drives them.
     *
     * Named by marks rather than by words, and that is the owner's own verdict on the cluster
     * rather than a preference of this screen: naming the three motor positions in Russian was
     * tried there and thrown out on the sound of it. A car seen from above with one block lit says
     * which motor in no language.
     */
    private fun readings(telemetry: VehicleTelemetry): Array<Reading> {
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
     * One cell of the shelf, refreshed rather than rebuilt.
     *
     * Five of these are made once and written on every frame. A list built per frame is 150
     * objects a second thrown away inside the frames that made them, over a quantity the car
     * answers four times a second.
     */
    private class Reading(val glyph: ContourGlyphs.Glyph, val band: Float) {
        var celsius: Float? = null
            private set
        var level: ContourReadout.Level = ContourReadout.Level.NORMAL
            private set

        fun set(celsius: Float?, level: ContourReadout.Level) {
            this.celsius = celsius
            this.level = level
        }

        val text: String get() = celsius?.let { "${it.roundToInt()}$DEGREE" } ?: DASH


        val ink: Int
            get() = when {
                celsius == null -> PanelPalette.MUTED
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

        /** The engine's figure is the shelf's rung, so the two cells never read as equals. */
        const val ENGINE = 34f
        const val ENGINE_BASELINE = 50f

        const val UNIT_SIZE = 24f
        const val UNIT_GAP = 12f

        const val INSTRUCTION = 19f
        const val INSTRUCTION_BASELINE = 15f

        /** The mark that means «into the pack», at the caption's own height. */
        const val MARK_RADIUS = 4f
        const val MARK_GAP = 8f
        const val MARK_RISE = 5f

        const val GRAPH = 130f
        const val GRAPH_NARROW = 60f
        const val EDGE = 2f
        val AREA_OUT = PanelPalette.alpha(PanelPalette.INK, 0.16f)
        val AREA_BACK = PanelPalette.alpha(DenzaPalette.RETURN, 0.26f)

        /** The whole left column, which is what the field centres. */
        const val COLUMN = BLOCK + GAP + GRAPH + GAP + CAP_LINE
        const val FOOT_BASELINE_UP = CAP_LINE - LABEL_BASELINE

        // The shelf: five rows of 30 a neighbour's gap apart, the consumption under them.
        const val SHELF_ROWS = 5
        const val ROW = 30f
        const val ROW_GAP = 8f
        const val ROW_BASELINE = 27f
        const val GLYPH = 30f

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
        const val UNIT_V = "В"
        const val UP = "↑"
        const val DOWN = "↓"

        const val TITLE_CLOSED = "ПИТАНИЕ ОТ МАШИНЫ"
        /** The gutter on the right of the box where the two axis figures stand. */
        const val AXIS = 44f
        const val AXIS_BASELINE = 13f

        /** ASCII, like every other number this app prints, and for the same reason as the arrows. */
        const val MINUS = "-"

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
