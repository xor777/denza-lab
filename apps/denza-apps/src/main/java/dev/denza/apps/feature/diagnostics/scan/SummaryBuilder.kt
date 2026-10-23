package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.vehicle.VehicleSignal
import kotlin.math.floor

/**
 * What one build of the v4 views has at hand (architecture, 7.3): the session's snapshot, the blocks' own
 * screens [ModelBuilder] decided, the catalog, and what every view counts by - each block's [BlockRecord]s,
 * the shown scan's exchanges, each record's life in the journal.
 */
internal class V4Inputs(
    val s: SessionSnapshot,
    val modules: Map<Int, ModuleView>,
    val catalog: OemDtcIndex,
    val clock: ScanClock,
) {
    /** Each read block's records, by request address. */
    val records: Map<Int, List<BlockRecord>> = s.results.mapValues { BlockRecords.of(it.value) }
    val facts = ScanFacts(s.results)
    private val lives = HashMap<JournalCodeKey, JournalCodeLife?>()

    fun meaning(r: BlockRecord): String? =
        Meanings.of(catalog, r, modules[r.request]?.dtc?.records?.firstOrNull { it.code == r.code }?.description)

    /** «неисправность связи с ABS», else the failure type's words «нет сообщения», else the code's group. */
    fun words(r: BlockRecord): String = meaning(r)?.let(Meanings::lower)
        ?: r.dtc?.failureTypeName
        ?: r.dtc?.let { ModelBuilder.codeGroup(it) }
        ?: r.source

    fun life(r: BlockRecord): JournalCodeLife? =
        lives.getOrPut(JournalCodeKey(r.request, r.code)) { JournalFacts.life(s.journal, JournalCodeKey(r.request, r.code)) }

    /** When the journal first read [r]: its life's first scan; the shown scan for a code the journal has not held yet; null without a journal. */
    fun since(r: BlockRecord): Long? {
        if (s.journal.scans.isEmpty()) return null
        return life(r)?.firstAtMillis ?: s.finishedAtMillis
    }

    /** «с 18.10». */
    fun sinceText(millis: Long?): String? = millis?.let { "с ${clock.date(it)}" }
}

/** The shown scan's exchanges, as the heads and the footers count them. */
internal class ScanFacts(results: Map<Int, ModuleResult>) {
    private val replies = EcuCatalog.routed.mapNotNull { e -> results[e.request] }.flatMap { it.read.replies }
    val count: Int = replies.size
    val startMillis: Long? = replies.minOfOrNull { it.atMillis - it.latencyMs }
    val endMillis: Long? = replies.maxOfOrNull { it.atMillis }
    val medianMs: Int? = JournalEntries.medianMs(replies)

    /** «19,9»: first ask to last answer, seconds; null without a span. */
    val seconds: String? = if (startMillis != null && endMillis != null && endMillis > startMillis) {
        Figures.number((endMillis - startMillis) / 1000.0, 1)
    } else null

    /** «09», «0D/09»: the memory masks the scan read with, the most used first. */
    val masks: String = results.values.filter { it.ecu.uds && it.ecu.dtcCountAnswers && it.memory !is Polled.NotPolled }
        .groupingBy { it.memoryMask }.eachCount().entries
        .sortedWith(compareBy({ -it.value }, { it.key })).joinToString("/") { "%02X".format(it.key) }.ifEmpty { "—" }
}

/**
 * The rail, the head, «Сводка» and «Блоки» of 7.3 out of the shown scan, the journal and the car (boards
 * `scanner-summary`, `-summary-active`, `-blocks`). Pure: [ModelBuilder] calls it once a build.
 */
internal object SummaryBuilder {
    private const val NA = "—"

    // ------------------------------------------------------------------ the rail

    /** «Сводка», «Блоки», the systems with blocks, «Журнал»: a mark where there are records, «Блоки»' own count. */
    fun rail(i: V4Inputs): List<RailLine> {
        val catalogued = EcuCatalog.all.size
        val silent = i.s.results.values.count { !it.answered }
        return listOf(RailLine(RailPlace.Summary, "Сводка", markCount(i, i.s.results.keys))) +
            RailLine(RailPlace.Blocks, "Блоки", null,
                plain = if (silent == 0) "$catalogued" else "${catalogued - silent}/$catalogued", plainLit = silent > 0) +
            SystemPagesBuilder.SHOWN.map { system ->
                RailLine(RailPlace.System(system), system.railName, markCount(i, SystemPagesBuilder.MEMBERS.getValue(system)))
            } +
            RailLine(RailPlace.Journal, "Журнал", null)
    }

