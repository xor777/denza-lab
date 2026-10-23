package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.GraphScope
import dev.denza.apps.feature.diagnostics.scan.GraphsView
import dev.denza.apps.feature.diagnostics.scan.LiveRow
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.RailLine
import dev.denza.apps.feature.diagnostics.scan.RailPlace
import dev.denza.apps.feature.diagnostics.scan.Scheme
import dev.denza.apps.feature.diagnostics.scan.SystemDtcRow
import dev.denza.apps.feature.diagnostics.scan.SystemPage
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.scan.extraPage
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * The section around the car's systems (architecture, 7.2, 7.3): the rail down the left
 * (`scanner.js` `rail()`), the chosen screen at the width it leaves (`shell()`), and a system's page
 * - its head, its tabs and what each tab shows (`sysDtc()`, `sysBlocks()`, `sysLive()`,
 * `sysStream()`, `sysGraphs()`, `sysDrivesCompare()`, `sysAdasCompare()`, `sysEngineDtc()`, the
 * schemes of `SchemeScreens.kt`, the pack's «Здоровье» of `HealthScreen.kt`, its cells of
 * `CellsScreen.kt` and its GB/T 32960 flags of `GbFlagsScreen.kt`).
 */

/** What the rail needs from the section: its lines, the one lit, the header over it and where a tap goes. */
internal class RailFrame(
    val lines: List<RailLine>,
    val active: RailPlace,
    val header: @Composable () -> Unit,
    val onPick: (RailPlace) -> Unit,
)

/**
 * A page beside the rail: the header across the window, the rail from the header to the footer,
 * the [content] in the area right of it - placed from that area's top-left in the board's units,
 * as wide as [LocalScanWidth] says - and the footer across the window.
 */
@Composable
internal fun RailPage(frame: RailFrame, footer: @Composable () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val w = LocalScanWidth.current - ScannerSpec.Rail.W
    ScannerPage(footer = footer) {
        frame.header()
        Rail(frame)
        Box(Modifier.at(ScannerSpec.Rail.W, 0f).width(w.dp).fillMaxHeight()) {
            CompositionLocalProvider(LocalScanWidth provides w) { content() }
        }
    }
}

/**
 * The rail: «Сводка» and «Блоки», a rule, the systems, a rule, «Журнал»; the chosen one lit. A mark
 * says only what the line's look cannot: its records - the count after the sign of the most severe
 * kind - or, on «Блоки», how many there are.
 */
@Composable
private fun Rail(frame: RailFrame) {
    val s = ScannerSpec.Rail
    val top = ScannerSpec.Header.HEIGHT
    val width = rememberWidths()
    Box(Modifier.at(0f, top).width(s.W.dp).fillMaxHeight().padding(bottom = (top + ScannerSpec.Footer.HEIGHT).dp).plate(Ink.BAR)) {
        Box(Modifier.at(s.W - 1f, 0f).width(1.dp).fillMaxHeight().plate(Ink.RULE))
        var y = s.PAD_TOP
        frame.lines.forEachIndexed { i, line ->
            // the systems stand apart from «Сводка» and «Блоки», «Журнал» apart from them
            val prev = frame.lines.getOrNull(i - 1)?.place
            val sep = (line.place is RailPlace.System && prev != null && prev !is RailPlace.System) ||
                (line.place == RailPlace.Journal && prev != null)
            if (sep) {
                y += s.SEP_GAP
                Rule(s.PAD_X, y, s.W - 2f * s.PAD_X)
                y += s.SEP_GAP + 1f
            }
            val on = line.place == frame.active
            Box(Modifier.at(0f, y).size((s.W - 1f).dp, s.ITEM.dp).plate(if (on) Ink.QUIET else null).tap { frame.onPick(line.place) }) {
                if (on) Box(Modifier.size(s.BAR.dp, s.ITEM.dp).plate(Ink.BLUE))
                var r = s.W - s.PAD_X
                val mark = line.mark
                val count = mark?.count?.toString() ?: line.plain
                if (count != null) {
                    val ink = when {
                        mark?.mark == Mark.ACTIVE -> Ink.RED_INK
                        mark != null -> Ink.INK3
                        line.plainLit -> Ink.ORANGE
                        else -> Ink.FAINT
                    }
                    ScanText(count, Type.RAIL_MARK, ink, Modifier.endAt(r, mid(0f, s.ITEM, Type.RAIL_MARK)))
                    r -= width(count, Type.RAIL_MARK)
                    if (mark != null) {
                        r -= s.MARK_GAP + ScannerSpec.Sign.R
                        SignAt(mark.mark, r, s.ITEM / 2f)
                        r -= ScannerSpec.Sign.R
                    }
                    r -= 8f
                }
                ScanText(line.name, Type.RAIL_ITEM, if (on) Ink.WHITE else Ink.INK3, Modifier.at(s.PAD_X, mid(0f, s.ITEM, Type.RAIL_ITEM)), maxWidth = r - s.PAD_X)
            }
            y += s.ITEM
        }
    }
}

