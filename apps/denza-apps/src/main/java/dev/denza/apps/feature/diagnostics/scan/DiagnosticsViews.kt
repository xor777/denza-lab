package dev.denza.apps.feature.diagnostics.scan

/*
 * The scanner as a diagnostician's instrument (architecture, 7.3): the views of «Сводка», «Блоки»,
 * the code's screen, «Тяговая батарея · Здоровье», the live data's «Графики» and «Журнал», already
 * decided - words, figures, marks and fractions of an axis. [DiagnosticsModel] carries them; the
 * session, the journal and the reads fill them; the screens never decode, look anything up or count.
 * Boards: `scanner-summary`, `-summary-active`, `-blocks`, `-code`, `-code-plain`,
 * `-sys-pack-health`, `-sys-pack-health-first`, `-sys-engine-graphs`, `-journal`, `-journal-first`;
 * «Отчёт» is [ReportState] (7.4).
 */

/** What a record, a block or a rail line is worst at: the mark's shape and colour (7.3). */
internal enum class Mark {
    /** testFailed now, or MIL on: a red dot. */
    ACTIVE,
    /** Pending only: a dashed orange ring. */
    PENDING,
    /** Confirmed, not failing now: an orange ring. */
    HISTORY,
    /** Asked and not answered: a grey dashed square. */
    NO_REPLY,
}

/** A mark with its count: the rail's «○ 10», a block's «8». */
internal data class MarkCount(val mark: Mark, val count: Int)

/** Where a rail line leads (7.3): «Сводка», «Блоки», a system, «Журнал». */
internal sealed interface RailPlace {
    data object Summary : RailPlace
    data object Blocks : RailPlace
    data class System(val system: CarSystem) : RailPlace
    data object Journal : RailPlace
}

/** A line of the rail: [mark] only where there are records; [plain] is «Блоки»' own count. */
internal data class RailLine(
    val place: RailPlace,
    val name: String,
    val mark: MarkCount?,
    /** «68» quiet while every block answered; «67/68» lit by [plainLit] when one did not; null elsewhere. */
    val plain: String? = null,
    val plainLit: Boolean = false,
)

/** A head figure that has something to say: «12 В» → «13,7» «В»; orange outside its norm, «—» not read. */
internal data class Vital(val label: String, val value: String, val unit: String, val outOfNorm: Boolean = false)

// ------------------------------------------------------------------ «Сводка»

/** «Сводка» (boards `scanner-summary`, `-summary-active`): the entry of the section. */
internal data class SummaryView(
    /** «Активных неисправностей нет», «1 активная неисправность», «Скан не завершён». */
    val verdict: String,
    /** Red with an active record or MIL on; null quiet. */
    val verdictMark: Mark?,
    /** «скан 20.10 19:31 · 68 из 68 блоков · маска 0D · 19,9 с». */
    val line: String,
    /** «12 В», «ИЗОЛЯЦИЯ», «Δ ЯЧЕЕК», «SOH», in that order. */
    val vitals: List<Vital>,
    /** «В ИСТОРИИ · 18 ЗАПИСЕЙ · 6 БЛОКОВ» with the worst mark; null when there is no record. */
    val band: SummaryBand?,
    /** The systems with records, two columns balanced by rows, each in the rail's order. */
    val columns: List<List<SummarySystem>>,
    /** «62 блока без записей · все ответили»; «1 блок не ответил» lit by [restAttention]. */
    val rest: String,
    val restAttention: Boolean,
    /** «148 обменов · медиана 18 мс · снимок 19:31:29». */
    val footnote: String,
)

internal data class SummaryBand(
    /** [parts]' words in one line. */
    val text: String,
    /** The worst of [parts]. */
    val mark: Mark,
    /** «в т. ч. 5 — потеря связи между блоками, U…-87»; null when there is none. */
    val note: String?,
    /** One a kind present, worst first, each with its own mark: «АКТИВНЫЕ · 1 ЗАПИСЬ», … «В ИСТОРИИ · 16 ЗАПИСЕЙ · 6 БЛОКОВ». */
    val parts: List<BandPart> = emptyList(),
)

internal data class BandPart(val mark: Mark, val text: String)

/** «КУЗОВ · 10» over its blocks with records. */
internal data class SummarySystem(val title: String, val blocks: List<SummaryBlock>)

