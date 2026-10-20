package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.SignalRow
import dev.denza.apps.feature.diagnostics.scan.SystemScreen
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink

/**
 * The words the screens derive from [DiagnosticsModel] themselves, kept out of the composables so
 * they can be tested: a status's chip, a status byte read out bit by bit, the jump links' rule,
 * «Только с DTC». Nothing here decodes bytes from the car - the session has done that.
 */
internal object ScannerWords {

    /** The status bits of a DTC, bit 0 first, as ISO 14229-1 names them. */
    val BITS = listOf("TF", "TFTOC", "PDTC", "CDTC", "TNCSLC", "TFSLC", "TNCTOC", "WIR")

    /** What each bit says, for the legend beside the codes. */
    val BIT_WORDS = listOf(
        "отказ сейчас", "отказ в текущем цикле", "ожидающий", "подтверждённый",
        "тест не выполнен после сброса", "отказ после сброса", "тест не выполнен в текущем цикле", "запрос лампы",
    )

    /** The look of a status: the list's chip, the tile's and the unit box's. */
    enum class Look { OK, HIST, UNREAD, NONE, ON }

    data class Chip(val text: String, val look: Look)

    fun chip(status: ModuleStatus): Chip = when (status) {
        ModuleStatus.OK -> Chip("OK", Look.OK)
        ModuleStatus.HISTORY -> Chip("ИСТОРИЯ", Look.HIST)
        ModuleStatus.ACTIVE -> Chip("АКТИВНЫЕ DTC", Look.ON)
        ModuleStatus.UNREAD -> Chip("DTC НЕ ЧИТАЛИ", Look.UNREAD)
        ModuleStatus.POLLING -> Chip("ОПРОС", Look.UNREAD)
        ModuleStatus.NO_REPLY -> Chip("НЕТ ОТВЕТА", Look.NONE)
        ModuleStatus.REFUSED -> Chip("ОТКАЗ", Look.NONE)
        ModuleStatus.OTHER_ECU -> Chip("ДРУГОЙ БЛОК", Look.ON)
        ModuleStatus.INCONSISTENT -> Chip("ОТВЕТ НЕ СХОДИТСЯ", Look.ON)
        ModuleStatus.NO_ROUTE -> Chip("НЕТ АДРЕСА", Look.NONE)
    }

    fun tone(look: Look): ScannerSpec.Tone = when (look) {
        Look.OK -> ScannerSpec.Tones.OK
        Look.HIST -> ScannerSpec.Tones.HIST
        Look.UNREAD -> ScannerSpec.Tones.UNREAD
        Look.NONE -> ScannerSpec.Tones.NONE
        Look.ON -> ScannerSpec.Tones.ON
    }

    /** A memory that was read: its counts mean something. */
    fun read(status: ModuleStatus): Boolean = status == ModuleStatus.OK || status == ModuleStatus.HISTORY || status == ModuleStatus.ACTIVE

    /** The topology tile's state word, beside the short name. */
    fun tileState(row: ModuleRowView): String = when (row.status) {
        ModuleStatus.OK -> "OK"
        ModuleStatus.HISTORY -> "ИСТ ${row.history}"
        ModuleStatus.ACTIVE -> "АКТ ${row.active}"
        ModuleStatus.UNREAD -> "не читали"
        ModuleStatus.POLLING -> "опрос"
        ModuleStatus.NO_REPLY -> "нет ответа"
        ModuleStatus.REFUSED -> "отказ"
        ModuleStatus.OTHER_ECU -> "другой блок"
        ModuleStatus.INCONSISTENT -> "не сходится"
        ModuleStatus.NO_ROUTE -> ""
    }

    data class Link(val text: String, val dot: Int, val glow: Boolean)

