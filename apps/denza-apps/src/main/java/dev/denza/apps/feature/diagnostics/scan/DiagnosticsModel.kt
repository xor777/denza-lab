package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.protocol.DataQuality

/**
 * What the diagnostic screens show, already decided: words, figures and states. The screens never
 * decode bytes or read the catalog. [DiagnosticsSession] builds it from the bus, the protocol and
 * the catalog; the debug fixtures build it from the Luminofor `scanner-*` scenes, so a screenshot
 * of a fixture and its board show the same model (architecture, 7).
 */
internal data class DiagnosticsModel(
    val header: Header,
    val scan: ScanSummary,
    /** The autoscan's groups in screen order, every catalogued ECU in one of them. */
    val groups: List<ModuleGroupView>,
    /** An ECU's own screen, by request address; present once it was scanned or opened. */
    val modules: Map<Int, ModuleView>,
    val systems: SystemsView,
)

internal data class Header(
    val link: LinkState,
    /** «Z9 GT DM · P · 0 км/ч», «Z9 GT DM · P · ДВС работает». */
    val car: String,
    /** The car's clock, «16:31:07». */
    val clock: String,
)

internal enum class LinkState {
    /** The section is open and nothing was asked yet. */
    IDLE,
    CONNECTING,
    OK,
    /** No trusted local ADB. */
    NO_ACCESS,
    /** The stock diagnostic manager, another reader or foreign traffic holds the channel. */
    BUSY,
    /** Not profile 170, not in P or not at 0 km/h. */
    NOT_PARKED,
    LOST,
}

internal data class ScanSummary(
    val phase: ScanPhase,
    /** ECUs finished in the running scan, of [total]. */
    val done: Int,
    val total: Int,
    /** When the shown scan finished, «16:31»; null before the first. */
    val finishedAt: String?,
    /** «43/46»: answered of catalogued. */
    val answered: String,
    /** Records with testFailed set, over every memory read; null before a scan. */
    val active: Int?,
    /** Confirmed records without testFailed. */
    val history: Int?,
    /** «16/43»: memories read of answered. */
    val memoryRead: String,
    val mil: MilState,
    /** One sentence for the band above the list when the scan stopped for a reason. */
    val notice: String?,
)

internal enum class ScanPhase { NEVER, RUNNING, PAUSED, STOPPED, DONE }

internal enum class MilState { ON, OFF, UNKNOWN }

internal data class ModuleGroupView(
    /** Anchor for the jump links: `power`, `chassis`, … */
    val id: String,
    /** «СИЛОВАЯ УСТАНОВКА». */
    val title: String,
    /** «Силовая». */
    val short: String,
    /** «8 бл. · прочитано 5 · нет адреса 3». */
    val summary: String,
    val rows: List<ModuleRowView>,
)

internal data class ModuleRowView(
    /** Null for an ECU without a route. */
    val request: Int?,
    val abbreviation: String,
    /** The unit column: «ECM · UAES». */
    val unit: String,
    /** «ЭБУ двигателя»; [inferred] puts «≈ » before it. */
    val name: String,
    /** The topology tile's name: «Двигатель». */
    val short: String,
    val inferred: Boolean,
    /** «7E0 → 7E8», or «—». */
    val route: String,
    /** «OBD-II, UDS», «UDS», «—». */
    val protocol: String,
    /** «0», «2», «—» where the memory was not read. */
    val active: String,
    val history: String,
    val status: ModuleStatus,
    /** Where a tap leads; null when nowhere. */
    val target: Target?,
)

internal enum class ModuleStatus {
    OK,
    /** Only confirmed records without testFailed. */
    HISTORY,
    /** At least one record with testFailed, or MIL on. */
    ACTIVE,
    /** Answered identification, its memory not read. */
    UNREAD,
    POLLING,
    NO_REPLY,
    /** A negative response to the memory read. */
    REFUSED,
    /** F194 is not the catalog's: another ECU on this route, its memory not read. */
    OTHER_ECU,
    /** The answer did not hold together. */
    INCONSISTENT,
    NO_ROUTE,
}

/** Where a row, a tile or a box leads. */
internal sealed interface Target {
    data class Module(val request: Int) : Target
    data class System(val screen: SystemScreen) : Target
}

internal enum class SystemScreen { HV, PACK, DRIVES, BRAKES, ADAS }

