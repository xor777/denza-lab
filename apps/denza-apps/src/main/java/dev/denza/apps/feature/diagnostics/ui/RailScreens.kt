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
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.RailItem
import dev.denza.apps.feature.diagnostics.scan.Scheme
import dev.denza.apps.feature.diagnostics.scan.SystemDtcRow
import dev.denza.apps.feature.diagnostics.scan.SystemPage
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * The section built around the car's systems (architecture, 7.2): the rail down the left
 * (`scanner.js` `rail()`), the chosen screen at the width it leaves (`shell()`), and a system's page
 * - its head, its tabs and what each tab shows (`sysDtc()`, `sysBlocks()`, `sysLive()`,
 * `sysPackLive()`, `sysDrivesCompare()`, `sysAdasCompare()`, `sysEngineDtc()`, `sysPending()` and
 * the schemes of `SchemeScreens.kt`).
 */

/** What the rail needs from the section: its lines, the one lit, the header over it and where a tap goes. */
internal class RailFrame(
    val items: List<RailItem>,
    val active: CarSystem?,
    val header: @Composable () -> Unit,
    val onPick: (CarSystem?) -> Unit,
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

/** The systems down the left: «Автоскан» with its mark, a rule, the systems; the chosen one lit. */
@Composable
private fun Rail(frame: RailFrame) {
    val s = ScannerSpec.Rail
    val top = ScannerSpec.Header.HEIGHT
    val width = rememberWidths()
    Box(Modifier.at(0f, top).width(s.W.dp).fillMaxHeight().padding(bottom = (top + ScannerSpec.Footer.HEIGHT).dp).plate(Ink.BAR)) {
        Box(Modifier.at(s.W - 1f, 0f).width(1.dp).fillMaxHeight().plate(Ink.RULE))
        var y = s.PAD_TOP
        frame.items.forEach { item ->
            val on = item.system == frame.active
            Box(
                Modifier.at(0f, y).size((s.W - 1f).dp, s.ITEM.dp).plate(if (on) Ink.PID_FILL else null)
                    .tap { frame.onPick(item.system) },
            ) {
                if (on) Box(Modifier.size(s.BAR.dp, s.ITEM.dp).plate(Ink.BLUE))
                var r = s.W - s.PAD_X
                item.mark?.let { m ->
                    ScanText(m, Type.RAIL_MARK, ScannerWords.railInk(item.tone), Modifier.endAt(r, mid(0f, s.ITEM, Type.RAIL_MARK)))
                    r -= width(m, Type.RAIL_MARK) + 8f
                }
                ScanText(item.name, Type.RAIL_ITEM, if (on) Ink.WHITE else Ink.DIM, Modifier.at(s.PAD_X, mid(0f, s.ITEM, Type.RAIL_ITEM)), maxWidth = r - s.PAD_X)
            }
            y += s.ITEM
            // the autoscan stands apart from the systems
            if (item.system == null) {
                y += s.SEP_GAP
                Rule(s.PAD_X, y, s.W - 2f * s.PAD_X)
                y += s.SEP_GAP + 1f
            }
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

/**
 * A system's page: its head as a block's (title, aside, mono line, counters), its tabs, and the
 * chosen tab. The engine's stream runs while its scheme or its live table is in front, and ECM's
 * identification and readiness are read while «DTC и готовность» is.
 */
@Composable
internal fun SystemPageScreen(
    model: DiagnosticsModel,
    page: SystemPage,
    tab: SystemTab,
    frame: RailFrame,
    onTab: (SystemTab) -> Unit,
    onOpen: (Target) -> Unit,
    actions: DiagnosticsActions,
    sample: ScannerSample?,
    /** A row of «Потоковые данные» with a key opens its parameter alone (architecture, 6.2). */
    onParam: (ParamKey) -> Unit = {},
) {
    val requests = page.blocks.mapNotNull { it.request }
    val ecm = if (page.system == CarSystem.ENGINE) requests.firstOrNull()?.let { moduleOf(model, it) } else null
    val obd = ecm?.obd
    val current = rememberUpdatedState(actions)
    val streaming = ecm != null && (tab == SystemTab.LIVE || (tab == SystemTab.STATE && page.scheme is Scheme.Engine))
    DisposableEffect(ecm?.request, streaming) {
        val r = ecm?.request
        if (streaming && r != null) current.value.setLiveVisible(r, true)
        onDispose { if (streaming && r != null) current.value.setLiveVisible(r, false) }
    }
    val readiness = ecm != null && tab == SystemTab.DTC_READINESS
    DisposableEffect(ecm?.request, readiness) {
        val r = ecm?.request
        if (readiness && r != null) current.value.setModuleOpen(r, true)
        onDispose { if (readiness && r != null) current.value.setModuleOpen(r, false) }
    }
    var picking by remember { mutableStateOf(false) }
    val read = { requests.forEach(actions::readModule) }
    val codes = FooterButton("Прочитать DTC", true, read)
    val codesNotes = listOfNotNull(page.dtcFootnote.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) })
    val refresh = listOf(FooterButton("Обновить", true, read))
    val snapshot = listOfNotNull(ScannerWords.snapshot(page.dtcFootnote)?.let { FooterNote.Words(it, mono = true) })

    RailPage(frame, footer = {
        when {
            // the engine's state and its stream: the stream's own buttons
            streaming && obd != null -> ScanFooter(
                buttons = listOfNotNull(
                    FooterButton(if (obd.live.running) "Пауза" else "Продолжить", true) { actions.setLiveVisible(ecm.request, !obd.live.running) },
                    FooterButton("Выбор PID", false) { picking = true },
                    if (tab == SystemTab.LIVE) FooterButton("Сбросить мин/макс", false) { actions.resetMinMax(ecm.request) } else null,
                ),
                notes = emptyList(),
            )
            tab == SystemTab.DTC_READINESS -> ScanFooter(listOf(codes), listOfNotNull(ecm?.dtc?.source?.let { FooterNote.Words(it) }))
            // what is read from the blocks' memories, and a scheme that is only the blocks
            tab == SystemTab.DTC || tab == SystemTab.BLOCKS || (tab == SystemTab.COMPARE && page.system == CarSystem.ADAS) ||
                (tab == SystemTab.STATE && page.scheme is Scheme.Adas) -> ScanFooter(listOf(codes), codesNotes)
            // readings: when they were taken
            else -> ScanFooter(refresh, snapshot)
        }
    }) {
        BlockHead(
            page.system.title, page.aside, page.line,
            counters = page.counters.map { Counter(it.label, it.value, on = it.on) },
        )
        ScanTabs(page.tabs.map { it.label }, page.tabs.indexOf(tab).coerceAtLeast(0), { onTab(page.tabs[it]) })
        when (tab) {
            SystemTab.STATE -> page.scheme?.let { SchemeContent(model, it, onOpen) }
            SystemTab.LIVE -> if (page.system == CarSystem.PACK) PackTable(model.systems.pack, onParam) else if (obd != null) {
                LiveTable(obd.live.rows, sample?.chartRange, ScannerSpec.Rail.LIVE_COLS, onParam)
            }
            SystemTab.COMPARE -> if (page.system == CarSystem.ADAS) AdasTable(model, model.systems.adas) else DrivesTable(model.systems.drives)
            SystemTab.GB_FLAGS -> Pending("Нет данных")
            SystemTab.DTC -> SystemDtcTable(page.dtc, onOpen)
            SystemTab.DTC_READINESS -> if (ecm != null) DtcContent(ecm, Halves.RAIL)
            SystemTab.BLOCKS -> BlockRows(page.blocks, onOpen)
        }
        if (picking && obd != null && ecm != null) {
            PidPicker(obd.live.rows, obd.live.focus, { actions.setFocus(ecm.request, it); picking = false }) { picking = false }
        }
    }
}

/** A tab with nothing to show yet: one sentence where the table would stand (`sysPending()`). */
@Composable
private fun Pending(text: String) {
    ScanText(text, Type.CELL, Ink.DIM, Modifier.at(ScannerSpec.Rail.PENDING_X, ScannerSpec.Rail.PENDING_Y))
}

/**
 * A system's codes, every block's on one table (`sysDtc()`): the block named with its address on
 * its first row, a rule between blocks, a row opening the block; a record failing now in the
 * car's orange.
 */
@Composable
private fun SystemDtcTable(rows: List<SystemDtcRow>, onOpen: (Target) -> Unit) {
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
                Box(Modifier.fillMaxWidth().height((h + 1f).dp).tap { onOpen(Target.Module(d.request)) }) {
                    if (d.active) Box(Modifier.at(24f, 0f).size(w.dp, h.dp).plate(Ink.ORANGE_ROW))
                    if (first && i > 0) Rule(24f, -1f, w)
                    if (first) {
                        ScanText(d.block, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(0f, h, Type.CELL)))
                        ScanText(d.address, Type.CELL_MONO, Ink.FAINT, Modifier.at(cols[0].first + 12f + width(d.block, Type.CELL) + 8f, mid(0f, h, Type.CELL_MONO)))
                    }
                    ScanText(d.code, Type.CODE, if (d.active) Ink.ORANGE_INK else Ink.WHITE, Modifier.at(cols[1].first, mid(0f, h, Type.CODE)))
                    ScanRuns(
                        listOf(Triple(d.failureType, Type.CELL_NUM, Ink.DIM), Triple(" · ${d.failureName}", Type.CELL, Ink.TEXT)),
                        Modifier.at(cols[2].first, mid(0f, h, Type.CELL)), maxWidth = cols[2].second - 12f, base = Type.CELL,
                    )
                    ScanText(d.description, Type.CELL, Ink.TEXT, Modifier.at(cols[3].first, mid(0f, h, Type.CELL)), maxWidth = cols[3].second - 16f)
                    ScanRuns(
                        listOf(Triple(d.status, Type.CELL_MONO, Ink.TEXT), Triple("  ${d.word}", Type.CELL_SMALL, Ink.DIM)),
                        Modifier.at(cols[4].first, mid(0f, h, Type.CELL)), maxWidth = cols[4].second, base = Type.CELL,
                    )
                    Chevron(Modifier.at(cols[5].first + (cols[5].second - ScannerSpec.Scan.CHEVRON) / 2f, (h - ScannerSpec.Scan.CHEVRON) / 2f))
                    Rule(24f, h, w, Ink.HAIR)
                }
            }
        }
    }
}
