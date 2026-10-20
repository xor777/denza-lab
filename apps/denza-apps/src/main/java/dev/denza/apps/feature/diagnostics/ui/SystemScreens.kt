package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.CompareCell
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.HvUnit
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.SignalRow
import dev.denza.apps.feature.diagnostics.scan.SystemScreen
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * The system screens: the HV system as a schematic (`hv()`), the HV battery's readings (`bms()`),
 * the drives side by side (`drives()`), the brakes' ESP/IPB beside its OBD interface (`brakes()`)
 * and the driver-assistance modules side by side (`adas()`).
 */

/** What a system screen is called in the crumbs, and its tabs. */
internal object Systems {
    fun crumb(screen: SystemScreen): String = when (screen) {
        SystemScreen.HV -> "Высоковольтная система"
        SystemScreen.PACK -> "Система управления ВВБ"
        SystemScreen.DRIVES -> "Электропривод"
        SystemScreen.BRAKES -> "Тормозная система"
        SystemScreen.ADAS -> "Ассистенты водителя"
    }

    fun tabs(screen: SystemScreen): List<String> = when (screen) {
        SystemScreen.HV -> listOf("Схема", "ВВБ · данные", "Приводы · данные")
        SystemScreen.PACK -> listOf("DTC", "Потоковые данные", "Флаги GB/T 32960", "Идентификация")
        SystemScreen.DRIVES -> listOf("Сравнение моторов", "DTC", "Идентификация")
        SystemScreen.BRAKES -> listOf("Ошибки DTC", "Идентификация")
        SystemScreen.ADAS -> listOf("Сравнение блоков", "DTC", "Идентификация")
    }

    /** The tab a tap on the system opens: the battery's readings, every other system's first. */
    fun firstTab(screen: SystemScreen): Int = if (screen == SystemScreen.PACK) 1 else 0

    /*
     * What the battery table's marks say. The rows carry the marks (`кОм¹`, `Ток ВВБ²`) but the model
     * has no place for their footnotes yet, so the screen keeps them and shows each only when a row
     * carries its mark.
     */
    private const val UNIT_NOTE = "¹ единица по GB/T 32960 (кОм), на этой машине не подтверждена"
    private const val SIGN_NOTE = "² знак тока не подтверждён"

    fun packNotes(rows: List<SignalRow>): List<FooterNote> {
        val marks = rows.joinToString(" ") { it.name + it.unit }
        val said = listOfNotNull(UNIT_NOTE.takeIf { '¹' in marks }, SIGN_NOTE.takeIf { '²' in marks })
        return if (said.isEmpty()) emptyList() else listOf(FooterNote.Words(said.joinToString(" · ")))
    }
}

private val BACK_LABEL = "‹ Автоскан"

