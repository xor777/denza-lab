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
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineBox
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import dev.denza.apps.design.luminofor.ThermalGlyphs
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.cluster.dashboard.ContourGeometry.AXIS
import kotlin.math.abs
import kotlin.math.floor
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
    private val area = Path()
    private val down = Path()

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
    private var boxShader: Shader? = null

    // And the trace's, which also depend on how far the window has filled.
    private var traceShadedCount = -1
    private var traceUp: Shader? = null
    private var traceDown: Shader? = null

    /** The last consumption figure's width, so its unit does not jump while the figure is stale. */
    private var lastFigureWidth = 0f

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
        f.volts?.let { pen.figures(it, left, base, Grid.FIGURE_SIZE, ClusterInk.INK, 1f) }

        for (index in 0 until ContourFrame.CELLS) {
            val cell = f.temps[index]
            if (!cell.shown) continue
            val x = ContourGeometry.tempX(index)
            val hot = cell.level != ContourReadout.Level.NORMAL
            val col = levelLight(cell.level)
            if (hot) hotPool(x, base, cell.level, f.t)
            ThermalGlyphs.draw(pen, GLYPHS[index], x, Grid.GLYPH_BASE, col, if (hot) 1f else 0.85f, hot)
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

    /** The engine at the group's left edge, and the trip - or the engine's box - flush right. */
    private fun rightGroup(f: ContourFrame) {
        if (f.engineGiving) engineBox(f) else trip(f)
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
        f.iceFigure?.let {
            pen.figures(it, ContourGeometry.GROUP_RIGHT_START, Grid.BASELINE, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
        }
    }

    private fun trip(f: ContourFrame) {
        val caption = f.tripCaption ?: return
        val kwh = f.tripKwh
        val base = Grid.BASELINE
        val cw = pen.textWidth(caption, Grid.CAPTION_SIZE, track = Grid.CELL_CAPTION_TRACK)
        val fw = if (kwh == null) 0f else WideDigits.width(kwh, Grid.FIGURE_SIZE)
        val payload = if (kwh == null) 0f else ContourGeometry.tripPayload(fw, pen.textWidth(f.tripUnit, Grid.UNIT_SIZE))
        val x = ContourGeometry.tripLeft(cw, payload)
        pen.text(caption, x, Grid.CAPTION, Grid.CAPTION_SIZE, ClusterInk.GREY, 1f, track = Grid.CELL_CAPTION_TRACK)
        if (kwh != null) {
            pen.figures(kwh, x, base, Grid.FIGURE_SIZE, ClusterInk.INK, 1f)
            pen.text(f.tripUnit, x + fw + Grid.UNIT_GAP, base, Grid.UNIT_SIZE, ClusterInk.GREY, 1f)
        }
        runLeft(f)
    }

    /**
     * The detail line under the trip, laid right to left off the margin: «1,1 кВт·ч ДАЛ ДВС», then
     * twenty-eight units, then «● 3,1 кВт·ч РЕКУПЕРАЦИЯ». A seat that does not exist this trip
     * takes no room, so what is there is packed against the edge; a figure gone stale takes its
     * unit with it and leaves the words.
     */
    private fun runLeft(f: ContourFrame) {
        val y = ContourGeometry.DETAIL_BASELINE
        var x = ContourGeometry.GROUP_RIGHT_END
        var pair = false
        f.gaveCaption?.let { caption ->
            x = detailPart(caption, f.gaveKwh, f.tripUnit, x, y, ContourGeometry.DETAIL_AFTER_UNIT, 0f)
            pair = true
        }
        val regen = f.regenCaption ?: return
        if (pair) x -= ContourGeometry.DETAIL_PAIR_GAP
        x = detailPart(regen, f.regenKwh, f.tripUnit, x, y, ContourGeometry.DETAIL_AFTER_UNIT, ContourGeometry.DETAIL_AFTER_FIGURE)
        path.rewind()
        path.addCircle(x - ContourGeometry.DOT_DX, y - ContourGeometry.DOT_DY, ContourGeometry.DOT_RADIUS, Path.Direction.CW)
        pen.glowFill(path, ClusterInk.BLUE, ContourGeometry.DOT_INTENSITY, ContourGeometry.DOT_BLUR)
    }

    /** One caption, its unit and its figure, right to left from [right]; returns where it ended. */
    private fun detailPart(
        caption: String,
        figure: String?,
        unit: String,
        right: Float,
        y: Float,
        afterUnit: Float,
        afterFigure: Float,
    ): Float {
        var x = right
        x -= pen.text(caption, x, y, Grid.DETAIL_SIZE, ClusterInk.GREY, 1f, track = Grid.DETAIL_TRACK, align = Align.RIGHT)
        if (figure == null) return x - afterFigure
        x -= ContourGeometry.DETAIL_AFTER_CAPTION
        x -= pen.text(unit, x, y, Grid.DETAIL_SIZE, ClusterInk.GREY, 1f, align = Align.RIGHT) + afterUnit
        x -= pen.figures(figure, x, y, ContourGeometry.DETAIL_FIGURE, ClusterInk.INK, 1f, Align.RIGHT) + afterFigure
        return x
    }

    /**
     * The engine's box in the trip's place: a blue step trace of what it gave, over the figures'
     * own height, its sentence on the caption line and its window under it.
     *
     * The board draws every bin as one step line; the app draws the runs of bins the engine
     * actually gave in, because a bin nothing answered in and a bin at zero are not a reading - a
     * step across a gap would claim a steady output through five seconds nobody watched. With every
     * bin answered and above zero, which is every fixture, the two are one path.
     */
    private fun engineBox(f: ContourFrame) {
        val count = f.generationCount
        val right = ContourGeometry.GROUP_RIGHT_END
        val start = right - count * ContourGeometry.BOX_PITCH
        val zeroY = ContourGeometry.BOX_ZERO
        path.rewind()
        area.rewind()
        ContourRuns.forEach(count, { f.generation[it] > 0f }) { first, length ->
            val x0 = start + first * ContourGeometry.BOX_PITCH
            area.moveTo(x0, zeroY)
            for (i in first until first + length) {
                val yy = ContourGeometry.boxY(f.generation[i])
                val a = start + i * ContourGeometry.BOX_PITCH
                val b = a + ContourGeometry.BOX_PITCH
                if (i == first) path.moveTo(a, yy) else path.lineTo(a, yy)
                path.lineTo(b, yy)
                area.lineTo(a, yy)
                area.lineTo(b, yy)
            }
            area.lineTo(start + (first + length) * ContourGeometry.BOX_PITCH, zeroY)
            area.close()
        }
        shade.shader = boxShader
        shade.alpha = OPAQUE
        pen.canvas.drawPath(pen.toPx(area), shade)
        shade.shader = null
        pen.beam(path, EngineBox.STROKE, ClusterInk.BLUE, BOX_EDGE_INTENSITY)
        path.rewind()
        path.moveTo(ContourGeometry.BOX_LEFT, zeroY)
        path.lineTo(right, zeroY)
        pen.beam(path, EngineBox.BASE_STROKE, ClusterInk.BLUE, BOX_BASE_INTENSITY)
        pen.text(
            f.engineCaption,
            ContourGeometry.BOX_LEFT,
            Grid.CAPTION,
            Grid.CAPTION_SIZE,
            ClusterInk.BLUE,
            1f,
            track = Grid.CELL_CAPTION_TRACK,
        )
        pen.text(
            f.engineWindow,
            ContourGeometry.BOX_LEFT,
            ContourGeometry.DETAIL_BASELINE,
            Grid.DETAIL_SIZE,
            ClusterInk.GREY,
            1f,
            track = Grid.CELL_CAPTION_TRACK,
        )
    }

    // ---------------------------------------------------------------- the ten kilometres

    /**
     * The hundred points under the axis, zero on the figure's baseline: spending climbs the cap to
     * 60, a return hangs a descender to 20 (`docs/energy-display-contract.md` §2.3). One pitch for
     * the hundred, so a filling window is anchored at the right edge and is as wide as its road.
     */
    private fun trace(f: ContourFrame) {
        val n = f.chartCount
        if (n <= 0) {
            traceFigure(f)
            return
        }
        val ch = f.chart
        val zero = Trace.ZERO
        val right = ContourGeometry.TRACE_RIGHT
        val st = ContourGeometry.TRACE_PITCH
        val x00 = ContourGeometry.traceStart(n)
        traceShaders(n)

        area.rewind()
        down.rewind()
        area.moveTo(x00, zero)
        down.moveTo(x00, zero)
        for (i in 0 until n) {
            val v = ch[i]
            val x0 = x00 + i * st
            val x1 = x0 + st
            val yu = if (v > 0f) ContourGeometry.traceUp(v) else zero
            val yd = if (v < 0f) ContourGeometry.traceDown(v) else zero
            area.lineTo(x0, yu)
            area.lineTo(x1, yu)
            down.lineTo(x0, yd)
            down.lineTo(x1, yd)
        }
        area.lineTo(right, zero)
        area.close()
        down.lineTo(right, zero)
        down.close()
        shade.alpha = OPAQUE
        shade.shader = traceUp
        pen.canvas.drawPath(pen.toPx(area), shade)
        shade.shader = traceDown
        pen.canvas.drawPath(pen.toPx(down), shade)
        shade.shader = null

        // Ten runs, the older ones dimmer. Spending is one white step line lying on zero through a
        // return; each return is its own blue shape under zero, and nothing blue runs along zero.
        val per = n.toDouble() / Trace.RUNS
        for (r in 0 until Trace.RUNS) {
            val i0 = floor(r * per).toInt()
            val i1 = min(n, floor((r + 1) * per).toInt())
            path.rewind()
            down.rewind()
            for (i in i0 until i1) {
                val v = ch[i]
                val x0 = x00 + i * st
                val x1 = x0 + st
                val yu = ContourGeometry.traceUp(maxOf(0f, v))
                if (i == i0) path.moveTo(x0, if (i > 0) ContourGeometry.traceUp(maxOf(0f, ch[i - 1])) else yu)
                path.lineTo(x0, yu)
                path.lineTo(x1, yu)
                if (v < 0f) {
                    val yd = ContourGeometry.traceDown(v)
                    val prevNeg = i > i0 && ch[i - 1] < 0f
                    if (!prevNeg) down.moveTo(x0, zero)
                    down.lineTo(x0, yd)
                    down.lineTo(x1, yd)
                    val nextNeg = i + 1 < i1 && ch[i + 1] < 0f
                    if (!nextNeg) down.lineTo(x1, zero)
                }
            }
            val intensity = ContourGeometry.runIntensity(r)
            if (i1 > i0) {
                pen.beam(path, Trace.STROKE, ClusterInk.INK, intensity)
                pen.beam(down, Trace.STROKE, ClusterInk.BLUE, intensity)
            }
        }
        val last = ch[n - 1]
        path.rewind()
        path.addCircle(
            right,
            if (last >= 0f) ContourGeometry.traceUp(last) else ContourGeometry.traceDown(last),
            ContourGeometry.TRACE_DOT,
            Path.Direction.CW,
        )
        pen.glowFill(path, if (last < 0f) ClusterInk.BLUE else ClusterInk.INK, 1f, ContourGeometry.TRACE_DOT_BLUR)
        traceFigure(f)
    }

    /** The figure one gap right of the axis on the trace's zero, and its unit after it. */
    private fun traceFigure(f: ContourFrame) {
        val x = ContourGeometry.TRACE_FIGURE_X
        f.consumption?.let {
            lastFigureWidth = pen.figures(it, x, Trace.ZERO, Trace.FIGURE_SIZE, toneLight(f.consumptionTone), 1f)
        }
        val unit = f.consumptionUnit ?: return
        pen.text(unit, x + lastFigureWidth + Trace.UNIT_GAP, Trace.ZERO, Trace.UNIT_SIZE, ClusterInk.GREY, 1f)
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
        traceShadedCount = -1
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
        boxShader = LinearGradient(
            0f, pen.y(ContourGeometry.BOX_TOP), 0f, pen.y(ContourGeometry.BOX_ZERO),
            LightPen.alpha(blue, BOX_FILL_TOP), LightPen.alpha(blue, BOX_FILL_ZERO), Shader.TileMode.CLAMP,
        )
    }

    /** The trace's two fills run from its oldest point to the right edge, so they follow the window. */
    private fun traceShaders(count: Int) {
        if (count == traceShadedCount) return
        traceShadedCount = count
        val from = pen.x(ContourGeometry.traceStart(count))
        val to = pen.x(ContourGeometry.TRACE_RIGHT)
        val ink = ClusterInk.INK.halo
        val blue = ClusterInk.BLUE.halo
        traceUp = LinearGradient(
            from, 0f, to, 0f,
            LightPen.alpha(ink, TRACE_UP_OLD), LightPen.alpha(ink, TRACE_UP_NEW), Shader.TileMode.CLAMP,
        )
        traceDown = LinearGradient(
            from, 0f, to, 0f,
            LightPen.alpha(blue, TRACE_DOWN_OLD), LightPen.alpha(blue, TRACE_DOWN_NEW), Shader.TileMode.CLAMP,
        )
    }

    private fun unit(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).roundToInt()

    private companion object {
        /** `ThermalGlyphs`' cells in the frame's order: pack, front, rear left, rear right, inverter. */
        val GLYPHS = ThermalGlyphs.Cell.entries.toTypedArray()

        const val OPAQUE = 255

        /** The filament's centre stop is 0.85 of the alpha spec.json gives it. */
        const val FILAMENT_PEAK = 0.85f

        /** The engine box: its edge, its base line and its fill, top to zero. */
        const val BOX_EDGE_INTENSITY = 0.95f
        const val BOX_BASE_INTENSITY = 0.35f
        const val BOX_FILL_TOP = 0.2f
        const val BOX_FILL_ZERO = 0.02f

        /** The trace's fills, oldest point to newest. */
        const val TRACE_UP_OLD = 0.03f
        const val TRACE_UP_NEW = 0.16f
        const val TRACE_DOWN_OLD = 0.05f
        const val TRACE_DOWN_NEW = 0.3f
    }
}
