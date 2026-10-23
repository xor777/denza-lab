package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.GraphLane
import dev.denza.apps.feature.diagnostics.scan.GraphsView
import dev.denza.apps.feature.diagnostics.scan.LiveRow
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.abs

/*
 * «Потоковые данные · Графики» (architecture, 7.3; `scanner.js` `sysGraphs()`, board
 * `scanner-sys-engine-graphs`): up to four signals on one 60 s axis, each its own lane and scale - the
 * norm a band - and a cursor a tap puts down, reading every lane at one moment; the legend at the
 * left says each signal's value at the cursor (its newest without one) and the minute's extremes. A
 * lane's legend opens its parameter alone.
 */

private const val WINDOW_S = 60f

/** Seconds back from [now] of a sample's moment. */
private fun back(now: Long, at: Long): Float = (now - at) / 1e9f

@Composable
internal fun GraphLanes(view: GraphsView?, start: Float?, onParam: (ParamKey) -> Unit) {
    val g = ScannerSpec.Graphs
    val w = LocalScanWidth.current
    val gx = g.PLOT_X
    val gw = w - 24f - gx
    val width = rememberWidths()
    var cursor by remember(view?.scope) { mutableStateOf(start) }
    val now = view?.nowNanos ?: 0L
    fun xOf(s: Float) = gx + (WINDOW_S - s) / WINDOW_S * gw
    val lanes = view?.lanes.orEmpty().take(g.LANES.size)
    // the cursor on the sample nearest its moment, in the first lane's timing
    val snapped = cursor?.let { c ->
        lanes.firstOrNull()?.samples?.filter { back(now, it.atNanos) in 0f..WINDOW_S }?.minByOrNull { abs(back(now, it.atNanos) - c) }?.let { back(now, it.atNanos) } ?: c
    }
    fun at(lane: GraphLane): Float? {
        val inWindow = lane.samples.filter { back(now, it.atNanos) in 0f..WINDOW_S }
        val s = if (snapped == null) inWindow.lastOrNull { it.value != null } else inWindow.minByOrNull { abs(back(now, it.atNanos) - snapped) }
        return s?.value
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(view?.scope) {
            detectTapGestures { p ->
                val x = p.x / density
                val y = p.y / density
                if (x >= gx && x <= gx + gw && y >= g.CURSOR_TOP && y <= g.CURSOR_BOTTOM) cursor = ((gx + gw - x) / gw * WINDOW_S).coerceIn(0f, WINDOW_S)
            }
        },
    ) {
        val k = density
        fun rect(rx: Float, ry: Float, rw: Float, rh: Float, color: Int) = drawRect(ink(color), Offset(rx * k, ry * k), Size(rw * k, rh * k))
        lanes.forEachIndexed { i, lane ->
            val top = g.LANES[i]
            val lh = g.LANE
            val lo = lane.scale.start
            val hi = lane.scale.endInclusive
            fun y(v: Float) = top + (hi - v) / (hi - lo) * lh
            rect(gx, top + lh, gw, 1f, Ink.RULE)
            rect(gx, top, gw, 1f, Ink.HAIR)
            lane.band?.let { b -> rect(gx, y(b.endInclusive), gw, y(b.start) - y(b.endInclusive), Ink.BAND) }
            val path = Path()
            var open = false
            lane.samples.forEach { s ->
                val v = s.value
                val t = back(now, s.atNanos)
                if (v == null || t > WINDOW_S || t < 0f) { open = false; return@forEach }
                val px = xOf(t) * k
                val py = y(v.coerceIn(lo, hi)) * k
                if (open) path.lineTo(px, py) else path.moveTo(px, py)
                open = true
            }
            drawPath(path, ink(Ink.BLUE), style = Stroke(g.TRACE * k, join = StrokeJoin.Round))
        }
        snapped?.let { c ->
            val cx = xOf(c)
            rect(cx - 0.5f, g.CURSOR_TOP, 1f, g.CURSOR_BOTTOM - g.CURSOR_TOP, Ink.WIRE4)
            lanes.forEachIndexed { i, lane ->
                val v = at(lane) ?: return@forEachIndexed
                val lo = lane.scale.start
                val hi = lane.scale.endInclusive
                drawCircle(ink(Ink.WHITE), g.CURSOR_DOT * k, Offset(cx * k, (g.LANES[i] + (hi - v.coerceIn(lo, hi)) / (hi - lo) * g.LANE) * k))
            }
        }
    }
    lanes.forEachIndexed { i, lane ->
        val top = g.LANES[i]
        ScanText(lane.scaleText.first, Type.SCALE, Ink.FAINT, Modifier.endAt(w - 24f - g.SCALE_INSET, top + g.SCALE_PAD))
        ScanText(lane.scaleText.second, Type.SCALE, Ink.FAINT, Modifier.endAt(w - 24f - g.SCALE_INSET, top + g.LANE - Type.SCALE.lh - g.SCALE_PAD))
        // the legend: name, source, the value at the cursor, the minute's extremes
        Box(Modifier.at(24f, top).size(g.LEGEND_W.dp, g.LANE.dp).tap(lane.key?.let { { onParam(it) } })) {
            var ly = 0f
            ScanText(lane.name, Type.TITLE_ASIDE, Ink.WHITE, Modifier.at(0f, ly), maxWidth = g.LEGEND_W)
            ly += Type.TITLE_ASIDE.lh + g.NAME_GAP
            ScanText(lane.source, Type.SIG, Ink.MUTE, Modifier.at(0f, ly))
            ly += Type.SIG.lh + g.NAME_GAP + g.VALUE_GAP
            val value = at(lane)?.let { ScannerWords.number(it, lane.decimals, lane.signed) } ?: "—"
            ScanText(value, Type.COUNTER, if (value == "—") Ink.FAINT else Ink.WHITE, Modifier.at(0f, ly))
            ScanText(lane.unit, Type.UNIT, Ink.DIM, Modifier.at(width(value, Type.COUNTER) + g.VALUE_GAP, ly + Type.COUNTER.lh - Type.UNIT.lh - 2f))
            ly += Type.COUNTER.lh + g.NAME_GAP
            val values = lane.samples.filter { back(now, it.atNanos) in 0f..WINDOW_S }.mapNotNull { it.value }
            if (values.isNotEmpty()) {
                val span = ScannerWords.number(values.min(), lane.decimals, lane.signed) + "…" + ScannerWords.number(values.max(), lane.decimals, lane.signed)
                ScanText(span, Type.UNIT_TAG, Ink.FAINT, Modifier.at(0f, ly))
            }
        }
    }
    // the axis: seconds back, the cursor's moment on it
    ScannerWords.GRAPH_TICKS.forEach { t -> ScanText("−$t с", Type.ROW_SIG, Ink.FAINT, Modifier.at(xOf(t.toFloat()), g.AXIS_Y)) }
    ScanText("сейчас", Type.ROW_SIG, Ink.FAINT, Modifier.endAt(w - 24f, g.AXIS_Y))
    val c = cursor
    if (snapped != null && c != null) {
        val cx = xOf(snapped)
        Box(Modifier.at(cx - g.TAG_W / 2f, g.TAG_Y).size(g.TAG_W.dp, g.TAG_H.dp).plate(Ink.QUIET, Ink.QUIET_LINE, g.TAG_R))
        ScanText(ScannerWords.secondsBack(c), Type.UNIT_TAG, Ink.WHITE, Modifier.centreAt(cx, g.TAG_Y + (g.TAG_H - Type.UNIT_TAG.lh) / 2f))
    }
}

