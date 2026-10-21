package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.HeadCounter
import dev.denza.apps.feature.diagnostics.scan.PackCellsView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Тяговая батарея · Ячейки» (architecture, 6.3; `scanner.js` `sysPackCells()`, boards
 * `scanner-sys-pack-cells`, `-partial`): every cell's voltage up or down from the pack's mean, the
 * extremes named at their bars' ends, every probe's temperature as a tile. A cell or probe whose
 * page is older than the last complete sweep is drawn faint; one not come yet is not drawn.
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
    val lo = view.cellScale.start
    val hi = view.cellScale.endInclusive
    val yv = { v: Float -> if (hi > lo) y1 - (v - lo) / (hi - lo) * (y1 - y0) else (y0 + y1) / 2f }
    val n = view.cells.size
    val pitch = if (n > 0) (x1 - x0) / n else 0f
    val bw = pitch - p.BAR_GAP
    val mean = view.cellMean

    // the cells' head: what is drawn, and its extremes at the right
    StatsHead(view.cellsHead, view.cellStats, p.CELLS_HEAD_Y, w)
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Int) = drawRect(ink(color), Offset(x * d, y * d), Size(rw * d, rh * d))
        // the scale: the grid
        view.cellGrid.forEach { v -> rect(x0, yv(v), x1 - x0, 1f, Ink.HAIR) }
        // the bars, one a cell, up or down from the pack's mean: a cell that lags or leads stands out
        if (mean != null) {
            val ym = yv(mean)
            view.cells.forEachIndexed { i, c ->
                val v = c.value ?: return@forEachIndexed
                val x = x0 + i * pitch
                val y = yv(v)
                val ext = i == view.cellMin || i == view.cellMax
                val top = minOf(y, ym)
                val h = maxOf(p.BAR_MIN, kotlin.math.abs(ym - y))
                rect(x, top, bw, h, if (!c.fresh) Ink.SEP else if (ext) Ink.BLUE_INK else if (v >= mean) Ink.BLUE else Ink.BLUE_SOFT)
            }
            rect(x0, ym - 0.5f, x1 - x0, 1f, Ink.WIRE4)
        }
        // the extremes' leaders: the lowest below its bar, the highest above
        extremes(view).forEach { (i, dir) ->
            val v = view.cells[i].value ?: return@forEach
            val x = x0 + i * pitch + bw / 2f
            val y = yv(v)
            drawLine(ink(Ink.WIRE5), Offset(x * d, (y + dir * p.LEADER_FROM) * d), Offset(x * d, (y + dir * p.LEADER_TO) * d), d)
        }
    }
    // the grid's labels
    view.cellGrid.forEachIndexed { i, v ->
        val t = view.cellGridText.getOrNull(i) ?: return@forEachIndexed
        ScanText(t, Type.SIG, Ink.FAINT, Modifier.endAt(x0 - p.GRID_LABEL_GAP, yv(v) - Type.SIG.lh / 2f))
    }
    // the extremes named at their bars' ends
    extremes(view).forEach { (i, dir) ->
        val v = view.cells[i].value ?: return@forEach
        val x = x0 + i * pitch + bw / 2f
        val y = yv(v)
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

/** The lowest cell named below its bar (+1), the highest above (−1). */
private fun extremes(view: PackCellsView): List<Pair<Int, Int>> =
    listOfNotNull(view.cellMin?.let { it to 1 }, view.cellMax?.let { it to -1 }).filter { it.first in view.cells.indices }

/** A head over the chart or the tiles: what is drawn at the left, its figures at the right. */
@Composable
private fun StatsHead(head: String, stats: List<HeadCounter>, y: Float, w: Float) {
    val p = ScannerSpec.PackCells
    val width = rememberWidths()
    ScanText(head, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(24f, y))
    var r = w - 24f
    stats.asReversed().forEach { s ->
        ScanText(s.value, Type.VALUE_BMS, Ink.WHITE, Modifier.endAt(r, y - p.STAT_LIFT))
        r -= width(s.value, Type.VALUE_BMS) + p.STAT_GAP
        ScanText(s.label, Type.COUNTER_LABEL, Ink.MUTE, Modifier.endAt(r, y))
        r -= width(s.label, Type.COUNTER_LABEL) + p.STAT_STEP
    }
}
