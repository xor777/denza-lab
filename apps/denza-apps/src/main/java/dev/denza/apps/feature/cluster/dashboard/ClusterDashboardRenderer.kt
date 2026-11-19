package dev.denza.apps.feature.cluster.dashboard

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.design.luminofor.LightPen.Align
import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Band
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineCharge
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import dev.denza.apps.design.luminofor.Silhouette
import dev.denza.apps.design.luminofor.ThermalGlyphs
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.cluster.dashboard.ContourGeometry.AXIS
import dev.denza.apps.feature.vehicle.EngineCharge as EngineRun
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Contour, drawn: the Luminofor board's triptych on the driver's display.
 *
 * `drawCluster(c, f)` in `tools/design-canvas/luminofor/luminofor.js` is the design - approved by
 * the owner as a live page on 2026-09-23 and frozen as the `cluster-*` boards - and this is that
 * function ported line for line onto [LightPen], the board's own four verbs with the board's own
 * additive compositing. The functions below keep the board's names and order - [centre],
 * [filament], [trace], [traceFigure], [runLeft] - so the two can be read side by side, and every
 * number is [LuminoforSpec] or a named literal in [ContourGeometry].
 *
 * A picture is a [ContourFrame] and nothing else: [ContourFrameBuilder] fills one from the car
 * every frame, and the debug build's `ContourFixtures` fills one from a board's fixture, so both go
 * through [drawFrame] and the second can be laid over the board's PNG pixel for pixel.
 *
 * Three rules from the panel this replaces still hold, and the frame is where they are kept.
 * **A zero is never drawn** - a quantity that did not happen this trip has no cell. **Alpha is not
 * a state channel** - a stale figure is removed and its caption stays; nothing dims. **One lit
 * thing, and it stands still** - the glow sits on zero and says how hard by its brightness.
 *
 * What the board draws and this does not: the keep-out hatching and its two labels. They are the
 * board's picture of the vehicle's own instruments, which are really there on the glass.
 *
 * ### Nothing is allocated in a frame, and nothing is thrown out of one
 *
 * This runs inside a `Presentation` over the vehicle's live instruments. Paths and paints are
 * fields; the gradients are built once per size (and the trace's once per window width) and the
 * rest of their variation is the paint's alpha; [LightPen] caches its blurs. The blurs are the
 * board's own: the beam, its head, the zero and peak ticks, the trace's end dot, the park line's
 * dot and a hot cell's lit part - no more.
 */
internal class ClusterDashboardRenderer(private val pen: LightPen) {

    constructor(context: Context) : this(LightPen.create(context))

    private val path = Path()

    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        blendMode = BlendMode.PLUS
        isDither = true
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        blendMode = BlendMode.PLUS
        isDither = true
    }

    /** The threads: butt-ended, because the board never set a cap for them. */
    private val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        blendMode = BlendMode.PLUS
    }

    // The gradients, keyed by the pixel scale and origin they were built for.
    private var shadedScale = Float.NaN
    private var shadedOrigin = Float.NaN
    private var filamentShader: Shader? = null
    private var glowInk: Shader? = null
    private var glowBlue: Shader? = null
    private var hotOrange: Shader? = null
    private var hotRed: Shader? = null

    /** The ten kilometres: ink over zero and blue under it, ten runs brightening to the present. */
    private val silhouette = Silhouette(
        upLight = ClusterInk.INK,
        downLight = ClusterInk.BLUE,
        stroke = Trace.STROKE,
        tick = Trace.TICK,
        runLevels = FloatArray(Trace.RUNS) { ContourGeometry.runIntensity(it) },
        upFill = floatArrayOf(TRACE_UP_OLD, TRACE_UP_NEW),
        downFill = floatArrayOf(TRACE_DOWN_OLD, TRACE_DOWN_NEW),
        capacity = Trace.POINTS,
    )

    /**
     * The engine's charge: the same painter in the return blue alone, nothing below its zero, ten
     * runs brightening to the present as the ten kilometres do.
     */
    private val charge = Silhouette(
        upLight = ClusterInk.BLUE,
        downLight = ClusterInk.BLUE,
        stroke = Trace.STROKE,
        tick = Trace.TICK,
        runLevels = FloatArray(Trace.RUNS) { ContourGeometry.runIntensity(it) },
        upFill = floatArrayOf(ContourGeometry.CHARGE_FILL_OLD, ContourGeometry.CHARGE_FILL_NEW),
        downFill = floatArrayOf(0f, 0f),
        capacity = EngineCharge.POINTS,
    )


    /**
     * The panel in its window. `FULL` alone is offered - see [ClusterDashboardLayout.supported] -
     * and anything else draws nothing, as it always has.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, layout: ClusterDashboardLayout, frame: ContourFrame) {
        if (!layout.supported) return
        drawFrame(canvas, width.roundToInt(), height.roundToInt(), frame)
    }

    /**
     * One frame onto a canvas of [widthPx] × [heightPx] pixels.
     *
     * The board's 424 units are the height, so a unit is `heightPx / 424` pixels - 1.698 on the
     * 2560 × 720 display, the scale `shot.py` renders the boards at - and the board's 1507.56 units
     * are centred on the canvas, which on that display is exactly its width. This is also the entry
     * point a debug activity uses to draw a fixture: `ClusterDashboardRenderer(context)`, then
     * `drawFrame(canvas, 2560, 720, ContourFixtures.frame(json))`.
     */
    fun drawFrame(canvas: Canvas, widthPx: Int, heightPx: Int, frame: ContourFrame) {
        canvas.drawColor(LuminoforSpec.BACKGROUND)
        if (widthPx <= 0 || heightPx <= 0) return
        val scale = heightPx / Cluster.H
        val originX = Cluster.W / 2f - widthPx / (2f * scale)
        pen.begin(canvas, scale, originX, 0f)
        shaders(scale, originX)

        if (frame.unavailable) {
            // No access: the axis and its zero, and the reason in the ten kilometres' place.
            filament(Grid.AXIS)
            pen.text(frame.message, AXIS, Trace.ZERO, Trace.UNIT_SIZE, ClusterInk.GREY, 1f, align = Align.CENTER)
            return
        }
        centre(frame)
        leftGroup(frame)
        rightGroup(frame)
        trace(frame)
    }

    // ---------------------------------------------------------------- the centre

    private fun centre(f: ContourFrame) {
        val p = f.powerKw
        val absP = abs(p)
        val heroCol = if (f.into) ClusterInk.BLUE else ClusterInk.INK
        val y = Grid.AXIS
        val base = Grid.BASELINE

        // The zero's own glow: it stays at zero; brightness and colour say how hard.
        if (f.powerFresh) {
            val ga = ContourGeometry.glowAlpha(f.glowKw)
            if (ga > ContourGeometry.GLOW_MIN) {
                val c = pen.canvas
                c.save()
                c.translate(pen.x(AXIS), pen.y(y))
                c.scale(1f, ContourGeometry.GLOW_SQUASH)
                shade.shader = if (f.into) glowBlue else glowInk
                shade.alpha = unit(ga)
                c.drawCircle(0f, 0f, pen.px(Band.GLOW_RADIUS), shade)
                c.restore()
                shade.shader = null
            }
        }

        // The hero: a three-digit field right-aligned, the field, gap and unit centred on the axis.
        val unitW = pen.textWidth(ContourReadout.UNIT_KW, Grid.HERO_UNIT_SIZE)
        val fieldR = ContourGeometry.heroFieldRight(unitW)
        f.heroFigure?.let {
            pen.figures(it, fieldR, base, Grid.HERO_SIZE, heroCol, 1f, Align.RIGHT, LuminoforSpec.Digits.HERO_STROKE)
        }
        if (f.heroUnit) {
            pen.text(
                ContourReadout.UNIT_KW,
                fieldR + Grid.HERO_UNIT_GAP,
                base,
                Grid.HERO_UNIT_SIZE,
                if (f.into) ClusterInk.BLUE else ClusterInk.GREY,
                1f,
            )
        }

        // The axis: one filament across the glass, brightest at zero, dying toward both edges.
        filament(y)
        if (!f.powerFresh) return

        val len = ContourGeometry.reach(p)
        val beamCol = if (p < 0f) ClusterInk.BLUE else ClusterInk.INK
        if (abs(len) > ContourGeometry.BEAM_MIN) {
            threads(f.t, len, absP, y, beamCol)
            path.rewind()
            path.moveTo(AXIS, y)
            path.lineTo(AXIS + len, y)
            pen.beam(path, Band.BEAM_STROKE, beamCol, Band.BEAM_INTENSITY, 1f)
            path.rewind()
            path.addCircle(AXIS + len, y, Band.HEAD_RADIUS, Path.Direction.CW)
            pen.glowFill(path, beamCol, 1f, Band.HEAD_BLUR)
            // Past the scale the beam stops at its end, and a tick beyond the head says so.
            if (ContourGeometry.clipped(p)) {
                val xc = ContourGeometry.clipTickX(p)
                path.rewind()
                path.moveTo(xc, y - Band.CLIP_TICK_HALF)
                path.lineTo(xc, y + Band.CLIP_TICK_HALF)
                pen.beam(path, Band.CLIP_TICK_STROKE, beamCol, Band.CLIP_TICK_INTENSITY, 1f)
            }
        }
        val peak = f.peakKw
        if (!peak.isNaN()) {
            val pk = ContourGeometry.reach(peak)
            if (abs(pk) > ContourGeometry.PEAK_MIN) {
                path.rewind()
                path.moveTo(AXIS + pk, y - Band.PEAK_TICK_HALF)
                path.lineTo(AXIS + pk, y + Band.PEAK_TICK_HALF)
                val col = if (peak < 0f) ClusterInk.BLUE else ClusterInk.INK
                pen.beam(path, Band.PEAK_TICK_STROKE, col, ContourGeometry.peakIntensity(f.peakAge), 1f)
            }
        }
    }

    /**
     * The threads along the beam: a golden-ratio scatter of short verticals that flicker with real
     * time, denser the further the beam reaches and taller the harder the pack works. Doubles, as
     * the board computes them, so the scatter lands on the same x's.
     */
    private fun threads(t: Float, len: Float, absP: Float, y: Float, col: Light) {
        val n = (Band.THREADS_BASE + abs(len) * Band.THREADS_PER_UNIT).roundToInt()
        val amp = Band.THREADS_AMP_BASE + Band.THREADS_AMP_RANGE * sqrt(min(1f, absP / Band.THREADS_AMP_KW))
        thread.strokeWidth = pen.px(Band.THREADS_WIDTH)
        val c = pen.canvas
        for (i in 0 until n) {
            val u = (i * 0.6180339) % 1.0
            val x = AXIS + len * u
            val fl = 0.55 + 0.45 * sin(t * (3.0 + (i % 7)) + i * 1.7)
            val h = amp * (0.25 + 0.75 * ((i * 0.3819) % 1.0)) * fl * (0.35 + 0.65 * u)
            thread.color = LightPen.alpha(col.halo, (0.14 + 0.36 * fl * u).toFloat())
            val px = pen.x(x.toFloat())
            c.drawLine(px, pen.y((y - h).toFloat()), px, pen.y((y + h * 0.55).toFloat()), thread)
        }
    }

    /** The one line that is there in every state: the axis across the glass, and its zero tick. */
    private fun filament(y: Float) {
        val c = pen.canvas
        val left = pen.x(Cluster.MARGIN)
        val right = pen.x(Cluster.W - Cluster.MARGIN)
        val py = pen.y(y)
        line.shader = filamentShader
        line.strokeWidth = pen.px(Band.FILAMENT_HALO_WIDTH)
        line.alpha = unit(Band.FILAMENT_HALO_ALPHA * FILAMENT_PEAK)
        c.drawLine(left, py, right, py, line)
        line.strokeWidth = pen.px(Band.FILAMENT_CORE_WIDTH)
        line.alpha = unit(Band.FILAMENT_CORE_ALPHA * FILAMENT_PEAK)
        c.drawLine(left, py, right, py, line)
        line.shader = null
        path.rewind()
        path.moveTo(AXIS, y - Band.ZERO_TICK_HALF)
        path.lineTo(AXIS, y + Band.ZERO_TICK_HALF)
        pen.beam(path, Band.ZERO_TICK_STROKE, ClusterInk.INK, Band.ZERO_TICK_INTENSITY, 1f)
    }

    // ---------------------------------------------------------------- the left group

    /** The battery: its volts, and five temperatures whose captions are the glyphs over them. */
    private fun leftGroup(f: ContourFrame) {
        val left = ContourGeometry.GROUP_LEFT
        val base = Grid.BASELINE
        f.batteryCaption?.let {
            pen.text(it, left, Grid.CAPTION, Grid.CAPTION_SIZE, ClusterInk.GREY, 1f, track = Grid.CAPTION_TRACK)
        }
        // A caption is a name; the unit stands small beside its figure.
        f.volts?.let { volts ->
            val w = pen.figures(volts, left, base, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
            f.voltsUnit?.let { pen.text(it, left + w + Grid.UNIT_GAP, base, Grid.UNIT_SIZE, ClusterInk.GREY, 1f) }
        }

        // Slot by slot in the row's order, each reading taken from its own glyph's place in the frame.
        for (slot in ThermalGlyphs.ROW.indices) {
            val glyph = ThermalGlyphs.ROW[slot]
            val cell = f.temps[glyph.ordinal]
            if (!cell.shown) continue
            val x = ContourGeometry.tempX(slot)
            val hot = cell.level != ContourReadout.Level.NORMAL
            val col = levelLight(cell.level)
            if (hot) hotPool(x, base, cell.level, f.t)
            ThermalGlyphs.draw(pen, glyph, x, Grid.GLYPH_BASE, col, if (hot) 1f else 0.85f, hot)
            val value = cell.value ?: continue
            pen.figures(value, x, base, Grid.TEMP_SIZE, col, 1f)
            pen.figures(ContourReadout.DEGREE, ContourGeometry.degreeX(x, value), base, Grid.TEMP_SIZE, col, 1f)
        }

        // The cell spread, only while it is out of line: one line under the battery, in its colour.
        val caption = f.spreadCaption ?: return
        val value = f.spreadValue ?: return
        val sc = levelLight(f.spreadLevel)
        val y = ContourGeometry.DETAIL_BASELINE
        var x = left + pen.text(caption, left, y, Grid.DETAIL_SIZE, sc, 1f, track = Grid.DETAIL_TRACK) +
            ContourGeometry.SPREAD_CAPTION_GAP
        x += pen.figures(value, x, y, ContourGeometry.DETAIL_FIGURE, sc, 1f) + ContourGeometry.SPREAD_UNIT_GAP
        f.spreadUnit?.let { pen.text(it, x, y, Grid.DETAIL_SIZE, sc, 1f) }
    }

    /** A hot cell's pool of light, breathing behind its glyph and figure. */
    private fun hotPool(x: Float, base: Float, level: ContourReadout.Level, t: Float) {
        val c = pen.canvas
        c.save()
        c.translate(pen.x(x + ContourGeometry.HOT_DX), pen.y(base - ContourGeometry.HOT_DY))
        shade.shader = if (level == ContourReadout.Level.ALERT) hotRed else hotOrange
        shade.alpha = unit(ContourGeometry.hotAlpha(t))
        c.drawCircle(0f, 0f, pen.px(ContourGeometry.HOT_RADIUS), shade)
        c.restore()
        shade.shader = null
    }

    // ---------------------------------------------------------------- the right group

    /** The engine at the group's left edge, and the trip - or the engine's block - flush right. */
    private fun rightGroup(f: ContourFrame) {
        if (f.engineBlock) engineBlock(f) else trip(f)
        f.iceCaption?.let {
            pen.text(
                it,
                ContourGeometry.GROUP_RIGHT_START,
                Grid.CAPTION,
                Grid.CAPTION_SIZE,
                ClusterInk.GREY,
                1f,
                track = Grid.CELL_CAPTION_TRACK,
            )
        }
        f.iceFigure?.let { figure ->
            val x = ContourGeometry.GROUP_RIGHT_START
            val w = pen.figures(figure, x, Grid.BASELINE, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
            f.iceUnit?.let { pen.text(it, x + w + Grid.UNIT_GAP, Grid.BASELINE, Grid.UNIT_SIZE, ClusterInk.GREY, 1f) }
        }
    }

    private fun trip(f: ContourFrame) {
        val caption = f.tripCaption ?: return
        val kwh = f.tripKwh
        val base = Grid.BASELINE
        val cw = pen.textWidth(caption, Grid.CAPTION_SIZE, track = Grid.CELL_CAPTION_TRACK)
        val fw = if (kwh == null) 0f else WideDigits.extent(kwh, Grid.FIGURE_SIZE)
        val payload = if (kwh == null) 0f else ContourGeometry.tripPayload(fw, pen.textWidth(f.tripUnit, Grid.UNIT_SIZE))
        pen.text(caption, ContourGeometry.tripCaptionLeft(cw), Grid.CAPTION, Grid.CAPTION_SIZE, ClusterInk.GREY, 1f, track = Grid.CELL_CAPTION_TRACK)
        if (kwh != null) {
            val x = ContourGeometry.tripFigureLeft(payload)
            pen.figures(kwh, x, base, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
            pen.text(f.tripUnit, x + fw + Grid.UNIT_GAP, base, Grid.UNIT_SIZE, ClusterInk.GREY, 1f)
        }
        runLeft(f)
    }

    /**
     * The detail line under the trip, laid right to left off the margin: the road «42 км», then
     * twenty-eight units, «ЗАРЯД ОТ ДВС 0,8 кВт·ч», twenty-eight, «● РЕКУПЕРАЦИЯ 3,1 кВт·ч» - each
     * reading as caption, figure, unit, as «РАЗБРОС ЯЧЕЕК 32 мВ» does. A seat that does not exist
     * this trip takes no room, so what is there is packed against the edge; a figure gone stale
     * takes its unit with it and leaves the words.
     */
    private fun runLeft(f: ContourFrame) {
        val y = ContourGeometry.DETAIL_BASELINE
        var x = ContourGeometry.GROUP_RIGHT_END
        var pair = false
        f.tripKm?.let { km ->
            x = detailPart(null, km, ContourReadout.UNIT_KM, x, y)
            pair = true
        }
        f.gaveCaption?.let { caption ->
            if (pair) x -= ContourGeometry.DETAIL_PAIR_GAP
            x = detailPart(caption, f.gaveKwh, f.tripUnit, x, y)
            pair = true
        }
        val regen = f.regenCaption ?: return
        if (pair) x -= ContourGeometry.DETAIL_PAIR_GAP
        x = detailPart(regen, f.regenKwh, f.tripUnit, x, y) - ContourGeometry.DETAIL_BEFORE_DOT
        path.rewind()
        path.addCircle(x - ContourGeometry.DOT_DX, y - ContourGeometry.DOT_DY, ContourGeometry.DOT_RADIUS, Path.Direction.CW)
        pen.glowFill(path, ClusterInk.BLUE, ContourGeometry.DOT_INTENSITY, ContourGeometry.DOT_BLUR)
    }

    /**
     * One reading of the detail line - its caption, its figure, its unit - laid right to left from
     * [right]; returns where it ended. No caption for the road, whose name is the trip's own.
     */
    private fun detailPart(caption: String?, figure: String?, unit: String, right: Float, y: Float): Float {
        var x = right
        if (figure != null) {
            x -= pen.text(unit, x, y, Grid.DETAIL_SIZE, ClusterInk.GREY, 1f, align = Align.RIGHT) + ContourGeometry.DETAIL_UNIT_GAP
            x -= pen.figures(figure, x, y, ContourGeometry.DETAIL_FIGURE, ClusterInk.INK, 1f, Align.RIGHT)
            if (caption != null) x -= ContourGeometry.DETAIL_CAPTION_GAP
        }
        if (caption != null) {
            x -= pen.text(caption, x, y, Grid.DETAIL_SIZE, ClusterInk.GREY, 1f, track = Grid.DETAIL_TRACK, align = Align.RIGHT)
        }
        return x
    }

    /**
     * The engine's block in the trip's place (`docs/energy-display-contract.md` §2.5): what the
     * pack took while the engine ran, a point a second over the last two minutes, drawn by the same
     * [Silhouette] as the ten kilometres - one curve through the points, its field, ten runs
     * brightening to the present, the end dot and the tick of a cut - in the return blue, from a
     * zero on the figures' baseline up to their cap. «ЗАРЯД ОТ ДВС» over the line, and at its right
     * end the run's figure with its time under it.
     */
    private fun engineBlock(f: ContourFrame) {
        val zero = ContourGeometry.CHARGE_ZERO
        path.rewind()
        path.moveTo(ContourGeometry.CHARGE_LEFT, zero)
        path.lineTo(ContourGeometry.CHARGE_RIGHT, zero)
        pen.beam(path, EngineCharge.BASE_STROKE, ClusterInk.INK, ContourGeometry.CHARGE_BASE_INTENSITY)
        val n = f.chargeCount
        val drawn = charge.draw(
            pen, f.charge, 0, n,
            right = ContourGeometry.CHARGE_RIGHT,
            pitch = ContourGeometry.CHARGE_PITCH,
            zero = zero,
            top = ContourGeometry.CHARGE_TOP,
            bottom = zero + 1f,
            upTo = EngineRun.ceilingKw(f.charge, n),
            downTo = NOTHING_BELOW,
        )
        if (drawn && n > 1) {
            path.rewind()
            path.addCircle(charge.endX, charge.endY, ContourGeometry.TRACE_DOT, Path.Direction.CW)
            pen.glowFill(path, ClusterInk.BLUE, 1f, ContourGeometry.TRACE_DOT_BLUR)
        }
        f.engineCaption?.let {
            pen.text(it, ContourGeometry.CHARGE_LEFT, Grid.CAPTION, Grid.CAPTION_SIZE, ClusterInk.GREY, 1f, track = Grid.CELL_CAPTION_TRACK)
        }
        val x = ContourGeometry.CHARGE_FIGURE_X
        f.engineKwh?.let { kwh ->
            val fw = pen.figures(kwh, x, Grid.BASELINE, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
            pen.text(f.tripUnit, x + fw + Grid.UNIT_GAP, Grid.BASELINE, Grid.UNIT_SIZE, ClusterInk.GREY, 1f)
        }
        f.engineRun?.let {
            pen.text(it, x, ContourGeometry.DETAIL_BASELINE, Grid.DETAIL_SIZE, ClusterInk.GREY, 1f, track = Grid.CELL_CAPTION_TRACK)
        }
    }

    // ---------------------------------------------------------------- the ten kilometres

    /**
     * The hundred points under the axis, zero on the figure's baseline: spending climbs the cap to
     * 60, a return hangs a descender to 20, one line through the points and the field under it
     * (`docs/energy-display-contract.md` §2.3) - [Silhouette], which the car page draws too.
     */
    private fun trace(f: ContourFrame) {
        val n = f.chartCount
        val drawn = silhouette.draw(
            pen, f.chart, 0, n,
            right = ContourGeometry.TRACE_RIGHT,
            pitch = ContourGeometry.TRACE_PITCH,
            zero = Trace.ZERO,
            top = Trace.TOP,
            bottom = Trace.DROP,
            upTo = Trace.UP_TO,
            downTo = Trace.DOWN_TO,
        )
        if (drawn) {
            path.rewind()
            path.addCircle(silhouette.endX, silhouette.endY, ContourGeometry.TRACE_DOT, Path.Direction.CW)
            val light = if (silhouette.endValue < 0f) ClusterInk.BLUE else ClusterInk.INK
            pen.glowFill(path, light, 1f, ContourGeometry.TRACE_DOT_BLUR)
        }
        traceFigure(f)
    }

    /** The figure one gap right of the axis on the trace's zero, and its unit after it. */
    private fun traceFigure(f: ContourFrame) {
        val x = ContourGeometry.TRACE_FIGURE_X
        val figure = f.consumption
        val held = f.consumptionHeld
        val light = toneLight(f.consumptionTone)
        var width = when {
            figure != null -> pen.figures(figure, x, Trace.ZERO, Trace.FIGURE_SIZE, light, 1f)
            held != null -> WideDigits.extent(held, Trace.FIGURE_SIZE)
            else -> 0f
        }
        // The charge countdown is a duration: «2 ч 15 мин до полной», each figure's unit after it.
        val firstUnit = f.consumptionFirstUnit
        if (figure != null && firstUnit != null) {
            width += Trace.UNIT_GAP + pen.text(firstUnit, x + width + Trace.UNIT_GAP, Trace.ZERO, Trace.UNIT_SIZE, ClusterInk.GREY, 1f)
            val second = f.consumptionSecond
            val secondUnit = f.consumptionSecondUnit
            if (second != null && secondUnit != null) {
                width += Trace.PAIR_GAP
                width += pen.figures(second, x + width, Trace.ZERO, Trace.FIGURE_SIZE, light, 1f)
                width += Trace.UNIT_GAP + pen.text(secondUnit, x + width + Trace.UNIT_GAP, Trace.ZERO, Trace.UNIT_SIZE, ClusterInk.GREY, 1f)
            }
        }
        val unit = f.consumptionUnit ?: return
        pen.text(unit, x + width + Trace.UNIT_GAP, Trace.ZERO, Trace.UNIT_SIZE, ClusterInk.GREY, 1f)
    }

    // ---------------------------------------------------------------- colour and shaders

    private fun levelLight(level: ContourReadout.Level): Light = when (level) {
        ContourReadout.Level.NORMAL -> ClusterInk.INK
        ContourReadout.Level.WATCH -> ClusterInk.ORANGE
        ContourReadout.Level.ALERT -> ClusterInk.RED
    }

    private fun toneLight(tone: ContourFrame.Tone): Light = when (tone) {
        ContourFrame.Tone.INK -> ClusterInk.INK
        ContourFrame.Tone.BLUE -> ClusterInk.BLUE
        ContourFrame.Tone.GREY -> ClusterInk.GREY
    }

    /**
     * The fixed gradients, once per pixel scale. Each is built at full strength and dimmed by the
     * paint's alpha, which is how the glow and a hot cell's pool can change every frame without a
     * shader per frame; the two whose ends differ by more than a factor are built at their own
     * alphas.
     */
    private fun shaders(scale: Float, originX: Float) {
        if (scale == shadedScale && originX == shadedOrigin) return
        shadedScale = scale
        shadedOrigin = originX
        val clear = { color: Int -> color and 0x00FFFFFF }
        val ink = ClusterInk.INK.halo
        filamentShader = LinearGradient(
            pen.x(Cluster.MARGIN), 0f, pen.x(Cluster.W - Cluster.MARGIN), 0f,
            intArrayOf(clear(ink), ink, clear(ink)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        val r = pen.px(Band.GLOW_RADIUS)
        glowInk = RadialGradient(0f, 0f, r, ink, clear(ink), Shader.TileMode.CLAMP)
        val blue = ClusterInk.BLUE.halo
        glowBlue = RadialGradient(0f, 0f, r, blue, clear(blue), Shader.TileMode.CLAMP)
        val hot = pen.px(ContourGeometry.HOT_RADIUS)
        hotOrange = RadialGradient(0f, 0f, hot, ClusterInk.ORANGE.halo, clear(ClusterInk.ORANGE.halo), Shader.TileMode.CLAMP)
        hotRed = RadialGradient(0f, 0f, hot, ClusterInk.RED.halo, clear(ClusterInk.RED.halo), Shader.TileMode.CLAMP)
    }

    private fun unit(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()

    private companion object {
        /** The filament's centre stop is 0.85 of the alpha spec.json gives it. */
        const val FILAMENT_PEAK = 0.85f

        /** The engine's charge is never below its zero, so its lower ladder is out of reach. */
        const val NOTHING_BELOW = 1e9f

        /** The trace's fills, oldest point to newest. */
        const val TRACE_UP_OLD = 0.03f
        const val TRACE_UP_NEW = 0.16f
        const val TRACE_DOWN_OLD = 0.05f
        const val TRACE_DOWN_NEW = 0.3f
    }
}