    /** The worst mark over [requests]' blocks and the sum of their records; null where there is nothing. */
    private fun markCount(i: V4Inputs, requests: Collection<Int>): MarkCount? {
        val records = requests.flatMap { i.records[it].orEmpty() }
        val mark = BlockRecords.worst(requests.mapNotNull { r -> BlockRecords.blockMark(i.s.results[r], i.records[r].orEmpty()) })
            ?: return null
        return MarkCount(mark, records.size)
    }

    // ------------------------------------------------------------------ the head

    /** The v4 head's figures: the car, the mode, the odometer, 12 V and the shown scan's median exchange. */
    fun header(i: V4Inputs, header: Header): Header {
        val car = i.s.car
        val volts = car.extra(DiagnosticExtra.LV)
        return header.copy(
            car = ModelBuilder.CAR,
            mode = mode(car),
            odometer = car.hub[VehicleSignal.ODOMETER_KM]?.let { "${Figures.number(floor(it), 0)} км" },
            volts = volts?.let { Figures.number(it, 1) },
            voltsOut = lowVoltageOut(volts),
            latency = i.facts.medianMs?.let { "$it мс" },
        )
    }

    /** «P», «P · ДВС работает», «не P»; null while the selector is not known. */
    fun mode(car: CarReadings): String? = when (car.parked) {
        true -> if (car.engineRunning == true) "P · ДВС работает" else "P"
        false -> if (car.engineRunning == true) "не P · ДВС работает" else "не P"
        null -> null
    }

    /**
     * The 12 V net's norm (7.3): 12,0…15,0 В - a resting lead-acid or LFP battery at 12,0 and above, the DC-DC's
     * charging voltage under 15. The scanner's own bounds, not the maker's.
     */
    val LV_NORM = 12.0..15.0

    fun lowVoltageOut(volts: Double?): Boolean = volts != null && volts !in LV_NORM

    // ------------------------------------------------------------------ «Сводка»

    fun summary(i: V4Inputs): SummaryView? {
        val s = i.s
        if (s.phase == ScanPhase.NEVER && s.results.isEmpty()) return null
        val catalogued = EcuCatalog.all.size
        val routed = EcuCatalog.routed
        val all = routed.flatMap { e -> i.records[e.request].orEmpty() }
        val active = all.count { it.mark == Mark.ACTIVE }
        val mil = s.results.values.any { it.mil == true }
        val running = s.phase == ScanPhase.RUNNING || s.phase == ScanPhase.PAUSED
        val verdict = when {
            running -> "Опрос… ${s.done} из ${routed.size}"
            s.phase == ScanPhase.STOPPED -> "Скан не завершён"
            active > 0 -> "$active " + Words.plural(active, "активная неисправность", "активные неисправности", "активных неисправностей")
            else -> "Активных неисправностей нет"
        }
        val answered = s.results.values.count { it.answered }
        val withRecords = routed.filter { e -> i.records[e.request].orEmpty().isNotEmpty() }
        val silent = s.results.values.count { !it.answered }
        val quiet = s.results.size - withRecords.size
        return SummaryView(
            verdict = verdict,
            verdictMark = if (active > 0 || mil) Mark.ACTIVE else null,
            line = scanLine(i, "$answered из $catalogued блоков"),
            vitals = vitals(s.car),
            band = band(all, withRecords.size),
            columns = columns(i),
            rest = "${Words.count(quiet, "блок", "блока", "блоков")} без записей · " +
                if (silent == 0) "все ответили" else "не ответили $silent",
            restAttention = silent > 0,
            footnote = listOfNotNull(
                Words.count(i.facts.count, "обмен", "обмена", "обменов"),
                i.facts.medianMs?.let { "медиана $it мс" },
                s.finishedAtMillis?.let { "снимок ${i.clock.seconds(it)}" },
            ).joinToString(" · "),
        )
    }

    /** «скан 20.10 19:31 · <middle> · маска 09 · 19,9 с»: the scan's end, or its start while it runs. */
    private fun scanLine(i: V4Inputs, middle: String): String {
        val at = i.s.finishedAtMillis ?: i.facts.startMillis
        return listOfNotNull(
            at?.let { "скан ${i.clock.dated(it)}" } ?: "скан",
            middle,
            "маска ${i.facts.masks}",
            i.facts.seconds?.takeIf { i.s.finishedAtMillis != null }?.let { "$it с" },
        ).joinToString(" · ")
    }

