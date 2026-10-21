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
    /** The autoscan's groups in screen order, every catalogued ECU in one of them; a group without blocks is left out. */
    val groups: List<ModuleGroupView>,
    /** An ECU's own screen, by request address; present once it was scanned or opened. */
    val modules: Map<Int, ModuleView>,
    val systems: SystemsView,
    /** The systems down the left, «Автоскан» first; a system without blocks is not there (architecture, 7.2). */
    val rail: List<RailItem> = emptyList(),
    /** Each system's page, by system; every [CarSystem] with blocks has one once a scan or a reading exists. */
    val systemPages: Map<CarSystem, SystemPage> = emptyMap(),
    /** The parameter page the screens asked for with [DiagnosticsActions.setParam]; null when none (architecture, 6.2). */
    val param: ParamView? = null,
    /** «Тяговая батарея · Ячейки» while [DiagnosticsActions.setCellsVisible] holds it; null otherwise (architecture, 6.3). */
    val packCells: PackCellsView? = null,
)

/**
 * Every cell's voltage and every probe's temperature as the BMS sends them in pages of its own
 * stream (1039 / 9900001F, pages 43D and 43C; board `scanner-sys-pack-cells`). A position is the
 * BMS's own numbering: its min/max indices point at the same places.
 */
internal data class PackCellsView(
    /** 166 on this car, in the BMS's order; a value is null until its page has come. */
    val cells: List<CellReading>,
    /** 14 on this car, °C. */
    val probes: List<CellReading>,
    /** «МИН» → «3 280», «МАКС», «СРЕДН.», «РАЗБРОС» over the cells now held. */
    val cellStats: List<HeadCounter>,
    /** «МИН» → «10 °C», «МАКС», «СРЕДН.». */
    val probeStats: List<HeadCounter>,
    /** The chart's range, rounded outward to its grid, and the grid with its labels. */
    val cellScale: ClosedFloatingPointRange<Float>,
    val cellGrid: List<Float>,
    val cellGridText: List<String>,
    /** The bars stand up or down from it; null before any cell. */
    val cellMean: Float?,
    /** 0-based; the first lowest and highest, as the BMS names them. */
    val cellMin: Int?,
    val cellMax: Int?,
    val probeMin: Int?,
    val probeMax: Int?,
    /** «обход 5,5 с · 0,4 с назад»: the last complete sweep's length and the newest page's age. */
    val age: String,
    /** «НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · 166», «ТЕМПЕРАТУРА ДАТЧИКОВ · 14». */
    val cellsHead: String,
    val probesHead: String,
)

/** One cell or probe: its value, its words, and whether its page is from the last sweep. */
internal data class CellReading(val value: Float?, val text: String, val fresh: Boolean)

/** What a parameter page reads alone (architecture, 6.2). */
internal sealed interface ParamKey {
    /** A Mode 01 PID of [request]: polled alone, the helper's gap at its smallest the car holds. */
    data class Pid(val request: Int, val pid: Int) : ParamKey
    /** A reading of the car - a `DiagnosticExtra.key`, or a hub signal's name: polled alone, about ten times a second. */
    data class Signal(val key: String) : ParamKey
}

/** One parameter on its own page (board `scanner-sys-engine-rpm`, `scanner-sys-engine-ect`). */
internal data class ParamView(
    val key: ParamKey,
    /** «Обороты коленвала». */
    val name: String,
    /** «· PID 0C · RPM», «· 1014·44600010». */
    val aside: String,
    /** «7E0 → 7E8 · Mode 01 · только 0C · 24,1 Гц · обмен 18 мс». */
    val line: String,
    /** «1 250»; «н/д» before the first reading. */
    val value: String,
    val unit: String,
    val outOfNorm: Boolean,
    /** The norm drawn as a band; null where none is known. */
    val norm: ClosedFloatingPointRange<Float>?,
    /** «80…105», «—». */
    val normText: String,
    /** The last five minutes, oldest first, timed by the car; a null value is a gap. */
    val samples: List<Sample>,
    /** The chart's right edge on the car's clock. */
    val nowNanos: Long,
    /** Per window - 10, 60 and 300 s - what the head, the side and the chart's scale say. */
    val windows: Map<Int, ParamStats>,
    val running: Boolean,
    /** Measured: «24,1 Гц · 18 мс»; «—» before two samples. */
    val rate: String,
    /** «Запись» is offered: a PID's stream can be written, a car signal's not. */
    val recordable: Boolean,
    val recording: Boolean,
)

