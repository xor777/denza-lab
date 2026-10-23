package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.BandPart
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.SummaryBlock
import dev.denza.apps.feature.diagnostics.scan.SummarySystem
import dev.denza.apps.feature.diagnostics.scan.SummaryView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Style
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Сводка», the section's entry (architecture, 7.3; `scanner.js` `summary()`, `blockGroup()`, boards
 * `scanner-summary`, `-summary-active`): the verdict, the scan's line and the four values a
 * diagnostician checks first; the band of record kinds; every record of the last scan by system and
 * block in two columns; the blocks with nothing to say. A record opens on its own screen.
 */

/** A code in runs: its letters in [ink], its failure type («-87») in grey (`codeRuns()`). */
internal fun codeRuns(code: String, style: Style, ink: Int, typeStyle: Style = style): List<Triple<String, Style, Int>> {
    val i = code.indexOf('-')
    return if (i < 0) listOf(Triple(code, style, ink)) else listOf(Triple(code.substring(0, i), style, ink), Triple(code.substring(i), typeStyle, Ink.MUTE))
}

@Composable
internal fun SummaryPage(
    model: DiagnosticsModel,
    frame: RailFrame,
    actions: DiagnosticsActions,
    onCode: (Int, String) -> Unit,
    onBlocks: () -> Unit,
    onCompare: () -> Unit,
    onReport: () -> Unit,
) {
    val view = model.summary
    RailPage(frame, footer = {
        ScanFooter(
            listOf(
                FooterButton(ScannerWords.scanButton(model.scan), true, onClick = actions::rescan),
                FooterButton("Сравнить с прошлым", false, onClick = onCompare),
                FooterButton("Отчёт", false, onClick = onReport),
            ),
            listOfNotNull(view?.footnote?.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) }),
        )
    }) {
        if (view == null) {
            val (title, line) = ScannerWords.noSummary(model.scan)
            BlockHead(title, "", line)
            return@RailPage
        }
        BlockHead(
            view.verdict, "", view.line,
            counters = view.vitals.map { Counter(it.label, if (it.unit.isEmpty()) it.value else "${it.value} ${it.unit}", out = it.outOfNorm) },
            titleInk = if (view.verdictMark == Mark.ACTIVE) Ink.RED else Ink.WHITE,
        )
        Band(view)
        Records(view, onCode, onBlocks)
    }
}

/** The band under the head: an item a kind present, the last with the blocks; the network's share at the right. */
@Composable
private fun Band(view: SummaryView) {
    val s = ScannerSpec.Summary
    val w = LocalScanWidth.current
    val width = rememberWidths()
    val band = view.band
    if (band != null) {
        var x = 24f
        band.parts.ifEmpty { listOf(BandPart(band.mark, band.text)) }.forEach { item ->
            SignAt(item.mark, x + ScannerSpec.Sign.R, s.BAND_TOP + s.BAND_H / 2f)
            x += 2f * ScannerSpec.Sign.R + s.BAND_GAP
            ScanText(item.text, Type.GROUP_TITLE, Ink.DIM, Modifier.at(x, mid(s.BAND_TOP, s.BAND_H, Type.GROUP_TITLE)))
            x += width(item.text, Type.GROUP_TITLE) + s.BAND_ITEM_GAP
        }
        band.note?.let { ScanText(it, Type.NOTE, Ink.MUTE, Modifier.endAt(w - 24f, mid(s.BAND_TOP, s.BAND_H, Type.NOTE))) }
    }
    Rule(24f, s.BAND_TOP + s.BAND_H, w - 48f)
}

/** How tall a system's records stand in its column: its caption, its blocks' heads and rows. */
private fun systemHeight(sys: SummarySystem): Float {
    val s = ScannerSpec.Summary
    return s.LABEL + sys.blocks.withIndex().sumOf { (i, b) ->
        ((if (i > 0) s.BLOCK_GAP else 0f) + s.HEAD + 1f + b.records.size * (s.ROW + 1f)).toDouble()
    }.toFloat()
}

