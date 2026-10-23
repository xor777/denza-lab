package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.report.ReportInput
import dev.denza.apps.feature.diagnostics.report.ReportNames
import dev.denza.apps.feature.diagnostics.report.ReportOutcome
import dev.denza.apps.feature.diagnostics.report.ReportSection
import dev.denza.apps.feature.diagnostics.scan.Figures
import java.time.format.DateTimeFormatter
import dev.denza.apps.feature.diagnostics.scan.BlockRow
import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.BlocksGroup
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.IdentProcedure
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.MarkCount
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.ParamView
import dev.denza.apps.feature.diagnostics.scan.RailLine
import dev.denza.apps.feature.diagnostics.scan.RailPlace
import dev.denza.apps.feature.diagnostics.scan.RawLine
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.SystemPage
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import kotlin.math.abs

/**
 * The words the screens derive from [DiagnosticsModel] themselves, kept out of the composables so
 * they can be tested: a status byte in words and as a strip, a counter's figure and unit, the rail
 * before the session has one, a tile's word. Nothing here decodes bytes from the car - the session
 * has done that.
 */
internal object ScannerWords {

    /** The status bits of a DTC from bit 7 to bit 0, the order the hex and the binary read in (`STRIP`). */
    val STRIP = listOf("WIR", "TNCTOC", "TFSLC", "TNCSLC", "CDTC", "PDTC", "TFTOC", "TF")

    /** «0x08». */
    fun hex2(v: Int): String = "0x" + Integer.toHexString(v and 0xFF).uppercase().padStart(2, '0')

    /** «0000 1000». */
    fun bin8(v: Int): String = Integer.toBinaryString(v and 0xFF).padStart(8, '0').let { it.substring(0, 4) + " " + it.substring(4) }

    /**
     * A status byte in words (`statusWords()`): what is set of what the block reports - confirmed,
     * pending, failed since the reset, the lamp - and «сейчас (не) активен» last where it reports bit 0.
     */
    fun statusWords(status: Int, availability: Int): String {
        fun has(b: Int) = (availability shr b) and 1 == 1
        fun on(b: Int) = has(b) && (status shr b) and 1 == 1
        val w = mutableListOf<String>()
        if (on(3)) w += "подтверждён"
        if (on(2)) w += "ожидающий"
        if (on(5)) w += "был отказ после сброса"
        if (on(7)) w += "запрос лампы"
        if (has(0)) w += if (on(0)) "сейчас активен" else "сейчас не активен"
        return w.joinToString(" · ")
    }

    /** Under a status strip: what «·» means where a bit is not reported, and the availability. */
    fun availabilityNote(availability: Int): String =
        (if ((availability.inv() and 0xFF) != 0) "«·» — блок этот бит не сообщает · " else "") + "доступность " + hex2(availability)

    /** A record's kind from its status byte: testFailed now, else pending, else in history (`kindOf()`). */
    fun markOf(status: Int): Mark = when {
        status and 1 == 1 -> Mark.ACTIVE
        status and 4 == 4 -> Mark.PENDING
        else -> Mark.HISTORY
    }

    /** A block's sign by its state: its records' worst kind, or no answer; none for the rest. */
    fun mark(status: ModuleStatus): Mark? = when (status) {
        ModuleStatus.ACTIVE -> Mark.ACTIVE
        ModuleStatus.PENDING -> Mark.PENDING
        ModuleStatus.HISTORY -> Mark.HISTORY
        ModuleStatus.NO_REPLY -> Mark.NO_REPLY
        else -> null
    }

    /** A memory that was read: its counts mean something. */
    fun read(status: ModuleStatus): Boolean =
        status == ModuleStatus.OK || status == ModuleStatus.HISTORY || status == ModuleStatus.PENDING || status == ModuleStatus.ACTIVE