/** Rows that scroll between [top] and the footer: a table's body under its fixed captions. */
@Composable
internal fun ScrollRows(top: Float, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(top = top.dp, bottom = ScannerSpec.Footer.HEIGHT.dp)
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/** The live data's two views at the tabs' right end. */
private val VIEWS = listOf("Таблица", "Графики")

/** The graphs a live tab opens with: its focus first, then the rows in order - up to four that can be read alone. */
internal fun graphKeys(rows: List<LiveRow>, focus: List<Int> = emptyList()): List<ParamKey> {
    val keyed = rows.filter { it.key != null }
    val first = keyed.filter { it.pid in focus }
    return (first + keyed.filter { it !in first }).mapNotNull { it.key }.take(4)
}

/**
 * A system's page: its head as a block's (title, aside, mono line, counters), its tabs, and the
 * chosen tab. The engine's stream runs while its scheme or its live data is in front, and ECM's
 * identification and readiness are read while «DTC и готовность» is; the pack's cells are listened
 * to while «Здоровье» or «Ячейки» is (7.3).
 */
@Composable
internal fun SystemPageScreen(
    model: DiagnosticsModel,
    page: SystemPage,
    tab: SystemTab,
    graphs: Boolean,
    frame: RailFrame,
    onTab: (SystemTab, Boolean) -> Unit,
    onOpen: (Target) -> Unit,
    actions: DiagnosticsActions,
    sample: ScannerSample?,
    /** A row of «Потоковые данные» or a lane of «Графики» with a key opens its parameter alone (architecture, 6.2). */
    onParam: (ParamKey) -> Unit = {},
    /** A record of «DTC» opens on its own screen. */
    onCode: (Int, String) -> Unit = { _, _ -> },
    onReport: () -> Unit = {},
) {
    val requests = page.blocks.mapNotNull { it.request }
    val system = page.system
    val ecm = if (system == CarSystem.ENGINE) requests.firstOrNull()?.let { moduleOf(model, it) } else null
    val obd = ecm?.obd
    val current = rememberUpdatedState(actions)
    val run = LocalScannerRun.current
    val streaming = ecm != null && (tab == SystemTab.LIVE || (tab == SystemTab.STATE && page.scheme is Scheme.Engine))
    DisposableEffect(ecm?.request, streaming, run) {
        val r = ecm?.request
        if (streaming && r != null) current.value.setLiveVisible(r, true)
        onDispose { if (streaming && r != null) current.value.setLiveVisible(r, false) }
    }
    val readiness = ecm != null && tab == SystemTab.DTC_READINESS
    DisposableEffect(ecm?.request, readiness, run) {
        val r = ecm?.request
        if (readiness && r != null) current.value.setModuleOpen(r, true)
        onDispose { if (readiness && r != null) current.value.setModuleOpen(r, false) }
    }
    // the BMS's pages are listened to only while «Здоровье» or «Ячейки» is in front (6.3, 7.3)
    val health = system == CarSystem.PACK && tab == SystemTab.STATE
    val cells = tab == SystemTab.CELLS || health
    DisposableEffect(cells, run) {
        if (cells) current.value.setCellsVisible(true)
        onDispose { if (cells) current.value.setCellsVisible(false) }
    }
    // a system's readings go round after round with its page's GETs and keep their minute while its
    // «Потоковые данные» is in front; «Пауза» takes the page away (6.3, 7.2)
    val stream = model.systems.live[system]?.takeIf { tab == SystemTab.LIVE }
    val streamed = stream != null
    DisposableEffect(system, streamed, run) {
        if (streamed) current.value.setSystemLiveVisible(system, true)
        onDispose { if (streamed) current.value.setSystemLiveVisible(system, false) }
    }
    // a page's own GETs join the batch only while it is in front: a system's scheme, the pack's
    // GB/T 32960 flags (architecture, 6); a stream's page is its own
    val extras = extraPage(system, tab).takeIf { !streamed && !health }
    DisposableEffect(extras, run) {
        extras?.let { current.value.setExtrasVisible(it, true) }
        onDispose { extras?.let { current.value.setExtrasVisible(it, false) } }
    }
    // «Графики»: up to four of the live rows on one axis, read first while they show (7.3)
    val live = tab == SystemTab.LIVE && (stream != null || obd != null)
    val showGraphs = live && graphs
    val liveRows = stream?.rows ?: obd?.live?.rows.orEmpty()
    val scope: GraphScope = if (ecm != null) GraphScope.Block(ecm.request) else GraphScope.System(system)
    var keys by remember(system) { mutableStateOf<List<ParamKey>?>(null) }
    val chosen = keys ?: graphKeys(liveRows, obd?.live?.focus.orEmpty())
    DisposableEffect(scope, showGraphs, chosen, run) {
        if (showGraphs) current.value.setGraphs(scope, chosen)
        onDispose { if (showGraphs) current.value.setGraphs(scope, emptyList()) }
    }
    val graphsView: GraphsView? = if (showGraphs) (stream?.graphs ?: obd?.live?.graphs) else null
    var picking by remember { mutableStateOf(false) }
    var pickingSignals by remember { mutableStateOf(false) }
    val read = { requests.forEach(actions::readModule) }
    val codes = FooterButton("Прочитать DTC", true) { read() }
    val codesNotes = listOfNotNull(page.dtcFootnote.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) })
    val refresh = listOf(FooterButton("Обновить", true) { read() })
    val snapshot = listOfNotNull(ScannerWords.snapshot(page.dtcFootnote)?.let { FooterNote.Words(it, mono = true) })
    val running = obd?.live?.running ?: (stream?.paused == false)
    val pause = FooterButton(if (running) "Пауза" else "Продолжить", true) {
        if (ecm != null) actions.setLiveVisible(ecm.request, !running) else actions.setSystemLivePaused(system, running)
    }

    RailPage(frame, footer = {
        when {
            // the graphs: the pause, a mark, the recording of a block's stream, the signals
            showGraphs -> ScanFooter(
                listOfNotNull(
                    pause,
                    FooterButton("Метка", false) { actions.markGraphs(scope) },
                    obd?.let { o -> FooterButton(if (o.live.recording) "Стоп записи" else "Запись", false, record = true) { actions.setRecording(ecm.request, !o.live.recording) } },
                    FooterButton(ScannerWords.signals(chosen.size), false) { pickingSignals = true },
                ),
                listOfNotNull(graphsView?.footnote?.let { FooterNote.Words(it, mono = true) }),
            )
            // the engine's state and its stream: the stream's own buttons
            streaming && obd != null -> ScanFooter(
                buttons = listOfNotNull(
                    pause,
                    FooterButton("Выбор PID", false) { picking = true },
                    if (tab == SystemTab.LIVE) FooterButton("Сбросить мин/макс", false) { actions.resetMinMax(ecm.request) } else null,
                ),
                notes = emptyList(),
            )
            // a system's stream: the pause, the extremes, and how long the last round took
            stream != null -> ScanFooter(
                listOf(pause, FooterButton("Сбросить мин/макс", false) { actions.resetSystemLive(system) }),
                listOfNotNull(stream.round?.let { FooterNote.Words(it, mono = true) }),
            )
            // «Здоровье»: the read again, the battery's report; the sweep's age and where the cells come from
            health -> ScanFooter(
                listOf(FooterButton("Обновить", true, onClick = actions::refreshCells), FooterButton("Отчёт о батарее", false, onClick = onReport)),
                listOfNotNull(
                    (model.health?.age ?: model.packCells?.age)?.let { FooterNote.Words(it, mono = true) }, FooterNote.Divider,
                    FooterNote.Words(ScannerWords.CELLS_SOURCE, mono = true),
                ),
            )
            // the cells: the pause, the last sweep's length and the newest page's age, and where they come from
            cells -> ScanFooter(
                listOf(
                    model.packCells?.paused.let { paused ->
                        FooterButton(if (paused == true) "Продолжить" else "Пауза", true) { actions.setCellsPaused(paused != true) }
                    },
                ),
                listOfNotNull(
                    model.packCells?.age?.let { FooterNote.Words(it, mono = true) }, model.packCells?.let { FooterNote.Divider },
                    FooterNote.Words(ScannerWords.CELLS_SOURCE, mono = true),
                ),
            )
            tab == SystemTab.DTC_READINESS -> ScanFooter(listOf(codes), listOfNotNull(ecm?.dtc?.source?.let { FooterNote.Words(it) }))
            // the flags: readings, and where they come from before when
            tab == SystemTab.GB_FLAGS -> ScanFooter(
                refresh,
                listOf(FooterNote.Words(ScannerWords.GB_FLAGS_SOURCE, mono = true)) + snapshot.flatMap { listOf(FooterNote.Divider, it) },
            )
            // what is read from the blocks' memories, and a scheme that is only the blocks
            tab == SystemTab.DTC || tab == SystemTab.BLOCKS || (tab == SystemTab.COMPARE && system == CarSystem.ADAS) ||
                (tab == SystemTab.STATE && page.scheme is Scheme.Adas) -> ScanFooter(listOf(codes), codesNotes)
            // readings: when they were taken
            else -> ScanFooter(refresh, snapshot)
        }
    }) {
        BlockHead(system.title, page.aside, graphsView?.line ?: page.line, counters = page.counters.map { it.counter() })
        ScanTabs(
            page.tabs.map { ScannerWords.tabLabel(system, it) }, page.tabs.indexOf(tab).coerceAtLeast(0), { onTab(page.tabs[it], graphs) },
            right = if (live) Segments(VIEWS, if (graphs) 1 else 0) { onTab(tab, it == 1) } else null,
        )
        when (tab) {
            SystemTab.STATE -> if (health) HealthContent(model) else page.scheme?.let { SchemeContent(model, it, onOpen) }
            SystemTab.LIVE -> when {
                showGraphs -> GraphLanes(graphsView, sample?.graphsCursor, onParam)
                stream != null -> LiveTable(stream.rows, sample?.chartRange, ScannerSpec.Rail.PACK_LIVE_COLS, onParam, first = "УСТР.", scroll = true)
                obd != null -> LiveTable(obd.live.rows, sample?.chartRange, ScannerSpec.Rail.LIVE_COLS, onParam, scroll = true)
            }
            SystemTab.COMPARE -> if (system == CarSystem.ADAS) AdasTable(model, model.systems.adas) else DrivesTable(model.systems.drives)
            SystemTab.GB_FLAGS -> GbFlagTiles(model.systems.gbFlags)
            SystemTab.CELLS -> model.packCells?.let { PackCells(it) }
            SystemTab.DTC -> SystemDtcTable(page.dtc) { onCode(it.request, it.code) }
            SystemTab.DTC_READINESS -> if (ecm != null) DtcContent(ecm, Halves.RAIL)
            SystemTab.BLOCKS -> BlocksTable(ScannerWords.systemBlocks(model, page), onOpen)
        }
        if (picking && obd != null && ecm != null) {
            PidPicker(obd.live.rows, obd.live.focus, { actions.setFocus(ecm.request, it); picking = false }) { picking = false }
        }
        if (pickingSignals) {
            SignalPicker(liveRows, chosen, { keys = it; pickingSignals = false }) { pickingSignals = false }
        }
    }
}