/** «Сигналы · N»: up to four of the live rows on the graphs. No board: the scanner's plate and rows, as «Выбор PID». */
@Composable
internal fun SignalPicker(rows: List<LiveRow>, keys: List<ParamKey>, onDone: (List<ParamKey>) -> Unit, onCancel: () -> Unit) {
    var chosen by remember { mutableStateOf(keys) }
    Box(Modifier.fillMaxSize().plate(0xB3000000.toInt()).tap(onCancel), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).plate(Ink.BAR, Ink.RULE, 8f).tap {}.padding(16.dp)) {
            ScanText("СИГНАЛЫ НА ГРАФИКАХ · ДО ЧЕТЫРЁХ", Type.COUNTER_LABEL, Ink.MUTE)
            Spacer(Modifier.height(8.dp))
            rows.filter { it.key != null }.forEach { r ->
                val key = r.key!!
                val on = key in chosen
                Box(
                    Modifier.fillMaxWidth().height(32.dp).plate(if (on) Ink.BLUE_FILL else null, null, 4f).tap {
                        chosen = if (on) chosen - key else if (chosen.size < 4) chosen + key else chosen
                    },
                ) {
                    ScanText(r.device ?: ScannerWords.pid(r.pid), Type.NORM_MONO, if (on) Ink.BLUE_SOFT else Ink.FAINT, Modifier.at(12f, mid(0f, 32f, Type.NORM_MONO)))
                    ScanRuns(listOf(Triple("${r.name} ", Type.CELL, Ink.WHITE), Triple(r.abbreviation, Type.CELL_ASIDE, Ink.FAINT)), Modifier.at(72f, mid(0f, 32f, Type.CELL)))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                ScanButton("Готово", true, { onDone(chosen) })
                Spacer(Modifier.width(10.dp))
                ScanButton("Отмена", false, onCancel)
            }
        }
    }
}
