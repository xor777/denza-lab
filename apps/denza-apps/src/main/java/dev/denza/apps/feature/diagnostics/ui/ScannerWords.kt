package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.IdentProcedure
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.ParamView
import dev.denza.apps.feature.diagnostics.scan.RailItem
import dev.denza.apps.feature.diagnostics.scan.RailTone
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.CarSystem
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
        ModuleStatus.UNIDENTIFIED -> Chip("НЕ ОПОЗНАН", Look.ON)
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

    /**
     * The word a topology tile shows in place of its address: how many codes where the look says only
     * that there are some, or what went wrong where looks are shared. Empty: the address, the look
     * says the rest (OK, not read, no address).
     */
    fun tileState(row: ModuleRowView): String = when (row.status) {
        ModuleStatus.HISTORY -> "ИСТ ${row.history}"
        ModuleStatus.ACTIVE -> "АКТ ${row.active}"
        ModuleStatus.POLLING -> "опрос"
        ModuleStatus.NO_REPLY -> "нет ответа"
        ModuleStatus.REFUSED -> "отказ"
        ModuleStatus.UNIDENTIFIED -> "не опознан"
        ModuleStatus.INCONSISTENT -> "не сходится"
        ModuleStatus.OK, ModuleStatus.UNREAD, ModuleStatus.NO_ROUTE -> ""
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

    /** The autoscan's row of [request], where the session put it. */
    fun row(model: DiagnosticsModel, request: Int): ModuleRowView? =
        model.groups.asSequence().flatMap { it.rows }.firstOrNull { it.request == request }

    /** The rail: the session's, or before it has one «Автоскан» and every system without a mark. */
    fun rail(model: DiagnosticsModel): List<RailItem> = model.rail.ifEmpty {
        listOf(RailItem(null, "Автоскан", model.scan.answered.takeIf { it.isNotEmpty() }, RailTone.COUNT)) +
            // before the session describes the systems: every one that holds a block in the catalog
            CarSystem.entries.filter { it != CarSystem.OTHER }.map { RailItem(it, it.railName, null, RailTone.NONE) }
    }

    /** A rail mark's ink: the autoscan's count quiet, history as the tiles have it, active in the car's orange. */
    fun railInk(tone: RailTone): Int = when (tone) {
        RailTone.NONE, RailTone.COUNT -> Ink.FAINT
        RailTone.HISTORY -> Ink.INK4
        RailTone.ACTIVE -> Ink.ORANGE
    }

    /** The autoscan's line under its title: when it ran, or how far it is, and the mask it reads with. */
    fun scanLine(scan: ScanSummary): String {
        val ran = when {
            scan.phase == ScanPhase.RUNNING -> "опрос ${scan.done}/${scan.total}"
            scan.finishedAt != null -> "опрос ${scan.finishedAt}"
            else -> "опроса не было"
        }
        return "$ran · маска DTC 09"
    }

    /** «снимок 19:31:29» out of a memory read's footnote: when the system's readings were taken. */
    fun snapshot(footnote: String): String? = footnote.split(" · ").lastOrNull { it.startsWith("снимок") }

    /** «· 68 блоков». */
    fun blocks(n: Int): String {
        val word = when {
            n % 10 == 1 && n % 100 != 11 -> "блок"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "блока"
            else -> "блоков"
        }
        return "· $n $word"
    }

    /* ------------------------------------------------ the pack's cells (6.3) */

    /** The footer's source of «Ячейки»: the BMS's stream and its two pages. */
    const val CELLS_SOURCE = "1039 · 9900001F · 43D 43C"

    /** The cells' axis: the first cell, then every [ScannerSpec.PackCells.TICK_STEP] up to [n] (`cellTicks`: 1, 20 … 160). */
    fun cellTicks(n: Int): List<Int> =
        if (n <= 0) emptyList() else listOf(1) + (ScannerSpec.PackCells.TICK_STEP..n step ScannerSpec.PackCells.TICK_STEP)

    /** An extreme named at its bar's end: «мин №9», «макс №82» (0-based [index]). */
    fun cellExtreme(max: Boolean, index: Int): String = (if (max) "макс №" else "мин №") + (index + 1)

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
