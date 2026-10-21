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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.LiveRow
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
 * `ecuDtc()`, `ecuFault()`), «Потоковые данные» (`ecuLive()`) and «Идентификация» (`ecuIdent()`).
 */

internal const val TAB_DTC = 0

internal fun moduleTabs(module: ModuleView): List<String> =
    if (module.obd != null) listOf("DTC и готовность", "Потоковые данные", "Идентификация") else listOf("Ошибки DTC", "Идентификация")

/** A block by its name; a role read from an abbreviation is the catalog's business, not the screen's. */
internal fun moduleTitle(module: ModuleView): String = module.name

/** What a fixture adds that the session does not have: its charts' scale. */
internal data class ScannerSample(
    /** The live charts' range; null scales each chart to its own last 60 s. */
    val chartRange: ClosedFloatingPointRange<Float>? = null,
)

@Composable
internal fun ModulePage(
    module: ModuleView,
    tab: Int,
    header: @Composable () -> Unit,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
    actions: DiagnosticsActions,
    sample: ScannerSample?,
    /** «‹ Автоскан», or the screen the module was opened from. */
    backLabel: String = ScannerStack.BACK_TO_SCAN,
    /** A live row with a key opens its parameter alone. */
    onParam: ((ParamKey) -> Unit)? = null,
) {
    val obd = module.obd
    val tabs = moduleTabs(module)
    val live = obd != null && tab == 1
    val ident = tab == tabs.lastIndex
    var picking by remember { mutableStateOf(false) }
    val back = FooterButton(backLabel, false, onBack)
    val footer: @Composable () -> Unit = {
        when {
            ident -> IdentFooter(module.ident, back) { actions.setModuleOpen(module.request, false); actions.setModuleOpen(module.request, true) }
            live -> ScanFooter(
                buttons = listOf(
                    back,
                    FooterButton(if (obd!!.live.running) "Пауза" else "Продолжить", true) { actions.setLiveVisible(module.request, !obd.live.running) },
                    FooterButton("Выбор PID", false) { picking = true },
                    FooterButton("Сбросить мин/макс", false) { actions.resetMinMax(module.request) },
                    FooterButton(if (obd.live.recording) "Стоп записи" else "Запись", obd.live.recording) {
                        actions.setRecording(module.request, !obd.live.recording)
                    },
                ),
                notes = emptyList(),
            )
            else -> DtcFooter(module, back) { actions.readModule(module.request) }
        }
    }
    ScannerPage(footer = footer) {
        header()
        ModuleHead(module, if (live) ScannerWords.liveLine(module.request, obd!!.live.rows.size, obd.live.rate) else module.line)
        ScanTabs(tabs, tab, onTab, alert = if (obd?.mil == MilState.ON) TAB_DTC else null)
        when {
            ident -> IdentContent(module.ident)
            live -> LiveTable(obd!!.live.rows, sample?.chartRange, ScannerSpec.EcuLive.COLS, onParam)
            else -> DtcContent(module)
        }
        if (picking && obd != null) {
            PidPicker(obd.live.rows, obd.live.focus, { actions.setFocus(module.request, it); picking = false }) { picking = false }
        }
    }
}

/** The head of an ECU: its counters when it speaks OBD, its chip and DTC count otherwise. */
@Composable
internal fun ModuleHead(module: ModuleView, line: String) {
    val obd = module.obd
    if (obd != null) {
        BlockHead(
            moduleTitle(module), "· ${module.unit}", line,
            counters = listOf(
                Counter("MIL", ScannerWords.mil(obd.mil), on = obd.mil == MilState.ON),
                Counter("DTC", module.dtc.records.size.toString()),
                Counter("ГОТОВН.", obd.readiness),
            ),
        )
    } else {
        val chip = ScannerWords.chip(module.status)
        BlockHead(
            moduleTitle(module), "· ${module.unit}", line,
            badge = chip.text to ScannerWords.tone(chip.look), dtc = module.dtc.records.size.toString(),
        )
    }
}