    /** «12 В», «ИЗОЛЯЦИЯ» (the weaker channel), «Δ ЯЧЕЕК», «SOH»: what a diagnostician checks first, lit outside its norm. */
    fun vitals(car: CarReadings): List<Vital> {
        val lv = car.extra(DiagnosticExtra.LV)
        val insulation = listOfNotNull(car.extra(DiagnosticExtra.INSULATION), car.extra(DiagnosticExtra.INSULATION_2)).minOrNull()
            ?.let(Insulation::mohm)
        val cellMin = car.hub[VehicleSignal.CELL_MIN_MV]
        val cellMax = car.hub[VehicleSignal.CELL_MAX_MV]
        val soh = car.extra(DiagnosticExtra.SOH)
        return listOf(
            Vital("12 В", lv?.let { Figures.number(it, 1) } ?: NA, "В", lowVoltageOut(lv)),
            Vital("ИЗОЛЯЦИЯ", insulation?.let { Figures.number(it, 1) } ?: NA, Insulation.UNIT,
                Insulation.low(insulation, car.hub[VehicleSignal.PACK_VOLT])),
            Vital("Δ ЯЧЕЕК", if (cellMin != null && cellMax != null) Figures.number(cellMax - cellMin, 0) else NA, "мВ"),
            Vital("SOH", soh?.let { Figures.number(it, 0) } ?: NA, "%", soh != null && soh < SOH_FLOOR),
        )
    }

    const val SOH_FLOOR = 80.0

    /** One part a kind present - «АКТИВНЫЕ · 1 ЗАПИСЬ» - the last with the blocks; the lost messages beside them. */
    private fun band(all: List<BlockRecord>, blocks: Int): SummaryBand? {
        if (all.isEmpty()) return null
        val kinds = listOf(Mark.ACTIVE to "АКТИВНЫЕ", Mark.PENDING to "ОЖИДАЮЩИЕ", Mark.HISTORY to "В ИСТОРИИ")
            .mapNotNull { (m, word) -> all.count { it.mark == m }.takeIf { it > 0 }?.let { Triple(m, word, it) } }
        val parts = kinds.mapIndexed { k, (m, word, n) ->
            var t = "$word · $n ${Words.plural(n, "ЗАПИСЬ", "ЗАПИСИ", "ЗАПИСЕЙ")}"
            if (k == kinds.lastIndex) t += " · $blocks ${Words.plural(blocks, "БЛОК", "БЛОКА", "БЛОКОВ")}"
            BandPart(m, t)
        }
        val net = all.count { it.network }
        return SummaryBand(
            text = parts.joinToString("   ") { it.text },
            mark = parts.first().mark,
            note = if (net > 0) "в т. ч. $net — потеря связи между блоками, U…-87" else null,
            parts = parts,
        )
    }

    /**
     * The systems with records, most severe first, then the most records, then the rail's order; each block in
     * the one system it is most its own - the system with the fewest blocks that holds it (the BMS in the pack,
     * the PTC in the climate). Two columns, each system into the one with fewer rows so far.
     */
    private fun columns(i: V4Inputs): List<List<SummarySystem>> {
        val home = EcuCatalog.routed.associate { e ->
            e.request!! to SystemPagesBuilder.SHOWN.filter { e.request in SystemPagesBuilder.MEMBERS.getValue(it) }
                .minByOrNull { SystemPagesBuilder.MEMBERS.getValue(it).size }
        }
        val systems = SystemPagesBuilder.SHOWN.mapNotNull { system ->
            val blocks = SystemPagesBuilder.blocks(system).filter { home[it.request] == system }
                .mapNotNull { e -> block(i, e) }
            if (blocks.isEmpty()) null else system to blocks
        }
        fun rank(m: Mark) = m.ordinal
        val order = systems.withIndex().sortedWith(compareBy(
            { rank(BlockRecords.worst(it.value.second.map { b -> b.mark })!!) },
            { -it.value.second.sumOf { b -> b.count } },
            { it.index },
        )).map { it.value }
        val cols = listOf(ArrayList<SummarySystem>(), ArrayList())
        val rows = intArrayOf(0, 0)
        order.forEach { (system, blocks) ->
            val sorted = blocks.withIndex().sortedWith(compareBy({ rank(it.value.mark) }, { -it.value.count }, { it.index })).map { it.value }
            val k = if (rows[1] < rows[0]) 1 else 0
            cols[k] += SummarySystem("${system.railName.uppercase()} · ${blocks.sumOf { it.count }}", sorted)
            rows[k] += 1 + sorted.sumOf { 1 + it.records.size }
        }
        return cols.filter { it.isNotEmpty() }
    }

    private fun block(i: V4Inputs, e: Ecu): SummaryBlock? {
        val request = e.request ?: return null
        val records = i.records[request].orEmpty()
        val mark = BlockRecords.blockMark(i.s.results[request], records) ?: return null
        if (records.isEmpty()) return null
        return SummaryBlock(
            request = request,
            abbreviation = e.abbreviation,
            address = Figures.address(request),
            name = ModelBuilder.name(e),
            mark = mark,
            count = records.size,
            since = i.sinceText(records.mapNotNull(i::since).minOrNull()),
            records = records.map { r ->
                val meaning = i.meaning(r)
                SummaryRecord(
                    code = r.code,
                    words = i.words(r),
                    meaning = meaning != null,
                    network = r.network,
                    mark = r.mark.takeIf { it != Mark.HISTORY },
                    fresh = i.life(r)?.isNew == true,
                )
            },
        )
    }