/** The systems in their two columns, scrolling over the footer; the quiet blocks under the left one. */
@Composable
private fun Records(view: SummaryView, onCode: (Int, String) -> Unit, onBlocks: () -> Unit) {
    val s = ScannerSpec.Summary
    val colX = listOf(24f, 24f + s.COL_W + s.COL_GAP)
    val heights = view.columns.map { col -> col.withIndex().sumOf { (i, sys) -> ((if (i > 0) s.SYSTEM_GAP else 0f) + systemHeight(sys)).toDouble() }.toFloat() }
    val leftBottom = heights.getOrElse(0) { 0f }
    val total = maxOf(heights.maxOrNull() ?: 0f, leftBottom + s.QUIET_GAP + s.QUIET_H)
    ScrollRows(s.TOP) {
        Box(Modifier.fillMaxWidth().height(total.dp)) {
            view.columns.forEachIndexed { k, col ->
                var y = 0f
                col.forEachIndexed { i, sys ->
                    if (i > 0) y += s.SYSTEM_GAP
                    ScanText(sys.title, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(colX.getOrElse(k) { colX.last() }, y))
                    y += s.LABEL
                    sys.blocks.forEachIndexed { j, b ->
                        if (j > 0) y += s.BLOCK_GAP
                        y = BlockGroup(b, colX.getOrElse(k) { colX.last() }, y, s.COL_W, onCode)
                    }
                }
            }
            // the blocks with nothing to say, under the left column
            val qy = leftBottom + s.QUIET_GAP
            val qw = rememberWidths()(view.rest, Type.UNIT)
            Box(Modifier.at(24f, qy).size((qw + s.QUIET_CHEVRON_GAP + ScannerSpec.CHEVRON).dp, s.QUIET_H.dp).tap(onBlocks)) {
                ScanText(view.rest, Type.UNIT, if (view.restAttention) Ink.ORANGE else Ink.MUTE, Modifier.at(0f, mid(0f, s.QUIET_H, Type.UNIT)))
                Chevron(Modifier.at(qw + s.QUIET_CHEVRON_GAP, (s.QUIET_H - ScannerSpec.CHEVRON) / 2f))
            }
        }
    }
}

/**
 * A block's records (`blockGroup()`): its head - its sign, abbreviation, address, name, then how many
 * records and since when - and a row a record, its sign only where it is worse than history; returns
 * the y under the last row.
 */
@Composable
private fun BlockGroup(b: SummaryBlock, x: Float, top: Float, w: Float, onCode: (Int, String) -> Unit): Float {
    val s = ScannerSpec.Summary
    val width = rememberWidths()
    val h = s.HEAD
    SignAt(b.mark, x + ScannerSpec.Sign.R, top + h / 2f)
    var tx = x + 2f * ScannerSpec.Sign.R + s.HEAD_GAP
    ScanText(b.abbreviation, Type.CODE_SMALL, Ink.WHITE, Modifier.at(tx, mid(top, h, Type.CODE_SMALL)))
    tx += width(b.abbreviation, Type.CODE_SMALL) + 8f
    ScanText(b.address, Type.CELL_ABBR, Ink.FAINT, Modifier.at(tx, mid(top, h, Type.CELL_ABBR)))
    tx += width(b.address, Type.CELL_ABBR) + s.HEAD_GAP
    val right = listOfNotNull(b.count.toString(), b.since).joinToString(" · ")
    val rw = width(right, Type.NOTE)
    ScanText(right, Type.NOTE, Ink.MUTE, Modifier.endAt(x + w, mid(top, h, Type.NOTE)))
    ScanText(b.name, Type.TITLE_ASIDE, Ink.INK3, Modifier.at(tx, mid(top, h, Type.TITLE_ASIDE)), maxWidth = x + w - rw - s.HEAD_GAP - tx)
    Rule(x, top + h, w)
    var y = top + h + 1f
    val cols = ScannerSpec.columns(s.ROW_COLS, x + s.ROW_PAD, w - s.ROW_PAD, s.ROW_GAP)
    b.records.forEach { d ->
        val rh = s.ROW
        Box(Modifier.at(x, y).size(w.dp, rh.dp).tap { onCode(b.request, d.code) })
        d.mark?.takeIf { it != Mark.HISTORY }?.let { SignAt(it, x + s.ROW_PAD / 2f, y + rh / 2f) }
        ScanRuns(
            codeRuns(d.code, Type.CODE_SMALL, if (d.mark == Mark.ACTIVE) Ink.RED else Ink.WHITE, Type.CELL_MONO),
            Modifier.at(cols[0].first, mid(y, rh, Type.CODE_SMALL)), base = Type.CODE_SMALL,
        )
        ScanText(d.words, Type.CELL_SMALL, if (d.meaning) Ink.TEXT else Ink.DIM, Modifier.at(cols[1].first, mid(y, rh, Type.CELL_SMALL)), maxWidth = cols[1].second)
        var chipEnd = cols[2].first + cols[2].second
        if (d.network) {
            TagChip("СЕТЬ", chipEnd, y + rh / 2f)
            chipEnd -= width("СЕТЬ", Type.TAG) + 2f * s.TAG_PAD + 6f
        }
        if (d.fresh) TagChip("НОВЫЙ", chipEnd, y + rh / 2f)
        Chevron(Modifier.at(cols[3].first, y + (rh - ScannerSpec.CHEVRON) / 2f))
        Rule(x, y + rh, w, Ink.HAIR)
        y += rh + 1f
    }
    return y
}