@Composable
private fun DtcFooter(module: ModuleView, back: FooterButton, read: () -> Unit) {
    val notes = if (module.obd != null) listOfNotNull(module.dtc.source?.let { FooterNote.Words(it) }) else dtcNotes(module.dtc)
    ScanFooter(listOf(back, FooterButton("Прочитать DTC", true, read)), notes)
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
private fun IdentFooter(ident: IdentView, back: FooterButton, reread: () -> Unit) {
    ScanFooter(listOf(back, FooterButton("Прочитать снова", true, reread)), listOf(FooterNote.Words("22 F18A · F193 · F194 · F195", mono = true)))
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
internal fun DtcContent(module: ModuleView, halves: Halves = Halves.FULL) {
    val obd = module.obd
    when {
        obd != null && module.dtc.records.isEmpty() -> { ObdLists(obd, halves); Monitors(obd.monitors, halves) }
        obd != null -> { ObdFault(module.dtc.records, obd, halves); Monitors(obd.monitors, halves) }
        module.dtc.records.isNotEmpty() -> CodesWithStatus(module.dtc.records)
        else -> MaskAsks(module.dtc, halves.leftX, halves.leftW)
    }
}

/**
 * A body block's records: code, failure type, the group, the status byte and its eight bits; the
 * chosen record's status explained under the last one, the bits' legend beside it. The column
 * heads stay; the rows and what is under them scroll between the heads and the footer, so a block
 * with eight codes reads as one with two (on the car 2026-10-20 the explanation stood over them).
 */
@Composable
private fun CodesWithStatus(records: List<DtcRow>) {
    val b = ScannerSpec.BodyDtc
    var chosen by remember { mutableIntStateOf(0) }
    val width = rememberWidths()
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(b.COLS, 24f, w)
    val rowsTop = b.TOP + b.HEAD + 1f
    val rowsH = records.size * (b.ROW_MIN + 1f)
    val below = maxOf(b.BELOW, rowsTop + rowsH + b.BELOW_GAP)
    Box(Modifier.fillMaxSize()) {
        HeadRow(cols, listOf(HeadLabel("КОД"), HeadLabel("ТИП ОТКАЗА (FTB)"), HeadLabel("ОПИСАНИЕ"), HeadLabel("СТАТУС")), b.TOP, b.HEAD, Type.COL_HEAD_TIGHT)
        ScannerWords.BITS.forEachIndexed { i, bit ->
            val (x, cw) = cols[4 + i]
            ScanText(bit, Type.BIT_HEAD, Ink.MUTE, Modifier.centreAt(x + cw / 2f, mid(b.TOP, b.HEAD, Type.BIT_HEAD)))
        }
        Column(
            Modifier.fillMaxSize()
                .padding(top = rowsTop.dp, bottom = ScannerSpec.Footer.HEIGHT.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // rows at their board offsets under the heads: y counts from the first row
            Box(Modifier.fillMaxWidth().height(rowsH.dp)) {
                records.forEachIndexed { r, d ->
                    val y = r * (b.ROW_MIN + 1f)
                    val h = b.ROW_MIN
                    Box(Modifier.at(24f, y).size(w.dp, h.dp).tap { chosen = r })
                    ScanText(d.code, Type.CODE, Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(y, h, Type.CODE)))
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
                    ScanText(ScannerWords.hex2(d.status), Type.CELL_NUM, Ink.TEXT, Modifier.at(cols[3].first, mid(y, h, Type.CELL_NUM)))
                    ScannerWords.BITS.indices.forEach { i ->
                        val (x, cw) = cols[4 + i]
                        StatusBit(on = (d.status shr i) and 1 == 1, supported = (d.availability shr i) and 1 == 1, Modifier.at(x + (cw - b.BIT) / 2f, y + (h - b.BIT) / 2f))
                    }
                    Rule(24f, y + h, w, Ink.HAIR)
                }
            }
            Spacer(Modifier.height((below - rowsTop - rowsH).dp))
            val e = ScannerWords.explain(records[chosen.coerceIn(0, records.lastIndex)].status)
            // as tall as the taller of the two: the legend's rows are placed, not stacked
            Box(Modifier.fillMaxWidth().heightIn(min = (ScannerWords.BITS.size * (Type.LEGEND.lh + b.LEGEND_ROW_GAP)).dp)) {
                Column(Modifier.at(24f, 0f)) {
                    ScanText(e.head, Type.COUNTER_LABEL, Ink.MUTE)
                    Spacer(Modifier.height(b.EXPLAIN_GAP.dp))
                    ScanParagraph(e.lead, Type.LEAD, Ink.WHITE, b.EXPLAIN_W)
                    Spacer(Modifier.height(b.EXPLAIN_GAP.dp))
                    ScanParagraph(e.body, Type.BODY, Ink.DIM, b.EXPLAIN_W)
                }
                ScannerWords.BITS.forEachIndexed { i, bit ->
                    val ly = i * (Type.LEGEND.lh + b.LEGEND_ROW_GAP)
                    ScanText(bit, Type.LEGEND_KEY, Ink.DIM, Modifier.at(b.LEGEND_X, ly))
                    ScanText("бит $i · ${ScannerWords.BIT_WORDS[i]}", Type.LEGEND, Ink.MUTE, Modifier.at(b.LEGEND_X + b.LEGEND_KEY + b.LEGEND_GAP, ly))
                }
            }
            Spacer(Modifier.height(b.BELOW_GAP.dp))
        }
    }
}

/** One status bit: lit and glowing, dark, or only outlined where the ECU does not report it. */
@Composable
private fun StatusBit(on: Boolean, supported: Boolean, modifier: Modifier) {
    val b = ScannerSpec.BodyDtc
    Canvas(modifier.size(b.BIT.dp)) {
        val r = b.BIT_R.dp.toPx()
        when {
            !supported -> edge(Ink.LINE4, b.BIT_R)
            on -> {
                glowUnder(Ink.BIT_GLOW, 6f) { c, p -> c.drawRoundRect(0f, 0f, size.width, size.height, r, r, p) }
                drawRoundRect(ink(Ink.BLUE), cornerRadius = CornerRadius(r, r))
            }
            else -> drawRoundRect(ink(Ink.BIT_OFF), cornerRadius = CornerRadius(r, r))
        }
    }
}

/** What was asked of the memory and with which mask, when nothing came back (`brakes()` left). */
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
        dtc.quality == DataQuality.COMPLETE_EMPTY ->
            "Записей нет"
        else -> dtc.quality.label.replaceFirstChar { it.uppercase() }
    }
    Column(
        Modifier.at(x, y + k.PLATE_GAP).width(w.dp).plate(Ink.PLATE, Ink.RULE, k.PLATE_R)
            .padding(horizontal = k.PLATE_PAD_X.dp, vertical = k.PLATE_PAD_Y.dp),
    ) {
        ScanText("МАСКА ${String.format("%02X", MASK)} · БИТЫ СТАТУСА", Type.COUNTER_LABEL, Ink.MUTE)
        Spacer(Modifier.height(k.PLATE_GAP_IN.dp))
        Row(Modifier.width(iw.dp).height(k.BITS_H.dp)) {
            ScannerWords.BITS.forEachIndexed { i, bit ->
                if (i > 0) Spacer(Modifier.width(k.BITS_GAP.dp))
                val on = (MASK shr i) and 1 == 1
                Box(
                    Modifier.weight(1f).height(k.BITS_H.dp).plate(if (on) Ink.BLUE_TINT else Ink.PID_FILL, if (on) Ink.BLUE_EDGE else null, k.BITS_R),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ScanText(bit, Type.BIT, if (on) Ink.BLUE_INK else Ink.FAINT2, Modifier.padding(top = ((k.BITS_H - Type.BIT.lh) / 2f).dp))
                }
            }
        }
        Spacer(Modifier.height(k.PLATE_GAP_IN.dp))
        ScanParagraph(result, Type.BODY_STRONG, Ink.WHITE, iw)
    }
}