    // ------------------------------------------------------------------ «Блоки»

    fun blocks(i: V4Inputs): BlocksView? {
        val s = i.s
        if (s.phase == ScanPhase.NEVER && s.results.isEmpty()) return null
        val catalogued = EcuCatalog.all
        val rows = catalogued.map { e -> row(i, e) }
        val withRecords = rows.withIndex().filter { BlocksFilter.WITH_RECORDS in it.value.filters }
            .sortedWith(compareBy({ it.value.mark!!.ordinal }, { -(it.value.records.toIntOrNull() ?: 0) }, { it.index })).map { it.value }
        val silent = rows.filter { BlocksFilter.NO_REPLY in it.filters }
        val rest = rows.filter { BlocksFilter.WITH_RECORDS !in it.filters && BlocksFilter.NO_REPLY !in it.filters }
        val changed = rows.count { BlocksFilter.SOFTWARE_CHANGED in it.filters }
        return BlocksView(
            aside = "· ${catalogued.size} · " + if (silent.isEmpty()) "все ответили" else "не ответили ${silent.size}",
            line = listOfNotNull(
                (s.finishedAtMillis ?: i.facts.startMillis)?.let { "скан ${i.clock.dated(it)}" } ?: "скан",
                Words.count(i.facts.count, "обмен", "обмена", "обменов"),
                i.facts.seconds?.takeIf { s.finishedAtMillis != null }?.let { "$it с" },
                "маска ${i.facts.masks}",
            ).joinToString(" · "),
            filters = listOf(
                BlocksFilter.ALL to "Все · ${rows.size}",
                BlocksFilter.WITH_RECORDS to "С записями · ${withRecords.size}",
                BlocksFilter.NO_REPLY to "Без ответа · ${silent.size}",
                BlocksFilter.SOFTWARE_CHANGED to "ПО изменено · $changed",
            ),
            groups = listOfNotNull(
                withRecords.takeIf { it.isNotEmpty() }?.let { BlocksGroup("С ЗАПИСЯМИ · ${it.size}", it) },
                rest.takeIf { it.isNotEmpty() }?.let { BlocksGroup("БЕЗ ЗАПИСЕЙ · ${it.size}", it) },
                silent.takeIf { it.isNotEmpty() }?.let { BlocksGroup("НЕТ ОТВЕТА · ${it.size}", it) },
            ),
            footnote = "порядок: записи, затем система",
        )
    }

    private fun row(i: V4Inputs, e: Ecu): BlockRow {
        val request = e.request
        val result = request?.let { i.s.results[it] }
        val records = request?.let { i.records[it] }.orEmpty()
        val silent = result != null && !result.answered
        val mark = if (silent) Mark.NO_REPLY else BlockRecords.blockMark(result, records)
        val f194 = result?.let { SoftwareReads.of(it).firstOrNull()?.code }
        val changed = result != null && softwareChanged(i, result, f194)
        val filters = buildSet {
            add(BlocksFilter.ALL)
            if (records.isNotEmpty()) add(BlocksFilter.WITH_RECORDS)
            if (silent) add(BlocksFilter.NO_REPLY)
            if (changed) add(BlocksFilter.SOFTWARE_CHANGED)
        }
        return BlockRow(
            request = request,
            abbreviation = e.abbreviation,
            name = ModelBuilder.name(e),
            route = request?.let(Figures::route) ?: NA,
            protocol = when {
                e.uds && e.obd -> "OBD + UDS"
                e.obd -> "OBD"
                e.uds -> "UDS"
                else -> NA
            },
            mark = mark,
            records = if (records.isEmpty()) NA else records.size.toString(),
            software = f194?.let(::fours) ?: NA,
            softwareChanged = changed,
            latency = result?.let { BlockRecords.medianMs(it.read.replies) }?.let { "$it мс" } ?: NA,
            filters = filters,
            target = ModelBuilder.target(e),
        )
    }

    /** «4B4D 0011 DE01 1406 00». */
    fun fours(code: String): String = code.chunked(4).joinToString(" ")

    /**
     * F194 or F195 differs from the journal's previous reading of the block - the newest journal scan before
     * this read that holds a software code for it (5.5, 6.4).
     */
    private fun softwareChanged(i: V4Inputs, result: ModuleResult, f194: String?): Boolean {
        if (f194 == null) return false
        val before = i.s.journal.scans.lastOrNull { it.atMillis < result.read.atMillis && it.module(result.read.request)?.f194 != null }
            ?.module(result.read.request) ?: return false
        if (before.f194 != f194) return true
        val f195 = i.s.idents[result.read.request]?.let { JournalEntries.module(result, it).f195 }
        return before.f195 != null && f195 != null && before.f195 != f195
    }
}
