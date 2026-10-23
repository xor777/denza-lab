package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.GraphPicks
import dev.denza.apps.feature.diagnostics.scan.GraphScope
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.LiveRow
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.MonitorRow
import dev.denza.apps.feature.diagnostics.scan.MonitorState
import dev.denza.apps.feature.diagnostics.scan.ObdView
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * One ECU's screen: «Ошибки DTC» or, for an OBD responder, «DTC и готовность» (`bodyDtc()`,
 * `ecuDtc()`, `ecuFault()`, `blockMask()`), «Потоковые данные» (`ecuLive()`) and «Идентификация»
 * (`ecuIdent()`).
 */

internal const val TAB_DTC = 0

/** What a block's tab shows. */
internal enum class ModuleTab(val title: String) {
    DTC("Ошибки DTC"), OBD_DTC("DTC и готовность"), TESTS("Тесты"), LIVE("Потоковые данные"), IDENT("Идентификация")
}

/** A block's tabs: «Тесты» only where its screen reads Mode 06 (the ECM), after «DTC и готовность». */
internal fun moduleTabKinds(module: ModuleView): List<ModuleTab> = when {
    module.obd == null -> listOf(ModuleTab.DTC, ModuleTab.IDENT)
    module.tests != null -> listOf(ModuleTab.OBD_DTC, ModuleTab.TESTS, ModuleTab.LIVE, ModuleTab.IDENT)
    else -> listOf(ModuleTab.OBD_DTC, ModuleTab.LIVE, ModuleTab.IDENT)
}

internal fun moduleTabs(module: ModuleView): List<String> = moduleTabKinds(module).map { it.title }

/** The kind of [module]'s tab [tab]; its first where the index is past them. */
internal fun moduleTab(module: ModuleView, tab: Int): ModuleTab = moduleTabKinds(module).let { it.getOrElse(tab) { _ -> it.first() } }

/** A block by its name; a role read from an abbreviation is the catalog's business, not the screen's. */
internal fun moduleTitle(module: ModuleView): String = module.name

/** What a fixture adds that the session does not have: its charts' scale, the graphs' cursor. */
internal data class ScannerSample(
    /** The live charts' range; null scales each chart to its own last 60 s. */
    val chartRange: ClosedFloatingPointRange<Float>? = null,
    /** «Графики»' cursor, seconds back, as if the driver had put it there; null - none until a tap. */
    val graphsCursor: Float? = null,
)

@Composable
internal fun ModulePage(
    module: ModuleView,
    tab: Int,
    /** «Потоковые данные» as «Графики» rather than the table (7.3). */
    graphs: Boolean,
    header: @Composable () -> Unit,
    onTab: (Int, Boolean) -> Unit,
    onBack: () -> Unit,
    actions: DiagnosticsActions,
    sample: ScannerSample?,
    /** «‹ Блоки», or the screen the module was opened from. */
    backLabel: String = ScannerStack.BACK_TO_SUMMARY,
    /** A live row with a key opens its parameter alone. */
    onParam: ((ParamKey) -> Unit)? = null,
    /** A record opens on its own screen. */
    onCode: (Int, String) -> Unit = { _, _ -> },
) {
    val obd = module.obd
    val tabs = moduleTabs(module)
    val kind = moduleTab(module, tab)
    val live = obd != null && kind == ModuleTab.LIVE
    val ident = kind == ModuleTab.IDENT
    val tests = module.tests?.takeIf { kind == ModuleTab.TESTS }
    // «Графики»: the signals ticked on the table, kept while the tab is on screen (7.3)
    val scope = GraphScope.Block(module.request)
    val stream = obd?.live
    val picks = stream?.picks.orEmpty()
    val showGraphs = live && graphs && picks.isNotEmpty()
    val current = rememberUpdatedState(actions)
    val run = LocalScannerRun.current
    DisposableEffect(scope, live, run) {
        if (live) current.value.setLiveTab(scope, true)
        onDispose { if (live) current.value.setLiveTab(scope, false) }
    }
    val back = FooterButton(backLabel, false, onClick = onBack)
    val footer: @Composable () -> Unit = {
        when {
            ident -> IdentFooter(back) { actions.setModuleOpen(module.request, false); actions.setModuleOpen(module.request, true) }
            tests != null -> ScanFooter(listOf(back, FooterButton("Прочитать снова", true) { actions.rereadTests(module.request) }), emptyList())
            live -> {
                val pause = FooterButton(if (stream!!.running) "Пауза" else "Продолжить", true) { actions.setLiveVisible(module.request, !stream.running) }
                if (showGraphs) {
                    GraphsFooter(back, pause, stream, stream.graphs?.footnote) { actions.setRecording(module.request, it) }
                } else {
                    ScanFooter(listOf(back, pause, FooterButton("Сбросить мин/макс", false) { actions.resetMinMax(module.request) }), emptyList())
                }
            }
            else -> DtcFooter(module, back) { actions.readModule(module.request) }
        }
    }
    ScannerPage(footer = footer) {
        header()
        ModuleHead(module, if (live) ScannerWords.liveLine(module.request, stream!!.rows.size, stream.rate) else module.line)
        ScanTabs(
            tabs, tab,
            // «Потоковые данные» tapped over its graphs brings the table back
            { onTab(it, graphs && !(live && it == tab)) },
            alert = if (obd?.mil == MilState.ON) TAB_DTC else null,
            right = if (live) graphsSwitch(picks.size, showGraphs) { onTab(tab, it) } else null,
        )
        when {
            ident -> IdentContent(module.ident)
            tests != null -> TestsContent(tests)
            showGraphs -> GraphLanes(stream!!.graphs, picks, sample?.graphsCursor, onParam ?: {}) { key ->
                val next = picks - key
                actions.pickGraphs(scope, next)
                if (next.isEmpty()) onTab(tab, false)
            }
            live -> LiveTable(
                stream!!.rows, sample?.chartRange, ScannerSpec.EcuLive.COLS, onParam, scroll = true,
                picks = picks, onPick = { actions.pickGraphs(scope, GraphPicks.toggle(picks, it)) },
            )
            else -> DtcContent(module) { onCode(module.request, it) }
        }
    }
}