internal data class SummaryBlock(
    val request: Int,
    val abbreviation: String,
    /** «720». */
    val address: String,
    val name: String,
    val mark: Mark,
    val count: Int,
    /** «с 18.10»: the journal's first reading of a record of it; null without a journal. */
    val since: String?,
    val records: List<SummaryRecord>,
)

internal data class SummaryRecord(
    /** «U0121-87». */
    val code: String,
    /** «нет связи с ABS» (a meaning), else the failure type's words «нет сообщения». */
    val words: String,
    /** [words] is the code's meaning, not only its failure type: drawn brighter. */
    val meaning: Boolean,
    /** A lost communication, «СЕТЬ». */
    val network: Boolean,
    /** Its own mark where it is worse than the block's history; null otherwise. */
    val mark: Mark?,
    /** Not there in the previous reading of its block: «НОВЫЙ». */
    val fresh: Boolean,
)

// ------------------------------------------------------------------ «Блоки»

internal enum class BlocksFilter { ALL, WITH_RECORDS, NO_REPLY, SOFTWARE_CHANGED }

/** «Блоки» (board `scanner-blocks`): every catalogued block, those with records first. */
internal data class BlocksView(
    /** «· 68 · все ответили». */
    val aside: String,
    /** «скан 20.10 19:31 · 148 обменов · 19,9 с · маска 0D». */
    val line: String,
    /** «Все · 68», «С записями · 6», «Без ответа · 0», «ПО изменено · 0». */
    val filters: List<Pair<BlocksFilter, String>>,
    /** «С ЗАПИСЯМИ · 6», «БЕЗ ЗАПИСЕЙ · 62», «НЕТ ОТВЕТА · 1». */
    val groups: List<BlocksGroup>,
    val footnote: String,
)

internal data class BlocksGroup(val title: String, val rows: List<BlockRow>)

internal data class BlockRow(
    val request: Int?,
    val abbreviation: String,
    val name: String,
    /** «720 → 728». */
    val route: String,
    /** «UDS», «OBD + UDS». */
    val protocol: String,
    val mark: Mark?,
    /** «8», «—». */
    val records: String,
    /** F194 in fours, «4B4D 0011 DE01 1406 00»; «—». */
    val software: String,
    /** F194 or F195 changed since the journal's previous reading of the block: lit. */
    val softwareChanged: Boolean,
    /** «85 мс», «—». */
    val latency: String,
    /** The filters the row passes; [BlocksFilter.ALL] always. */
    val filters: Set<BlocksFilter>,
    val target: Target?,
)

// ------------------------------------------------------------------ the code's screen

/** One record on its own screen (boards `scanner-code`, `-code-plain`), over the whole window. */
internal data class CodeView(
    val request: Int,
    /** «U0121-87». */
    val code: String,
    /** «· LA_BCM 720 · Кузовной контроллер левый». */
    val aside: String,
    /** «SAE J2012 · U — сеть · 0121 — связь с ABS · 87 — нет сообщения». */
    val line: String,
    /** «СТАТУС» 0x08, «ВПЕРВЫЕ» 18.10, «В СКАНАХ» 5 из 5. */
    val counters: List<HeadCounter>,
    /** «Нет связи с блоком ABS»; the failure type's words where no meaning is known. */
    val meaning: String,
    /** «ABS в этой машине — ESP/IPB 782»; null where the meaning names no catalogued block. */
    val meaningNote: String?,
    val status: StatusByteView,
    /** «19 01 0D → 59 01 09 00 00 08», «19 02 0D → … C1 21 87 08 …». */
    val raw: List<RawLine>,
    /** The block a lost-communication code names, as the last scan found it. */
    val addressee: AddresseeView?,
    /** «ТОТ ЖЕ ТИП ОТКАЗА · 87». */
    val sameTypeTitle: String,
    val sameType: List<CodeRef>,
    /** «5 кодов потери связи в 2 блоках»; null with none. */
    val sameTypeNote: String?,
    /** Its life in the journal; null before the journal has read it. */
    val timeline: CodeTimelineView?,
    /** «СТОП-КАДР · 19 04 · ЗАПИСЬ 01», or the OBD freeze frame. */
    val snapshot: ReadSection,
    /** «СЧЁТЧИКИ · 19 06». */
    val extended: ReadSection,
    /** «скан 23.10 09:12:40». */
    val footnote: String,
)