internal data class ParamStats(
    val min: String,
    val max: String,
    val mean: String,
    /** The dashed lines; null before a reading in the window. */
    val minValue: Float?,
    val maxValue: Float?,
    /** The chart's vertical range, rounded outward to its grid. */
    val scale: ClosedFloatingPointRange<Float>,
    val grid: List<Float>,
    /** The grid's labels: «2 200», «90». */
    val gridText: List<String>,
    /** The change over the last second: «+12», «−0,1», «—». */
    val delta: String,
    /** Readings in the window: «1 441». */
    val points: String,
)

/**
 * The car's systems, in the rail's order (architecture, 7.2): [railName] down the left, [title] in
 * the page's head. A block may stand in two systems - the BMS in the HV system and the pack, the
 * PTC in the HV system and the climate - and counts in both.
 */
internal enum class CarSystem(val railName: String, val title: String) {
    HV("ВВ-система", "Высоковольтная система"),
    PACK("Тяговая батарея", "Тяговая батарея"),
    DRIVES("Электропривод", "Электропривод"),
    ENGINE("Двигатель", "Двигатель"),
    CHASSIS("Шасси", "Шасси"),
    SAFETY("Безопасность", "Безопасность"),
    ADAS("Ассистенты", "Ассистенты"),
    BODY("Кузов", "Кузов"),
    CABIN("Салон", "Салон"),
    CLIMATE("Климат", "Климат"),
    LV("Сеть 12 В", "Сеть 12 В"),
    OTHER("Прочие блоки", "Прочие блоки"),
}

/** A line of the rail: «Автоскан» ([system] null) or a system, its mark only where there are codes. */
internal data class RailItem(
    val system: CarSystem?,
    val name: String,
    /** «68/68» for the autoscan, «ИСТ 10», «АКТ 2»; null for none. */
    val mark: String?,
    val tone: RailTone,
)

internal enum class RailTone { NONE, COUNT, HISTORY, ACTIVE }

/** A system's tabs; which ones it has is [SystemPage.tabs]. */
internal enum class SystemTab(val label: String) {
    /** The scheme, [SystemPage.scheme]. */
    STATE("Состояние"),
    /** The pack's readings ([SystemsView.pack]) or the engine's stream (ECM's [ObdView.live]). */
    LIVE("Потоковые данные"),
    /** The drives' or the driver-assistance blocks side by side ([SystemsView.drives], [SystemsView.adas]). */
    COMPARE("Сравнение"),
    /** The pack's cells and probes, [DiagnosticsModel.packCells]. */
    CELLS("Ячейки"),
    GB_FLAGS("Флаги GB/T 32960"),
    /** Every record of the system's blocks, [SystemPage.dtc]. */
    DTC("DTC"),
    /** The engine's: ECM's own DTC lists and readiness, as its block screen has them. */
    DTC_READINESS("DTC и готовность"),
    /** The system's blocks as the autoscan's rows; a row opens the block. */
    BLOCKS("Блоки"),
}

internal data class SystemPage(
    val system: CarSystem,
    /** «· 11 блоков», «· BMS 7E7», «· ECM 7E0 · UAES». */
    val aside: String,
    /** The mono line under the title: its blocks and where its readings come from. */
    val line: String,
    /** The head's counters left to right: «DTC ИСТ.» → «10», «U» → «553 В». */
    val counters: List<HeadCounter>,
    val tabs: List<SystemTab>,
    /** «Блоки»: its blocks as the autoscan's rows, in the catalog's order. */
    val blocks: List<ModuleRowView>,
    /** «DTC»: every record of its blocks, block by block in [blocks]' order. */
    val dtc: List<SystemDtcRow>,
    /** «19 01 09 · 19 02 09 · снимок 19:31:29»; «не читали» before any read. */
    val dtcFootnote: String,
    /** «Состояние»; null for a system without one (cabin, other blocks). */
    val scheme: Scheme?,
)

