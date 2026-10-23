package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.report.ReportDocuments
import dev.denza.apps.feature.diagnostics.report.ReportLayout
import dev.denza.apps.feature.diagnostics.report.ReportOutcome
import dev.denza.apps.feature.diagnostics.report.ReportPainter
import dev.denza.apps.feature.diagnostics.report.ReportPaints
import dev.denza.apps.feature.diagnostics.report.ReportSheet
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Отчёт» (architecture, 7.4; `scanner.js` `report()`, board `scanner-report`): the report's first page
 * as it will be saved - drawn by the same painter that draws the PDF - what goes in, ticked, and where
 * it lands. «Сохранить в Загрузки» writes it; a save that failed leaves the button there again, without
 * a word.
 */

@Composable
internal fun ReportPage(model: DiagnosticsModel, header: @Composable () -> Unit, onBack: () -> Unit, actions: DiagnosticsActions) {
    val state = model.report
    val input = state.input
    var ticked by remember(state.options.map { it.section }) {
        mutableStateOf(state.options.filter { it.byDefault && it.available }.map { it.section }.toSet())
    }
    val saving = state.outcome == ReportOutcome.Saving
    val context = LocalContext.current
    val paints = remember(context) { ReportPaints(context) }
    val pages = remember(input, ticked, paints) { input?.let { ReportLayout(paints).lay(ReportDocuments.of(it, ticked)) }.orEmpty() }
    ScannerPage(footer = {
        ScanFooter(
            listOfNotNull(
                FooterButton("Отмена", false, onClick = onBack),
                input?.let { FooterButton(if (saving) "Сохраняю…" else "Сохранить в Загрузки", true) { if (!saving) actions.saveReport(ticked) } },
            ),
            listOfNotNull(input?.exchanges?.services?.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(ScannerWords.reportServices(it), mono = true) }),
        )
    }) {
        header()
        val r = ScannerSpec.Report
        val w = LocalScanWidth.current - 24f - r.X
        // the first page, small: the sheet and the painter's own ink on it
        val first = pages.firstOrNull()
        Canvas(Modifier.at(r.PAPER_X, r.PAPER_Y).size(r.PAPER_W.dp, r.PAPER_H.dp)) {
            drawRoundRect(ink(Ink.PAPER), cornerRadius = CornerRadius(r.PAPER_R.dp.toPx()))
            if (first != null) {
                drawIntoCanvas { c ->
                    val k = size.width / ReportSheet.WIDTH
                    c.nativeCanvas.save()
                    c.nativeCanvas.scale(k, k)
                    ReportPainter.draw(first, c.nativeCanvas, paints)
                    c.nativeCanvas.restore()
                }
            }
        }
        ScanText("Отчёт о диагностике", Type.TITLE, Ink.WHITE, Modifier.at(r.X, r.TITLE_Y))
        input?.let { ScanText(ScannerWords.reportLine(it, pages.size), Type.LINE, Ink.MUTE, Modifier.at(r.X, r.LINE_Y), maxWidth = w) }
        ScanText("ЧТО ВОЙДЁТ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(r.X, r.LABEL_Y))
        var y = r.LIST_TOP
        state.options.forEach { o ->
            val on = o.section in ticked
            Box(
                Modifier.at(r.X, y).size(w.dp, r.ROW.dp).tap(if (o.available) ({ ticked = if (on) ticked - o.section else ticked + o.section }) else null),
            )
            CheckBox(on, r.X, y + (r.ROW - r.BOX) / 2f)
            ScanText(o.section.title, Type.ITEM, if (on) Ink.WHITE else Ink.DIM, Modifier.at(r.X + r.BOX + r.BOX_GAP, mid(y, r.ROW, Type.ITEM)))
            ScanText(o.detail, Type.UNIT, Ink.MUTE, Modifier.endAt(r.X + w, mid(y, r.ROW, Type.UNIT)))
            Rule(r.X, y + r.ROW, w, Ink.HAIR)
            y += r.ROW + 1f
        }
        ScanText("КУДА", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(r.X, r.DEST_LABEL))
        val saved = state.outcome as? ReportOutcome.Saved
        input?.let { inp ->
            val dest = ScannerWords.reportDestination(inp, ticked, saved)
            ScanText(dest.path, Type.KEY_VALUE, Ink.WHITE, Modifier.at(r.X, r.DEST), maxWidth = w)
            ScanText(dest.note, Type.CELL_SMALL, Ink.INK3, Modifier.at(r.X, r.DEST_NOTE))
            ScanText(dest.size, Type.NOTE_MONO, Ink.MUTE, Modifier.at(r.X, r.DEST_SIZE))
        }
    }
}

/** A line of «что войдёт»'s box: filled blue with a tick, or an empty frame. */
@Composable
private fun CheckBox(on: Boolean, x: Float, y: Float) {
    val r = ScannerSpec.Report
    Canvas(Modifier.at(x, y).size(r.BOX.dp)) {
        val k = density
        if (on) {
            drawRoundRect(ink(Ink.BLUE), cornerRadius = CornerRadius(r.BOX_R * k))
            val p = Path().apply { moveTo(4.5f * k, 9.3f * k); lineTo(7.5f * k, 12.9f * k); lineTo(14.4f * k, 5.4f * k) }
            drawPath(p, ink(Ink.WHITE), style = Stroke(1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawRoundRect(
                ink(Ink.LINE3), Offset(0.75f * k, 0.75f * k), Size(size.width - 1.5f * k, size.height - 1.5f * k), CornerRadius(r.BOX_R * k),
                style = Stroke(1.5f * k),
            )
        }
    }
}