@Composable
internal fun SystemPage(
    model: DiagnosticsModel,
    screen: SystemScreen,
    tab: Int,
    header: @Composable () -> Unit,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
    onOpen: (Target) -> Unit,
    actions: DiagnosticsActions,
    module: (Int) -> ModuleView?,
) {
    val back = FooterButton(BACK_LABEL, false, onBack)
    val lead = ScannerWords.leadModule(model, screen)?.let(module)
    val tabs = Systems.tabs(screen)
    val refresh = { requests: List<Int?> -> requests.filterNotNull().forEach(actions::readModule) }
    when (screen) {
        SystemScreen.PACK -> {
            val rows = model.systems.pack
            val title = lead?.let(::moduleTitle) ?: Systems.crumb(screen)
            val counters = listOf(
                Counter("SOC", ScannerWords.signal(rows, "(SOC)") ?: "—"),
                Counter("SOH", ScannerWords.signal(rows, "(SOH)") ?: "—"),
                Counter("DTC", lead?.dtc?.records?.size?.toString() ?: "—"),
            )
            val ident = lead != null && tab == 3
            val dtc = lead != null && tab == 0
            ScannerPage(footer = {
                when {
                    dtc -> ScanFooter(listOf(back, FooterButton("Прочитать DTC", true) { refresh(listOf(lead!!.request)) }), dtcNotes(lead!!.dtc))
                    ident -> ScanFooter(listOf(back, FooterButton("Прочитать снова", true) { refresh(listOf(lead!!.request)) }), listOf(FooterNote.Words("22 F194 · F195 · только чтение", mono = true)))
                    else -> ScanFooter(listOf(back, FooterButton("Обновить", true) { refresh(listOf(lead?.request)) }), Systems.packNotes(rows))
                }
            }) {
                header()
                BlockHead(title, lead?.let { "· ${it.unit}" } ?: "", lead?.line ?: "", counters)
                ScanTabs(tabs, tab, onTab)
                when {
                    dtc -> DtcContent(lead!!)
                    ident -> IdentContent(lead!!.ident)
                    tab == 1 -> PackTable(rows)
                    else -> Pending("Флаги GB/T 32960 ещё не читаются: их разбор не подтверждён на этой машине.")
                }
            }
        }
        SystemScreen.DRIVES -> {
            val view = model.systems.drives
            val requests = view.columns.map { it.request }
            val volts = model.systems.hv.busVoltages.map { it.removeSuffix(" В") }
            ScannerPage(footer = {
                ScanFooter(listOf(back, FooterButton("Обновить", true) { refresh(requests) }), listOf(FooterNote.Words(view.notes.joinToString(" · "))))
            }) {
                header()
                BlockHead(
                    "Электропривод", ScannerWords.drivesAside(view.columns), ScannerWords.drivesLine(view.columns),
                    listOf(
                        Counter("DTC", ScannerWords.dtcSum(model, requests)),
                        Counter("ШИНА П, В", volts.getOrElse(0) { "—" }),
                        Counter("ШИНА З, В", volts.getOrElse(1) { "—" }),
                    ),
                )
                ScanTabs(tabs, tab, onTab)
                if (tab == 0) DrivesTable(view) else ModuleRows(requests.mapNotNull { r -> r?.let { ScannerWords.row(model, it) } }, ScannerSpec.W - 48f, onOpen)
            }
        }
        SystemScreen.HV -> {
            val hv = model.systems.hv
            val pack = ScannerWords.leadModule(model, SystemScreen.PACK)
            val requests = listOf(pack) + hv.units.map { it.address.toIntOrNull(16) }
            ScannerPage(footer = {
                when (tab) {
                    1 -> ScanFooter(listOf(back, FooterButton("Обновить", true) { refresh(listOf(pack)) }), Systems.packNotes(model.systems.pack))
                    2 -> ScanFooter(listOf(back, FooterButton("Обновить", true) { refresh(model.systems.drives.columns.map { it.request }) }), listOf(FooterNote.Words(model.systems.drives.notes.joinToString(" · "))))
                    else -> ScanFooter(
                        listOf(FooterButton("Обновить", true) { refresh(requests) }),
                        legend = listOf(Swatch.OK to "блок отвечает по UDS", Swatch.UNREAD to "ответил, ошибки не читали", Swatch.NONE to "адрес не найден", Swatch.MECH to "механическая связь"),
                        legendGap = 18f,
                        notes = listOf(FooterNote.Words("Схема функциональная · ≈ по сокращению")),
                    )
                }
            }) {
                header()
                BlockHead(
                    "Высоковольтная система", "· ВВБ, привод, мотор-генератор", ScannerWords.hvLine(model, pack),
                    listOf(
                        Counter("U ВВБ", ScannerWords.signal(model.systems.pack, "Напряжение ВВБ") ?: "—"),
                        Counter("SOC", ScannerWords.signal(model.systems.pack, "(SOC)") ?: "—"),
                        Counter("DTC", ScannerWords.dtcSum(model, requests)),
                    ),
                )
                ScanTabs(tabs, tab, onTab)
                when (tab) {
                    1 -> PackTable(model.systems.pack)
                    2 -> DrivesTable(model.systems.drives)
                    else -> HvScheme(model, pack, onOpen)
                }
            }
        }
        SystemScreen.BRAKES -> {
            val obdRequest = model.systems.brakeModules.getOrNull(1)
            val obd = obdRequest?.let(module)
            ScannerPage(footer = {
                if (tab == 1) {
                    ScanFooter(listOf(back, FooterButton("Прочитать снова", true) { refresh(listOf(lead?.request)) }), listOf(FooterNote.Words("22 F194 · F195 · только чтение", mono = true)))
                } else {
                    ScanFooter(listOf(back, FooterButton("Прочитать DTC", true) { refresh(model.systems.brakeModules) }), lead?.let { dtcNotes(it.dtc) } ?: emptyList())
                }
            }) {
                header()
                if (lead != null) {
                    BlockHead(
                        moduleTitle(lead), "· ${lead.unit}", lead.line,
                        listOf(
                            Counter("DTC", lead.dtc.records.size.toString()),
                            Counter("МАСКА", "09"),
                            Counter("MIL", obd?.obd?.mil?.let(ScannerWords::mil) ?: "—", on = obd?.obd?.mil == MilState.ON),
                        ),
                    )
                }
                ScanTabs(tabs, tab, onTab)
                if (lead != null) {
                    if (tab == 1) IdentContent(lead.ident) else {
                        val d = ScannerSpec.EcuDtc
                        if (lead.dtc.records.isEmpty()) MaskAsks(lead.dtc, d.LEFT_X, d.LEFT_W) else DtcContent(lead)
                        if (obd != null) ObdInterface(obd, lead.request)
                    }
                }
            }
        }
        SystemScreen.ADAS -> {
            val view = model.systems.adas
            val requests = view.columns.map { it.request }
            ScannerPage(footer = {
                ScanFooter(
                    listOf(back, FooterButton("Прочитать DTC", true) { refresh(requests) }),
                    listOf(
                        FooterNote.Words("≈ — по метке штатного менеджера версий"), FooterNote.Divider,
                        FooterNote.Words("22 F194 · 19 01 09 · 19 02 09 · только чтение", mono = true),
                    ),
                )
            }) {
                header()
                BlockHead(
                    "Ассистенты водителя", "· контроллер, радары, парковка", ScannerWords.blocksLine(view.columns),
                    listOf(Counter("DTC", ScannerWords.dtcSum(model, requests)), Counter("ПРОЧИТАНО", ScannerWords.readOf(model, view))),
                    counterW = ScannerSpec.Adas.COUNTER_W,
                )
                ScanTabs(tabs, tab, onTab)
                if (tab == 0) AdasTable(model, view) else ModuleRows(requests.mapNotNull { r -> r?.let { ScannerWords.row(model, it) } }, ScannerSpec.W - 48f, onOpen)
            }
        }
    }
}