/** The mask the product reads memories with: failed now or confirmed (architecture, 5.2). */
private const val MASK = 0x09

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
        ScanText(a.name, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, mid(y, h, Type.CELL)), maxWidth = cols[0].second - 12f)
        ScanText(a.service, Type.CELL_MONO, Ink.DIM, Modifier.at(cols[1].first, mid(y, h, Type.CELL_MONO)))
        val st = if (num) Type.CELL_NUM else Type.NORM_MONO
        ScanText(a.count, st, if (num) Ink.WHITE else Ink.FAINT, Modifier.endAt(cols[2].first + cols[2].second - 12f, mid(y, h, st)))
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
private fun ObdFault(records: List<DtcRow>, obd: ObdView, x: Halves) {
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
                Modifier.fillMaxWidth().heightIn(min = f.ROW_MIN.dp).plate(if (hot) Ink.ORANGE_ROW else null)
                    .padding(vertical = f.ROW_PAD.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(cols[0].second.dp).padding(start = 12.dp)) {
                    ScanText(r.code, Type.CODE_FAULT, if (hot) Ink.ORANGE_INK else Ink.WHITE)
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
                        ScanText(a.count, Type.LIST_COUNT, if (a.count == "0") Ink.DIM2 else Ink.ORANGE_INK)
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

private val MONITOR = mapOf(
    MonitorState.READY to ("ГОТОВ" to ScannerSpec.Tones.READY),
    MonitorState.NOT_READY to ("НЕ ГОТОВ" to ScannerSpec.Tones.WAITING),
    MonitorState.UNSUPPORTED to ("НЕ ПОДДЕРЖ." to ScannerSpec.Tones.ABSENT),
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
        val (label, tone) = MONITOR.getValue(m.state)
        Badge(label, tone, modifier = Modifier.at(cols[2].first, y + (h - ScannerSpec.Badge.H) / 2f))
        Rule(x.rightX, y + h, x.rightW, Ink.HAIR)
        y += h + 1f
    }
}

/* ---------------------------------------------------------------- live */

/** An OBD responder's live data (`liveTable()`): the block's columns across the window, the rail's beside it. */
@Composable
internal fun LiveTable(rows: List<LiveRow>, chartRange: ClosedFloatingPointRange<Float>?, colSpec: List<Float>, onParam: ((ParamKey) -> Unit)? = null) {
    val e = ScannerSpec.EcuLive
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(colSpec, 24f, w)
    HeadRow(
        cols,
        listOf(
            HeadLabel("PID"), HeadLabel("ПАРАМЕТР"), HeadLabel("ЗНАЧЕНИЕ", end = true, pad = 12f), HeadLabel("ЕД."),
            HeadLabel("МИН", end = true, pad = 12f), HeadLabel("МАКС", end = true, pad = 12f), HeadLabel("НОРМА"), HeadLabel("ГРАФИК · 60 С"),
        ),
        e.TOP, e.HEAD,
    )
    var y = e.TOP + e.HEAD + 1f
    rows.forEach { r ->
        val h = e.ROW
        fun end(i: Int) = cols[i].first + cols[i].second - 12f
        // a row with a key opens its parameter alone; the row itself draws nothing more
        val key = r.key
        if (key != null && onParam != null) Box(Modifier.at(24f, y).size(w.dp, h.dp).tap { onParam(key) })
        ScanText(ScannerWords.pid(r.pid), Type.NORM_MONO, Ink.FAINT, Modifier.at(cols[0].first + 12f, mid(y, h, Type.NORM_MONO)))
        ScanRuns(
            listOf(Triple("${r.name} ", Type.CELL, Ink.WHITE), Triple(r.abbreviation, Type.CELL_ASIDE, Ink.FAINT)),
            Modifier.at(cols[1].first, mid(y, h, Type.CELL)), maxWidth = cols[1].second,
        )
        // a value stays in its column, whatever its length
        ScanText(r.value, Type.VALUE, if (r.outOfNorm) Ink.ORANGE_INK else Ink.WHITE, Modifier.endAt(end(2), mid(y, h, Type.VALUE)), maxWidth = cols[2].second - 12f)
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[3].first, mid(y, h, Type.UNIT)))
        ScanText(r.min, Type.CELL_MONO, Ink.DIM2, Modifier.endAt(end(4), mid(y, h, Type.CELL_MONO)))
        ScanText(r.max, Type.CELL_MONO, Ink.DIM2, Modifier.endAt(end(5), mid(y, h, Type.CELL_MONO)))
        ScanText(r.norm, Type.NORM_MONO, Ink.FAINT, Modifier.at(cols[6].first, mid(y, h, Type.NORM_MONO)), maxWidth = cols[6].second - 8f)
        if (r.samples.isNotEmpty()) Spark(r, chartRange, minOf(e.SPARK_W, cols[7].second - 8f), Modifier.at(cols[7].first, y + (h - e.SPARK_H) / 2f))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

/** The last 60 s on the car's clock: x by time, a gap where a reading is missing, never interpolated. */
@Composable
private fun Spark(r: LiveRow, range: ClosedFloatingPointRange<Float>?, width: Float, modifier: Modifier) {
    val e = ScannerSpec.EcuLive
    Canvas(modifier.size(width.dp, e.SPARK_H.dp)) {
        val last = r.samples.last().atNanos
        val span = 60_000_000_000L
        val values = r.samples.mapNotNull { it.value }
        if (values.isEmpty()) return@Canvas
        val lo = range?.start ?: values.min()
        val hi = range?.endInclusive ?: values.max()
        val two = 2.dp.toPx()
        val inner = size.height - 2f * two
        val path = Path()
        var open = false
        r.samples.forEach { s ->
            val v = s.value
            if (v == null) { open = false; return@forEach }
            val t = ((s.atNanos - (last - span)).toFloat() / span).coerceIn(0f, 1f)
            val n = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0f, 1f) else 0.5f
            val x = t * size.width
            val y = size.height - two - n * inner
            if (open) path.lineTo(x, y) else path.moveTo(x, y)
            open = true
        }
        val sw = e.SPARK_STROKE.dp.toPx()
        glowUnder(Ink.TRACE_GLOW, e.SPARK_GLOW * 2f) { c, p ->
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = sw
            p.strokeJoin = android.graphics.Paint.Join.ROUND
            c.drawPath(path.asAndroidPath(), p)
        }
        drawPath(path, ink(Ink.BLUE), style = Stroke(sw, join = StrokeJoin.Round))
    }
}