/**
 * The head of an ECU: MIL and readiness when it speaks OBD; its records - their worst kind's sign and
 * their count - when it has any. A block with nothing to say has no figure.
 */
@Composable
internal fun ModuleHead(module: ModuleView, line: String) {
    val obd = module.obd
    val n = module.dtc.records.size
    val records = Counter("ЗАПИСИ", n.toString(), mark = ScannerWords.mark(module.status)?.takeIf { it != Mark.NO_REPLY } ?: Mark.HISTORY)
        .takeIf { n > 0 }
    // a test out of its limits is an exception the head says, whichever tab is in front
    val tests = module.tests?.outOfNorm?.takeIf { it > 0 }?.let { Counter("ВНЕ НОРМЫ", it.toString(), out = true) }
    val counters = if (obd != null) {
        listOfNotNull(Counter("MIL", ScannerWords.mil(obd.mil), on = obd.mil == MilState.ON), records, Counter("ГОТОВН.", obd.readiness), tests)
    } else {
        listOfNotNull(records)
    }
    BlockHead(moduleTitle(module), "· ${module.unit}", line, counters = counters)
}

@Composable
private fun DtcFooter(module: ModuleView, back: FooterButton, read: () -> Unit) {
    val notes = if (module.obd != null) listOfNotNull(module.dtc.source?.let { FooterNote.Words(it) }) else dtcNotes(module.dtc)
    ScanFooter(listOf(back, FooterButton("Прочитать DTC", true, onClick = read)), notes)
}

/**
 * A memory read's footnote: what was asked and when; a record outside the mask said first, and the
 * descriptions' source, when the screen shows one, before what was asked.
 */
internal fun dtcNotes(dtc: DtcView): List<FooterNote> {
    val words = listOfNotNull(
        FooterNote.Words("Есть записи вне маски запроса").takeIf { dtc.outsideMask },
        dtc.source?.let { FooterNote.Words(it) },
        dtc.footnote.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) },
    )
    return words.flatMapIndexed { i, n -> if (i == 0) listOf(n) else listOf(FooterNote.Divider, n) }
}

@Composable
private fun IdentFooter(back: FooterButton, reread: () -> Unit) {
    ScanFooter(listOf(back, FooterButton("Прочитать снова", true, onClick = reread)), listOf(FooterNote.Words("22 F18A · F193 · F194 · F195", mono = true)))
}

/* ------------------------------------------------------------------ DTC */

/**
 * Where the DTC screens' two halves stand: a block's screen across the window, or ECM's
 * «DTC и готовность» beside the rail (`scanner.json` `ecuDtc`, `rail.ecuDtc`).
 */
internal data class Halves(
    val leftX: Float, val leftW: Float, val rightX: Float, val rightW: Float,
    val listCols: List<Float>, val monCols: List<Float>,
) {
    companion object {
        val FULL = ScannerSpec.EcuDtc.let { Halves(it.LEFT_X, it.LEFT_W, it.RIGHT_X, it.RIGHT_W, it.LIST_COLS, it.MON_COLS) }
        val RAIL = ScannerSpec.Rail.let { Halves(it.ECU_LEFT_X, it.ECU_LEFT_W, it.ECU_RIGHT_X, it.ECU_RIGHT_W, it.ECU_LIST_COLS, it.ECU_MON_COLS) }
    }
}

@Composable
internal fun DtcContent(module: ModuleView, halves: Halves = Halves.FULL, onCode: (String) -> Unit = {}) {
    val obd = module.obd
    when {
        obd != null && module.dtc.records.isEmpty() -> { ObdLists(obd, halves); Monitors(obd.monitors, halves) }
        obd != null -> { ObdFault(module.dtc.records, obd, halves, onCode); Monitors(obd.monitors, halves) }
        module.dtc.records.isNotEmpty() -> CodesWithStatus(module.dtc, onCode)
        else -> MaskAsks(module.dtc, halves.leftX, halves.leftW)
    }
}