internal data class StatusByteView(
    /** «СТАТУС · 0x08 = 0000 1000». */
    val head: String,
    val byte: Int,
    /** A bit outside it is drawn «·». */
    val availability: Int,
    /** «подтверждён · сейчас не активен». */
    val words: String,
    /** ««·» — блок этот бит не сообщает · доступность 0x09»; «доступность 0xFF» when every bit is reported; null for an OBD list's code. */
    val legend: String?,
)

/** A request and its answer; [lit] is the record inside [text], drawn on a plate. */
internal data class RawLine(val text: String, val lit: IntRange? = null)

internal data class AddresseeView(
    /** «ABS»: the name the code's meaning gives it, the section's title «АДРЕСАТ · ABS». */
    val label: String,
    val request: Int,
    val abbreviation: String,
    val address: String,
    val name: String,
    /** «сейчас отвечает · записей нет · 262 мс». */
    val state: String,
    /** Its own worst record; null when it has none. */
    val mark: Mark?,
)

/** A record of the same failure type: «U029D-87 · LA_BCM 720 · с 18.10». */
internal data class CodeRef(val request: Int, val code: String, val block: String, val since: String?)

/** The record's life on the journal's axis. */
internal data class CodeTimelineView(
    /** «18.10» … «23.10», a label a day. */
    val days: List<String>,
    /** The reads that found it, as fractions of the axis; the first is where its line starts. */
    val seen: List<Float>,
    /** «впервые 18.10 15:42 · в каждом из 5 сканов». */
    val line: String,
)

/** A read's state as [ReadOutcome] has it; [INCONSISTENT] an answer that did not hold together, shown raw. */
internal enum class ReadState { NOT_READ, READING, EMPTY, READ, REFUSED, NO_REPLY, INCONSISTENT }

/** A read shown as a section: decoded rows where it decodes, the raw where it does not. */
internal data class ReadSection(
    val title: String,
    val state: ReadState,
    /** «Пробег» → «12 874 км»; [Field.muted] off and a lit value carried by [lit]. */
    val rows: List<Field>,
    /** Keys of [rows] whose value is outside its norm, drawn orange. */
    val lit: Set<String> = emptySet(),
    /** «DID DD01 · 00 32 4A 1C»: what came back undecoded. */
    val raw: List<String> = emptyList(),
    /** «записи нет», «блок не поддерживает», «читаю…»; null when rows or raw say it. */
    val word: String? = null,
)

// ------------------------------------------------------------------ «Тяговая батарея · Здоровье»

/** «Здоровье» (boards `scanner-sys-pack-health`, `-health-first`): the pack's first tab. */
internal data class HealthView(
    /** SOH, the spreads, the weakest cell, insulation, temperatures, flags, records - in the board's order. */
    val rows: List<HealthRow>,
    /** The cells as «Ячейки» draws them, small: the open page listens to them (7.3). */
    val cells: PackCellsView?,
    /** «обход 5,5 с · 0,4 с назад». */
    val age: String,
)

internal data class HealthRow(
    val name: String,
    /** «в покое · SOC 74 %», «по BMS». */
    val sub: String,
    val value: String,
    val unit: String,
    val outOfNorm: Boolean,
    /** «≥ 80 %» a norm, «обычно 3–6» this car's p10–p90 of 30 days, «7 из 9 снимков»; null none. */
    val reference: HealthReference?,
    /** A value a day for 30 days, oldest first, null a gap; empty without a journal. */
    val trend: List<Float?>,
    /** Not narrower than the norm or 20 × the resolution; null without a trend. */
    val trendScale: ClosedFloatingPointRange<Float>?,
    /** Points, not a line: a sparse series such as the full-charge spread. */
    val trendPoints: Boolean = false,
)

/** [USUAL] puts the word «обычно» before [text] in the label's ink. */
internal enum class ReferenceKind { NORM, USUAL, NOTE }

internal data class HealthReference(val text: String, val kind: ReferenceKind)

// ------------------------------------------------------------------ «Потоковые данные · Графики»

/** Whose live data the graphs are: a block's stream or a system's readings. */
internal sealed interface GraphScope {
    data class Block(val request: Int) : GraphScope
    data class System(val system: CarSystem) : GraphScope
}

/** «Графики» (board `scanner-sys-engine-graphs`): up to four signals on one time axis. */
internal data class GraphsView(
    val scope: GraphScope,
    val lanes: List<GraphLane>,
    /** «4 PID в 2 запросах · 8,3 Гц», «3 PID одним запросом · …»; «4 сигнала · обход 0,4 с» for a system's readings. */
    val line: String,
    /** The car's clock at the right edge. */
    val nowNanos: Long,
    /** «окно 60 с · 499 точек на сигнал». */
    val footnote: String,
)