/** One ECU's screen: «Ошибки DTC», «Идентификация», and for an OBD responder readiness and live data. */
internal data class ModuleView(
    val request: Int,
    val name: String,
    val unit: String,
    val inferred: Boolean,
    /** The mono line under the name: route, protocols, DTC availability and format, F194 or F195. */
    val line: String,
    val status: ModuleStatus,
    val dtc: DtcView,
    val ident: IdentView,
    val obd: ObdView?,
)

internal data class DtcView(
    /** What was asked and what came back, one row per request kind. */
    val asks: List<AskRow>,
    val records: List<DtcRow>,
    val quality: DataQuality,
    /** The ECU returned records outside the request mask (architecture, 4). */
    val outsideMask: Boolean,
    /** «19 01 09 · 19 02 09 · снимок 16:31:07». */
    val footnote: String,
    /**
     * «Описания: каталог мануала Z9GT PHEV, перевод» when at least one row has a
     * [DtcRow.description]; the source of every description on the screen, said once. Null otherwise.
     */
    val source: String? = null,
)

internal data class AskRow(
    /** «Отказ сейчас или подтверждённые». */
    val name: String,
    /** «19 02 09», «Mode 03». */
    val service: String,
    /** «0», «2», «не запрашивали», «нет ответа». */
    val count: String,
    val asked: Boolean,
)

internal data class DtcRow(
    /** «C1170-09», or six hex digits for format 01. */
    val code: String,
    /** «09». */
    val failureType: String,
    /** «Отказ компонента». */
    val failureName: String,
    /** «Шасси · код производителя (C1xxx)». */
    val group: String,
    /**
     * The word of a qualified BYD dictionary when there is one; empty now - there is none, and the
     * row says nothing about it (owner's decision 2026-10-20).
     */
    val note: String,
    /** The ISO 14229-1 status byte; the screen draws its eight bits. */
    val status: Int,
    /** The availability mask: a bit outside it is drawn as not supported. */
    val availability: Int,
    /**
     * The row's description: the Russian heading the public catalog gives this code in this module's
     * section, as plain text - «Датчик атмосферного давления: неисправность сигнала». Two headings of
     * one section are «A / B»; the rear drives' sections, when they differ, «Л: A / П: B». Null where
     * the catalog has nothing: another module, format 01, a format nobody reported, a code it does
     * not list. Its source is the screen's footer ([DtcView.source]), never the row.
     */
    val description: String? = null,
    /** What [description] is made of, with the Chinese original; not drawn on the row. */
    val reference: List<DtcReference> = emptyList(),
)

/** One heading of the public catalog behind a [DtcRow.description]. */
internal data class DtcReference(
    /** «P222729». */
    val code: String,
    val titleRu: String,
    /** The public Chinese heading the translation was made from. */
    val titleZh: String,
    /** «engine_ecm · outline_26_a»: where it stands in the index; both rear sections when equal there. */
    val locators: List<String>,
)

internal data class Field(val key: String, val value: String, val muted: Boolean = false)

internal data class IdentView(
    /** What the ECU answered: route, protocols, F18A, F194, F195, F197, PID 1C, DTC format, VIN policy. */
    val own: List<Field>,
    /** What the stock OTA cache or route list knows: identifier, versions. */
    val cache: List<Field>,
    /** F194 against the catalog: true matched, false another ECU, null not read. */
    val matched: Boolean?,
    /** Supported Mode 01 PIDs, «0C»; the bitmap PIDs come last and are drawn muted by the screen. */
    val pids: List<String>,
)

internal data class ObdView(
    val mil: MilState,
    /** «MIL выключена · DTC, включивших MIL: 0 · зажигание искровое». */
    val milLine: String,
    /** «41 01 00 07 F5 F4». */
    val raw: String,
    /** «4/9». */
    val readiness: String,
    val monitors: List<MonitorRow>,
    /** Mode 03, 07, 0A and the UDS reads. */
    val lists: List<AskRow>,
    val live: LiveView,
)

internal data class MonitorRow(
    val name: String,
    val abbreviation: String,
    /** «непрерывный», «разовый», «—». */
    val kind: String,
    val state: MonitorState,
)

internal enum class MonitorState { READY, NOT_READY, UNSUPPORTED }