/** A tab with nothing to show yet: one sentence where the table would stand. */
@Composable
private fun Pending(text: String) {
    ScanText(text, Type.CELL, Ink.DIM, Modifier.at(36f, 184f))
}

/* ---------------------------------------------------------------- pack */

@Composable
internal fun PackTable(rows: List<SignalRow>) {
    val b = ScannerSpec.Bms
    val w = ScannerSpec.W - 48f
    val cols = ScannerSpec.columns(b.COLS, 24f, w)
    HeadRow(
        cols,
        listOf(HeadLabel("СИГНАЛ"), HeadLabel("ПАРАМЕТР"), HeadLabel("ЗНАЧЕНИЕ", end = true, pad = b.VALUE_PAD), HeadLabel("ЕД."), HeadLabel("НОРМА"), HeadLabel("ОЦЕНКА")),
        b.TOP, b.HEAD,
    )
    var y = b.TOP + b.HEAD + 1f
    rows.forEach { r ->
        val h = b.ROW
        ScanText(r.source, Type.SIG, Ink.FAINT2, Modifier.at(cols[0].first + 12f, mid(y, h, Type.SIG)))
        ScanText(r.name, Type.CELL14, Ink.WHITE, Modifier.at(cols[1].first, mid(y, h, Type.CELL14)), maxWidth = cols[1].second)
        ScanText(r.value, Type.VALUE_BMS, if (r.stale) Ink.FAINT else Ink.WHITE, Modifier.endAt(cols[2].first + cols[2].second - b.VALUE_PAD, mid(y, h, Type.VALUE_BMS)))
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[3].first, mid(y, h, Type.UNIT)))
        ScanText(r.norm, Type.NORM, Ink.FAINT, Modifier.at(cols[4].first, mid(y, h, Type.NORM)), maxWidth = cols[4].second)
        val mark = when {
            r.stale -> "УСТАРЕЛО" to ScannerSpec.Tones.UNREAD
            r.ok == true -> "OK" to ScannerSpec.Tones.OK
            r.ok == false -> "ВНЕ НОРМЫ" to ScannerSpec.Tones.ON
            else -> null
        }
        if (mark != null) Badge(mark.first, mark.second, BadgeSize.SMALL, Modifier.at(cols[5].first, y + (h - ScannerSpec.Badge.SMALL_H) / 2f))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