internal data class GraphLane(
    /** «Обороты коленвала». */
    val name: String,
    /** «PID 0C · RPM», «1014 · 44600010». */
    val source: String,
    val unit: String,
    /** The last 5 minutes and the sample before them, oldest first; a null value is a gap. */
    val samples: List<Sample>,
    /** The lane's vertical scale: not narrower than the norm or 20 × the resolution. */
    val scale: ClosedFloatingPointRange<Float>,
    /** «2 200», «1 000». */
    val scaleText: Pair<String, String>,
    /** The norm drawn as a band; null where none is known. */
    val band: ClosedFloatingPointRange<Float>?,
    /** Decimals of a value read at the cursor: «1 882» 0, «18,0» 1. */
    val decimals: Int,
    /** What a tap on the lane opens alone. */
    val key: ParamKey?,
    /** A value read at the cursor carries its sign: «+3,1». */
    val signed: Boolean = false,
)

// ------------------------------------------------------------------ «Журнал»

/** «Журнал» (boards `scanner-journal`, `-journal-first`): the car over time, on one axis. */
internal data class JournalView(
    /** «· 18.10 — 23.10». */
    val aside: String,
    /** «7 сканов · 2 снимка ячеек · 1 обновление ПО». */
    val line: String,
    /** «НОВЫХ КОДОВ», «УШЛО», «ПО ИЗМЕНЕНО» of [compare], each only where it is not zero. */
    val counters: List<HeadCounter>,
    /** A label a day under the axis. */
    val days: List<String>,
    /** Scans: [JournalTick.full] a whole autoscan, else one block read by hand. */
    val scans: List<JournalTick>,
    /** «BMS 3.48.06 → 3.48.07» at its day. */
    val software: List<JournalSoftwareTick>,
    /** Cell snapshots of an opened battery page; a full charge lit and labelled. */
    val cells: List<JournalCellTick>,
    /** «КОДЫ · С ПЕРВОГО ПРОЧТЕНИЯ»: a lane a block with records. */
    val codes: List<JournalCodeLane>,
    /** «12 В на стоянке», «Δ ячеек, полный заряд»: a lane a quantity. */
    val quantities: List<JournalQuantityLane>,
    /** The panel «СРАВНЕНИЕ», or «СКАН» with one full scan ([ScanCompareView.title] without «→»); null with none. */
    val compare: ScanCompareView?,
    /** «Журнал · Коды»: a row a code the journal has read. */
    val codeRows: List<JournalCodeRow> = emptyList(),
)

/** «Журнал · Коды»: every code the journal has read - the present ones worst first, then the gone ones, newest gone first. */
internal data class JournalCodeRow(
    val request: Int,
    /** «U0121-87». */
    val code: String,
    /** «LA_BCM 720». */
    val block: String,
    /** Its mark in the newest read of its block; null once it is gone. */
    val mark: Mark?,
    /** «18.10 15:42». */
    val first: String,
    /** «23.10 08:50». */
    val last: String,
    /** «9 из 9»: reads of its block since the first that found it. */
    val seen: String,
    /** «есть», «ушёл 22.10». */
    val now: String,
    val gone: Boolean,
)

/** A mark on the axis, 0..1; [atMillis] the scan's end, what [DiagnosticsActions.compareScans] names it by. */
internal data class JournalTick(val at: Float, val full: Boolean, val atMillis: Long = 0)

internal data class JournalSoftwareTick(val at: Float, val text: String)

/** A cell snapshot on the axis; [label] «SOC 97 %» only at a full charge. */
internal data class JournalCellTick(val at: Float, val full: Boolean, val label: String?)

internal data class JournalCodeLane(
    val request: Int,
    /** «LA_BCM 720». */
    val block: String,
    val count: Int,
    val mark: Mark,
    /** The reads that found its records, 0..1; its line runs from the first to the last. */
    val seen: List<Float>,
    /** A later whole reading of the block held none of them: the lane ends at its last read, muted and with no sign. */
    val gone: Boolean = false,
)

