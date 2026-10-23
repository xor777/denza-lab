package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.HealthRow
import dev.denza.apps.feature.diagnostics.scan.PackCellsView
import dev.denza.apps.feature.diagnostics.scan.ReferenceKind
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.abs

/*
 * «Тяговая батарея · Здоровье», the pack's first tab (architecture, 7.3; `scanner.js` `packHealth()`,
 * boards `scanner-sys-pack-health`, `-health-first`): the pack's health on one table - now, its
 * reference (the norm, or what is usual for this car over the journal's 30 days) and the 30 days as
 * a mini chart - and beside it the live cells from their mean, the sweep under way and the probes.
 */

@Composable
internal fun HealthContent(model: DiagnosticsModel) {
    val view = model.health
    view?.rows?.let { HealthTable(it) }
    (view?.cells ?: model.packCells)?.let { HealthCells(it) }
}

@Composable
private fun HealthTable(rows: List<HealthRow>) {
    val t = ScannerSpec.Health
    val cols = ScannerSpec.columns(t.COLS, 24f, t.W)
    HeadRow(
        cols, listOf(HeadLabel("ПОКАЗАТЕЛЬ", pad = 0f), HeadLabel("СЕЙЧАС", end = true, pad = t.VALUE_PAD), HeadLabel("ОПОРА"), HeadLabel("30 ДНЕЙ")),
        t.TOP, t.HEAD, ruleW = t.W,
    )
    val width = rememberWidths()
    var y = t.TOP + t.HEAD + 1f
    rows.forEach { r ->
        val h = t.ROW
        val nt = y + (h - Type.CELL.lh - t.SUB_GAP - Type.SMALL12.lh) / 2f
        ScanText(r.name, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first, nt), maxWidth = cols[0].second - 8f)
        ScanText(r.sub, Type.SMALL12, Ink.MUTE, Modifier.at(cols[0].first, nt + Type.CELL.lh + t.SUB_GAP), maxWidth = cols[0].second - 8f)
        var xr = cols[1].first + cols[1].second - t.VALUE_PAD
        val none = r.value == "—"
        if (r.unit.isNotEmpty() && !none) {
            ScanText(r.unit, Type.CELL_ASIDE, Ink.DIM, Modifier.endAt(xr, mid(y, h, Type.CELL_ASIDE)))
            xr -= width(r.unit, Type.CELL_ASIDE) + t.UNIT_GAP
        }
        ScanText(r.value, Type.VALUE, if (none) Ink.FAINT else if (r.outOfNorm) Ink.ORANGE else Ink.WHITE, Modifier.endAt(xr, mid(y, h, Type.VALUE)))
        r.reference?.let { ref ->
            if (ref.kind == ReferenceKind.USUAL) {
                ScanRuns(
                    listOf(Triple("обычно ", Type.UNIT, Ink.MUTE), Triple(ref.text, Type.NORM_MONO, Ink.DIM)),
                    Modifier.at(cols[2].first, mid(y, h, Type.NORM_MONO)), base = Type.NORM_MONO,
                )
            } else {
                ScanText(ref.text, Type.NORM_MONO, Ink.DIM, Modifier.at(cols[2].first, mid(y, h, Type.NORM_MONO)))
            }
        }
        val scale = r.trendScale
        if (r.trend.isNotEmpty() && scale != null) Spark(r.trend, scale, r.trendPoints, cols[3].first, y + (h - t.SPARK_H) / 2f)
        Rule(24f, y + h, t.W, Ink.HAIR)
        y += h + 1f
    }
}

/** A mini chart (`spark()`): the values on their scale, a line - broken where a day has none - and dots for separate snapshots. */
@Composable
private fun Spark(values: List<Float?>, scale: ClosedFloatingPointRange<Float>, points: Boolean, x: Float, y: Float) {
    val t = ScannerSpec.Health
    Canvas(Modifier.at(x, y).size(t.SPARK_W.dp, t.SPARK_H.dp)) {
        val k = density
        val n = values.size
        val span = (scale.endInclusive - scale.start).takeIf { it != 0f } ?: 1f
        fun px(i: Int) = (1f + i * (t.SPARK_W - 2f) / maxOf(1, n - 1)) * k
        fun py(v: Float) = (t.SPARK_H - 2f - (v - scale.start) / span * (t.SPARK_H - 4f)) * k
        val path = Path()
        var open = false
        values.forEachIndexed { i, v ->
            if (v == null) { open = false; return@forEachIndexed }
            if (open) path.lineTo(px(i), py(v)) else path.moveTo(px(i), py(v))
            open = true
        }
        drawPath(path, ink(Ink.BLUE), style = Stroke(t.SPARK_STROKE * k, join = StrokeJoin.Round))
        if (points) values.forEachIndexed { i, v -> if (v != null) drawCircle(ink(Ink.BLUE), t.SPARK_DOT * k, Offset(px(i), py(v))) }
    }
}