/* -------------------------------------------------------------- drives */

@Composable
internal fun DrivesTable(view: CompareView) {
    val d = ScannerSpec.Drives
    val w = ScannerSpec.W - 48f
    val cols = ScannerSpec.columns(d.COLS, 24f, w)
    val h0 = d.HEAD
    ScanText("ПАРАМЕТР", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[0].first + 12f, mid(d.TOP, h0, Type.COL_HEAD)))
    view.columns.take(4).forEachIndexed { i, c ->
        val (x, cw) = cols[1 + i]
        val top = d.TOP + (h0 - Type.UNIT_NAME.lh - 2f - Type.UNIT_TAG.lh) / 2f
        ScanText(c.name, Type.UNIT_NAME, Ink.WHITE, Modifier.endAt(x + cw - d.PAD, top))
        ScanText(ScannerWords.columnTag(c), Type.UNIT_TAG, Ink.FAINT, Modifier.endAt(x + cw - d.PAD, top + Type.UNIT_NAME.lh + 2f))
    }
    ScanText("ЕД.", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[5].first, mid(d.TOP, h0, Type.COL_HEAD)))
    Rule(24f, d.TOP + h0, w)
    var y = d.TOP + h0 + 1f
    view.rows.forEach { r ->
        val h = d.ROW
        val top = y + (h - Type.CELL.lh - 2f - Type.ROW_SIG.lh) / 2f
        ScanText(r.name, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, top), maxWidth = cols[0].second - 12f)
        ScanText(r.source, Type.ROW_SIG, Ink.FAINT3, Modifier.at(cols[0].first + 12f, top + Type.CELL.lh + 2f), maxWidth = cols[0].second - 12f)
        r.cells.take(4).forEachIndexed { i, v ->
            val (x, cw) = cols[1 + i]
            val st = if (v.text.length > 12) Type.DRIVE_SMALL else Type.DRIVE_CELL
            ScanText(v.text, st, if (v.muted) Ink.FAINT2 else Ink.WHITE, Modifier.endAt(x + cw - d.PAD, mid(y, h, st)))
        }
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[5].first, mid(y, h, Type.UNIT)))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

/* ---------------------------------------------------------------- adas */

/** A comparison cell's type and ink: by the row it stands in, as the page set them. */
private fun adasLook(row: String, cell: CompareCell): Pair<ScannerSpec.Style, Int> {
    val size = when {
        cell.muted && cell.text != "—" -> Type.ADAS12_5
        row.startsWith("DTC") -> Type.ADAS16
        row.startsWith("Запрос") || row.startsWith("Формат") -> Type.ADAS13
        "F194" in row -> Type.ADAS11
        "кэше" in row -> Type.ADAS12_5
        else -> Type.ADAS14
    }
    val inkOf = when {
        cell.muted -> Ink.FAINT2
        cell.warn -> Ink.ORANGE_INK
        row.startsWith("Запрос") -> Ink.DIM
        cell.shared && "F194" in row -> Ink.BLUE_INK
        cell.shared && "кэше" in row -> Ink.DIM
        else -> Ink.WHITE
    }
    return size to inkOf
}