/**
 * A system's codes, every block's on one table (`sysDtc()`): the block named with its address on
 * its first row, a rule between blocks, a row opening the record on its own screen; a record
 * failing now red.
 */
@Composable
private fun SystemDtcTable(rows: List<SystemDtcRow>, onCode: (SystemDtcRow) -> Unit) {
    val e = ScannerSpec.EcuLive
    val r = ScannerSpec.Rail
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(r.DTC_COLS, 24f, w)
    HeadRow(cols, listOf(HeadLabel("БЛОК"), HeadLabel("КОД"), HeadLabel("ТИП ОТКАЗА (FTB)"), HeadLabel("ОПИСАНИЕ"), HeadLabel("СТАТУС")), e.TOP, e.HEAD)
    val width = rememberWidths()
    ScrollRows(e.TOP + e.HEAD + 1f) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, d ->
                val h = r.DTC_ROW
                val first = i == 0 || rows[i - 1].request != d.request
                Box(Modifier.fillMaxWidth().height((h + 1f).dp).tap { onCode(d) }) {
                    if (d.active) Box(Modifier.at(24f, 0f).size(w.dp, h.dp).plate(Ink.RED_ROW))
                    if (first && i > 0) Rule(24f, -1f, w)
                    if (first) {
                        ScanText(d.block, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(0f, h, Type.CELL)))
                        ScanText(d.address, Type.CELL_MONO, Ink.FAINT, Modifier.at(cols[0].first + 12f + width(d.block, Type.CELL) + 8f, mid(0f, h, Type.CELL_MONO)))
                    }
                    ScanText(d.code, Type.CODE, if (d.active) Ink.RED else Ink.WHITE, Modifier.at(cols[1].first, mid(0f, h, Type.CODE)))
                    ScanRuns(
                        listOf(Triple(d.failureType, Type.CELL_NUM, Ink.DIM), Triple(" · ${d.failureName}", Type.CELL, Ink.TEXT)),
                        Modifier.at(cols[2].first, mid(0f, h, Type.CELL)), maxWidth = cols[2].second - 12f, base = Type.CELL,
                    )
                    ScanText(d.description, Type.CELL, Ink.TEXT, Modifier.at(cols[3].first, mid(0f, h, Type.CELL)), maxWidth = cols[3].second - 16f)
                    ScanRuns(
                        listOf(Triple(d.status, Type.CELL_MONO, Ink.TEXT), Triple("  ${d.word}", Type.CELL_SMALL, Ink.DIM)),
                        Modifier.at(cols[4].first, mid(0f, h, Type.CELL)), maxWidth = cols[4].second, base = Type.CELL,
                    )
                    Chevron(Modifier.at(cols[5].first + (cols[5].second - ScannerSpec.CHEVRON) / 2f, (h - ScannerSpec.CHEVRON) / 2f))
                    Rule(24f, h, w, Ink.HAIR)
                }
            }
        }
    }
}