    /** Every record of a row, whatever its kind: «18»; null where the memory was not read. */
    fun records(row: ModuleRowView): Int? {
        val n = listOf(row.active, row.pending, row.history).mapNotNull { it.toIntOrNull() }
        return if (n.isEmpty()) null else n.sum()
    }

    /**
     * A counter's figure and its unit (`splitUnit()`): after the last space, a word with no digit in it -
     * «553 В» is «553» and «В», «1 250» stays whole.
     */
    fun splitUnit(value: String): Pair<String, String> {
        val i = value.lastIndexOf(' ')
        return if (i > 0 && value.substring(i + 1).none(Char::isDigit)) value.substring(0, i) to value.substring(i + 1) else value to ""
    }

    data class Link(val text: String, val dot: Int)

    fun link(state: LinkState): Link = when (state) {
        LinkState.OK -> Link("Связь OK", Ink.BLUE)
        LinkState.IDLE -> Link("Связь не открыта", Ink.FAINT)
        LinkState.CONNECTING -> Link("Подключение", Ink.FAINT)
        LinkState.NO_ACCESS -> Link("Нет доступа ADB", Ink.ORANGE)
        LinkState.BUSY -> Link("Канал занят", Ink.ORANGE)
        LinkState.NOT_PARKED -> Link("Только на стоянке", Ink.ORANGE)
        LinkState.LOST -> Link("Связь потеряна", Ink.ORANGE)
    }

    fun mil(state: MilState): String = when (state) {
        MilState.ON -> "ВКЛ"
        MilState.OFF -> "ВЫКЛ"
        MilState.UNKNOWN -> "—"
    }

    /** «7E0 → 7E8»: the request and the answer's address, request + 8. */
    fun route(request: Int?): String =
        if (request == null) "—" else "${hex(request)} → ${hex(request + 8)}"

