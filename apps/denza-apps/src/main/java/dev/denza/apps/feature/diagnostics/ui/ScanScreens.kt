package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Автоскан» beside the rail: every catalogued ECU by system as a list (`scanner.js` `sysScan()`)
 * and as tiles on the diagnostic bus (`sysTopology()`, `topologyBody()`).
 */

/** The autoscan's head: what answered, the codes, what was read. */
@Composable
private fun ScanHead(model: DiagnosticsModel) {
    val s = model.scan
    BlockHead(
        "Автоскан", ScannerWords.blocks(model.groups.sumOf { it.rows.size }), ScannerWords.scanLine(s),
        counters = listOf(
            Counter("ОТВЕТИЛО", s.answered),
            Counter("DTC АКТ.", s.active?.toString() ?: "—", on = (s.active ?: 0) > 0),
            Counter("DTC ИСТ.", s.history?.toString() ?: "—"),
            Counter("ПРОЧИТАНО", s.memoryRead),
        ),
    )
}

/** «Опросить снова», or what the running scan is doing instead. */
internal fun scanButton(model: DiagnosticsModel, rescan: () -> Unit): FooterButton = when (model.scan.phase) {
    ScanPhase.RUNNING -> FooterButton("Опрос… ${model.scan.done}/${model.scan.total}", true) {}
    ScanPhase.PAUSED -> FooterButton("Продолжить опрос", true, rescan)
    else -> FooterButton("Опросить снова", true, rescan)
}

/** A sentence for why the scan stopped, in the car's orange, as the footer's note. */
private fun noticeNotes(model: DiagnosticsModel): List<FooterNote> = listOfNotNull(model.scan.notice?.let { FooterNote.Words(it) })

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScanListPage(
    model: DiagnosticsModel,
    frame: RailFrame,
    onTab: (Int) -> Unit,
    listState: LazyListState,
    onlyDtc: Boolean,
    onOnlyDtc: (Boolean) -> Unit,
    onOpen: (Target) -> Unit,
    rescan: () -> Unit,
) {
    val groups = remember(model.groups, onlyDtc) { ScannerWords.filter(model.groups, onlyDtc) }
    RailPage(frame, footer = {
        ScanFooter(
            buttons = listOf(scanButton(model, rescan), FooterButton("Только с DTC", onlyDtc) { onOnlyDtc(!onlyDtc) }),
            notes = noticeNotes(model),
            noteInk = Ink.ORANGE_INK,
        )
    }) {
        ScanHead(model)
        ScanTabs(listOf("Список", "Топология"), 0, onTab)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val s = ScannerSpec.Scan
            val w = maxWidth.value - 48f
            val cols = ScannerSpec.columns(ScannerSpec.Rail.SCAN_COLS, 0f, w)
            ScanHeadRow(ScannerSpec.columns(ScannerSpec.Rail.SCAN_COLS, 24f, w), "СИСТЕМА", "БЛОК")
            val listH = maxHeight.value - (ScannerSpec.H - s.LIST_BOTTOM) - s.LIST_TOP
            LazyColumn(Modifier.at(24f, s.LIST_TOP).size(w.dp, listH.dp), state = listState) {
                groups.forEach { g ->
                    stickyHeader(key = "group:${g.id}") { GroupHeader(g) }
                    items(g.rows, key = { "row:${g.id}:${it.request}:${it.abbreviation}" }) { row ->
                        ScanRow(row, cols) { row.target?.let(onOpen) }
                    }
                }
            }
        }
    }
}

/** The list's column captions: the first two are the autoscan's «СИСТЕМА / БЛОК», a system's «БЛОК» and none. */
@Composable
internal fun ScanHeadRow(cols: List<Pair<Float, Float>>, first: String, second: String) {
    val s = ScannerSpec.Scan
    HeadRow(
        cols,
        listOf(
            HeadLabel(first), HeadLabel(second), HeadLabel("ЗАПРОС → ОТВЕТ"), HeadLabel("ПРОТОКОЛ"),
            HeadLabel("DTC АКТ.", end = true, pad = s.NUM_PAD), HeadLabel("DTC ИСТ.", end = true, pad = s.NUM_PAD), HeadLabel("СТАТУС"),
        ),
        s.TABLE_TOP, s.TABLE_HEAD,
    )
}