/**
 * A UDS block's records (`bodyDtc()`): code, failure type, the description or the group, the status byte
 * and its words; under them the picked record's status byte as one strip from bit 7 to 0 and, beside
 * it, how the memory was read with the record picked out. A row picks its record; the picked row opens
 * it on its own screen. The column heads stay; the rows and what is under them scroll between the heads
 * and the footer, so a block with eight codes reads as one with two.
 */
@Composable
private fun CodesWithStatus(dtc: DtcView, onCode: (String) -> Unit) {
    val b = ScannerSpec.BodyDtc
    val records = dtc.records
    var chosen by remember { mutableIntStateOf(0) }
    val width = rememberWidths()
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(b.TABLE, 24f, w)
    val rowsTop = b.TOP + b.HEAD + 1f
    val rowsH = records.size * (b.ROW_MIN + 1f)
    val below = maxOf(b.BELOW, rowsTop + rowsH + b.BELOW_GAP)
    val picked = records[chosen.coerceIn(0, records.lastIndex)]
    val sy = below + Type.GROUP_TITLE.lh + b.EXPLAIN_GAP
    val wy = sy + ScannerSpec.BitStrip.NAME_Y + Type.BIT_HEAD.lh + b.EXPLAIN_GAP + 4f
    Box(Modifier.fillMaxSize()) {
        HeadRow(cols, listOf(HeadLabel("КОД"), HeadLabel("ТИП ОТКАЗА (FTB)"), HeadLabel("ОПИСАНИЕ"), HeadLabel("СТАТУС")), b.TOP, b.HEAD, Type.COL_HEAD_TIGHT)
        Column(
            Modifier.fillMaxSize()
                .padding(top = rowsTop.dp, bottom = ScannerSpec.Footer.HEIGHT.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // the page's offsets kept: y counts from the first row
            val total = maxOf(wy + Type.LEAD.lh + Type.SMALL12.lh, sy + picked.reads.size * Type.RAW.lh) - rowsTop + b.BELOW_GAP
            Box(Modifier.fillMaxWidth().height(total.dp)) {
                records.forEachIndexed { r, d ->
                    val y = r * (b.ROW_MIN + 1f)
                    val h = b.ROW_MIN
                    val active = ScannerWords.markOf(d.status) == Mark.ACTIVE
                    val availability = d.availability
                    Box(Modifier.at(24f, y).size(w.dp, h.dp).plate(if (r == chosen) Ink.BAND else null).tap { if (r == chosen) onCode(d.code) else chosen = r })
                    ScanText(d.code, Type.CODE, if (active) Ink.RED else Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(y, h, Type.CODE)))
                    ScanRuns(
                        listOf(Triple(d.failureType, Type.CELL_NUM, Ink.DIM), Triple(" · ${d.failureName}", Type.CELL, Ink.WHITE)),
                        Modifier.at(cols[1].first, mid(y, h, Type.CELL)), maxWidth = cols[1].second, base = Type.CELL,
                    )
                    val room = cols[2].second - 16f
                    val desc = d.description
                    if (desc != null) {
                        // the catalog's description in up to two lines, in place of the group
                        val lines = ScannerWords.twoLines(desc, room) { width(it, Type.CELL) }
                        val dt = y + (h - lines.size * Type.CELL.lh) / 2f
                        lines.forEachIndexed { i, l -> ScanText(l, Type.CELL, Ink.TEXT, Modifier.at(cols[2].first, dt + i * Type.CELL.lh), maxWidth = room) }
                    } else {
                        val dt = y + (h - Type.CELL.lh - (if (d.note.isNotEmpty()) 2f + Type.NOTE.lh else 0f)) / 2f
                        ScanText(d.group, Type.CELL, Ink.TEXT, Modifier.at(cols[2].first, dt), maxWidth = room)
                        if (d.note.isNotEmpty()) ScanText(d.note, Type.NOTE, Ink.FAINT, Modifier.at(cols[2].first, dt + Type.CELL.lh + 2f), maxWidth = room)
                    }
                    ScanRuns(
                        listOf(
                            Triple(ScannerWords.hex2(d.status) + "  ", Type.CELL_NUM, Ink.TEXT),
                            Triple(ScannerWords.statusWords(d.status, availability), Type.CELL_SMALL, if (active) Ink.RED else Ink.DIM),
                        ),
                        Modifier.at(cols[3].first, mid(y, h, Type.CELL)), maxWidth = cols[3].second, base = Type.CELL,
                    )
                    Rule(24f, y + h, w, Ink.HAIR)
                }
                // the picked record's status byte and its read, at the page's offsets less the rows' top
                val dy = -rowsTop
                ScanText(
                    "СТАТУС ${picked.code} · ${ScannerWords.hex2(picked.status)} = ${ScannerWords.bin8(picked.status)}",
                    Type.GROUP_TITLE, Ink.MUTE, Modifier.at(24f, below + dy),
                )
                BitStrip(24f, sy + dy, picked.status, picked.availability)
                ScanText(ScannerWords.statusWords(picked.status, picked.availability), Type.LEAD, Ink.WHITE, Modifier.at(24f, wy + dy))
                ScanText(ScannerWords.availabilityNote(picked.availability), Type.SMALL12, Ink.FAINT, Modifier.at(24f, wy + Type.LEAD.lh + dy))
                if (picked.reads.isNotEmpty()) {
                    ScanText("КАК ПРОЧИТАН", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(b.READ_X, below + dy))
                    picked.reads.forEachIndexed { i, r -> RawRead(r, b.READ_X, sy + dy + i * Type.RAW.lh) }
                }
            }
        }
    }
}

