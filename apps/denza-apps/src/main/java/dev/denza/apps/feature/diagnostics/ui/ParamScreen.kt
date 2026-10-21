package dev.denza.apps.feature.diagnostics.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.ParamStats
import dev.denza.apps.feature.diagnostics.scan.ParamView
import dev.denza.apps.feature.diagnostics.scan.Sample
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.floor

/*
 * One parameter on its own page (architecture, 6.2; `scanner.js` `sysParam()`, boards
 * `scanner-sys-engine-rpm`, `-ect`, `scanner-sys-pack-cell`): the reading large with its side rows,
 * the chart of the chosen window across the rest. Everything is the session's [ParamView]; the
 * window is the screen's own choice.
 */

/**
 * The page: beside the rail when [frame] is given (it stands on a system's page), over the whole
 * window under [header] otherwise (it stands on a block's) - drawn at the area's width either way.
 */
@Composable
internal fun ParamPage(view: ParamView, frame: RailFrame?, header: @Composable () -> Unit, actions: DiagnosticsActions, onBack: () -> Unit) {
    val p = ScannerSpec.Param
    var tab by remember(view.key) { mutableIntStateOf(p.FIRST_WINDOW) }
    val window = p.WINDOWS[tab]
    val pid = view.key as? ParamKey.Pid
    val footer: @Composable () -> Unit = {
        ScanFooter(
            buttons = listOfNotNull(
                FooterButton(ScannerStack.BACK_TO_LIVE, false, onBack),
                FooterButton(if (view.running) "Пауза" else "Продолжить", true) { actions.setParamPaused(view.running) },
                FooterButton("Сбросить мин/макс", false) { actions.resetParam() },
                if (view.recordable && pid != null) {
                    FooterButton(if (view.recording) "Стоп записи" else "Запись", view.recording) { actions.setRecording(pid.request, !view.recording) }
                } else {
                    null
                },
            ),
            notes = listOf(FooterNote.Words(view.rate, mono = true)),
        )
    }
    val body: @Composable BoxScope.() -> Unit = { ParamBody(view, view.windows[window], window, tab) { tab = it } }
    if (frame != null) {
        RailPage(frame, footer, body)
    } else {
        ScannerPage(footer = footer) {
            header()
            body()
        }
    }
}

@Composable
private fun BoxScope.ParamBody(view: ParamView, stats: ParamStats?, window: Int, tab: Int, onTab: (Int) -> Unit) {
    val p = ScannerSpec.Param
    BlockHead(
        view.name, view.aside, view.line,
        counters = listOf(Counter("МИН", stats?.min ?: "—"), Counter("МАКС", stats?.max ?: "—"), Counter("СРЕДН.", stats?.mean ?: "—")),
    )
    ScanTabs(p.WINDOWS.map(ScannerWords::window), tab, onTab)
    // the reading: drawn over its own glow, as the board draws it twice
    ScanText("СЕЙЧАС", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(24f, p.LABEL_Y))
    val ink = if (view.outOfNorm) Ink.ORANGE_INK else Ink.WHITE
    ScanText(
        view.value, Type.PARAM_VALUE, ink, Modifier.at(24f, p.VALUE_Y),
        glow = if (view.outOfNorm) Ink.PARAM_GLOW_OUT else Ink.PARAM_GLOW, glowBlur = p.VALUE_GLOW,
    )
    ScanText(view.value, Type.PARAM_VALUE, ink, Modifier.at(24f, p.VALUE_Y))
    ScanText(view.unit, Type.PARAM_UNIT, Ink.DIM, Modifier.at(24f, p.VALUE_Y + Type.PARAM_VALUE.lh + p.UNIT_GAP))
    listOf("НОРМА" to view.normText, "ЗА 1 С" to (stats?.delta ?: "—"), "ТОЧЕК" to (stats?.points ?: "—")).forEachIndexed { i, (k, v) ->
        val y = p.STATS_Y + i * p.STATS_STEP
        ScanText(k, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(24f, y))
        ScanText(v, Type.VALUE_BMS, Ink.TEXT, Modifier.endAt(p.STATS_W, y - p.STATS_LIFT))
        Rule(24f, y + p.STATS_RULE, p.STATS_W - 24f, Ink.HAIR)
    }
    if (stats != null) ParamChart(view, stats, window)
}

/**
 * The chart: the norm as a band, the grid with its labels, the time ticks, the window's min and
 * max dashed, the trace on the car's clock - a missing reading breaks it - and its last point.
 */
