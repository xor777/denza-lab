package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.HeadCounter
import dev.denza.apps.feature.diagnostics.scan.PackCellsView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.abs

/*
 * «Тяговая батарея · Ячейки» (architecture, 6.3; `scanner.js` `sysPackCells()`, boards
 * `scanner-sys-pack-cells`, `-partial`, `-paused`): every cell's voltage up or down from a reference
 * held to a whole millivolt, on a grid symmetric about it, the sweep under way along the chart's
 * foot, the extremes named at their bars' ends, every probe's temperature as a tile. A cell or probe
 * the sweep drawn did not refresh is drawn faint; one not come yet is not drawn. The engine changes
 * what is drawn once a sweep; between two the bars move to their new offsets in one animation.
 */

/** The tab's body under the system's head and tabs. */
@Composable
internal fun BoxScope.PackCells(view: PackCellsView) {
    val p = ScannerSpec.PackCells
    val w = LocalScanWidth.current
    val x0 = p.CHART_X
    val x1 = w - 24f
    val y0 = p.CHART_TOP
    val y1 = p.CHART_BOTTOM
    val ref = view.cellRef
    // millivolts from the reference, the grid's last line at the chart's top
    val span = view.cellGrid.lastOrNull()?.takeIf { it > 0 }?.toFloat()
    val yv = { d: Float -> (y0 + y1) / 2f - (if (span != null) d.coerceIn(-span, span) / span else 0f) * (y1 - y0) / 2f }
    val n = view.cells.size
    val pitch = if (n > 0) (x1 - x0) / n else 0f
    val bw = pitch - p.BAR_GAP
    val offsets = barOffsets(view)

    // the cells' head: what is drawn, and its extremes at the right
    StatsHead(view.cellsHead, view.cellStats, p.CELLS_HEAD_Y, w)
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Int) = drawRect(ink(color), Offset(x * d, y * d), Size(rw * d, rh * d))
        // the scale: millivolts from the reference, its own line brighter
        view.cellGrid.forEach { g -> rect(x0, yv(g.toFloat()), x1 - x0, 1f, if (g == 0) Ink.WIRE4 else Ink.HAIR) }
        // the bars, one a cell, up or down from the reference: a cell that lags or leads stands out
        if (ref != null) {
            val ym = yv(0f)
            view.cells.forEachIndexed { i, c ->
                val v = c.value ?: return@forEachIndexed
                val off = offsets.getOrNull(i) ?: return@forEachIndexed
                val x = x0 + i * pitch
                val y = yv(off)
                val ext = i == view.cellMin || i == view.cellMax
                val top = minOf(y, ym)
                val h = maxOf(p.BAR_MIN, abs(ym - y))
                rect(x, top, bw, h, if (!c.fresh) Ink.SEP else if (ext) Ink.BLUE_INK else if (v >= ref) Ink.BLUE else Ink.BLUE_SOFT)
            }
        }
        // the sweep under way: a line along the chart's foot up to the page that came last
        view.sweep?.let { s ->
            val sx = x0 + s * (x1 - x0)
            rect(x0, y1 + 1f, sx - x0, p.SWEEP, Ink.BLUE_SOFT)
            drawCircle(ink(Ink.BLUE), p.SWEEP_DOT * d, Offset(sx * d, (y1 + 1f + p.SWEEP / 2f) * d))
        }
        // the extremes' leaders: the lowest below its bar, the highest above
        extremes(view).forEach { (i, dir) ->
            val off = offsets.getOrNull(i) ?: return@forEach
            val x = x0 + i * pitch + bw / 2f
            val y = yv(off)
            drawLine(ink(Ink.WIRE5), Offset(x * d, (y + dir * p.LEADER_FROM) * d), Offset(x * d, (y + dir * p.LEADER_TO) * d), d)
        }
    }
    // the grid's labels: offsets, the reference by its value
    view.cellGrid.forEachIndexed { i, g ->
        val t = view.cellGridText.getOrNull(i) ?: return@forEachIndexed
        ScanText(t, Type.SIG, if (g == 0) Ink.DIM2 else Ink.FAINT, Modifier.endAt(x0 - p.GRID_LABEL_GAP, yv(g.toFloat()) - Type.SIG.lh / 2f))
    }
    // the extremes named at their bars' ends
    extremes(view).forEach { (i, dir) ->
        val off = offsets.getOrNull(i) ?: return@forEach
        val x = x0 + i * pitch + bw / 2f
        val y = yv(off)
        val word = ScannerWords.cellExtreme(dir < 0, i)
        ScanText(word, Type.SIG, Ink.WHITE, Modifier.centreAt(x, if (dir > 0) y + p.LEADER_WORD else y - p.LEADER_WORD - Type.SIG.lh))
    }
    // the axis: numbers every twenty cells
    ScannerWords.cellTicks(n).forEach { k ->
        ScanText(k.toString(), Type.SIG, Ink.FAINT, Modifier.centreAt(x0 + (k - 1) * pitch + bw / 2f, y1 + p.TICK_GAP))
    }

    // the probes: a tile each, the extremes outlined
    StatsHead(view.probesHead, view.probeStats, p.PROBES_HEAD_Y, w)
    val m = view.probes.size
    val tw = if (m > 0) (w - 48f - (m - 1) * p.PROBE_GAP) / m else 0f
    view.probes.forEachIndexed { i, c ->
        val x = 24f + i * (tw + p.PROBE_GAP)
        val ext = i == view.probeMin || i == view.probeMax
        val old = !c.fresh
        Box(
            Modifier.at(x, p.PROBES_TOP).size(tw.dp, p.PROBE_H.dp)
                .plate(if (old) Ink.TILE_UNREAD else Ink.TILE_OK, if (ext) Ink.WHITE else if (old) Ink.LINE3 else Ink.BLUE_EDGE, p.PROBE_R),
        ) {
            ScanText(ScannerWords.probe(i), Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(p.PROBE_PAD_X, p.PROBE_PAD_Y))
            ScanText(c.text, Type.COUNTER, if (old || c.value == null) Ink.FAINT else Ink.WHITE, Modifier.at(p.PROBE_PAD_X, p.PROBE_PAD_Y + p.PROBE_VALUE_Y))
            ScanText("°C", Type.UNIT, if (old) Ink.FAINT else Ink.DIM2, Modifier.endAt(tw - p.PROBE_PAD_X, p.PROBE_PAD_Y + p.PROBE_UNIT_Y))
        }
    }
}