internal data class HeadCounter(val label: String, val value: String, val on: Boolean = false)

/** One record on a system's «DTC»: the block named on its first row, the row opening the block. */
internal data class SystemDtcRow(
    val request: Int,
    /** «LA_BCM». */
    val block: String,
    /** «720». */
    val address: String,
    /** «C1160-06». */
    val code: String,
    /** «06», «—». */
    val failureType: String,
    /** «Отказ алгоритма». */
    val failureName: String,
    /** The catalog's description, else the code's group: «Шасси · код производителя (C1xxx)». */
    val description: String,
    /** «0x08». */
    val status: String,
    /** «подтверждённый», «активный, подтверждённый». */
    val word: String,
    /** testFailed set: the row is drawn in the car's orange. */
    val active: Boolean,
)

/** A wheel's or a door's corner of the car seen from above, front up. */
internal enum class Corner { FL, FR, RL, RR }

/** A plate of lines beside a scheme: «ПЕРЕДНИЙ МОТОР · FMCU 7E3» over its readings. */
internal data class Callout(val label: String, val rows: List<String>)

/**
 * A system's «Состояние» (architecture, 7.2; boards `scanner-sys-*`). Tiles are the autoscan's
 * rows ([ModuleRowView]): abbreviation, address, short name, status, history; null when the
 * catalog has no such block. A value nobody read is «н/д», never zero.
 */
internal sealed interface Scheme {
    /** The HV schema: [SystemsView.hv] at the rail's width. */
    data object Hv : Scheme

    data class Pack(
        /** The charge's fill, 0..100; null leaves the pack empty. */
        val soc: Float?,
        val socText: String,
        val soh: String,
        /** «166 ячеек · 14 датчиков». */
        val cells: String,
        val volts: String,
        val current: String,
        /** «канал BMC» → «14,4 МОм». */
        val insulation: List<Field>,
        /** «норма ≥ 500 Ом/В · 277 кОм». */
        val isoNorm: String,
        val ranges: List<RangeView>,
    ) : Scheme

    data class Drives(
        val vcu: ModuleRowView?,
        val front: Callout,
        val isg: Callout,
        /** «ЗАДНИЙ ЛЕВЫЙ · SMCU 744»: SMCU drives the left rear motor, SMCU3 the right (findings 2026-10-21). */
        val rearLeft: Callout,
        val rearRight: Callout,
        val engineRunning: Boolean,
        /** PDC-VCU 743, the power-domain controller beside the VCU. */
        val powerDomain: ModuleRowView? = null,
    ) : Scheme

    /** The engine's air path; its readings come from ECM's stream, which runs while it is shown. */
    data class Engine(val points: List<EnginePoint>) : Scheme

    data class Chassis(
        val tyres: List<Tyre>,
        val esp: ModuleRowView?,
        val eps: ModuleRowView?,
        val bscm: ModuleRowView?,
        /** RWS-L 582 and RWS-R 796, the rear wheels' steering, at the rear wheels. */
        val rearSteerLeft: ModuleRowView? = null,
        val rearSteerRight: ModuleRowView? = null,
        /** IDC 597, the vehicle motion controller: suspension and posture. */
        val motion: ModuleRowView? = null,
    ) : Scheme

    data class Body(
        val doors: List<Door>,
        val front: ModuleRowView?,
        val back: ModuleRowView?,
        val left: ModuleRowView?,
        val right: ModuleRowView?,
        val lampLeft: ModuleRowView?,
        val lampRight: ModuleRowView?,
        val uwb: ModuleRowView?,
        /** The four UWB anchors' statuses, FL FR RL RR. */
        val anchors: List<ModuleStatus>,
        /** The doors' obstacle radars, SDR 5B2 5B1 5B0 5A7, at their doors. */
        val doorRadars: Map<Corner, ModuleRowView> = emptyMap(),
        /** HFRM 754, the key's RF receiver; SEC 7D6, smart entry; R-NCM 595, the right NFC. */
        val keyReceiver: ModuleRowView? = null,
        val smartEntry: ModuleRowView? = null,
        val nfc: ModuleRowView? = null,
        /** PDCMB 724, the tailgate's projection sensing. */
        val tailgate: ModuleRowView? = null,
    ) : Scheme