@Composable
private fun BoxScope.ParamChart(view: ParamView, stats: ParamStats, window: Int) {
    val p = ScannerSpec.Param
    val x0 = p.CHART_X
    val x1 = LocalScanWidth.current - 24f
    val y0 = p.CHART_TOP
    val y1 = p.CHART_BOTTOM
    val lo = stats.scale.start
    val hi = stats.scale.endInclusive
    val yv = { v: Float -> if (hi > lo) y1 - (v - lo) / (hi - lo) * (y1 - y0) else (y0 + y1) / 2f }
    val xt = { t: Float -> x1 - t / window * (x1 - x0) }
    val ticks = ScannerWords.ticks(window)
    val trace = remember { Trace() }
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        view.norm?.let { n ->
            val top = yv(n.endInclusive).coerceIn(y0, y1)
            val bottom = yv(n.start).coerceIn(y0, y1)
            if (bottom > top) drawRect(ink(Ink.BLUE_SHARE), Offset(x0 * d, top * d), Size((x1 - x0) * d, (bottom - top) * d))
        }
        stats.grid.forEach { v -> drawRect(ink(Ink.HAIR), Offset(x0 * d, yv(v) * d), Size((x1 - x0) * d, d)) }
        ticks.forEach { (t, _) -> drawRect(ink(Ink.HAIR), Offset(xt(t) * d, y0 * d), Size(d, (y1 - y0) * d)) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(p.DASH * d, p.DASH * d))
        listOfNotNull(stats.minValue, stats.maxValue).forEach { v ->
            drawLine(ink(Ink.LINE4), Offset(x0 * d, yv(v) * d), Offset(x1 * d, yv(v) * d), d, pathEffect = dash)
        }
        trace.draw(this, view.samples, view.nowNanos, window, x0 * d, x1 * d) { yv(it) * d }
    }
    stats.grid.forEachIndexed { i, v ->
        val text = stats.gridText.getOrNull(i) ?: return@forEachIndexed
        ScanText(text, Type.SIG, Ink.FAINT, Modifier.endAt(x0 - p.GRID_LABEL_GAP, yv(v) - Type.SIG.lh / 2f))
    }
    ticks.forEach { (t, words) ->
        val m = Modifier.let { if (t == 0f) it.endAt(xt(t), y1 + p.TICK_LABEL_GAP) else it.centreAt(xt(t), y1 + p.TICK_LABEL_GAP) }
        ScanText(words, Type.SIG, Ink.FAINT, m)
    }
    stats.minValue?.let { v ->
        ScanText("мин ${stats.min}", Type.SIG, Ink.DIM2, Modifier.at(x0 + p.LIMIT_LABEL_X, yv(v) + p.LIMIT_LABEL_GAP))
    }
    stats.maxValue?.let { v ->
        ScanText("макс ${stats.max}", Type.SIG, Ink.DIM2, Modifier.at(x0 + p.LIMIT_LABEL_X, yv(v) - Type.SIG.lh - p.LIMIT_LABEL_GAP))
    }
}

/**
 * The trace, kept between frames: one path rebuilt in place, at most four points a pixel column -
 * its first, lowest, highest and last, in their order - so five minutes at 24 Hz cost what the
 * chart's width does, and the line looks as if every point were drawn.
 */
private class Trace {
    private val path = android.graphics.Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Ink.BLUE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Ink.PARAM_TRACE_GLOW
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLUE_INK }
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.PARAM_DOT_GLOW }
    private var density = 0f

    // the pixel column being gathered
    private var open = false
    private var column = Int.MIN_VALUE
    private var count = 0
    private var fx = 0f
    private var fy = 0f
    private var lx = 0f
    private var ly = 0f
    private var lowX = 0f
    private var lowY = 0f
    private var highX = 0f
    private var highY = 0f
    private var lowAt = 0
    private var highAt = 0

    fun draw(scope: DrawScope, samples: List<Sample>, now: Long, window: Int, x0: Float, x1: Float, y: (Float) -> Float) {
        if (samples.isEmpty()) return
        val p = ScannerSpec.Param
        if (scope.density != density) {
            density = scope.density
            line.strokeWidth = p.TRACE * density
            glow.strokeWidth = p.TRACE * density
            glow.maskFilter = blur(LightPen.blurRadius(p.TRACE_GLOW * density))
            dotGlow.maskFilter = blur(LightPen.blurRadius(p.DOT_GLOW * density))
        }
        val span = window * 1_000_000_000.0
        val from = now - window * 1_000_000_000L
        // the first reading in the window, and the one before it so the line enters from the edge
        var start = firstAtOrAfter(samples, from)
        val older = start > 0
        if (older) start--
        path.rewind()
        open = false
        count = 0
        column = Int.MIN_VALUE
        for (k in start until samples.size) {
            val s = samples[k]
            val v = s.value
            if (v == null) {
                flush()
                open = false
                continue
            }
            val x = (x1 - (now - s.atNanos) / span * (x1 - x0)).toFloat()
            val yy = y(v)
            val c = floor(x).toInt()
            if (c != column) {
                flush()
                column = c
                fx = x; fy = yy; lowX = x; lowY = yy; highX = x; highY = yy; lowAt = 0; highAt = 0
            } else {
                if (yy < lowY) { lowX = x; lowY = yy; lowAt = count }
                if (yy > highY) { highX = x; highY = yy; highAt = count }
            }
            lx = x; ly = yy
            count++
        }
        flush()
        scope.clipRect(left = if (older) x0 else 0f) {
            drawIntoCanvas { c ->
                c.nativeCanvas.drawPath(path, glow)
                c.nativeCanvas.drawPath(path, line)
            }
        }
        // the last reading, when it is one
        val last = samples.last()
        val lv = last.value ?: return
        if (last.atNanos < from) return
        val cx = (x1 - (now - last.atNanos) / span * (x1 - x0)).toFloat()
        val cy = y(lv)
        val r = p.DOT_R * density
        scope.drawIntoCanvas { c ->
            c.nativeCanvas.drawCircle(cx, cy, r, dotGlow)
            c.nativeCanvas.drawCircle(cx, cy, r, dot)
        }
    }

    /** Puts the gathered column on the path: its first point, its extremes in their order, its last. */
    private fun flush() {
        if (count == 0) return
        if (open) path.lineTo(fx, fy) else path.moveTo(fx, fy)
        if (count > 2) {
            // the extremes in the order they came
            if (lowAt <= highAt) { path.lineTo(lowX, lowY); path.lineTo(highX, highY) } else { path.lineTo(highX, highY); path.lineTo(lowX, lowY) }
        }
        if (count > 1) path.lineTo(lx, ly)
        open = true
        count = 0
    }

    private fun blur(radius: Float): BlurMaskFilter? = if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null

    private fun firstAtOrAfter(samples: List<Sample>, at: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].atNanos < at) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