/**
 * What was asked of the memory and with which mask, when nothing came back (`blockMask()`): the asks,
 * then a plate with the mask's bits from 7 to 0 under its hex and binary - «·» where the block's
 * availability has no bit - and what came back.
 */
@Composable
internal fun MaskAsks(dtc: DtcView, x: Float, w: Float) {
    val k = ScannerSpec.Brakes
    val cols = ScannerSpec.columns(k.ASK_COLS, x, w)
    HeadRow(cols, listOf(HeadLabel("ЧТО ЗАПРОШЕНО"), HeadLabel("СЕРВИС"), HeadLabel("DTC", end = true, pad = 12f)), k.TOP, k.HEAD)
    var y = k.TOP + k.HEAD + 1f
    dtc.asks.forEach { a ->
        val h = k.ASK_ROW
        ScanText(a.name, Type.CELL, if (a.asked) Ink.WHITE else Ink.DIM, Modifier.at(cols[0].first + 12f, mid(y, h, Type.CELL)), maxWidth = cols[0].second - 12f)
        ScanText(a.service, Type.CELL_MONO, Ink.DIM, Modifier.at(cols[1].first, mid(y, h, Type.CELL_MONO)))
        val st = if (a.asked) Type.COUNT16 else Type.NORM_MONO
        ScanText(a.count, st, if (a.asked) Ink.WHITE else Ink.FAINT, Modifier.endAt(cols[2].first + cols[2].second - 12f, mid(y, h, st)))
        Rule(x, y + h, w, Ink.HAIR)
        y += h + 1f
    }
    val iw = w - 2f * k.PLATE_PAD_X
    val result = when {
        dtc.records.isNotEmpty() -> "Записей: ${dtc.records.size}"
        dtc.quality == DataQuality.COMPLETE_EMPTY -> "Записей нет"
        else -> dtc.quality.label.replaceFirstChar { it.uppercase() }
    }
    val mask = dtc.mask ?: MASK
    val availability = dtc.availability ?: 0xFF
    Column(
        Modifier.at(x, y + k.PLATE_GAP).width(w.dp).plate(Ink.PLATE, Ink.RULE, k.PLATE_R)
            .padding(horizontal = k.PLATE_PAD_X.dp, vertical = k.PLATE_PAD_Y.dp),
    ) {
        ScanText(
            "МАСКА ${ScannerWords.hex2(mask)} = ${ScannerWords.bin8(mask)} · ДОСТУПНОСТЬ ${ScannerWords.hex2(availability)}",
            Type.COUNTER_LABEL, Ink.MUTE,
        )
        Spacer(Modifier.height(k.PLATE_GAP_IN.dp))
        Box(Modifier.width(iw.dp).height((ScannerSpec.BitStrip.NAME_Y + Type.BIT_HEAD.lh).dp)) { BitStrip(0f, 0f, mask, availability, plain = true) }
        Spacer(Modifier.height(k.PLATE_GAP_IN.dp))
        ScanParagraph(result, Type.BODY_STRONG, Ink.WHITE, iw)
    }
}

/** The mask the product reads memories with when the session has not said (architecture, 5.2, 7.3): now, pending or confirmed. */
private const val MASK = 0x0D

/* ---------------------------------------------------------------- OBD */

@Composable
private fun ObdLists(obd: ObdView, x: Halves) {
    val d = ScannerSpec.EcuDtc
    val cols = ScannerSpec.columns(x.listCols, x.leftX, x.leftW)
    HeadRow(cols, listOf(HeadLabel("КОДЫ НЕИСПРАВНОСТЕЙ"), HeadLabel("СЕРВИС"), HeadLabel("DTC", end = true, pad = 12f)), d.TOP, d.HEAD)
    var y = d.TOP + d.HEAD + 1f
    obd.lists.forEach { a ->
        val h = d.LIST_ROW
        val num = a.count.all { it.isDigit() } && a.count.isNotEmpty()
        val none = a.count == "0"
        ScanText(a.name, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(y, h, Type.CELL)), maxWidth = cols[0].second - 12f)
        ScanText(a.service, Type.CELL_MONO, Ink.DIM, Modifier.at(cols[1].first, mid(y, h, Type.CELL_MONO)))
        val st = if (num) Type.CELL_NUM else Type.NORM_MONO
        ScanText(if (none) "—" else a.count, st, if (num && !none) Ink.WHITE else Ink.FAINT, Modifier.endAt(cols[2].first + cols[2].second - 12f, mid(y, h, st)))
        Rule(x.leftX, y + h, x.leftW, Ink.HAIR)
        y += h + 1f
    }
    Pid01Plate(obd, Modifier.at(x.leftX, y + d.PLATE_GAP), x.leftW)
}