@Composable
private fun GroupHeader(g: ModuleGroupView) {
    val s = ScannerSpec.Scan
    Box(Modifier.fillMaxWidth().height(s.GROUP.dp).plate(Ink.GROUND)) {
        Row(Modifier.padding(start = s.PAD.dp, top = ((s.GROUP - 1f - Type.GROUP_TITLE.lh) / 2f).dp)) {
            ScanText(g.title, Type.GROUP_TITLE, Ink.DIM)
            Spacer(Modifier.width(s.GROUP_GAP.dp))
            ScanText(g.summary, Type.GROUP_SUMMARY, Ink.FAINT2)
        }
        Rule(0f, s.GROUP - 1f, 10000f)
    }
}

/** One ECU in the list: name, unit, route, protocol, both counts, the chip and a chevron where it leads. */
@Composable
internal fun ScanRow(row: ModuleRowView, cols: List<Pair<Float, Float>>, onClick: (() -> Unit)?) {
    val s = ScannerSpec.Scan
    val h = s.ROW
    val nameInk = when (row.status) {
        ModuleStatus.NO_ROUTE, ModuleStatus.NO_REPLY -> Ink.FAINT
        ModuleStatus.UNREAD, ModuleStatus.POLLING -> Ink.INK3
        else -> Ink.WHITE
    }
    val chip = ScannerWords.chip(row.status)
    Box(Modifier.fillMaxWidth().height((h + s.RULE).dp).tap(if (row.target != null) onClick else null)) {
        ScanText(row.name, Type.CELL, nameInk, Modifier.at(cols[0].first + s.PAD, mid(0f, h, Type.CELL)), maxWidth = cols[0].second - s.PAD)
        ScanText(row.unit, Type.CELL_SMALL, Ink.DIM, Modifier.at(cols[1].first, mid(0f, h, Type.CELL_SMALL)), maxWidth = cols[1].second)
        ScanText(row.route, Type.CELL_MONO, Ink.DIM, Modifier.at(cols[2].first, mid(0f, h, Type.CELL_MONO)))
        ScanText(row.protocol, Type.CELL_SMALL, Ink.DIM, Modifier.at(cols[3].first, mid(0f, h, Type.CELL_SMALL)))
        ScanText(row.active, Type.CELL_NUM, nameInk, Modifier.endAt(cols[4].first + cols[4].second - s.NUM_PAD, mid(0f, h, Type.CELL_NUM)))
        ScanText(row.history, Type.CELL_NUM, nameInk, Modifier.endAt(cols[5].first + cols[5].second - s.NUM_PAD, mid(0f, h, Type.CELL_NUM)))
        Badge(chip.text, ScannerWords.tone(chip.look), modifier = Modifier.at(cols[6].first, (h - ScannerSpec.Badge.H) / 2f))
        if (row.target != null) Chevron(Modifier.at(cols[7].first + (cols[7].second - s.CHEVRON) / 2f, (h - s.CHEVRON) / 2f))
        Rule(0f, h, 10000f, Ink.HAIR)
    }
}

/** A system's «Блоки»: its blocks as the autoscan's rows under the list's captions (`sysBlocks()`). */
@Composable
internal fun BlockRows(rows: List<ModuleRowView>, onOpen: (Target) -> Unit) {
    val s = ScannerSpec.Scan
    val width = LocalScanWidth.current - 48f
    ScanHeadRow(ScannerSpec.columns(ScannerSpec.Rail.SCAN_COLS, 24f, width), "БЛОК", "")
    val cols = ScannerSpec.columns(ScannerSpec.Rail.SCAN_COLS, 0f, width)
    ScrollRows(s.LIST_TOP) {
        Column(Modifier.padding(start = 24.dp).width(width.dp)) {
            rows.forEach { r -> ScanRow(r, cols) { r.target?.let(onOpen) } }
        }
    }
}

/** A tile's look by its status (`TILE` in the board). */
internal data class TileLook(val fill: Int?, val line: Int, val dash: Boolean, val ink: Int, val sub: Int, val state: Int, val addr: Int)