@Composable
private fun AdasTable(model: DiagnosticsModel, view: CompareView) {
    val a = ScannerSpec.Adas
    val w = ScannerSpec.W - 48f
    val cols = ScannerSpec.columns(a.COLS, 24f, w)
    ScanText("ПАРАМЕТР", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[0].first + 12f, mid(a.TOP, a.HEAD, Type.COL_HEAD)))
    view.columns.take(cols.size - 1).forEachIndexed { i, c ->
        val (x, cw) = cols[1 + i]
        val top = a.TOP + (a.HEAD - Type.ABBR.lh - 2f - Type.ABBR_NAME.lh) / 2f
        val read = c.request?.let { ScannerWords.row(model, it) }?.let { ScannerWords.read(it.status) } == true
        ScanText(c.abbreviation, Type.ABBR, if (read) Ink.WHITE else Ink.INK2, Modifier.at(x + a.PAD, top))
        ScanText(c.name, Type.ABBR_NAME, Ink.MUTE, Modifier.at(x + a.PAD, top + Type.ABBR.lh + 2f), maxWidth = cw - a.PAD)
    }
    Rule(24f, a.TOP + a.HEAD, w)
    var y = a.TOP + a.HEAD + 1f
    view.rows.forEach { r ->
        val h = a.ROW
        ScanText(r.name, Type.KEY, Ink.DIM2, Modifier.at(cols[0].first + 12f, mid(y, h, Type.KEY)))
        r.cells.take(cols.size - 1).forEachIndexed { i, v ->
            val (x, cw) = cols[1 + i]
            if (v.shared) Box(Modifier.at(x, y).size(cw.dp, h.dp).plate(Ink.BLUE_SHARE))
            val (st, color) = adasLook(r.name, v)
            ScanText(v.text, st, color, Modifier.at(x + a.PAD, mid(y, h, st)), maxWidth = cw - a.PAD)
        }
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
    if (view.notes.isEmpty()) return
    val inner = w - 2f * a.NOTE_PAD_X
    val gaps = a.NOTE_GAP * (view.notes.size - 1)
    val nw = (inner - gaps) / view.notes.size
    Row(
        Modifier.at(24f, a.NOTE_TOP).width(w.dp).plate(Ink.PLATE, Ink.RULE, a.NOTE_R)
            .padding(horizontal = a.NOTE_PAD_X.dp, vertical = a.NOTE_PAD_Y.dp),
    ) {
        view.notes.forEachIndexed { i, note ->
            if (i > 0) Spacer(Modifier.width(a.NOTE_GAP.dp))
            val (lead, body) = ScannerWords.leadAndBody(note)
            Column(Modifier.width(nw.dp)) {
                ScanText(lead, Type.PLATE_TEXT, Ink.TEXT, maxWidth = nw)
                Spacer(Modifier.height(3.dp))
                if (body.isNotEmpty()) ScanParagraph(body, Type.SMALL, Ink.DIM2, nw)
            }
        }
    }
}

/* -------------------------------------------------------------- brakes */

/** The brakes' OBD interface, its own address beside the ESP/IPB module. */
@Composable
private fun ObdInterface(module: ModuleView, beside: Int) {
    val d = ScannerSpec.EcuDtc
    val k = ScannerSpec.Brakes
    val cols = ScannerSpec.columns(k.OBD_COLS, d.RIGHT_X, d.RIGHT_W)
    HeadRow(cols, listOf(HeadLabel("ИНТЕРФЕЙС OBD · ${ScannerWords.route(module.request)}")), k.TOP, k.HEAD)
    val y = KeyRows(module.ident.own, cols, k.TOP + k.HEAD + 1f, k.OBD_ROW, d.RIGHT_X, d.RIGHT_W)
    Column(
        Modifier.at(d.RIGHT_X, y + k.ASIDE_GAP).width(d.RIGHT_W.dp).plate(Ink.PLATE, Ink.RULE, 6f)
            .padding(horizontal = k.ASIDE_PAD_X.dp, vertical = k.ASIDE_PAD_Y.dp),
    ) {
        ScanText("Один это блок с ${ScannerWords.hex(beside)} или два — не установлено.", Type.PLATE_TEXT, Ink.TEXT)
        Spacer(Modifier.height(k.ASIDE_GAP2.dp))
        ScanParagraph(
            "В автоскане ${ScannerWords.hex(module.request)} — отдельный адрес, а не второй блок тормозов.",
            Type.SMALL, Ink.DIM2, d.RIGHT_W - 2f * k.ASIDE_PAD_X,
        )
    }
}

/* ------------------------------------------------------------------ hv */

private data class UnitLook(val fill: Int, val line: Int, val dash: Boolean, val ink: Int, val sub: Int, val state: Int)