internal data class JournalQuantityLane(
    val name: String,
    /** (at 0..1, value). */
    val points: List<Pair<Float, Float>>,
    val scale: ClosedFloatingPointRange<Float>,
    /** «13,6», «12 мВ»: the newest value, written at its point. */
    val last: String,
    /** Points only, no line between them. */
    val pointsOnly: Boolean,
    /** Each point's own words where they are worth reading one by one («11 мВ», «12 мВ»); empty - only [last]. */
    val pointLabels: List<String> = emptyList(),
)

/** «СРАВНЕНИЕ»: two scans, what came and went. */
internal data class ScanCompareView(
    /** «20.10 19:31 → 23.10 08:50». */
    val title: String,
    /** «новые» → «0», «ушли» → «0», «без изменений» → «18». */
    val codes: List<Field>,
    /** «BMS 7E7» → «3.48.06 → 3.48.07». */
    val software: List<Field>,
    /** «12 В» → «13,7 → 13,6 В». */
    val quantities: List<Field>,
)

// ------------------------------------------------------------------ «Тесты» of an OBD block

/**
 * «Тесты» (boards `scanner-ecu-tests`, `-ecu-tests-out`): the block's Mode 06 results as its screen read them on
 * opening (architecture, 6, 7.3), built by [TestsBuilder] from the tests' own bytes. The norm says nothing: a test
 * within its limits has no word, one outside them is orange, one that has not run since the codes were cleared
 * has no scale. Null on [ModuleView.tests] of a block whose screen does not read Mode 06.
 */
internal data class TestsView(
    val state: ReadState,
    /** «06 00 · 06 20 · 06 40 · 06 60 · 06 80 · 06 A0 → 11 мониторов · 19 тестов · 15:48:39»; null until the maps came. */
    val line: String?,
    /** «читаю…», «ECM не отдаёт тесты · 7F 06 12», «блок не перечисляет тестов»; null when the groups say it. */
    val word: String?,
    /** «06 00 → 46 00 00 00 00 00»: what came back where no group stands. */
    val raw: List<String>,
    /** The monitors in two columns balanced by lines; a monitor with a test outside its limits first, the rest by MID. */
    val columns: List<List<TestsGroup>>,
    /** Tests outside the block's limits: the head's «ВНЕ НОРМЫ n» when above 0. */
    val outOfNorm: Int,
    /** Tests within their limits. */
    val passed: Int,
    /** Tests that have not run since the codes were cleared (all three words zero). */
    val notRun: Int,
) {
    val groups: List<TestsGroup> get() = columns.flatten()
}

/** One monitor: «КАТАЛИЗАТОР, РЯД 1 · MID 21» over its tests by TID and, under them, its exchange. */
internal data class TestsGroup(
    /** «21». */
    val mid: String,
    /** «Катализатор, ряд 1»: SAE J1979's name of the monitor. */
    val name: String,
    /** «КАТАЛИЗАТОР, РЯД 1 · MID 21». */
    val title: String,
    val rows: List<TestRow>,
    /** «не отдаёт · 7F 06 31», «нет ответа · шлюз 3»: the monitor gave no tests; null when [rows] say it. */
    val word: String?,
    /** «06 21 → 46 21 80 03 00 3E 00 1E 00 64», two tests a line; a further line indented under the answer. */
    val raw: List<String>,
) {
    /** One of its tests is outside its limits. */
    val out: Boolean get() = rows.any { it.pass == false }

    /** How many lines it stands on: its title, a test or its word each, its exchange. */
    val lines: Int get() = 1 + (if (rows.isEmpty() && word != null) 1 else rows.size) + raw.size
}

/** One test: «TID 80 · 0,62 · 0,30 ├──●──┤ 1,00». */
internal data class TestRow(
    /** «80». */
    val tid: String,
    /** «03»: the unit and scaling ID. */
    val uasid: String,
    /** «0,62», «−2,34», «58»; «—» for a test that has not run. */
    val value: String,
    /** «мс», «В», «» for a count; «ед. блока» where the scaling ID is not in J1979's table and the numbers are the block's own. */
    val unit: String,
    val min: String,
    val max: String,
    /** Within the block's limits; null for a test that has not run. */
    val pass: Boolean?,
    /** Where the value stands on min … max, 0..1, held to its ends; null where no scale is drawn (not run, limits the wrong way round). */
    val position: Float?,
    /** −1 under the minimum, +1 over the maximum, 0 within: the side a mark at an end points out of. */
    val beyond: Int,
    /** The test's nine bytes, «21 80 03 00 3E 00 1E 00 64». */
    val raw: String,
)