/** The live stream of one OBD responder (architecture, 6.1). */
internal data class LiveView(
    val running: Boolean,
    /** Measured, not set: «4,6 Гц · 120 мс»; «—» before two samples. */
    val rate: String,
    /** PIDs in focus, polled first and drawn largest. */
    val focus: List<Int>,
    val rows: List<LiveRow>,
    val recording: Boolean,
)

internal data class LiveRow(
    /** The Mode 01 PID; negative for a fast car signal beside the PIDs, whose [source] names it. */
    val pid: Int,
    /** «Температура ОЖ». */
    val name: String,
    /** «ECT». */
    val abbreviation: String,
    val value: String,
    val unit: String,
    val min: String,
    val max: String,
    /** «80…105», «—». */
    val norm: String,
    val outOfNorm: Boolean,
    /** «PID», or the source of a fast car signal, «autoservice · 100 мс». */
    val source: String,
    /** The last 60 s, oldest first, timed by the car. */
    val samples: List<Sample>,
)

/** One reading on the car's clock; a null [value] is a gap the chart leaves open. */
internal data class Sample(val atNanos: Long, val value: Float?)

/** The system screens: values from `autoservice` and the ECUs they belong to. */
internal data class SystemsView(
    val hv: HvView,
    val pack: List<SignalRow>,
    val drives: CompareView,
    val adas: CompareView,
    /** The brake screen is the ESP/IPB module beside the OBD interface: both are in [DiagnosticsModel.modules]. */
    val brakeModules: List<Int>,
)

/** A value of the car's signal bus, with where it comes from and whether it is still fresh. */
internal data class SignalRow(
    /** «1014·44400028», «расчёт». */
    val source: String,
    val name: String,
    val value: String,
    val unit: String,
    val norm: String,
    /** True when the value is inside [norm], false outside, null when there is no norm. */
    val ok: Boolean?,
    val stale: Boolean,
)

/** ECUs side by side: the drives, the driver-assistance modules. */
internal data class CompareView(
    val columns: List<CompareColumn>,
    val rows: List<CompareRow>,
    /** Sentences under the table. */
    val notes: List<String>,
)

internal data class CompareColumn(val abbreviation: String, val name: String, val request: Int?)

internal data class CompareRow(
    val name: String,
    /** «1039 · 46406018 · …», or empty. */
    val source: String,
    val unit: String,
    val cells: List<CompareCell>,
)

internal data class CompareCell(
    val text: String,
    /** Drawn muted: «—», «н/д», «не читали», «нет адреса». */
    val muted: Boolean = false,
    /** One value shared by neighbours, drawn as a band: the corner radars' F194. */
    val shared: Boolean = false,
    /** Worth a second look: ADC's DoIP route, its format 01. */
    val warn: Boolean = false,
)

internal data class HvView(
    /** The pack box: «Напряжение / ток» → «553 В · −4,5 А». */
    val pack: List<Field>,
    val packStatus: ModuleStatus,
    /** «555 В», front and rear bus. */
    val busVoltages: List<String>,
    val units: List<HvUnit>,
    /** The machines' lines: «Передний мотор» → «статор 8 °C · 0 об/мин · 0 Н·м». */
    val machines: List<Field>,
)

internal data class HvUnit(
    val abbreviation: String,
    /** «7E3», «—». */
    val address: String,
    val name: String,
    /** «OK · IPM 9 °C», «нет адреса · в кэше». */
    val state: String,
    val status: ModuleStatus,
    val target: Target?,
)

/** What the screens may ask of the session; everything else is the session's own business. */
internal interface DiagnosticsActions {
    /** «Опросить снова»: a new autoscan, and the only way past a busy channel or a lost link. */
    fun rescan()
    /** «Прочитать DTC» on an ECU's screen. */
    fun readModule(request: Int)
    /** An ECU's screen opened or closed: its identification and OBD readiness are read once. */
    fun setModuleOpen(request: Int, open: Boolean)
    /** «Потоковые данные» visible or not: the stream runs only while it is. */
    fun setLiveVisible(request: Int, visible: Boolean)
    /** «Выбор PID». */
    fun setFocus(request: Int, pids: List<Int>)
    fun resetMinMax(request: Int)
    /** «Запись»: the stream to `filesDir/diagnostics/stream-<time>.csv`. */
    fun setRecording(request: Int, on: Boolean)
}