    fun link(state: LinkState): Link = when (state) {
        LinkState.OK -> Link("Связь OK", Ink.BLUE, true)
        LinkState.IDLE -> Link("Связь не открыта", Ink.FAINT, false)
        LinkState.CONNECTING -> Link("Подключение", Ink.FAINT, false)
        LinkState.NO_ACCESS -> Link("Нет доступа ADB", Ink.ORANGE, false)
        LinkState.BUSY -> Link("Канал занят", Ink.ORANGE, false)
        LinkState.NOT_PARKED -> Link("Только на стоянке", Ink.ORANGE, false)
        LinkState.LOST -> Link("Связь потеряна", Ink.ORANGE, false)
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

    fun hex2(v: Int): String = "0x" + Integer.toHexString(v).uppercase().padStart(2, '0')

    /** «Только с DTC»: a row whose memory holds a record, active or in history. */
    fun hasDtc(row: ModuleRowView): Boolean =
        row.status == ModuleStatus.ACTIVE || row.status == ModuleStatus.HISTORY ||
            (row.active.toIntOrNull() ?: 0) > 0 || (row.history.toIntOrNull() ?: 0) > 0

    fun filter(groups: List<ModuleGroupView>, onlyDtc: Boolean): List<ModuleGroupView> =
        if (!onlyDtc) groups else groups.map { g -> g.copy(rows = g.rows.filter(::hasDtc)) }.filter { it.rows.isNotEmpty() }

    /**
     * The topology's caption for a group: its short name in capitals, or its whole title when it
     * runs over more than one column (the unassigned blocks) and so has the room.
     */
    fun topologyLabel(group: ModuleGroupView, columnsSpanned: Int): String =
        (if (columnsSpanned > 1) group.title else group.short.uppercase()) + " · " + group.rows.size

    /** A status byte, read out: what the codes table's matrix shows, in sentences. */
    data class Explanation(val head: String, val lead: String, val body: String)

    fun explain(status: Int): Explanation {
        fun bit(i: Int) = (status shr i) and 1
        val binary = Integer.toBinaryString(status and 0xFF).padStart(8, '0').let { it.substring(0, 4) + " " + it.substring(4) }
        val tf = bit(0) == 1
        val confirmed = bit(3) == 1
        val lead = when {
            tf && confirmed -> "Активный, подтверждённый."
            tf -> "Активный: отказ сейчас, ещё не подтверждён."
            confirmed -> "Подтверждённый, неактивный (исторический)."
            bit(2) == 1 -> "Ожидающий: отказ был, ещё не подтверждён."
            else -> "Отказа сейчас нет, подтверждённого нет."
        }
        val sentences = mutableListOf<String>()
        val since = listOf(3, 5).filter { bit(it) == 1 }.map { BITS[it] }
        if (since.isNotEmpty()) {
            sentences += since.joinToString(" и ") { "$it = 1" } + ": отказ был зафиксирован после последнего сброса."
        }
        sentences += when {
            tf -> "TF = 1: отказ есть сейчас."
            bit(1) == 1 -> "TF = 0, TFTOC = 1: в текущем цикле отказ был, сейчас его нет."
            bit(6) == 1 -> "TF = 0, TNCTOC = 1: в текущем цикле тест ещё не выполнен."
            else -> "TF = 0, TFTOC = 0 и TNCTOC = 0: в текущем цикле тест выполнен, отказа нет."
        }
        sentences += if (bit(7) == 1) "WIR = 1: блок запрашивает лампу." else "WIR = 0: лампу не запрашивает."
        return Explanation("СТАТУС ${hex2(status)} = $binary", lead, sentences.joinToString(" "))
    }

    /** The fault view's four counts: the OBD lists and the UDS confirmed read, by service. */
    val SHORT_LISTS = linkedMapOf(
        "Mode 03" to "СОХРАНЁННЫЕ", "Mode 07" to "ОЖИДАЮЩИЕ", "Mode 0A" to "ПОСТОЯННЫЕ", "19 02 08" to "UDS ПОДТВ.",
    )

    /** A Mode 01 PID in hex; a fast car signal beside the PIDs (negative) has none. */
    fun pid(pid: Int): String = if (pid < 0) "—" else String.format("%02X", pid)

    /**
     * A legend says only what the screen shows: an entry for a tile kind no tile has is left out
     * (the board's `footer(…, present)`); entries that are not tile kinds stay.
     */
    fun present(legend: List<Pair<Swatch, String>>, statuses: List<ModuleStatus>): List<Pair<Swatch, String>> {
        val shown = statuses.map { chip(it).look }.toSet()
        fun kind(s: Swatch): Look? = when (s) {
            Swatch.OK -> Look.OK
            Swatch.HIST -> Look.HIST
            Swatch.UNREAD -> Look.UNREAD
            Swatch.NONE -> Look.NONE
            Swatch.MECH -> null
        }
        return legend.filter { (s, _) -> kind(s)?.let { it in shown } ?: true }
    }

    /** WIR, bit 7: the record asks for the lamp. */
    fun asksLamp(status: Int): Boolean = (status shr 7) and 1 == 1

    /** A Mode 01 PID that is itself a bitmap of the next 32: 20, 40, 60, … */
    fun bitmapPid(pid: String): Boolean = pid.toIntOrNull(16)?.let { it % 0x20 == 0 } ?: false

    /** The bitmaps that were asked: 00, then every bitmap PID the ECU listed but the last. */
    fun pidsAsked(pids: List<String>): String {
        val maps = pids.filter(::bitmapPid)
        return (listOf("00") + maps.dropLast(1)).joinToString(", ") { "01 $it" }
    }

    /** «побайтно, 9 байт»: the F194 the catalog was matched against, by its length. */
    fun matchedBytes(ident: IdentView): String? {
        val f194 = ident.own.firstOrNull { "F194" in it.key }?.value ?: return null
        val hex = f194.filter { it.isLetterOrDigit() }
        return if (hex.length % 2 == 0 && hex.all { it in "0123456789ABCDEFabcdef" }) "побайтно, ${hex.length / 2} байт" else null
    }

    /** The live tab's line: the route, the mode, how many PIDs, the measured rate. */
    fun liveLine(request: Int, rows: Int, rate: String): String =
        "${route(request)} · Mode 01 · $rows PID · опрос $rate · график за 60 с"

    /** The drives' line: the routed controllers, then those without an address. */
    fun drivesLine(columns: List<CompareColumn>): String {
        val routed = columns.filter { it.request != null }.joinToString(" · ") { "${it.abbreviation} ${route(it.request)}" }
        val none = columns.filter { it.request == null }.joinToString(", ") { it.abbreviation }
        return listOf(routed, if (none.isEmpty()) "" else "$none: адрес не найден", "данные: шина сигналов (autoservice)")
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** «· 3 мотора + мотор-генератор»: the drive columns, the generator named apart. */
    fun drivesAside(columns: List<CompareColumn>): String {
        val generators = columns.count { "генератор" in it.name.lowercase() }
        val motors = columns.size - generators
        val word = when {
            motors % 10 == 1 && motors % 100 != 11 -> "мотор"
            motors % 10 in 2..4 && motors % 100 !in 12..14 -> "мотора"
            else -> "моторов"
        }
        return "· $motors $word" + if (generators > 0) " + мотор-генератор" else ""
    }

    /**
     * The HV system's line: what is read by UDS - the pack and every unit on the schematic that
     * leads somewhere, by address - those without an address, and the signals beside them.
     */
    fun hvLine(model: DiagnosticsModel, pack: Int?): String {
        val packRow = pack?.let { row(model, it) }
        val units = model.systems.hv.units
        val routed = listOfNotNull(packRow?.let { "${it.abbreviation} ${hex(pack)}" }) +
            units.filter { it.address.toIntOrNull(16) != null && it.target != null }.map { "${it.abbreviation} ${it.address}" }
        val none = units.filter { it.address.toIntOrNull(16) == null }.joinToString(", ") { it.abbreviation }
        return "UDS: " + (routed + listOfNotNull(if (none.isEmpty()) null else "нет адреса: $none") + "+ сигналы").joinToString(" · ")
    }

    /** A comparison's line: how many blocks and each one's request address. */
    fun blocksLine(columns: List<CompareColumn>): String =
        (listOf("${columns.size} блоков") + columns.map { c -> c.request?.let { "${c.abbreviation} ${hex(it)}" } ?: c.abbreviation }).joinToString(" · ")

    /** A comparison column's tag under its name: «FMCU · 7E3», or the abbreviation alone. */
    fun columnTag(c: CompareColumn): String = if (c.request == null) c.abbreviation else "${c.abbreviation} · ${hex(c.request)}"

    /** The autoscan's row of [request], where the session put it. */
    fun row(model: DiagnosticsModel, request: Int): ModuleRowView? =
        model.groups.asSequence().flatMap { it.rows }.firstOrNull { it.request == request }

    /** The ECU a system screen leads with: the row whose tap opens that screen. */
    fun leadModule(model: DiagnosticsModel, screen: SystemScreen): Int? = when (screen) {
        SystemScreen.BRAKES -> model.systems.brakeModules.firstOrNull()
        else -> model.groups.asSequence().flatMap { it.rows }
            .firstOrNull { it.target == Target.System(screen) && it.request != null && it.status != ModuleStatus.NO_ROUTE }?.request
    }

    /** DTC over [requests]: the records the autoscan counted, active and in history. */
    fun dtcSum(model: DiagnosticsModel, requests: List<Int?>): String {
        val rows = requests.filterNotNull().mapNotNull { row(model, it) }.filter { read(it.status) }
        if (rows.isEmpty()) return "—"
        return rows.sumOf { (it.active.toIntOrNull() ?: 0) + (it.history.toIntOrNull() ?: 0) }.toString()
    }

    /** «2 из 7»: the comparison's blocks whose memory was read. */
    fun readOf(model: DiagnosticsModel, view: CompareView): String {
        val read = view.columns.count { c -> c.request?.let { row(model, it) }?.let { read(it.status) } == true }
        return "$read из ${view.columns.size}"
    }

    /** A signal's value with its unit, «553 В»; null when the row is not there. */
    fun signal(rows: List<SignalRow>, nameHas: String): String? =
        rows.firstOrNull { nameHas in it.name }?.let { if (it.unit.isEmpty()) it.value else "${it.value} ${it.unit}" }
}