/** A plate: PID 01's caption, its sentence - broken at spaces where the plate is narrow - and the raw reply. */
@Composable
private fun Pid01Plate(obd: ObdView, modifier: Modifier, w: Float) {
    val d = ScannerSpec.EcuDtc
    Column(modifier.width(w.dp).plate(Ink.PLATE, Ink.RULE, d.PLATE_R).padding(horizontal = d.PLATE_PAD_X.dp, vertical = d.PLATE_PAD_Y.dp)) {
        ScanText("PID 01 · СОСТОЯНИЕ", Type.COUNTER_LABEL, Ink.MUTE)
        Spacer(Modifier.height(d.PLATE_GAP_IN.dp))
        ScanParagraph(obd.milLine, Type.PLATE_LINE, Ink.WHITE, w - 2f * d.PLATE_PAD_X)
        Spacer(Modifier.height(d.PLATE_GAP_IN.dp))
        ScanText(obd.raw, Type.PLATE_MONO, Ink.FAINT)
    }
}

/** The OBD responder with records: each code over its sentence, then the four lists' counts. */
@Composable
private fun ObdFault(records: List<DtcRow>, obd: ObdView, x: Halves, onCode: (String) -> Unit) {
    val d = ScannerSpec.EcuDtc
    val f = ScannerSpec.EcuFault
    val cols = ScannerSpec.columns(f.COLS, x.leftX, x.leftW)
    HeadRow(cols, listOf(HeadLabel("КОД"), HeadLabel("ОПИСАНИЕ"), HeadLabel("MIL")), d.TOP, d.HEAD)
    Column(Modifier.at(x.leftX, d.TOP + d.HEAD + 1f).width(x.leftW.dp)) {
        records.forEach { r ->
            val lamp = ScannerWords.asksLamp(r.status)
            val failed = r.status and 1 == 1
            val hot = lamp || failed
            Row(
                Modifier.fillMaxWidth().heightIn(min = f.ROW_MIN.dp).plate(if (hot) Ink.RED_ROW else null).tap { onCode(r.code) }
                    .padding(vertical = f.ROW_PAD.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(cols[0].second.dp).padding(start = 12.dp)) {
                    ScanText(r.code, Type.CODE_FAULT, if (hot) Ink.RED else Ink.WHITE)
                }
                val (title, under) = ScannerWords.faultLines(r)
                Column(Modifier.width(cols[1].second.dp)) {
                    ScanParagraph(title, Type.FAULT_TITLE, Ink.WHITE, cols[1].second - 12f)
                    if (under.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        ScanParagraph(under, Type.FAULT_NOTE, Ink.DIM2, cols[1].second - 12f)
                    }
                }
                Badge(if (lamp) "ВКЛ" else "ВЫКЛ", if (lamp) ScannerSpec.Tones.ON else ScannerSpec.Tones.UNREAD)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).plate(Ink.HAIR))
        }
        Spacer(Modifier.height(f.LIST_GAP.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).plate(Ink.RULE))
        val lists = ScannerWords.SHORT_LISTS.mapNotNull { (service, caption) -> obd.lists.firstOrNull { it.service == service }?.let { caption to it } }
        val lh = 2f * f.LIST_PAD_Y + Type.COUNTER_LABEL.lh + f.LIST_GAP_IN + Type.LIST_COUNT.lh + f.LIST_GAP_IN + Type.LIST_SERVICE.lh
        Row(Modifier.fillMaxWidth().height(lh.dp)) {
            lists.forEachIndexed { i, (caption, a) ->
                Box(Modifier.weight(1f).height(lh.dp)) {
                    if (i > 0) VRule(0f, 0f, lh)
                    Column(Modifier.padding(start = ((if (i > 0) 1f else 0f) + f.LIST_PAD_X).dp, top = f.LIST_PAD_Y.dp)) {
                        ScanText(caption, Type.COL_HEAD_TIGHT, Ink.MUTE)
                        Spacer(Modifier.height((Type.COUNTER_LABEL.lh - Type.COL_HEAD_TIGHT.lh + f.LIST_GAP_IN).dp))
                        ScanText(if (a.count == "0") "—" else a.count, Type.LIST_COUNT, if (a.count == "0") Ink.FAINT else Ink.RED_INK)
                        Spacer(Modifier.height(f.LIST_GAP_IN.dp))
                        ScanText(a.service, Type.LIST_SERVICE, Ink.FAINT)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).plate(Ink.RULE))
        Spacer(Modifier.height((1f + f.LIST_GAP - 1f).dp))
        Pid01Plate(obd, Modifier, x.leftW)
    }
}

/** A monitor's readiness in words, no chip: ready is the quiet case. */
private val MONITOR = mapOf(
    MonitorState.READY to ("готов" to Ink.DIM2),
    MonitorState.NOT_READY to ("не готов" to Ink.WHITE),
    MonitorState.UNSUPPORTED to ("не поддерж." to Ink.FAINT),
)

@Composable
private fun Monitors(list: List<MonitorRow>, x: Halves) {
    val d = ScannerSpec.EcuDtc
    val cols = ScannerSpec.columns(x.monCols, x.rightX, x.rightW)
    HeadRow(cols, listOf(HeadLabel("ГОТОВНОСТЬ МОНИТОРОВ · С МОМЕНТА СБРОСА DTC"), HeadLabel("ТИП"), HeadLabel("СТАТУС")), d.TOP, d.HEAD)
    var y = d.TOP + d.HEAD + 1f
    list.forEach { m ->
        val h = d.MON_ROW
        val off = m.state == MonitorState.UNSUPPORTED
        ScanRuns(
            listOf(Triple("${m.name} ", Type.CELL, if (off) Ink.FAINT2 else Ink.WHITE), Triple(m.abbreviation, Type.CELL_ABBR, Ink.FAINT)),
            Modifier.at(cols[0].first + 12f, mid(y, h, Type.CELL)), maxWidth = cols[0].second - 12f,
        )
        ScanText(m.kind, Type.UNIT, if (off) Ink.SEP else Ink.DIM2, Modifier.at(cols[1].first, mid(y, h, Type.UNIT)))
        val (word, inkW) = MONITOR.getValue(m.state)
        ScanText(word, Type.CELL_SMALL, inkW, Modifier.at(cols[2].first, mid(y, h, Type.CELL_SMALL)), maxWidth = cols[2].second)
        Rule(x.rightX, y + h, x.rightW, Ink.HAIR)
        y += h + 1f
    }
}

/* ---------------------------------------------------------------- live */

/**
 * A live table (`liveTable()`): an OBD responder's PIDs - the block's columns across the window, the
 * rail's beside it - or the pack's readings, whose [first] column is the device and whose rows
 * [scroll] under the captions between them and the footer. A value outside its norm is orange.
 *
 * With [picks] the table chooses «Графики» (7.3): a row that can go on them ([GraphPicks.pickable]) leads with
 * its square in a column of its own - blue with its lane's number when ticked, an empty frame when not, pale once
 * four are - and a tap anywhere in that column's height of the row is [onPick]; the rest of the row opens its
 * parameter.
 */
@Composable
internal fun LiveTable(
    rows: List<LiveRow>,
    chartRange: ClosedFloatingPointRange<Float>?,
    colSpec: List<Float>,
    onParam: ((ParamKey) -> Unit)? = null,
    first: String = "PID",
    scroll: Boolean = false,
    picks: List<ParamKey>? = null,
    onPick: (ParamKey) -> Unit = {},
) {
    val e = ScannerSpec.EcuLive
    val w = LocalScanWidth.current - 48f
    val px = if (picks != null) ScannerSpec.Pick.COL else 0f
    val cols = ScannerSpec.columns(colSpec, 24f + px, w - px)
    HeadRow(
        cols,
        listOf(
            HeadLabel(first, pad = if (picks != null) 0f else 12f), HeadLabel("ПАРАМЕТР"), HeadLabel("ЗНАЧЕНИЕ", end = true, pad = 12f), HeadLabel("ЕД."),
            HeadLabel("МИН", end = true, pad = 12f), HeadLabel("МАКС", end = true, pad = 12f), HeadLabel("НОРМА"), HeadLabel("ГРАФИК · 60 С"),
        ),
        e.TOP, e.HEAD,
    )
    if (picks != null) Rule(24f, e.TOP + e.HEAD, px)
    val top = e.TOP + e.HEAD + 1f
    val pick = picks?.let { PickColumn(it, onPick) }
    if (scroll) {
        // the rows at their board places, scrolling under the captions
        ScrollRows(top) {
            Box(Modifier.fillMaxWidth().height((rows.size * (e.ROW + 1f)).dp)) { LiveRows(rows, chartRange, cols, w, 0f, onParam, pick) }
        }
    } else {
        LiveRows(rows, chartRange, cols, w, top, onParam, pick)
    }
}

/** The squares' column of a live table: what is ticked, and what a tap on a square does. */
private class PickColumn(val picks: List<ParamKey>, val onPick: (ParamKey) -> Unit)

@Composable
private fun LiveRows(
    rows: List<LiveRow>,
    chartRange: ClosedFloatingPointRange<Float>?,
    cols: List<Pair<Float, Float>>,
    w: Float,
    top: Float,
    onParam: ((ParamKey) -> Unit)?,
    pick: PickColumn? = null,
) {
    val e = ScannerSpec.EcuLive
    val p = ScannerSpec.Pick
    val width = rememberWidths()
    val full = pick != null && pick.picks.size >= GraphPicks.MAX
    var y = top
    rows.forEach { r ->
        val h = e.ROW
        fun end(i: Int) = cols[i].first + cols[i].second - 12f
        // a row with a key opens its parameter alone; the row itself draws nothing more
        val key = r.key
        if (key != null && onParam != null) Box(Modifier.at(24f, y).size(w.dp, h.dp).tap { onParam(key) })
        // its square: the whole column at the row's height is the square's target, over the row's own
        if (pick != null && key != null && (GraphPicks.pickable(r) || key in pick.picks)) {
            val n = pick.picks.indexOf(key) + 1
            PickBox(24f + p.INSET, y + (h - p.SIZE) / 2f, n.takeIf { it > 0 }, pale = full && n == 0)
            Box(Modifier.at(24f, y).size(p.COL.dp, h.dp).tap { pick.onPick(key) })
        }
        ScanText(r.device ?: ScannerWords.pid(r.pid), Type.NORM_MONO, Ink.FAINT, Modifier.at(cols[0].first + if (pick != null) 0f else 12f, mid(y, h, Type.NORM_MONO)))
        ScanRuns(
            listOf(Triple("${r.name} ", Type.CELL, Ink.WHITE), Triple(r.abbreviation, Type.CELL_ASIDE, Ink.FAINT)),
            Modifier.at(cols[1].first, mid(y, h, Type.CELL)), maxWidth = cols[1].second,
        )
        // a value stays in its column, or - a state's longer word - reaches back over what the name
        // leaves free, as far as 12 dp after it
        val nameEnd = cols[1].first + minOf(cols[1].second, width("${r.name} ", Type.CELL) + width(r.abbreviation, Type.CELL_ASIDE))
        val room = maxOf(cols[2].second - 12f, end(2) - nameEnd - 12f)
        ScanText(r.value, Type.VALUE, if (r.outOfNorm) Ink.ORANGE else Ink.WHITE, Modifier.endAt(end(2), mid(y, h, Type.VALUE)), maxWidth = room)
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[3].first, mid(y, h, Type.UNIT)))
        ScanText(r.min, Type.CELL_MONO, Ink.DIM2, Modifier.endAt(end(4), mid(y, h, Type.CELL_MONO)))
        ScanText(r.max, Type.CELL_MONO, Ink.DIM2, Modifier.endAt(end(5), mid(y, h, Type.CELL_MONO)))
        // a state's norm in words («замкн. петля») runs on where the row draws no trace, as the board's
        ScanText(r.norm, Type.NORM_MONO, Ink.FAINT, Modifier.at(cols[6].first, mid(y, h, Type.NORM_MONO)), maxWidth = if (r.samples.isNotEmpty()) cols[6].second - 8f else null)
        if (r.samples.isNotEmpty()) Spark(r, chartRange, minOf(e.SPARK_W, cols[7].second - 8f), Modifier.at(cols[7].first, y + (h - e.SPARK_H) / 2f))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

/**
 * The last 60 s on the car's clock: x by time, a gap where a reading is missing, never interpolated. The sample
 * before the minute starts the line left of the chart, clipped, so it enters from the left edge without a jump.
 */
@Composable
private fun Spark(r: LiveRow, range: ClosedFloatingPointRange<Float>?, width: Float, modifier: Modifier) {
    val e = ScannerSpec.EcuLive
    // the glow reaches past the line: clipped at the chart's ends only, where the line enters and leaves
    Canvas(modifier.size(width.dp, e.SPARK_H.dp)) {
        val last = r.samples.last().atNanos
        val span = 60_000_000_000L
        val shown = inWindow(r.samples, last, span)
        val values = shown.mapNotNull { it.value }
        if (values.isEmpty()) return@Canvas
        val lo = range?.start ?: values.min()
        val hi = range?.endInclusive ?: values.max()
        val two = 2.dp.toPx()
        val inner = size.height - 2f * two
        val path = Path()
        var open = false
        shown.forEach { s ->
            val v = s.value
            if (v == null) { open = false; return@forEach }
            val t = (s.atNanos - (last - span)).toFloat() / span
            val n = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0f, 1f) else 0.5f
            val x = t * size.width
            val y = size.height - two - n * inner
            if (open) path.lineTo(x, y) else path.moveTo(x, y)
            open = true
        }
        val sw = e.SPARK_STROKE.dp.toPx()
        clipRect(0f, -size.height, size.width, 2f * size.height) {
            glowUnder(Ink.TRACE_GLOW, e.SPARK_GLOW * 2f) { c, p ->
                p.style = android.graphics.Paint.Style.STROKE
                p.strokeWidth = sw
                p.strokeJoin = android.graphics.Paint.Join.ROUND
                c.drawPath(path.asAndroidPath(), p)
            }
            drawPath(path, ink(Ink.BLUE), style = Stroke(sw, join = StrokeJoin.Round))
        }
    }
}