    /** Words broken at spaces into lines no wider than [max] (`scanner.js` `wrap()`). */
    fun wrap(text: String, max: Float, width: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (w in text.split(' ')) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (line.isNotEmpty() && width(t) > max) { lines += line; line = w } else line = t
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** At most two lines: what does not fit the first goes on the second, which the screen cuts (`twoLines()`). */
    fun twoLines(text: String, max: Float, width: (String) -> Float): List<String> {
        val lines = wrap(text, max, width)
        return if (lines.size <= 2) lines else listOf(lines[0], lines.drop(1).joinToString(" "))
    }

    /**
     * A fault row's title and the line under it: the catalog's description over the failure type when
     * there is one, else the failure type over the note; an empty line is not drawn.
     */
    fun faultLines(row: DtcRow): Pair<String, String> {
        val title = row.description ?: row.failureName
        val under = listOf(if (row.description != null) row.failureName else "", row.note).filter { it.isNotEmpty() }
        return title to under.joinToString(" · ")
    }

    fun hex(v: Int): String = Integer.toHexString(v).uppercase()

    /** The fault view's four counts: the OBD lists and the UDS confirmed read, by service. */
    val SHORT_LISTS = linkedMapOf(
        "Mode 03" to "СОХРАНЁННЫЕ", "Mode 07" to "ОЖИДАЮЩИЕ", "Mode 0A" to "ПОСТОЯННЫЕ", "19 02 08" to "UDS ПОДТВ.",
    )

    /** A Mode 01 PID in hex; a fast car signal beside the PIDs (negative) has none. */
    fun pid(pid: Int): String = if (pid < 0) "—" else String.format("%02X", pid)

    /** WIR, bit 7: the record asks for the lamp. */
    fun asksLamp(status: Int): Boolean = (status shr 7) and 1 == 1

    /** A Mode 01 PID that is itself a bitmap of the next 32: 20, 40, 60, … */
    fun bitmapPid(pid: String): Boolean = pid.toIntOrNull(16)?.let { it % 0x20 == 0 } ?: false

    /**
     * The bitmaps that were asked: 00, then every bitmap PID the ECU listed that the identification
     * reads ([IdentProcedure.MAPS]); a map pointed to beyond those is listed and not asked.
     */
    fun pidsAsked(pids: List<String>): String {
        val maps = pids.filter(::bitmapPid).filter { it.toInt(16) in IdentProcedure.MAPS }
        return (listOf("00") + maps).joinToString(", ") { "01 $it" }
    }

    /** The live tab's line: the route, the mode, how many PIDs, the measured rate. */
    fun liveLine(request: Int, rows: Int, rate: String): String =
        "${route(request)} · Mode 01 · $rows PID · опрос $rate · график за 60 с"

    /** A comparison column's tag under its name: «FMCU · 7E3», or the abbreviation alone. */
    fun columnTag(c: CompareColumn): String = if (c.request == null) c.abbreviation else "${c.abbreviation} · ${hex(c.request)}"

    /** The row of [request] where the session put it. */
    fun row(model: DiagnosticsModel, request: Int): ModuleRowView? =
        model.groups.asSequence().flatMap { it.rows }.firstOrNull { it.request == request }

    /**
     * The rail: the session's, or before it has one «Сводка», «Блоки», every system that holds a block in
     * the catalog and «Журнал», without a mark.
     */
    fun railLines(model: DiagnosticsModel): List<RailLine> = model.railLines.ifEmpty {
        listOf(RailLine(RailPlace.Summary, "Сводка", null), RailLine(RailPlace.Blocks, "Блоки", null)) +
            CarSystem.entries.filter { it != CarSystem.OTHER }.map { RailLine(RailPlace.System(it), it.railName, null) } +
            RailLine(RailPlace.Journal, "Журнал", null)
    }

    /** A system's tab by its words: the pack's first is «Здоровье» since 7.3. */
    fun tabLabel(system: CarSystem, tab: SystemTab): String =
        if (system == CarSystem.PACK && tab == SystemTab.STATE) "Здоровье" else tab.label

    /** «Опросить снова», or what the running scan is doing instead. */
    fun scanButton(scan: ScanSummary): String = when (scan.phase) {
        ScanPhase.RUNNING -> "Опрос… ${scan.done}/${scan.total}"
        ScanPhase.PAUSED -> "Продолжить опрос"
        else -> "Опросить снова"
    }

    /** «Сводка» before its first scan: the head says what the scan is doing. */
    fun noSummary(scan: ScanSummary): Pair<String, String> = when (scan.phase) {
        ScanPhase.RUNNING -> "Скан идёт" to "опрос ${scan.done} из ${scan.total} блоков"
        ScanPhase.NEVER -> "Скана ещё не было" to "${scan.total} блоков"
        else -> "Скан не завершён" to (scan.notice ?: "опрос ${scan.done} из ${scan.total} блоков")
    }

    /** The live data's two views at the tabs' right end: «Таблица», «Графики · 4» with how many are ticked. */
    fun views(picked: Int): List<String> = listOf("Таблица", "Графики · $picked")

    /** «запись 0:42»: a recording under way, minutes and seconds; the footer puts the red dot before it. */
    fun recording(ms: Long): String = "запись " + minutes(ms)

    /** «запись 1:24 · войдёт в отчёт»: the last recording, stopped, which «Отчёт» copies. */
    fun recorded(ms: Long): String = "запись " + minutes(ms) + " · войдёт в отчёт"

    private fun minutes(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /** «снимок 19:31:29» out of a memory read's footnote: when the system's readings were taken. */
    fun snapshot(footnote: String): String? = footnote.split(" · ").lastOrNull { it.startsWith("снимок") }

    /**
     * The row of «Блоки» a system's row stands for: the session's, or - before it has one - what the
     * system's row knows, with no F194 and no answer measured.
     */
    fun blockRow(model: DiagnosticsModel, row: ModuleRowView): BlockRow =
        row.request?.let { req -> model.blocks?.groups?.asSequence()?.flatMap { it.rows }?.firstOrNull { it.request == req } } ?: run {
            val n = records(row)
            BlockRow(
                request = row.request, abbreviation = row.abbreviation, name = row.name, route = row.route, protocol = row.protocol,
                mark = mark(row.status), records = if (n == null || n == 0) "—" else n.toString(), software = "—",
                softwareChanged = false, latency = "—",
                filters = setOfNotNull(BlocksFilter.ALL, BlocksFilter.WITH_RECORDS.takeIf { (n ?: 0) > 0 }, BlocksFilter.NO_REPLY.takeIf { row.status == ModuleStatus.NO_REPLY }),
                target = row.target,
            )
        }

    /**
     * A system's «Блоки» on the table of «Блоки» (`sysBlocks()`): its blocks with records first - the
     * most severe, then the most records, then the catalog's order - then the rest, each group counted.
     */
    fun systemBlocks(model: DiagnosticsModel, page: SystemPage): List<BlocksGroup> {
        val rows = page.blocks.map { blockRow(model, it) }
        return groups(rows)
    }

    /** Rows into «С ЗАПИСЯМИ · n» and «БЕЗ ЗАПИСЕЙ · n» as `blocksTable()` orders them; an empty group is left out. */
    fun groups(rows: List<BlockRow>): List<BlocksGroup> {
        val rank = mapOf(Mark.ACTIVE to 0, Mark.PENDING to 1, Mark.HISTORY to 2)
        val with = rows.withIndex().filter { (it.value.records.toIntOrNull() ?: 0) > 0 }
            .sortedWith(compareBy({ rank[it.value.mark] ?: 3 }, { -(it.value.records.toIntOrNull() ?: 0) }, { it.index }))
            .map { it.value }
        val without = rows.filter { (it.records.toIntOrNull() ?: 0) == 0 }
        return listOfNotNull(
            BlocksGroup("С ЗАПИСЯМИ · ${with.size}", with).takeIf { with.isNotEmpty() },
            BlocksGroup("БЕЗ ЗАПИСЕЙ · ${without.size}", without).takeIf { without.isNotEmpty() },
        )
    }

    /** «Блоки» at a filter: the session's groups with the rows that pass it, each group's count of what it shows. */
    fun filtered(groups: List<BlocksGroup>, filter: BlocksFilter): List<BlocksGroup> =
        if (filter == BlocksFilter.ALL) groups else groups.mapNotNull { g ->
            val rows = g.rows.filter { filter in it.filters }
            if (rows.isEmpty()) null else BlocksGroup(g.title.substringBeforeLast(" · ") + " · " + rows.size, rows)
        }

    /** A block on a scheme: what stands in place of its address - its records' sign and count, a word, or the address. */
    data class TileRight(val mark: MarkCount?, val word: String)

    fun tileRight(row: ModuleRowView): TileRight {
        val n = records(row) ?: 0
        val m = mark(row.status)
        return when {
            m != null && m != Mark.NO_REPLY && n > 0 -> TileRight(MarkCount(m, n), "")
            row.status == ModuleStatus.NO_REPLY -> TileRight(null, "нет ответа")
            row.status == ModuleStatus.POLLING -> TileRight(null, "опрос")
            row.status == ModuleStatus.REFUSED -> TileRight(null, "отказ")
            row.status == ModuleStatus.UNIDENTIFIED -> TileRight(null, "не опознан")
            row.status == ModuleStatus.INCONSISTENT -> TileRight(null, "не сходится")
            else -> TileRight(null, row.request?.let(::hex) ?: "нет адреса")
        }
    }

    /** A raw read cut at its record: the words before it, the record, and the rest. */
    fun pieces(line: RawLine): Triple<String, String, String> {
        val lit = line.lit?.takeIf { it.first >= 0 && it.last < line.text.length } ?: return Triple(line.text, "", "")
        return Triple(line.text.substring(0, lit.first), line.text.substring(lit.first, lit.last + 1), line.text.substring(lit.last + 1))
    }

    /**
     * A number as the graphs read it (`numText()`): [decimals] after a comma, «−» for minus, «+» where the
     * signal has a sign, thousands apart by a space.
     */
    fun number(v: Float, decimals: Int, signed: Boolean = false): String = Figures.number(v.toDouble(), decimals, signed)

    /* -------------------------------------------------------- the report (7.4) */

    private val SCAN_AT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** «скан 20.10 19:31 · 13 199,7 км · 68 блоков · 3 страницы»: what the report is of, and how long it is. */
    fun reportLine(input: ReportInput, pages: Int): String = listOfNotNull(
        input.scanAt?.let { "скан " + SCAN_AT.format(it) },
        input.odometerKm?.let { Figures.number(it, 1) + " км" },
        "${input.blocks.size} " + plural(input.blocks.size, "блок", "блока", "блоков"),
        pages.takeIf { it > 0 }?.let { "$it " + plural(it, "страница", "страницы", "страниц") },
    ).joinToString(" · ")

    /** The footer's requests, the memory's reads by their sub-functions: «22 F194 · 19 01/02 · OBD 01/03/07/09/0A». */
    fun reportServices(services: List<String>): String {
        val memory = services.filter { it.startsWith("19 ") }.map { it.split(' ').getOrElse(1) { "" } }.distinct()
        val out = mutableListOf<String>()
        var said = false
        services.forEach { s ->
            if (s.startsWith("19 ")) {
                if (!said) out += "19 " + memory.joinToString("/")
                said = true
            } else {
                out += s
            }
        }
        return out.joinToString(" · ")
    }

    data class Destination(val path: String, val note: String, val size: String)

    /**
     * «КУДА»: the files' names in `Download/Denza` before the save - «scan-20261020-1931.pdf · .json» - or as
     * the provider gave them after it; what each is for; how many there are.
     */
    fun reportDestination(input: ReportInput, sections: Set<ReportSection>, saved: ReportOutcome.Saved?): Destination {
        val raw = ReportSection.RAW in sections
        val stream = ReportSection.STREAM in sections && input.recording != null
        val path = if (saved != null) {
            saved.folder + "/" + saved.names.joinToString(" · ")
        } else {
            ReportNames.FOLDER + "/" + ReportNames.base(input.scanAt ?: input.madeAt) + ReportNames.PDF +
                (if (raw) " · " + ReportNames.JSON else "") + (if (stream) " · " + ReportNames.CSV else "")
        }
        val note = listOfNotNull("PDF — для людей", "JSON — сырые ответы".takeIf { raw }, "CSV — запись потока".takeIf { stream }).joinToString(" · ")
        val n = saved?.names?.size ?: (1 + (if (raw) 1 else 0) + (if (stream) 1 else 0))
        val size = "$n " + plural(n, "файл", "файла", "файлов") + (saved?.let { " · сохранено" } ?: "")
        return Destination(path, note, size)
    }

    /** Russian counts: 1 блок, 2 блока, 5 блоков. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = abs(n) % 100
        val b = a % 10
        return if (a in 11..19) many else if (b == 1) one else if (b in 2..4) few else many
    }

    /** «−37,7 с»: the cursor's moment, seconds back. */
    fun secondsBack(s: Float): String = "−" + number(s, 1) + " с"

    /** «Таблица»'s and «Графики»' axis: seconds back every 15, «сейчас» at the right (`sysGraphs()`). */
    val GRAPH_TICKS = listOf(60, 45, 30, 15)

    /* ------------------------------------------------ the pack's cells (6.3) */

    /** The footer's source of «Ячейки»: the BMS's stream and its two pages. */
    const val CELLS_SOURCE = "1039 · 9900001F · 43D 43C"

    /** The cells' axis: the first cell, then every [ScannerSpec.PackCells.TICK_STEP] up to [n] (`cellTicks`: 1, 20 … 160). */
    fun cellTicks(n: Int): List<Int> =
        if (n <= 0) emptyList() else listOf(1) + (ScannerSpec.PackCells.TICK_STEP..n step ScannerSpec.PackCells.TICK_STEP)

    /** An extreme named at its bar's end: «мин №9», «макс №82» (0-based [index]). */
    fun cellExtreme(max: Boolean, index: Int): String = (if (max) "макс №" else "мин №") + (index + 1)

    /** «Здоровье»'s extremes under the cells: «мин № 9 · 3 280». */
    fun healthExtreme(max: Boolean, index: Int, value: String): String = (if (max) "макс № " else "мин № ") + (index + 1) + " · " + value

    /** A probe's tile: «Д1». */
    fun probe(index: Int): String = "Д${index + 1}"

    /* ------------------------------------------- the pack's GB/T 32960 flags (7.2) */

    /** The footer's source of «Флаги GB/T 32960»: the device the flags are read from and the stock reporter that sets them. */
    const val GB_FLAGS_SOURCE = "1039 · gbacqservice"

    /** A flag's tile: «бит 16». */
    fun gbBit(bit: Int): String = "бит $bit"

    /** A flag's code as read: «код 1». */
    fun gbCode(raw: Int): String = "код $raw"

    /** The HV schema's contactor, under its label: «замкнут», «разомкнут»; nothing while it is not known. */
    fun contactor(closed: Boolean?): String? = when (closed) {
        true -> "замкнут"
        false -> "разомкнут"
        null -> null
    }

    /* ------------------------------------------------- a parameter's page */

    /** The window tabs: «10 с», «60 с», «5 мин». */
    fun window(seconds: Int): String = if (seconds % 60 == 0 && seconds > 60) "${seconds / 60} мин" else "$seconds с"

    /**
     * The chart's time ticks, seconds back with their words, from the window's left edge to
     * «сейчас» in round steps: 60 s by 15 as the board (`sysParam()`: «−60 с», «−45 с» …), 10 s by
     * 2, five minutes by the minute («−5 мин» … «−1 мин»).
     */
    fun ticks(window: Int): List<Pair<Float, String>> {
        val step = when {
            window <= 10 -> 2
            window <= 60 -> 15
            else -> 60
        }
        return (window downTo 0 step step).map { t ->
            t.toFloat() to when {
                t == 0 -> "сейчас"
                step == 60 -> "−${t / 60} мин"
                else -> "−$t с"
            }
        }
    }

    /**
     * The page of [key] as the session describes it, or before it does: the row's name and reading,
     * nothing on the chart yet. Null when no row has the key - the page has nothing to stand on.
     */
    fun paramOf(model: DiagnosticsModel, key: ParamKey): ParamView? {
        model.param?.takeIf { it.key == key }?.let { return it }
        return when (key) {
            is ParamKey.Pid -> model.modules[key.request]?.obd?.live?.rows?.firstOrNull { it.key == key }?.let { r ->
                ParamView(
                    key, r.name, "· PID ${pid(r.pid)} · ${r.abbreviation}", "${route(key.request)} · Mode 01 · только ${pid(r.pid)}",
                    r.value, r.unit, r.outOfNorm, null, r.norm, emptyList(), 0L, emptyMap(), running = true, rate = "—",
                    recordable = true, recording = false,
                )
            }
            is ParamKey.Signal -> model.systems.live.values.flatMap { it.rows }.firstOrNull { it.key == key }?.let { r ->
                ParamView(
                    key, r.name, "· ${r.source}", "autoservice · только этот сигнал", r.value, r.unit, r.outOfNorm, null, r.norm,
                    emptyList(), 0L, emptyMap(), running = true, rate = "—", recordable = false, recording = false,
                )
            }
        }
    }
}