private fun unitLook(status: ModuleStatus): UnitLook = when (ScannerWords.chip(status).look) {
    ScannerWords.Look.OK -> UnitLook(Ink.TILE_OK, Ink.BLUE_EDGE, false, Ink.WHITE, Ink.DIM, Ink.BLUE_SOFT)
    ScannerWords.Look.HIST -> UnitLook(Ink.TILE_HIST, Ink.SEP, false, Ink.WHITE, Ink.DIM, Ink.INK4)
    ScannerWords.Look.ON -> UnitLook(Ink.TILE_HIST, Ink.ORANGE, false, Ink.WHITE, Ink.DIM, Ink.ORANGE_INK)
    ScannerWords.Look.UNREAD -> UnitLook(Ink.TILE_UNREAD, Ink.LINE3, false, Ink.INK2, Ink.DIM, Ink.DIM2)
    ScannerWords.Look.NONE -> UnitLook(Ink.GROUND, Ink.LINE4, true, Ink.DIM, Ink.MUTE, Ink.FAINT)
}

@Composable
private fun HvScheme(model: DiagnosticsModel, packRequest: Int?, onOpen: (Target) -> Unit) {
    val v = ScannerSpec.Hv
    val hv = model.systems.hv
    Canvas(Modifier.fillMaxSize()) {
        fun stroke(d: String, color: Int, width: Float, dash: FloatArray? = null) {
            val p = PathParser().parsePathString(d).toPath()
            val s = density
            scaleTo(s) {
                drawPath(
                    p, ink(color),
                    style = Stroke(width, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }),
                )
            }
        }
        stroke(v.CONTACTOR_WIRE, Ink.WIRE, 2.4f)
        stroke(v.CONTACTOR_BLADE, Ink.WIRE, 2f)
        v.CONTACTOR_PINS.forEach { (x, y) -> drawCircle(ink(Ink.TEXT), v.CONTACTOR_PIN.dp.toPx(), Offset(x.dp.toPx(), y.dp.toPx())) }
        stroke(v.TRUNK, Ink.WIRE, 3f)
        stroke(v.TAPS, Ink.WIRE2, 2f)
        stroke(v.SHAFTS, Ink.WIRE3, 1.6f)
        stroke(v.COUPLING, Ink.WIRE4, 1.4f)
        v.MACHINES.forEach { m ->
            val c = Offset(m.x.dp.toPx(), m.y.dp.toPx())
            val r = v.MACHINE_R.dp.toPx()
            drawCircle(ink(Ink.GROUND), r, c)
            val d = density
            drawCircle(
                ink(if (m.live) Ink.WIRE5 else Ink.WIRE4), r, c,
                style = Stroke(1.6f * d, pathEffect = if (m.live) null else PathEffect.dashPathEffect(floatArrayOf(4f * d, 3f * d))),
            )
        }
        stroke(v.FEEDS, Ink.WIRE3, 1.6f, floatArrayOf(5f, 4f))
    }
    v.MACHINES.forEach { m ->
        ScanText(m.glyph, Type.GLYPH, if (m.live) Ink.TEXT else Ink.DIM, Modifier.centreAt(m.x, m.y + 5f - Type.GLYPH.baseline))
    }
    val (lx, ly, lw) = v.CONTACTOR_LABEL
    ScanParagraph("главный контактор", Type.HV_LABEL, Ink.MUTE, lw, Modifier.at(lx, ly))
    ScanText("ШИНА DC", Type.COL_HEAD, Ink.MUTE, Modifier.endAt(v.BUS_LABEL_RIGHT, v.BUS_LABEL_Y))
    hv.busVoltages.take(v.VOLTS.size).forEachIndexed { i, t -> ScanText(t, Type.HV_VOLTS, Ink.TEXT, Modifier.at(v.VOLTS[i].first, v.VOLTS[i].second)) }

    // the pack
    val packRow = packRequest?.let { ScannerWords.row(model, it) }
    val chip = ScannerWords.chip(hv.packStatus)
    Box(
        Modifier.at(v.PACK_X, v.PACK_Y).size(v.PACK_W.dp, v.PACK_H.dp).plate(Ink.TILE_OK, Ink.BLUE_EDGE, v.PACK_R)
            .tap { onOpen(Target.System(SystemScreen.PACK)) },
    ) {
        val iw = v.PACK_W - 2f * v.PACK_PAD_X
        Column(Modifier.padding(horizontal = v.PACK_PAD_X.dp, vertical = v.PACK_PAD_Y.dp)) {
            val tb = maxOf(Type.PACK_TITLE.baseline, Type.PACK_TAG.baseline)
            Box(Modifier.width(iw.dp).height(Type.PACK_TITLE.lh.dp)) {
                ScanText("ВВБ", Type.PACK_TITLE, Ink.WHITE, baseline = tb)
                val tag = packRow?.let { "${it.abbreviation} · ${ScannerWords.hex(packRequest)}" } ?: ""
                ScanText(tag, Type.PACK_TAG, Ink.FAINT, Modifier.endAt(iw, 0f), baseline = tb)
            }
            Spacer(Modifier.height(v.PACK_GAP.dp))
            ScanText("${chip.text} · DTC ${ScannerWords.dtcSum(model, listOf(packRequest))}", Type.PACK_STATE, ScannerWords.tone(chip.look).ink)
            Spacer(Modifier.height(v.PACK_GAP.dp))
            val rb = maxOf(Type.PACK_KEY.baseline, Type.PACK_VALUE.baseline)
            hv.pack.forEach { f ->
                Row(Modifier.width(iw.dp).height(Type.PACK_VALUE.lh.dp)) {
                    Box(Modifier.weight(1f).padding(end = 10.dp)) { ScanText(f.key, Type.PACK_KEY, Ink.DIM2, baseline = rb) }
                    ScanText(f.value, Type.PACK_VALUE, Ink.WHITE, baseline = rb)
                }
                Spacer(Modifier.height(v.PACK_GAP.dp))
            }
        }
    }
    hv.units.forEach { u -> v.UNITS[u.abbreviation]?.let { (x, y) -> HvUnitBox(u, Modifier.at(x, y), onOpen) } }
    hv.machines.take(v.CAPTIONS.size).forEachIndexed { i, m ->
        val my = v.CAPTIONS[i]
        ScanText(m.key, Type.MACHINE, if (m.muted) Ink.TEXT else Ink.WHITE, Modifier.at(v.CAPTION, my))
        ScanText(m.value, Type.MACHINE_MONO, Ink.DIM, Modifier.at(v.CAPTION, my + Type.MACHINE.lh + 3f))
    }
}