/* ------------------------------------------------------- identification */

@Composable
internal fun KeyRows(rows: List<Field>, cols: List<Pair<Float, Float>>, top: Float, h: Float, x: Float, w: Float): Float {
    var y = top
    rows.forEach { f ->
        ScanText(f.key, Type.KEY, Ink.DIM2, Modifier.at(cols[0].first + 12f, mid(y, h, Type.KEY)))
        ScanText(f.value, Type.KEY_VALUE, if (f.muted) Ink.MUTE else Ink.WHITE, Modifier.at(cols[1].first, mid(y, h, Type.KEY_VALUE)), maxWidth = cols[1].second)
        Rule(x, y + h, w, Ink.HAIR)
        y += h + 1f
    }
    return y
}

/**
 * «Идентификация»: the block's own rows on the left, «Как опознан» and the PIDs on the right, at the
 * board's offsets. A block with many rows (B_ZCU with its lamps, an «ПО обновлено» row more) runs
 * past the footer, so the tab scrolls under the tabs as the codes table does; at rest it is the board.
 */
@Composable
internal fun IdentContent(ident: IdentView) {
    val d = ScannerSpec.EcuDtc
    val i = ScannerSpec.EcuIdent
    val first = d.TOP + d.HEAD + 1f
    val left = first + ident.own.size * (i.ROW + 1f)
    val pidRows = (ident.pids.size + i.PID_COLS - 1) / i.PID_COLS
    val right = first + ident.cache.size * (i.ROW + 1f) +
        if (ident.pids.isEmpty()) 0f else i.PID_GAP + i.PID_HEAD_H + i.PID_GRID_GAP + pidRows * (i.PID_H + i.PID_CELL_GAP)
    Column(
        Modifier.fillMaxSize()
            .padding(top = d.TOP.dp, bottom = ScannerSpec.Footer.HEIGHT.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // the content keeps its page offsets: shifted up by the scroller's own top
        Box(Modifier.fillMaxWidth().height((maxOf(left, right) - d.TOP + i.ROW).dp)) {
            Box(Modifier.fillMaxSize().offset(y = (-d.TOP).dp)) { IdentRows(ident) }
        }
    }
}

@Composable
private fun IdentRows(ident: IdentView) {
    val d = ScannerSpec.EcuDtc
    val i = ScannerSpec.EcuIdent
    var cols = ScannerSpec.columns(i.OWN_COLS, i.LEFT_X, i.LEFT_W)
    HeadRow(cols, listOf(HeadLabel("ОТВЕЧАЕТ БЛОК")), d.TOP, d.HEAD)
    KeyRows(ident.own, cols, d.TOP + d.HEAD + 1f, i.ROW, i.LEFT_X, i.LEFT_W)

    cols = ScannerSpec.columns(i.CACHE_COLS, i.RIGHT_X, i.RIGHT_W)
    HeadRow(cols, listOf(HeadLabel("КАК ОПОЗНАН")), d.TOP, d.HEAD)
    var y = KeyRows(ident.cache, cols, d.TOP + d.HEAD + 1f, i.ROW, i.RIGHT_X, i.RIGHT_W)
    if (ident.pids.isEmpty()) return
    y += i.PID_GAP
    // the caption and the count on one baseline, the lower of the two styles' baselines
    val by = maxOf(Type.COUNTER_LABEL.baseline, Type.PID_HEAD.baseline) + (i.PID_HEAD_H - Type.COUNTER_LABEL.lh) / 2f
    ScanText("ПОДДЕРЖИВАЕМЫЕ PID · MODE 01", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(i.RIGHT_X, y), baseline = by)
    ScanText(
        "${ident.pids.size} шт · ${ScannerWords.pidsAsked(ident.pids)}", Type.PID_HEAD, Ink.FAINT,
        Modifier.endAt(i.RIGHT_X + i.RIGHT_W, y), baseline = by,
    )
    y += i.PID_HEAD_H + i.PID_GRID_GAP
    val cw = (i.RIGHT_W - (i.PID_COLS - 1) * i.PID_CELL_GAP) / i.PID_COLS
    ident.pids.forEachIndexed { k, id ->
        val px = i.RIGHT_X + (k % i.PID_COLS) * (cw + i.PID_CELL_GAP)
        val py = y + (k / i.PID_COLS) * (i.PID_H + i.PID_CELL_GAP)
        val service = ScannerWords.bitmapPid(id)
        Box(Modifier.at(px, py).size(cw.dp, i.PID_H.dp).plate(if (service) null else Ink.PID_FILL, null, i.PID_R)) {
            ScanText(id, Type.PID, if (service) Ink.SEP else Ink.TEXT, Modifier.centreAt(cw / 2f, mid(0f, i.PID_H, Type.PID)))
        }
    }
}