    data class Adas(
        val front: ModuleRowView?,
        val corners: Map<Corner, ModuleRowView>,
        val controller: ModuleRowView?,
        val parking: ModuleRowView?,
    ) : Scheme

    data class Climate(
        val outside: ClimateZone,
        val cabin: ClimateZone,
        val unit: ModuleRowView?,
        val heater: ModuleRowView?,
        val thermal: ModuleRowView?,
        /** AQDM 590, the air-quality detection module, at the cabin. */
        val air: ModuleRowView? = null,
    ) : Scheme

    /** The car from above: the airbag controller, the belt pretensioners at the front seats, the rear child-presence radar. */
    data class Safety(
        val airbags: ModuleRowView?,
        /** MSRZ 742, the driver's (left) belt; MSRF 713, the front passenger's. */
        val driverBelt: ModuleRowView?,
        val passengerBelt: ModuleRowView?,
        /** CPD 5E0. */
        val childRadar: ModuleRowView?,
    ) : Scheme

    data class Lv(
        /** «553 В», the DC bus. */
        val hv: String,
        /** «13,7 В». */
        val lv: String,
        val dcdc: ModuleRowView?,
        val battery: ModuleRowView?,
        /** The body's controllers on the 12 V line. */
        val loads: List<ModuleRowView>,
    ) : Scheme
}

/** A range on a scale: «ЯЧЕЙКИ, мВ» 3 333–3 337 on 3 000…3 700. */
internal data class RangeView(
    val head: String,
    val value: String,
    val scale: ClosedFloatingPointRange<Float>,
    /** What the readings take on the scale; null draws none. */
    val span: ClosedFloatingPointRange<Float>?,
    /** A mark across the bar: the modules' mean temperature. */
    val mark: Float?,
    val lo: String,
    val hi: String,
    val note: String,
)

internal enum class EnginePlace { INLET, THROTTLE, MANIFOLD, BLOCK_TOP, BLOCK_BOTTOM, CATALYST, OXYGEN }

internal data class EnginePoint(val place: EnginePlace, val label: String, val rows: List<String>)

internal data class Tyre(val corner: Corner, val label: String, val pressure: String, val temp: String)

internal data class Door(
    val corner: Corner,
    val label: String,
    /** «закрыта», «открыта», «приоткрыта», «н/д». */
    val state: String,
    /** «не заперта · окно 0 %». */
    val detail: String,
    /** Drawn swung out, in orange. */
    val open: Boolean,
)

internal data class ClimateZone(val label: String, val temp: String, val pm: String, val inside: Boolean)

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
    data class System(val screen: CarSystem) : Target
}

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
    /** What a tap on the row opens alone; null when it cannot be read on its own. */
    val key: ParamKey? = null,
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
    /** What a tap on the row opens alone; null for a calculation («расчёт»). */
    val key: ParamKey? = null,
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
    /** The main contactor (1032 / 34400010, architecture 6.3): true closed, false open, null not known. */
    val contactorClosed: Boolean? = null,
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
    /**
     * A parameter page shown ([key]) or left (null): while shown only it is polled - every other
     * stream and the autoscan stand - and [DiagnosticsModel.param] describes it (architecture, 6.2).
     */
    fun setParam(key: ParamKey?)
    /** «Пауза» / «Продолжить» on the parameter page. */
    fun setParamPaused(paused: Boolean)
    /** «Сбросить мин/макс» on the parameter page: its windows start over. */
    fun resetParam()
    /** «Ячейки» shown or left: the pages are listened to only while it is (architecture, 6.3). */
    fun setCellsVisible(visible: Boolean)
}
