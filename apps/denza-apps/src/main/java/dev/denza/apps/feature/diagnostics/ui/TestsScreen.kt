package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.TestRow
import dev.denza.apps.feature.diagnostics.scan.TestsView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Тесты» of the ECM (architecture, 7.3; `scanner.js` `ecuTests()`, boards `scanner-ecu-tests`, `-ecu-tests-out`):
 * what was asked and what came on one muted line, then the monitors in two columns - a monitor's title, a test
 * a line, its exchange under it. A test's value, its unit, the minimum, a hairline scale with the value's mark
 * and the maximum; the norm says nothing, a value out of its limits is orange and its mark stands at the end it
 * left by with a point beyond it, a test that has not run says so and has no scale. The page reads as it
 * opens; «Прочитать снова» reads the tests again.
 */

@Composable
internal fun TestsContent(view: TestsView) {
    val e = ScannerSpec.EcuTests
    val x0 = e.X.first()
    if (view.line == null) {
        // read as the code's page reads: a word, and what came back raw
        var y = e.LINE
        view.word?.let { ScanText(it, Type.CELL_SMALL, Ink.DIM, Modifier.at(x0, y)); y += Type.CELL_SMALL.lh + 4f }
        view.raw.forEach { ScanText(it, Type.NOTE_MONO, Ink.FAINT, Modifier.at(x0, y)); y += e.RAW }
        return
    }
    val lines = view.columns.maxOfOrNull { c -> c.sumOf { it.lines } } ?: 0
    ScrollRows(e.LINE) {
        Box(Modifier.fillMaxWidth().height((e.TOP - e.LINE + lines * e.ROW).dp)) {
            // y on the page, less the scroll's top
            fun at(x: Float, y: Float) = Modifier.at(x, y - e.LINE)
            ScanText(view.line, Type.NOTE_MONO, Ink.DIM2, at(x0, e.LINE))
            val marks = ArrayList<Triple<Pair<Float, Float>, Float, TestRow>>()
            val hairs = ArrayList<Triple<Float, Float, Float>>()
            view.columns.forEachIndexed { ci, column ->
                val x = e.X[ci]
                val cols = ScannerSpec.columns(e.ROW_COLS, x, e.W)
                var y = e.TOP
                column.forEach { g ->
                    ScanText(g.title, Type.GROUP_TITLE, Ink.MUTE, at(x, y + e.TITLE_Y), maxWidth = e.W)
                    y += e.TITLE
                    if (g.rows.isEmpty() && g.word != null) {
                        ScanText(g.word, Type.CELL_SMALL, Ink.DIM, at(cols[0].first, mid(y, e.ROW, Type.CELL_SMALL)))
                        y += e.ROW
                    }
                    g.rows.forEach { r ->
                        val h = e.ROW
                        val out = r.pass == false
                        val ran = r.pass != null
                        ScanText("TID ${r.tid}", Type.NORM_MONO, Ink.FAINT, at(cols[0].first, mid(y, h, Type.NORM_MONO)))
                        ScanText(
                            r.value, Type.CELL_NUM, if (out) Ink.ORANGE else if (ran) Ink.WHITE else Ink.FAINT,
                            Modifier.endAt(cols[1].first + cols[1].second - e.VALUE_PAD, mid(y, h, Type.CELL_NUM) - e.LINE),
                        )
                        if (r.unit.isNotEmpty()) ScanText(r.unit, Type.UNIT, Ink.DIM2, at(cols[2].first, mid(y, h, Type.UNIT)), maxWidth = cols[2].second - 4f)
                        if (!ran) {
                            ScanText("не выполнялся", Type.CELL_SMALL, Ink.FAINT, at(cols[3].first, mid(y, h, Type.CELL_SMALL)))
                        } else {
                            ScanText(r.min, Type.CELL_MONO, Ink.DIM2, Modifier.endAt(cols[3].first + cols[3].second - e.LIMIT_PAD, mid(y, h, Type.CELL_MONO) - e.LINE))
                            if (r.position != null) marks += Triple(cols[4], y - e.LINE, r)
                            ScanText(r.max, Type.CELL_MONO, Ink.DIM2, at(cols[5].first + e.LIMIT_PAD, mid(y, h, Type.CELL_MONO)))
                            if (out) ScanText("вне нормы", Type.CELL_SMALL, Ink.ORANGE, at(cols[6].first, mid(y, h, Type.CELL_SMALL)))
                        }
                        hairs += Triple(x, y + h - 1f - e.LINE, e.W)
                        y += h
                    }
                    g.raw.forEach { l ->
                        ScanText(l, Type.NOTE_MONO, Ink.FAINT, at(x, y + e.RAW_Y), maxWidth = e.W)
                        y += e.RAW
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val k = density
                fun rect(rx: Float, ry: Float, rw: Float, rh: Float, color: Int) =
                    drawRect(ink(color), Offset(rx * k, ry * k), Size(rw * k, rh * k))
                hairs.forEach { (hx, hy, hw) -> rect(hx, hy, hw, 1f, Ink.HAIR) }
                marks.forEach { (col, y, r) ->
                    // the scale: a hairline from the minimum to the maximum with a tick at each end, the value's mark
                    val (sx, sw) = col
                    val a = sx + e.SCALE_INSET
                    val b = sx + sw - e.SCALE_INSET
                    val cy = y + e.ROW / 2f
                    rect(a, cy - 0.5f, b - a, 1f, Ink.LINE3)
                    rect(a, cy - e.SCALE_TICK / 2f, 1f, e.SCALE_TICK, Ink.LINE3)
                    rect(b - 1f, cy - e.SCALE_TICK / 2f, 1f, e.SCALE_TICK, Ink.LINE3)
                    val out = r.pass == false
                    val mx = a + r.position!! * (b - a)
                    rect(mx - e.MARK_W / 2f, cy - e.MARK_H / 2f, e.MARK_W, e.MARK_H, if (out) Ink.ORANGE else Ink.WHITE)
                    if (r.beyond != 0) {
                        // a point beyond the end the value left by
                        val ex = if (r.beyond > 0) b + e.OUT_GAP else a - e.OUT_GAP
                        val tip = ex + r.beyond * e.OUT_W
                        val p = Path().apply {
                            moveTo(ex * k, (cy - e.OUT_H / 2f) * k)
                            lineTo(tip * k, cy * k)
                            lineTo(ex * k, (cy + e.OUT_H / 2f) * k)
                            close()
                        }
                        drawPath(p, ink(Ink.ORANGE))
                    }
                }
            }
        }
    }
}
