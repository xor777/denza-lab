package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.BlockRow
import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.BlocksGroup
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Блоки» beside the rail (architecture, 7.3; `scanner.js` `modules()`, `blocksTable()`, board
 * `scanner-blocks`): every catalogued block on one table - those with records first, then the rest
 * in the catalog's order - with the route, the protocol, the records, F194 and the block's median
 * answer; the filters as tabs. A system's «Блоки» is the same table of its own blocks (`sysBlocks()`).
 */

@Composable
internal fun BlocksPage(
    model: DiagnosticsModel,
    filter: BlocksFilter,
    frame: RailFrame,
    onFilter: (BlocksFilter) -> Unit,
    onOpen: (Target) -> Unit,
    actions: DiagnosticsActions,
    onReport: () -> Unit,
) {
    val view = model.blocks
    // before the first scan: the catalog's blocks as the section knows them
    val groups = view?.groups ?: ScannerWords.groups(model.groups.flatMap { it.rows }.map { ScannerWords.blockRow(model, it) })
    RailPage(frame, footer = {
        ScanFooter(
            listOf(FooterButton(ScannerWords.scanButton(model.scan), true, onClick = actions::rescan), FooterButton("Отчёт", false, onClick = onReport)),
            listOfNotNull(view?.footnote?.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) }),
        )
    }) {
        BlockHead("Блоки", view?.aside.orEmpty(), view?.line.orEmpty())
        val filters = view?.filters.orEmpty()
        if (filters.isNotEmpty()) {
            ScanTabs(filters.map { it.second }, filters.indexOfFirst { it.first == filter }.coerceAtLeast(0), { onFilter(filters[it].first) })
        }
        BlocksTable(ScannerWords.filtered(groups, filter), onOpen)
    }
}

/** The table of «Блоки» (`blocksTable()`): its captions, then each group's caption over its rows. */
@Composable
internal fun BlocksTable(groups: List<BlocksGroup>, onOpen: (Target) -> Unit) {
    val m = ScannerSpec.Modules
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(m.COLS, 24f, w)
    HeadRow(
        cols,
        listOf(
            null, HeadLabel("БЛОК", pad = 0f), HeadLabel("НАЗНАЧЕНИЕ"), HeadLabel("ЗАПРОС → ОТВЕТ"), HeadLabel("ПРОТОКОЛ"),
            HeadLabel("ЗАПИСИ", end = true, pad = m.NUM_PAD), HeadLabel("НОМЕР ПО · F194"), HeadLabel("ОТВЕТ", end = true, pad = m.NUM_PAD), null,
        ),
        m.TOP, m.HEAD,
    )
    val rowCols = ScannerSpec.columns(m.COLS, 0f, w)
    val h = groups.sumOf { (m.GROUP + it.rows.size * (m.ROW + m.RULE)).toDouble() }.toFloat()
    ScrollRows(m.LIST_TOP) {
        Column(Modifier.padding(start = 24.dp).width(w.dp).height(h.dp)) {
            groups.forEach { g ->
                Box(Modifier.fillMaxWidth().height(m.GROUP.dp)) {
                    ScanText(g.title, Type.GROUP_TITLE, Ink.DIM, Modifier.at(m.GROUP_PAD, mid(0f, m.GROUP - 1f, Type.GROUP_TITLE)))
                    Rule(0f, m.GROUP - 1f, w)
                }
                g.rows.forEach { BlockLine(it, rowCols, w, onOpen) }
            }
        }
    }
}

/** One block: its sign, abbreviation and name, the route and protocol, its records, F194 and its median answer. */
@Composable
private fun BlockLine(b: BlockRow, cols: List<Pair<Float, Float>>, w: Float, onOpen: (Target) -> Unit) {
    val m = ScannerSpec.Modules
    val h = m.ROW
    val silent = b.mark == Mark.NO_REPLY
    val target = b.target ?: b.request?.let { Target.Module(it) }
    Box(Modifier.fillMaxWidth().height((h + m.RULE).dp).tap(target?.let { { onOpen(it) } })) {
        b.mark?.let { SignAt(it, cols[0].first + cols[0].second / 2f, h / 2f) }
        ScanText(b.abbreviation, Type.CODE_SMALL, if (silent) Ink.DIM else Ink.WHITE, Modifier.at(cols[1].first, mid(0f, h, Type.CODE_SMALL)), maxWidth = cols[1].second - 8f)
        ScanText(b.name, Type.CELL, if (silent) Ink.DIM else Ink.TEXT, Modifier.at(cols[2].first, mid(0f, h, Type.CELL)), maxWidth = cols[2].second - 12f)
        ScanText(b.route, Type.CELL_MONO, Ink.DIM, Modifier.at(cols[3].first, mid(0f, h, Type.CELL_MONO)))
        ScanText(b.protocol, Type.CELL_SMALL, Ink.DIM, Modifier.at(cols[4].first, mid(0f, h, Type.CELL_SMALL)))
        val none = b.records == "—"
        ScanText(b.records, Type.CELL_NUM, if (none) Ink.FAINT3 else Ink.TEXT, Modifier.endAt(cols[5].first + cols[5].second - m.NUM_PAD, mid(0f, h, Type.CELL_NUM)))
        ScanText(b.software, Type.SIG, if (b.software == "—") Ink.FAINT3 else if (b.softwareChanged) Ink.ORANGE else Ink.DIM, Modifier.at(cols[6].first, mid(0f, h, Type.SIG)))
        ScanText(b.latency, Type.NOTE_MONO, if (b.latency == "—") Ink.FAINT3 else Ink.MUTE, Modifier.endAt(cols[7].first + cols[7].second - m.NUM_PAD, mid(0f, h, Type.NOTE_MONO)))
        if (target != null) Chevron(Modifier.at(cols[8].first + (cols[8].second - ScannerSpec.CHEVRON) / 2f, (h - ScannerSpec.CHEVRON) / 2f))
        Box(Modifier.at(0f, h).size(w.dp, 1.dp).plate(Ink.HAIR))
    }
}