internal fun tileLook(status: ModuleStatus): TileLook = when (ScannerWords.chip(status).look) {
    ScannerWords.Look.OK -> TileLook(Ink.TILE_OK, Ink.BLUE_EDGE, false, Ink.WHITE, Ink.DIM, Ink.BLUE_SOFT, Ink.FAINT)
    ScannerWords.Look.HIST -> TileLook(Ink.TILE_HIST, Ink.SEP, false, Ink.WHITE, Ink.DIM, Ink.INK4, Ink.FAINT)
    ScannerWords.Look.ON -> TileLook(Ink.TILE_HIST, Ink.ORANGE, false, Ink.WHITE, Ink.DIM, Ink.ORANGE_INK, Ink.FAINT)
    ScannerWords.Look.UNREAD -> TileLook(Ink.TILE_UNREAD, Ink.LINE3, false, Ink.INK2, Ink.DIM, Ink.DIM2, Ink.FAINT)
    ScannerWords.Look.NONE -> TileLook(null, Ink.LINE4, true, Ink.FAINT, Ink.FAINT2, Ink.FAINT2, Ink.FAINT2)
}

/** A tile's size: the topology's beside the rail, or the full one the schemes hang from the car. */
internal data class TileSize(val w: Float, val padX: Float) {
    companion object {
        val SCHEME = TileSize(ScannerSpec.Topology.TILE_W, ScannerSpec.Topology.TILE_PAD_X)
        val RAIL = TileSize(ScannerSpec.Rail.TOPO_TILE_W, ScannerSpec.Rail.TOPO_TILE_PAD_X)
    }
}

@Composable
internal fun TopologyPage(model: DiagnosticsModel, frame: RailFrame, onTab: (Int) -> Unit, onOpen: (Target) -> Unit, rescan: () -> Unit) {
    val p = ScannerSpec.Topology
    val column = ScannerSpec.Rail.TOPO_COLUMN
    val inset = ScannerSpec.Rail.TOPO_TILE_INSET
    val byId = model.groups.associateBy { it.id }
    val spans = p.COLUMNS.flatten().groupingBy { it.id }.eachCount()
    data class Placed(val x: Float, val y: Float, val row: ModuleRowView)
    val labels = mutableListOf<Triple<Float, Float, String>>()
    val tiles = mutableListOf<Placed>()
    // the bus: [x0, y0, x1, y1] segments in dp. The trunk runs from the head unit to the last column
    // that opens a group; a column that continues a group hangs from the one before it.
    val opens = p.COLUMNS.map { !it.first().continued }
    val lastOpen = opens.lastIndexOf(true)
    val bus = mutableListOf(floatArrayOf(p.HEAD_X + p.HEAD_W, p.TRUNK_Y, p.LEFT + lastOpen * column + p.BUS_INSET, p.TRUNK_Y))
    val live = mutableListOf<FloatArray>()
    val dead = mutableListOf<FloatArray>()
    p.COLUMNS.forEachIndexed { k, parts ->
        val x = p.LEFT + k * column
        val bx = x + p.BUS_INSET
        var y = p.FIRST_LABEL
        var last = 0f
        parts.forEachIndexed { gi, part ->
            val g = byId[part.id] ?: return@forEachIndexed
            val rows = g.rows.subList(part.from.coerceAtMost(g.rows.size), (part.to ?: g.rows.size).coerceAtMost(g.rows.size))
            if (gi > 0) y += p.GROUP_GAP
            if (!part.continued) labels += Triple(x + inset, y, ScannerWords.topologyLabel(g, spans[part.id] ?: 1))
            y += p.LABEL_H
            rows.forEach { r ->
                tiles += Placed(x + inset, y, r)
                val m = y + p.TILE_H / 2f
                val stub = floatArrayOf(bx, m, x + inset, m)
                if (r.status == ModuleStatus.NO_ROUTE) dead += stub else live += stub
                last = m
                y += p.TILE_PITCH
            }
        }
        if (last > 0f) {
            if (opens[k]) {
                val from = if (k > 0) p.TRUNK_Y else p.TRUNK_FIRST_Y
                bus += floatArrayOf(bx, from, bx, last)
            } else {
                bus += floatArrayOf(bx - column, p.TAIL_Y, bx, p.TAIL_Y)
                bus += floatArrayOf(bx, p.TAIL_Y, bx, last)
            }
        }
    }
    RailPage(frame, footer = {
        ScanFooter(
            buttons = listOf(scanButton(model, rescan)),
            legend = ScannerWords.present(
                listOf(Swatch.OK to "OK", Swatch.HIST to "DTC в истории", Swatch.UNREAD to "ответил, ошибки не читали", Swatch.NONE to "адрес не найден"),
                tiles.map { it.row.status },
            ),
            legendGap = 16f,
            notes = noticeNotes(model),
            noteInk = Ink.ORANGE_INK,
        )
    }) {
        ScanHead(model)
        ScanTabs(listOf("Список", "Топология"), 1, onTab)
        Canvas(Modifier.fillMaxSize()) {
            val sw = 1.5.dp.toPx()
            fun path(segs: List<FloatArray>) = Path().apply {
                segs.forEach { (x0, y0, x1, y1) -> moveTo(x0.dp.toPx(), y0.dp.toPx()); lineTo(x1.dp.toPx(), y1.dp.toPx()) }
            }
            drawPath(path(bus), ink(Ink.BUS), style = Stroke(sw))
            drawPath(path(live), ink(Ink.BUS_LIVE), style = Stroke(sw))
            val d = 3.dp.toPx()
            drawPath(path(dead), ink(Ink.LINE4), style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(d, d))))
        }
        Box(Modifier.at(p.HEAD_X, p.HEAD_Y).size(p.HEAD_W.dp, p.HEAD_H.dp).plate(Ink.PID_FILL, Ink.LINE4, p.HEAD_R)) {
            ScanText("ГУ · диагностика", Type.HEAD_UNIT, Ink.WHITE, Modifier.at(p.HEAD_PAD, mid(0f, p.HEAD_H, Type.HEAD_UNIT)))
            ScanText("+8", Type.HEAD_UNIT_HOPS, Ink.FAINT, Modifier.endAt(p.HEAD_W - p.HEAD_PAD, mid(0f, p.HEAD_H, Type.HEAD_UNIT_HOPS)))
        }
        labels.forEach { (x, y, t) -> ScanText(t, Type.TOPO_LABEL, Ink.DIM2, Modifier.at(x, y)) }
        tiles.forEach { (x, y, r) -> Tile(r, x, y, TileSize.RAIL, onOpen) }
    }
}