/**
 * Every bar's offset from the reference, mV, as drawn now: a new snapshot moves each bar from where
 * it stood to its new offset over [ScannerSpec.PackCells.MOVE_MS], one animation for all of them;
 * a bar that stood nowhere stands at its new offset at once. The first view is drawn as it is.
 */
@Composable
private fun barOffsets(view: PackCellsView): List<Float?> {
    val target = remember(view.cells, view.cellRef) {
        val ref = view.cellRef
        view.cells.map { c -> c.value?.let { v -> ref?.let { v - it } } }
    }
    val from = remember { mutableStateOf(target) }
    val to = remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to.value) return@LaunchedEffect
        from.value = between(from.value, to.value, t.value)
        to.value = target
        t.snapTo(0f)
        t.animateTo(1f, tween(ScannerSpec.PackCells.MOVE_MS, easing = FastOutSlowInEasing))
    }
    return between(from.value, to.value, t.value)
}

/** [b] with each bar [t] of the way from where it stood in [a]; a bar new in [b] at its own. */
private fun between(a: List<Float?>, b: List<Float?>, t: Float): List<Float?> =
    if (t >= 1f) b else b.mapIndexed { i, end -> end?.let { e -> a.getOrNull(i)?.let { s -> s + (e - s) * t } ?: e } }

/** The lowest cell named below its bar (+1), the highest above (−1). */
private fun extremes(view: PackCellsView): List<Pair<Int, Int>> =
    listOfNotNull(view.cellMin?.let { it to 1 }, view.cellMax?.let { it to -1 }).filter { it.first in view.cells.indices }

/**
 * A head over the chart or the tiles: what is drawn at the left, its figures at the right, a lit one
 * ([HeadCounter.on]) orange - the GB/T 32960 flags' count of the raised ones.
 */
@Composable
internal fun StatsHead(head: String, stats: List<HeadCounter>, y: Float, w: Float) {
    val p = ScannerSpec.PackCells
    val width = rememberWidths()
    ScanText(head, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(24f, y))
    var r = w - 24f
    stats.asReversed().forEach { s ->
        ScanText(s.value, Type.VALUE_BMS, if (s.on) Ink.ORANGE else Ink.WHITE, Modifier.endAt(r, y - p.STAT_LIFT))
        r -= width(s.value, Type.VALUE_BMS) + p.STAT_GAP
        ScanText(s.label, Type.COUNTER_LABEL, Ink.MUTE, Modifier.endAt(r, y))
        r -= width(s.label, Type.COUNTER_LABEL) + p.STAT_STEP
    }
}