/** «Выбор PID»: up to four parameters polled first. No board yet: the scanner's plate and rows. */
@Composable
internal fun PidPicker(rows: List<LiveRow>, focus: List<Int>, onDone: (List<Int>) -> Unit, onCancel: () -> Unit) {
    var chosen by remember { mutableStateOf(focus) }
    Box(Modifier.fillMaxSize().plate(0xB3000000.toInt()).tap(onCancel), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).plate(Ink.BAR, Ink.RULE, 8f).tap {}.padding(16.dp)) {
            ScanText("ВЫБОР PID · ДО ЧЕТЫРЁХ, ОПРАШИВАЮТСЯ ПЕРВЫМИ", Type.COUNTER_LABEL, Ink.MUTE)
            Spacer(Modifier.height(8.dp))
            rows.forEach { r ->
                val on = r.pid in chosen
                Box(
                    Modifier.fillMaxWidth().height(32.dp).plate(if (on) Ink.BLUE_FILL else null, null, 4f).tap {
                        chosen = if (on) chosen - r.pid else if (chosen.size < 4) chosen + r.pid else chosen
                    },
                ) {
                    ScanText(ScannerWords.pid(r.pid), Type.NORM_MONO, if (on) Ink.BLUE_SOFT else Ink.FAINT, Modifier.at(12f, mid(0f, 32f, Type.NORM_MONO)))
                    ScanRuns(
                        listOf(Triple("${r.name} ", Type.CELL, Ink.WHITE), Triple(r.abbreviation, Type.CELL_ASIDE, Ink.FAINT)),
                        Modifier.at(56f, mid(0f, 32f, Type.CELL)),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                ScanButton("Готово", true, { onDone(chosen) })
                Spacer(Modifier.width(10.dp))
                ScanButton("Отмена", false, onCancel)
            }
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

@Composable
internal fun IdentContent(ident: IdentView) {
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