/**
 * A block as the topology draws it (`unitTile()`): its abbreviation and address over its short
 * name; its state is its look, and a word stands in place of the address only where the look
 * cannot say it (a count, a failure).
 */
@Composable
internal fun Tile(r: ModuleRowView, x: Float, y: Float, size: TileSize, onOpen: (Target) -> Unit) {
    val p = ScannerSpec.Topology
    val look = tileLook(r.status)
    val width = rememberWidths()
    Box(
        Modifier.at(x, y).size(size.w.dp, p.TILE_H.dp).plate(look.fill, look.line, p.TILE_R, look.dash)
            .tap(r.target?.let { { onOpen(it) } }),
    ) {
        val ix = 1f + size.padX
        val iw = size.w - 2f - 2f * size.padX
        val top = (p.TILE_H - Type.TILE_ABBR.lh - p.TILE_ROW_GAP - Type.TILE_NAME.lh) / 2f
        val state = ScannerWords.tileState(r)
        val (right, style, ink) = if (state.isNotEmpty()) Triple(state, Type.TILE_STATE, look.state)
            else Triple(r.request?.let(ScannerWords::hex) ?: "нет адреса", Type.TILE_ADDR, look.addr)
        val rw = width(right, style)
        ScanText(right, style, ink, Modifier.endAt(ix + iw, top + (Type.TILE_ABBR.lh - style.lh) / 2f))
        ScanText(r.abbreviation, Type.TILE_ABBR, look.ink, Modifier.at(ix, top), maxWidth = iw - rw - 6f)
        ScanText(r.short, Type.TILE_NAME, look.sub, Modifier.at(ix, top + Type.TILE_ABBR.lh + p.TILE_ROW_GAP), maxWidth = iw)
    }
}