@Composable
private fun HvUnitBox(u: HvUnit, modifier: Modifier, onOpen: (Target) -> Unit) {
    val v = ScannerSpec.Hv
    val k = unitLook(u.status)
    Box(modifier.size(v.UNIT_W.dp, v.UNIT_H.dp).plate(k.fill, k.line, v.UNIT_R, k.dash).tap(u.target?.let { { onOpen(it) } })) {
        val ux = 1f + v.UNIT_PAD_X
        val uw = v.UNIT_W - 2f - 2f * v.UNIT_PAD_X
        val top = (v.UNIT_H - Type.HV_ABBR.lh - Type.HV_NAME.lh - Type.HV_STATE.lh - 2f * v.UNIT_ROW_GAP) / 2f
        ScanText(u.abbreviation, Type.HV_ABBR, k.ink, Modifier.at(ux, top))
        ScanText(u.address, Type.TILE_ADDR, Ink.FAINT, Modifier.endAt(ux + uw, top))
        ScanText(u.name, Type.HV_NAME, k.sub, Modifier.at(ux, top + Type.HV_ABBR.lh + v.UNIT_ROW_GAP), maxWidth = uw)
        ScanText(u.state, Type.HV_STATE, k.state, Modifier.at(ux, top + Type.HV_ABBR.lh + Type.HV_NAME.lh + 2f * v.UNIT_ROW_GAP), maxWidth = uw)
    }
}

/** Draws in dp: the board's paths are written in window units. */
private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.scaleTo(s: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.transform.scale(s, s, Offset.Zero)
    block()
    drawContext.transform.scale(1f / s, 1f / s, Offset.Zero)
}