/**
 * The cells from the sweep's mean, a millivolt to [ScannerSpec.Health.CELLS_MV] - the extremes white,
 * the rest at three quarters of their light - the sweep under way along the chart's foot, the extremes
 * named under it; the probes as tiles, the coldest and the warmest outlined.
 */
@Composable
private fun HealthCells(view: PackCellsView) {
    val c = ScannerSpec.Health
    val x = c.CELLS_X
    val pw = c.CELLS_W - c.CELLS_SCALE_W
    val ym = c.CELLS_TOP + c.CELLS_H / 2f
    val ref = view.cellRef
    ScanText("ЯЧЕЙКИ · ОТ СРЕДНЕГО ${ref?.let { grouped(it) } ?: "—"} мВ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x, c.CELLS_LABEL_Y))
    c.CELLS_GRID.forEach { mv ->
        val gy = ym - mv * c.CELLS_MV
        ScanText((if (mv > 0) "+" else "−") + abs(mv), Type.TICK, Ink.FAINT, Modifier.endAt(x + c.CELLS_W - 2f, gy - Type.TICK.lh / 2f))
    }
    val n = view.cells.size
    val step = if (n > 0) (c.CELLS_W - c.CELLS_RIGHT) / n else 0f
    Canvas(Modifier.fillMaxSize()) {
        val k = density
        fun rect(rx: Float, ry: Float, rw: Float, rh: Float, color: Int, alpha: Float = 1f) =
            drawRect(ink(color), Offset(rx * k, ry * k), Size(rw * k, rh * k), alpha = alpha)
        c.CELLS_GRID.forEach { mv -> rect(x, ym - mv * c.CELLS_MV, pw, 1f, Ink.RULE) }
        rect(x, ym, pw, 1f, Ink.LINE3)
        if (ref != null) {
            view.cells.forEachIndexed { i, cell ->
                val v = cell.value ?: return@forEachIndexed
                val d = v - ref
                if (d == 0f) return@forEachIndexed
                val ext = i == view.cellMin || i == view.cellMax
                rect(
                    x + i * step, if (d > 0) ym - d * c.CELLS_MV else ym, maxOf(c.BAR_MIN_W, step - c.CELLS_GAP), abs(d) * c.CELLS_MV,
                    if (ext) Ink.WHITE else Ink.BLUE, if (ext) 1f else c.BAR_ALPHA,
                )
            }
        }
        view.sweep?.let { s ->
            val p = ScannerSpec.PackCells
            val sx = x + s * (c.CELLS_W - c.CELLS_RIGHT)
            val fy = c.CELLS_TOP + c.CELLS_H + 1f
            rect(x, fy, sx - x, p.SWEEP, Ink.BLUE_SOFT)
            drawCircle(ink(Ink.BLUE), p.SWEEP_DOT * k, Offset(sx * k, (fy + p.SWEEP / 2f) * k))
        }
    }
    view.cellMin?.let { i -> view.cells.getOrNull(i)?.let { ScanText(ScannerWords.healthExtreme(false, i, it.text), Type.SIG, Ink.INK3, Modifier.at(x, c.CELLS_STATS_Y)) } }
    view.cellMax?.let { i ->
        view.cells.getOrNull(i)?.let { ScanText(ScannerWords.healthExtreme(true, i, it.text), Type.SIG, Ink.INK3, Modifier.endAt(x + c.CELLS_W - c.CELLS_RIGHT, c.CELLS_STATS_Y)) }
    }
    // the probes, the extremes outlined
    ScanText("ДАТЧИКИ, °C", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x, c.PROBES_LABEL_Y))
    val lo = view.probeMin?.let { view.probes.getOrNull(it)?.value }
    val hi = view.probeMax?.let { view.probes.getOrNull(it)?.value }
    view.probes.forEachIndexed { i, p ->
        val px = x + (i % c.PROBES_COLS) * c.PROBE_STEP_X
        val py = c.PROBES_TOP + (i / c.PROBES_COLS) * c.PROBE_STEP_Y
        val ext = p.value != null && (p.value == lo || p.value == hi)
        Box(Modifier.at(px, py).size(c.PROBE_W.dp, c.PROBE_H.dp).plate(null, if (ext) Ink.INK3 else Ink.QUIET_LINE, c.PROBE_R))
        ScanText(ScannerWords.probe(i), Type.BIT_HEAD, Ink.MUTE, Modifier.at(px + c.PROBE_PAD_X, py + c.PROBE_PAD_Y))
        ScanText(p.text, Type.DRIVE_CELL, if (p.fresh && p.value != null) Ink.WHITE else Ink.FAINT, Modifier.at(px + c.PROBE_PAD_X, py + c.PROBE_H - c.PROBE_PAD_Y - Type.DRIVE_CELL.lh))
    }
}

/** «3 282»: thousands apart by a space. */
internal fun grouped(v: Int): String {
    val digits = abs(v).toString().reversed().chunked(3).joinToString(" ").reversed()
    return if (v < 0) "−$digits" else digits
}
