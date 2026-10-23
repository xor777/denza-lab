package dev.denza.apps.debug

import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.AskRow
import dev.denza.apps.feature.diagnostics.scan.BodyLamps
import dev.denza.apps.feature.diagnostics.scan.Callout
import dev.denza.apps.feature.diagnostics.scan.CabinPlace
import dev.denza.apps.feature.diagnostics.scan.CabinPlate
import dev.denza.apps.feature.diagnostics.scan.CabinRow
import dev.denza.apps.feature.diagnostics.scan.CabinRun
import dev.denza.apps.feature.diagnostics.scan.CabinSeat
import dev.denza.apps.feature.diagnostics.scan.CabinTone
import dev.denza.apps.feature.diagnostics.scan.CellReading
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.ClimateZone
import dev.denza.apps.feature.diagnostics.scan.CompareCell
import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.scan.CompareRow
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.Corner
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Door
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.EnginePlace
import dev.denza.apps.feature.diagnostics.scan.EnginePoint
import dev.denza.apps.feature.diagnostics.scan.Epb
import dev.denza.apps.feature.diagnostics.scan.EpbLook
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.FridgeMode
import dev.denza.apps.feature.diagnostics.scan.GbFlagGroup
import dev.denza.apps.feature.diagnostics.scan.GbFlagState
import dev.denza.apps.feature.diagnostics.scan.GbFlagView
import dev.denza.apps.feature.diagnostics.scan.GbFlagsView
import dev.denza.apps.feature.diagnostics.scan.Header
import dev.denza.apps.feature.diagnostics.scan.HeadCounter
import dev.denza.apps.feature.diagnostics.scan.HvUnit
import dev.denza.apps.feature.diagnostics.scan.HvView
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.Lid
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.LiveRow
import dev.denza.apps.feature.diagnostics.scan.LiveView
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.ParamStats
import dev.denza.apps.feature.diagnostics.scan.ParamView
import dev.denza.apps.feature.diagnostics.scan.MonitorRow
import dev.denza.apps.feature.diagnostics.scan.MonitorState
import dev.denza.apps.feature.diagnostics.scan.ObdView
import dev.denza.apps.feature.diagnostics.scan.PackCellsView
import dev.denza.apps.feature.diagnostics.scan.PadState
import dev.denza.apps.feature.diagnostics.report.ReportBlock
import dev.denza.apps.feature.diagnostics.report.ReportCode
import dev.denza.apps.feature.diagnostics.report.ReportCodeKind
import dev.denza.apps.feature.diagnostics.report.ReportEngine
import dev.denza.apps.feature.diagnostics.report.ReportExchanges
import dev.denza.apps.feature.diagnostics.report.ReportInput
import dev.denza.apps.feature.diagnostics.report.ReportOption
import dev.denza.apps.feature.diagnostics.report.ReportPack
import dev.denza.apps.feature.diagnostics.report.ReportRecording
import dev.denza.apps.feature.diagnostics.report.ReportSection
import dev.denza.apps.feature.diagnostics.report.ReportState
import dev.denza.apps.feature.diagnostics.report.ReportSummary
import dev.denza.apps.feature.diagnostics.scan.AddresseeView
import dev.denza.apps.feature.diagnostics.scan.BandPart
import dev.denza.apps.feature.diagnostics.scan.BlockRow
import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.BlocksView
import dev.denza.apps.feature.diagnostics.scan.CodeRef
import dev.denza.apps.feature.diagnostics.scan.CodeTimelineView
import dev.denza.apps.feature.diagnostics.scan.CodeView
import dev.denza.apps.feature.diagnostics.scan.GraphLane
import dev.denza.apps.feature.diagnostics.scan.GraphScope
import dev.denza.apps.feature.diagnostics.scan.GraphsView
import dev.denza.apps.feature.diagnostics.scan.HealthReference
import dev.denza.apps.feature.diagnostics.scan.HealthRow
import dev.denza.apps.feature.diagnostics.scan.HealthView
import dev.denza.apps.feature.diagnostics.scan.JournalCellTick
import dev.denza.apps.feature.diagnostics.scan.JournalCodeLane
import dev.denza.apps.feature.diagnostics.scan.JournalQuantityLane
import dev.denza.apps.feature.diagnostics.scan.JournalSoftwareTick
import dev.denza.apps.feature.diagnostics.scan.JournalTick
import dev.denza.apps.feature.diagnostics.scan.JournalView
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.MarkCount
import dev.denza.apps.feature.diagnostics.scan.RailLine
import dev.denza.apps.feature.diagnostics.scan.RailPlace
import dev.denza.apps.feature.diagnostics.scan.RangeView
import dev.denza.apps.feature.diagnostics.scan.RawLine
import dev.denza.apps.feature.diagnostics.scan.ReadSection
import dev.denza.apps.feature.diagnostics.scan.ReadState
import dev.denza.apps.feature.diagnostics.scan.ReferenceKind
import dev.denza.apps.feature.diagnostics.scan.ScanCompareView
import dev.denza.apps.feature.diagnostics.scan.StatusByteView
import dev.denza.apps.feature.diagnostics.scan.SummaryBand
import dev.denza.apps.feature.diagnostics.scan.SummaryBlock
import dev.denza.apps.feature.diagnostics.scan.SummaryRecord
import dev.denza.apps.feature.diagnostics.scan.SummarySystem
import dev.denza.apps.feature.diagnostics.scan.SummaryView
import dev.denza.apps.feature.diagnostics.scan.Vital
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec
import dev.denza.apps.feature.diagnostics.scan.Sample
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.Scheme
import dev.denza.apps.feature.diagnostics.scan.SeatBelt
import dev.denza.apps.feature.diagnostics.scan.SystemDtcRow
import dev.denza.apps.feature.diagnostics.scan.SystemLive
import dev.denza.apps.feature.diagnostics.scan.SystemLiveView
import dev.denza.apps.feature.diagnostics.scan.SystemPage
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.SystemStreams
import dev.denza.apps.feature.diagnostics.scan.SystemsView
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.scan.Tyre
import dev.denza.apps.feature.diagnostics.scan.WordPlate
import dev.denza.apps.feature.diagnostics.ui.ScannerRoute
import dev.denza.apps.feature.diagnostics.ui.ScannerSample
import dev.denza.apps.feature.diagnostics.ui.ScannerWords
import dev.denza.apps.feature.vehicle.VehicleSignal
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Luminofor `scanner-*` scenes (`fixtures.js`, exported to `luminofor/fixtures.json`) as the
 * [DiagnosticsModel] the screens draw, so a fixture's screenshot and its board show one model.
 *
 * A scene is data, not strings laid out: an ECU is its name, unit, request address, protocol and
 * kind, and the screens derive the rest - the reply address, the chips, the summaries - as the
 * board does. Every board shares the blocks of `scanner-blocks`, its own rail and every
 * system's page from the `scanner-sys-*` scenes; each adds its own header. What a scene draws that
 * a session would never say (its values are illustrative) comes back as a [ScannerSample].
 */
internal object ScannerFixtures {

    private const val ECM = 0x7E0
    private const val R_ZCU = 0x747
    private const val ESP = 0x782
    private const val BSCM = 0x7E5
    private const val BMS = 0x7E7

    private val summary = ScannerRoute.Summary
    private val blocks = listOf(summary, ScannerRoute.Blocks())

    /** Where each board's screen is: the stack the section opens with, «Сводка» at its bottom. */
    fun route(id: String): List<ScannerRoute> {
        fun sys(s: CarSystem, tab: SystemTab) = listOf(summary, ScannerRoute.System(s, tab))
        return when (id) {
            "scanner-summary", "scanner-summary-active" -> listOf(summary)
            "scanner-blocks" -> blocks
            "scanner-journal", "scanner-journal-first" -> listOf(summary, ScannerRoute.Journal(0))
            "scanner-journal-compare" -> listOf(summary, ScannerRoute.Journal(1))
            "scanner-journal-records" -> listOf(summary, ScannerRoute.Journal(2))
            "scanner-report" -> listOf(summary, ScannerRoute.Report)
            "scanner-code" -> listOf(summary, ScannerRoute.Code(0x720, "U0121-87"))
            "scanner-code-plain" -> listOf(summary, ScannerRoute.Code(R_ZCU, "C1170-09"))
            "scanner-sys-engine-graphs" -> listOf(summary, ScannerRoute.System(CarSystem.ENGINE, SystemTab.LIVE, graphs = true))
            "scanner-sys-engine" -> sys(CarSystem.ENGINE, SystemTab.LIVE)
            "scanner-sys-engine-rpm", "scanner-sys-engine-ect", "scanner-sys-pack-cell" -> {
                val key = PARAM_KEYS.getValue(id)
                sys(if (key is ParamKey.Pid) CarSystem.ENGINE else CarSystem.PACK, SystemTab.LIVE) + ScannerRoute.Param(key)
            }
            "scanner-sys-engine-scheme" -> sys(CarSystem.ENGINE, SystemTab.STATE)
            "scanner-sys-engine-dtc" -> sys(CarSystem.ENGINE, SystemTab.DTC_READINESS)
            "scanner-sys-body" -> sys(CarSystem.BODY, SystemTab.DTC)
            "scanner-sys-body-state", "scanner-sys-body-state-lit" -> sys(CarSystem.BODY, SystemTab.STATE)
            "scanner-sys-body-blocks" -> sys(CarSystem.BODY, SystemTab.BLOCKS)
            "scanner-sys-chassis" -> sys(CarSystem.CHASSIS, SystemTab.STATE)
            "scanner-sys-adas" -> sys(CarSystem.ADAS, SystemTab.STATE)
            "scanner-sys-adas-compare" -> sys(CarSystem.ADAS, SystemTab.COMPARE)
            "scanner-sys-drives" -> sys(CarSystem.DRIVES, SystemTab.STATE)
            "scanner-sys-drives-compare" -> sys(CarSystem.DRIVES, SystemTab.COMPARE)
            "scanner-sys-safety", "scanner-sys-safety-trip" -> sys(CarSystem.SAFETY, SystemTab.STATE)
            "scanner-sys-hv" -> sys(CarSystem.HV, SystemTab.STATE)
            "scanner-sys-pack-health", "scanner-sys-pack-health-first" -> sys(CarSystem.PACK, SystemTab.STATE)
            in LIVE_BOARDS -> sys(LIVE_BOARDS.getValue(id), SystemTab.LIVE)
            in FLAGS_BOARDS -> sys(CarSystem.PACK, SystemTab.GB_FLAGS)
            in CELLS_BOARDS -> sys(CarSystem.PACK, SystemTab.CELLS)
            "scanner-sys-climate" -> sys(CarSystem.CLIMATE, SystemTab.STATE)
            "scanner-sys-lv" -> sys(CarSystem.LV, SystemTab.STATE)
            in CABIN_BOARDS -> sys(CarSystem.CABIN, SystemTab.STATE)
            "scanner-body-dtc" -> blocks + ScannerRoute.Module(R_ZCU, 0)
            "scanner-ecu-dtc", "scanner-ecu-fault" -> blocks + ScannerRoute.Module(ECM, 0)
            "scanner-ecu-live" -> blocks + ScannerRoute.Module(ECM, 1)
            "scanner-ecu-ident" -> blocks + ScannerRoute.Module(ECM, 2)
            "scanner-ecu-ident-updated" -> blocks + ScannerRoute.Module(BMS, 1)
            "scanner-esp-dtc" -> listOf(summary, ScannerRoute.System(CarSystem.CHASSIS), ScannerRoute.Module(ESP, 0))
            else -> listOf(summary)
        }
    }

    /** The scenes' live traces are already 0..1 of their own range; the graphs' cursor is the board's. */
    fun sample(all: JSONObject, id: String): ScannerSample = ScannerSample(
        chartRange = 0f..1f,
        graphsCursor = if (id == GRAPHS) fixture(all, id).getDouble("cursor").toFloat() else null,
    )

    private const val GRAPHS = "scanner-sys-engine-graphs"

    fun model(all: JSONObject, id: String): DiagnosticsModel {
        // the v4 boards (2026-10-23): the blocks and the scan are `scanner-blocks`'; a board's header
        // is the car, its mileage and mode, 12 V and the link's median exchange
        val scanBoard = fixture(all, "scanner-blocks")
        val board = fixture(all, id)
        val top = board.getJSONObject("top")
        val scan = scanBoard.getJSONObject("scan")
        val groups = groups(scanBoard.getJSONArray("groups"))
        val ecus = (0 until scanBoard.getJSONArray("groups").length()).flatMap { objects(scanBoard.getJSONArray("groups").getJSONObject(it).getJSONArray("ecus")) }
        val answered = "${scan.getInt("answered")}/${scan.getInt("blocks")}"
        val summaryBoard = if (id == "scanner-summary-active") id else "scanner-summary"
        return DiagnosticsModel(
            header = Header(
                link = if (top.has("link")) LinkState.OK else LinkState.LOST,
                car = top.getString("car"),
                clock = top.getString("time"),
                odometer = top.optStringOrNull("odo"),
                mode = top.optStringOrNull("mode"),
                volts = top.optStringOrNull("volts"),
                voltsOut = top.optBoolean("voltsOut"),
                latency = top.optStringOrNull("link"),
            ),
            scan = ScanSummary(
                phase = ScanPhase.DONE, done = scan.getInt("answered"), total = scan.getInt("blocks"),
                finishedAt = scan.getString("at").substringAfter(' '),
                answered = answered, active = ecus.sumOf { it.optString("active").toIntOrNull() ?: 0 },
                history = ecus.sumOf { it.optString("history").toIntOrNull() ?: 0 }, memoryRead = answered,
                mil = MilState.OFF, notice = null,
            ),
            groups = groups,
            modules = modules(all, id),
            systems = systems(all, id),
            railLines = railLines(board.optJSONObject("rail") ?: fixture(all, summaryBoard).getJSONObject("rail")),
            systemPages = pages(all, groups, id),
            param = PARAM_KEYS[id]?.let { param(fixture(all, id), it) },
            packCells = if (id in CELLS_BOARDS) cells(fixture(all, id)) else null,
            summary = summary(fixture(all, summaryBoard)),
            blocks = blocksView(scanBoard),
            code = CODE_BOARDS[id]?.let { code(fixture(all, it)) },
            health = health(fixture(all, if (id in HEALTH_BOARDS) id else "scanner-sys-pack-health")),
            journal = if (id in JOURNAL_BOARDS) journal(fixture(all, id)) else null,
            report = if (id == "scanner-report") report(fixture(all, id), scanBoard, summary(fixture(all, "scanner-summary"))) else ReportState(),
        )
    }

    /* ------------------------------------------------------ the pack's cells */

    private val CELLS_BOARDS = setOf("scanner-sys-pack-cells", "scanner-sys-pack-cells-partial", "scanner-sys-pack-cells-paused")

    /**
     * «Ячейки» from its board (`sysPackCells()`): `cells` and `probes` are the BMS's values in its
     * order, `staleCells` / `staleProbes` the positions the drawn sweep did not refresh; the heads,
     * the reference, the grid, the sweep under way and the age are the board's; «Продолжить» in the
     * footer is the paused view.
     */
    private fun cells(f: JSONObject): PackCellsView {
        fun values(key: String) = f.getJSONArray(key).let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getDouble(it).toFloat() } }
        fun ints(key: String) = f.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        val staleCells = ints("staleCells").toSet()
        val staleProbes = ints("staleProbes").toSet()
        val gridText = f.getJSONArray("cellGridText").let { a -> (0 until a.length()).map(a::getString) }
        val buttons = arrays(f.getJSONObject("footer").getJSONArray("buttons")).map { it.getString(0) }
        return PackCellsView(
            cells = values("cells").mapIndexed { i, v -> CellReading(v, v?.let { grouped(it) } ?: "—", i !in staleCells) },
            probes = values("probes").mapIndexed { i, v -> CellReading(v, v?.let { Math.round(it).toString() } ?: "—", i !in staleProbes) },
            cellStats = arrays(f.getJSONArray("cellsStats")).map { HeadCounter(it.getString(0), it.getString(1)) },
            probeStats = arrays(f.getJSONArray("probesStats")).map { HeadCounter(it.getString(0), it.getString(1)) },
            cellRef = f.getInt("cellRef"),
            cellGrid = ints("cellGrid"),
            cellGridText = gridText,
            cellMin = f.getInt("minIndex"),
            cellMax = f.getInt("maxIndex"),
            probeMin = f.getInt("probeMin"),
            probeMax = f.getInt("probeMax"),
            sweep = if (f.isNull("sweep")) null else f.getDouble("sweep").toFloat(),
            paused = "Продолжить" in buttons,
            age = f.getString("cellsAge"),
            cellsHead = f.getString("cellsHead"),
            probesHead = f.getString("probesHead"),
        )
    }

    /* ------------------------------------------- the pack's GB/T 32960 flags */

    /** The parked sweep of 2026-10-20, and the same with the interlock open. */
    private val FLAGS_BOARDS = setOf("scanner-sys-pack-flags", "scanner-sys-pack-flags-alarm")

    /**
     * «Флаги GB/T 32960» from its board (`sysGbFlags()`): the head, its count lit by its third element,
     * and each group's tiles - bit, FID, name, the code read (none when absent), state and word.
     */
    private fun gbFlags(f: JSONObject): GbFlagsView = GbFlagsView(
        head = f.getString("head"),
        counters = arrays(f.getJSONArray("counters")).map { HeadCounter(it.getString(0), it.getString(1), on = it.optBoolean(2)) },
        groups = objects(f.getJSONArray("groups")).map { g ->
            GbFlagGroup(
                g.getString("title"),
                objects(g.getJSONArray("flags")).map { t ->
                    GbFlagView(
                        t.getInt("bit"), t.getString("fid"), t.getString("name"), if (t.isNull("raw")) null else t.getInt("raw"),
                        GbFlagState.valueOf(t.getString("state").uppercase()), t.getString("word"),
                    )
                },
            )
        },
    )

    /* ------------------------------------------------------ the parameter */

    /** What each parameter board opened: the row of the live data it stands for. */
    private val PARAM_KEYS: Map<String, ParamKey> = mapOf(
        "scanner-sys-engine-rpm" to ParamKey.Pid(ECM, 0x0C),
        "scanner-sys-engine-ect" to ParamKey.Pid(ECM, 0x05),
        "scanner-sys-pack-cell" to ParamKey.Signal(VehicleSignal.CELL_MIN_MV.name),
    )

    /** The board's clock: its series counts seconds back from here. */
    private const val NOW = 3_600_000_000_000L

    /**
     * A parameter's page from its board (`sysParam()`): `series` is `[seconds back, value]` oldest
     * first; the board's one window is the 60 s tab, its counters, side rows, scale and grid that
     * window's figures, the grid's words the board's `gridText` - the figure with thin groups.
     */
    private fun param(f: JSONObject, key: ParamKey): ParamView {
        val block = f.getJSONObject("block")
        val counters = arrays(block.getJSONArray("counters")).associate { it.getString(0) to it.getString(1) }
        val side = arrays(f.getJSONArray("stats")).associate { it.getString(0) to it.getString(1) }
        val series = arrays(f.getJSONArray("series"))
        val scale = f.getJSONArray("scale")
        val grid = (0 until f.getJSONArray("grid").length()).map { f.getJSONArray("grid").getDouble(it).toFloat() }
        val norm = f.optJSONArray("norm")
        val buttons = arrays(f.getJSONObject("footer").getJSONArray("buttons")).map { it.getString(0) }
        val window = f.getInt("window")
        return ParamView(
            key = key, name = block.getString("title"), aside = block.getString("aside"), line = block.getString("line"),
            value = f.getString("value"), unit = f.getString("unit"), outOfNorm = f.optBoolean("outOfNorm"),
            norm = norm?.let { it.getDouble(0).toFloat()..it.getDouble(1).toFloat() }, normText = side.getValue("НОРМА"),
            samples = series.map { Sample(NOW - (it.getDouble(0) * 1e9).toLong(), it.getDouble(1).toFloat()) },
            nowNanos = NOW,
            windows = mapOf(
                window to ParamStats(
                    min = counters.getValue("МИН"), max = counters.getValue("МАКС"), mean = counters.getValue("СРЕДН."),
                    minValue = f.getDouble("min").toFloat(), maxValue = f.getDouble("max").toFloat(),
                    scale = scale.getDouble(0).toFloat()..scale.getDouble(1).toFloat(),
                    grid = grid, gridText = grid.map(::grouped),
                    delta = side.getValue("ЗА 1 С"), points = side.getValue("ТОЧЕК"),
                ),
            ),
            running = "Пауза" in buttons,
            rate = footnote(f),
            recordable = "Запись" in buttons,
            recording = false,
        )
    }

    /** The board's `num()`: rounded, thousands apart by a space. */
    private fun grouped(v: Float): String {
        val n = Math.round(v)
        val digits = kotlin.math.abs(n).toString().reversed().chunked(3).joinToString(" ").reversed()
        return if (n < 0) "−$digits" else digits
    }

    /** Does nothing: a fixture is a still. */
    val noActions = object : DiagnosticsActions {
        override fun rescan() {}
        override fun readModule(request: Int) {}
        override fun setModuleOpen(request: Int, open: Boolean) {}
        override fun setLiveVisible(request: Int, visible: Boolean) {}
        override fun setFocus(request: Int, pids: List<Int>) {}
        override fun resetMinMax(request: Int) {}
        override fun setRecording(request: Int, on: Boolean) {}
        override fun setParam(key: ParamKey?) {}
        override fun setParamPaused(paused: Boolean) {}
        override fun resetParam() {}
        override fun setCellsVisible(visible: Boolean) {}
        override fun setCellsPaused(paused: Boolean) {}
        override fun setExtrasVisible(page: ExtraPage, visible: Boolean) {}
        override fun setSystemLiveVisible(system: CarSystem, visible: Boolean) {}
        override fun setSystemLivePaused(system: CarSystem, paused: Boolean) {}
        override fun resetSystemLive(system: CarSystem) {}
    }

    /* ----------------------------------------------------------- autoscan */

    private fun groups(a: JSONArray): List<ModuleGroupView> = objects(a).map { g ->
        val ecus = objects(g.getJSONArray("ecus"))
        ModuleGroupView(g.getString("id"), g.getString("title"), g.getString("short"), summary(ecus), ecus.map(::row))
    }

    /** The board's `groupSummary()`. */
    private fun summary(ecus: List<JSONObject>): String {
        fun n(k: String) = ecus.count { it.getString("kind") == k }
        val parts = mutableListOf("${ecus.size} бл.")
        if (n("ok") + n("hist") > 0) parts += "прочитано ${n("ok") + n("hist")}"
        if (n("hist") > 0) parts += "история ${n("hist")}"
        if (n("unread") > 0) parts += "DTC не читали ${n("unread")}"
        if (n("none") > 0) parts += "нет адреса ${n("none")}"
        return parts.joinToString(" · ")
    }

    /** An autoscan row; a block with an address opens its own screen. */
    private fun row(e: JSONObject): ModuleRowView {
        val req = e.optStringOrNull("req")?.toInt(16)
        val name = e.getString("name")
        return ModuleRowView(
            request = req,
            abbreviation = e.getString("abbr"),
            unit = e.getString("unit"),
            name = name.removePrefix("≈ "),
            short = e.getString("short"),
            inferred = name.startsWith("≈ "),
            route = ScannerWords.route(req),
            protocol = e.optStringOrNull("proto") ?: "—",
            active = e.optStringOrNull("active") ?: "—",
            history = e.optStringOrNull("history") ?: "—",
            status = status(e.getString("kind")),
            target = if (e.optBoolean("opens") && req != null) Target.Module(req) else null,
        )
    }

    private fun status(kind: String): ModuleStatus = when (kind) {
        "ok" -> ModuleStatus.OK
        "hist" -> ModuleStatus.HISTORY
        "unread" -> ModuleStatus.UNREAD
        else -> ModuleStatus.NO_ROUTE
    }

    /* ---------------------------------------------------------------- rail */

    /** The board's rail (`sysRail()`): a line's records as the sign of its worst kind and their count; «Блоки» its count. */
    private fun railLines(r: JSONObject): List<RailLine> {
        val items = r.getJSONArray("items")
        return (0 until items.length()).mapNotNull { i -> items.get(i) as? JSONObject }.map { it ->
            val name = it.getString("name")
            val place = when (name) {
                "Сводка" -> RailPlace.Summary
                "Блоки" -> RailPlace.Blocks
                "Журнал" -> RailPlace.Journal
                else -> RailPlace.System(CarSystem.entries.first { s -> s.railName == name })
            }
            RailLine(place, name, kindOf(it)?.let { k -> MarkCount(k, records(it)) }, plain = if (it.has("count")) it.getInt("count").toString() else null)
        }
    }

    /** A block's or a line's records, whatever their kind (`records()`). */
    private fun records(o: JSONObject): Int = listOf("active", "pending", "history").sumOf { o.optString(it).toIntOrNull() ?: 0 }

    /** The worst kind of a block's or a line's records (`blockKind()`). */
    private fun kindOf(o: JSONObject): Mark? = when {
        (o.optString("active").toIntOrNull() ?: 0) > 0 -> Mark.ACTIVE
        (o.optString("pending").toIntOrNull() ?: 0) > 0 -> Mark.PENDING
        (o.optString("history").toIntOrNull() ?: 0) > 0 -> Mark.HISTORY
        else -> null
    }

    /** A record's kind from its status byte (`kindOf()`). */
    private fun markOf(status: Int): Mark = ScannerWords.markOf(status)

    /* ------------------------------------------------------------ «Блоки» */

    /** «Блоки» from its board (`modules()`, `blocksTable()`): the scan's line, the filters with their counts, the groups. */
    private fun blocksView(f: JSONObject): BlocksView {
        val sc = f.getJSONObject("scan")
        val ecus = objects(f.getJSONArray("groups")).flatMap { objects(it.getJSONArray("ecus")) }
        val rows = ecus.map { e ->
            val req = e.optStringOrNull("req")?.toInt(16)
            val n = records(e)
            val silent = e.has("answered") && !e.getBoolean("answered")
            val f194 = e.optStringOrNull("f194")
            BlockRow(
                request = req, abbreviation = e.getString("abbr"), name = e.getString("name").removePrefix("≈ "),
                route = ScannerWords.route(req), protocol = e.optStringOrNull("proto") ?: "—",
                mark = if (silent) Mark.NO_REPLY else kindOf(e), records = if (n > 0) n.toString() else "—",
                software = f194?.chunked(4)?.joinToString(" ") ?: "—", softwareChanged = e.optBoolean("swChanged"),
                latency = if (e.has("ms") && !e.isNull("ms")) "${e.getInt("ms")} мс" else "—",
                filters = setOfNotNull(
                    BlocksFilter.ALL, BlocksFilter.WITH_RECORDS.takeIf { n > 0 }, BlocksFilter.NO_REPLY.takeIf { silent },
                    BlocksFilter.SOFTWARE_CHANGED.takeIf { e.optBoolean("swChanged") },
                ),
                target = req?.let { Target.Module(it) },
            )
        }
        val silent = rows.count { it.mark == Mark.NO_REPLY }
        val withRecords = rows.count { BlocksFilter.WITH_RECORDS in it.filters }
        return BlocksView(
            aside = "· ${rows.size} · " + if (silent > 0) "не ответили $silent" else "все ответили",
            line = "скан ${sc.getString("at")} · ${sc.getInt("exchanges")} обменов · ${sc.getString("seconds")} с · маска ${sc.getString("mask")}",
            filters = listOf(
                BlocksFilter.ALL to "Все · ${rows.size}", BlocksFilter.WITH_RECORDS to "С записями · $withRecords",
                BlocksFilter.NO_REPLY to "Без ответа · $silent", BlocksFilter.SOFTWARE_CHANGED to "ПО изменено · ${rows.count { it.softwareChanged }}",
            ),
            groups = ScannerWords.groups(rows),
            footnote = footnote(f),
        )
    }

    /* ------------------------------------------------------------ «Сводка» */

    private val RANK = mapOf(Mark.ACTIVE to 0, Mark.PENDING to 1, Mark.HISTORY to 2)

    /** The worst of [marks] (`worst()`). */
    private fun worst(marks: List<Mark>): Mark = marks.minByOrNull { RANK[it] ?: 3 } ?: Mark.HISTORY

    /** A U-code of the type 87: a message lost between blocks (`netLoss()`). */
    private fun netLoss(code: String): Boolean = code.startsWith("U") && code.endsWith("-87")

    private fun plural(n: Int, one: String, few: String, many: String) = ScannerWords.plural(n, one, few, many)

    /**
     * «Сводка» from its board (`summary()`): the verdict, the scan's line, the vitals, the band of kinds and
     * the systems - most severe first, then the most records - into the shorter of two columns.
     */
    private fun summary(f: JSONObject): SummaryView {
        val s = ScannerSpec.Summary
        val sc = f.getJSONObject("scan")
        val systems = objects(f.getJSONArray("systems"))
        val all = systems.flatMap { sys -> objects(sys.getJSONArray("blocks")).flatMap { b -> objects(b.getJSONArray("codes")).map { markOf(it.getInt("status")) } } }
        fun count(m: Mark) = all.count { it == m }
        val act = count(Mark.ACTIVE)
        val nBlocks = systems.sumOf { it.getJSONArray("blocks").length() }
        val kinds = listOf(Mark.ACTIVE to "АКТИВНЫЕ", Mark.PENDING to "ОЖИДАЮЩИЕ", Mark.HISTORY to "В ИСТОРИИ").filter { count(it.first) > 0 }
        val items = kinds.mapIndexed { i, (m, word) ->
            val n = count(m)
            BandPart(m, "$word · $n ${plural(n, "ЗАПИСЬ", "ЗАПИСИ", "ЗАПИСЕЙ")}" + if (i == kinds.lastIndex) " · $nBlocks ${plural(nBlocks, "БЛОК", "БЛОКА", "БЛОКОВ")}" else "")
        }
        val net = systems.flatMap { sys -> objects(sys.getJSONArray("blocks")).flatMap { b -> objects(b.getJSONArray("codes")) } }.count { netLoss(it.getString("code")) }
        fun <T> ordered(list: List<T>, marks: (T) -> List<Mark>, n: (T) -> Int): List<T> = list.withIndex()
            .sortedWith(compareBy({ RANK[worst(marks(it.value))] ?: 3 }, { -n(it.value) }, { it.index })).map { it.value }
        fun codes(b: JSONObject) = objects(b.getJSONArray("codes"))
        fun bMarks(b: JSONObject) = codes(b).map { markOf(it.getInt("status")) }
        val views = ordered(systems, { sys -> objects(sys.getJSONArray("blocks")).flatMap(::bMarks) }, { sys -> objects(sys.getJSONArray("blocks")).sumOf { codes(it).size } })
            .map { sys ->
                val bs = ordered(objects(sys.getJSONArray("blocks")), ::bMarks) { codes(it).size }
                val n = bs.sumOf { codes(it).size }
                SummarySystem(
                    "${sys.getString("name").uppercase()} · $n",
                    bs.map { b ->
                        val marks = bMarks(b)
                        SummaryBlock(
                            request = b.getString("addr").toInt(16), abbreviation = b.getString("abbr"), address = b.getString("addr"),
                            name = b.getString("name"), mark = worst(marks), count = marks.size, since = "с ${b.getString("since")}",
                            records = codes(b).map { d ->
                                val k = markOf(d.getInt("status"))
                                val meaning = d.optStringOrNull("meaning")
                                SummaryRecord(d.getString("code"), meaning ?: d.getString("type"), meaning != null, netLoss(d.getString("code")), k.takeIf { it != Mark.HISTORY }, fresh = false)
                            },
                        )
                    },
                )
            }
        // into the shorter column, as the board stacks them
        val colY = floatArrayOf(s.TOP, s.TOP)
        val columns = listOf(mutableListOf<SummarySystem>(), mutableListOf())
        views.forEach { v ->
            val k = if (colY[1] < colY[0]) 1 else 0
            var y = colY[k] + if (colY[k] > s.TOP) s.SYSTEM_GAP else 0f
            y += s.LABEL
            v.blocks.forEachIndexed { i, b -> if (i > 0) y += s.BLOCK_GAP; y += s.HEAD + 1f + b.records.size * (s.ROW + 1f) }
            colY[k] = y
            columns[k] += v
        }
        val blocks = sc.getInt("blocks")
        val answered = sc.getInt("answered")
        val quiet = blocks - nBlocks
        return SummaryView(
            verdict = if (act > 0) "$act " + plural(act, "активная неисправность", "активные неисправности", "активных неисправностей") else "Активных неисправностей нет",
            verdictMark = Mark.ACTIVE.takeIf { act > 0 },
            line = "скан ${sc.getString("at")} · $answered из $blocks блоков · маска ${sc.getString("mask")} · ${sc.getString("seconds")} с",
            vitals = objects(f.getJSONArray("vitals")).map { Vital(it.getString("key"), it.getString("value"), it.optString("unit"), it.optBoolean("out")) },
            band = items.lastOrNull()?.let {
                SummaryBand(items.joinToString(" · ") { it.text }, items.first().mark, if (net > 0) "в т. ч. $net — потеря связи между блоками, U…-87" else null, items)
            },
            columns = columns,
            rest = "$quiet ${plural(quiet, "блок", "блока", "блоков")} без записей · " + if (answered == blocks) "все ответили" else "не ответили ${blocks - answered}",
            restAttention = answered < blocks,
            footnote = footnote(f),
        )
    }

    /* ------------------------------------------------------- a code's screen */

    private val CODE_BOARDS = mapOf("scanner-code" to "scanner-code", "scanner-code-plain" to "scanner-code-plain")

    /** «DD.MM HH:MM» as minutes, for ordering (`when()`). */
    private fun minutes(s: String): Int {
        val d = s.substring(0, 2).toInt()
        val m = s.substring(3, 5).toInt()
        val hm = if (s.length >= 11) s.substring(6, 8).toInt() * 60 + s.substring(9, 11).toInt() else 0
        return (m * 31 + d) * 1440 + hm
    }

    /** Where a moment stands on a day axis of [days], 0..1 (`dayAxis()`'s x). */
    private fun along(s: String, days: List<String>): Float {
        val i = days.indexOf(s.take(5))
        val h = if (s.length >= 11) (s.substring(6, 8).toInt() + s.substring(9, 11).toInt() / 60f) / 24f else 0f
        return (i + h) / days.size
    }

    /** A DTC's three bytes: «U0121-87» is C1 21 87 (`dtcBytes()`). */
    private fun dtcBytes(code: String): List<String>? {
        val m = Regex("^([PCBU])([0-3])([0-9A-F])([0-9A-F]{2})-([0-9A-F]{2})$").find(code) ?: return null
        val (letter, d2, d3, b2, b3) = m.destructured
        val hi = ("PCBU".indexOf(letter) shl 6) or (d2.toInt(16) shl 4) or d3.toInt(16)
        return listOf("%02X".format(hi), b2, b3)
    }

    /** A read as it went (`readLine()`): ask, «→», the answer cut to its head and [code]'s record, picked out. */
    private fun readLine(ask: String, answer: String, code: String?): RawLine {
        val bytes = answer.split(' ')
        val rec = code?.let(::dtcBytes)
        var at = -1
        if (rec != null) for (i in 2 until bytes.size) if (at < 0 && i + 3 < bytes.size && bytes[i] == rec[0] && bytes[i + 1] == rec[1] && bytes[i + 2] == rec[2]) at = i
        val lead = "$ask → "
        if (at < 0) return RawLine(lead + answer)
        val before = bytes.take(minOf(3, at)).joinToString(" ") + if (at > 3) " · … · " else " "
        val record = bytes.subList(at, at + 4).joinToString(" ")
        val after = if (at + 4 < bytes.size) " · …" else ""
        val start = (lead + before).length
        return RawLine(lead + before + record + after, start until start + record.length)
    }

    /** A code's screen from its board (`codeScreen()`). */
    private fun code(f: JSONObject): CodeView {
        val code = f.getString("code")
        val block = f.getJSONObject("block")
        val status = f.getInt("status")
        val availability = f.getInt("availability")
        val active = markOf(status) == Mark.ACTIVE
        val tl = f.getJSONObject("timeline")
        val days = strings(tl.getJSONArray("days"))
        val seen = strings(tl.getJSONArray("seen"))
        val since = strings(tl.getJSONArray("scans")).filter { minutes(it) >= minutes(seen.first()) }
        val sae = f.getJSONObject("sae")
        val ftb = code.substringAfter('-')
        val same = objects(f.getJSONArray("same"))
        val sameBlocks = (listOf(block.getString("abbr")) + same.map { it.getString("block") }).toSet().size
        val n = same.size + 1
        val what = if (netLoss(code)) "${plural(n, "код", "кода", "кодов")} потери связи" else "${plural(n, "код", "кода", "кодов")} с типом $ftb"
        val every = seen.size == since.size
        val scans = plural(since.size, "скана", "сканов", "сканов")
        fun section(title: String, o: JSONObject, empty: String, rowsKey: String = "rows"): ReadSection = if (o.has(rowsKey)) {
            val rows = arrays(o.getJSONArray(rowsKey))
            ReadSection(title, ReadState.READ, rows.map { Field(it.getString(0), it.getString(1)) }, rows.filter { it.optBoolean(2) }.map { it.getString(0) }.toSet())
        } else {
            ReadSection(title, ReadState.EMPTY, emptyList(), raw = listOf("${o.getString("ask")} → ${o.getString("answer")}"), word = empty)
        }
        val snap = f.getJSONObject("snapshot")
        return CodeView(
            request = block.getString("addr").toInt(16), code = code,
            aside = "· ${block.getString("abbr")} ${block.getString("addr")} · ${block.getString("name")}",
            line = (listOf(sae.getString("standard")) + strings(sae.getJSONArray("parts"))).joinToString(" · "),
            counters = listOf(HeadCounter("СТАТУС", ScannerWords.hex2(status), on = active), HeadCounter("ВПЕРВЫЕ", seen.first().take(5)), HeadCounter("В СКАНАХ", "${seen.size} из ${since.size}")),
            meaning = f.getJSONObject("meaning").getString("title"),
            meaningNote = f.getJSONObject("meaning").optStringOrNull("note"),
            status = StatusByteView(
                "СТАТУС · ${ScannerWords.hex2(status)} = ${ScannerWords.bin8(status)}", status, availability,
                ScannerWords.statusWords(status, availability), ScannerWords.availabilityNote(availability),
            ),
            raw = objects(f.getJSONArray("reads")).map { readLine(it.getString("ask"), it.getString("answer"), code) },
            addressee = f.optJSONObject("addressee")?.let { a ->
                val records = a.getInt("records")
                AddresseeView(
                    a.getString("label"), a.getString("addr").toInt(16), a.getString("abbr"), a.getString("addr"), a.getString("name"),
                    listOf(
                        if (a.getBoolean("answers")) "сейчас отвечает" else "сейчас не отвечает",
                        if (records > 0) "записей $records" else "записей нет",
                        if (a.isNull("ms")) "—" else "${a.getInt("ms")} мс",
                    ).joinToString(" · "),
                    if (records > 0) Mark.HISTORY else null,
                )
            },
            sameTypeTitle = "ТОТ ЖЕ ТИП ОТКАЗА · $ftb",
            sameType = same.map { CodeRef(it.getString("addr").toInt(16), it.getString("code"), "${it.getString("block")} ${it.getString("addr")}", "с ${it.getString("since")}") },
            sameTypeNote = if (same.isEmpty()) null else "$n $what в $sameBlocks ${if (sameBlocks == 1) "блоке" else "блоках"}",
            timeline = CodeTimelineView(
                days, seen.map { along(it, days) },
                "впервые ${seen.first()} · " + if (every) "в каждом из ${since.size} $scans" else "в ${seen.size} из ${since.size} $scans",
            ),
            snapshot = section("СТОП-КАДР · 19 04" + (snap.optStringOrNull("record")?.let { " · ЗАПИСЬ $it" } ?: ""), snap, "записи нет"),
            extended = section("СЧЁТЧИКИ · 19 06", f.getJSONObject("counts"), "записей нет"),
            footnote = footnote(f),
        )
    }

    /* -------------------------------------------- «Тяговая батарея · Здоровье» */

    private val HEALTH_BOARDS = setOf("scanner-sys-pack-health", "scanner-sys-pack-health-first")

    /** «Здоровье» from its board (`packHealth()`): the table's rows, the live cells and probes, the sweep's age. */
    private fun health(f: JSONObject): HealthView {
        val cells = f.getJSONArray("cells").let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
        val probes = f.getJSONArray("probes").let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
        val ref = f.getInt("cellRef")
        return HealthView(
            rows = objects(f.getJSONArray("rows")).map { r ->
                val trend = r.optJSONObject("trend")
                HealthRow(
                    name = r.getString("name"), sub = r.getString("sub"), value = r.getString("value"), unit = r.optString("unit"),
                    outOfNorm = r.optBoolean("out"),
                    reference = r.optStringOrNull("norm")?.let { HealthReference(it, ReferenceKind.NORM) }
                        ?: r.optStringOrNull("usual")?.let { HealthReference(it, ReferenceKind.USUAL) }
                        ?: r.optStringOrNull("note")?.let { HealthReference(it, ReferenceKind.NOTE) },
                    trend = trend?.getJSONArray("values")?.let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }.orEmpty(),
                    trendScale = trend?.getJSONArray("scale")?.let { it.getDouble(0).toFloat()..it.getDouble(1).toFloat() },
                    trendPoints = trend?.optBoolean("points") == true,
                )
            },
            cells = PackCellsView(
                cells = cells.map { CellReading(it, grouped(it), true) },
                probes = probes.map { CellReading(it, Math.round(it).toString(), true) },
                cellStats = emptyList(), probeStats = emptyList(), cellRef = ref, cellGrid = emptyList(), cellGridText = emptyList(),
                cellMin = cells.indexOf(cells.min()), cellMax = cells.indexOf(cells.max()),
                probeMin = probes.indexOf(probes.min()), probeMax = probes.indexOf(probes.max()),
                sweep = if (f.isNull("sweep")) null else f.getDouble("sweep").toFloat(), paused = false,
                age = firstNote(f),
                cellsHead = "", probesHead = "",
            ),
            age = firstNote(f),
        )
    }

    /* ------------------------------------------------------------- «Графики» */

    /** The board's `numText()`: decimals, a comma, «−», a sign where the signal has one, thousands apart. */
    private fun numText(v: Double, decimals: Int, signed: Boolean) = ScannerWords.number(v.toFloat(), decimals, signed)

    /** «Графики» from its board (`sysGraphs()`): four lanes, each value at its own moment of the 60 s window. */
    private fun graphs(f: JSONObject): GraphsView {
        val window = f.getInt("window")
        return GraphsView(
            scope = GraphScope.Block(ECM),
            lanes = objects(f.getJSONArray("signals")).map { s ->
                val values = s.getJSONArray("values")
                val n = values.length()
                val scale = s.getJSONArray("scale")
                val lo = scale.getDouble(0)
                val hi = scale.getDouble(1)
                val decimals = s.optInt("decimals")
                val scaleDecimals = if (s.has("scaleDecimals")) s.getInt("scaleDecimals") else decimals
                val signed = s.optBoolean("signed")
                GraphLane(
                    name = s.getString("name"), source = "PID ${s.getString("pid")} · ${s.getString("abbr")}", unit = s.getString("unit"),
                    samples = (0 until n).map { i -> Sample(NOW - ((window - i * window.toDouble() / (n - 1)) * 1e9).toLong(), values.getDouble(i).toFloat()) },
                    scale = lo.toFloat()..hi.toFloat(),
                    scaleText = numText(hi, scaleDecimals, signed) to numText(lo, scaleDecimals, signed),
                    band = s.optJSONArray("norm")?.let { it.getDouble(0).toFloat()..it.getDouble(1).toFloat() },
                    decimals = decimals, key = ParamKey.Pid(ECM, s.getString("pid").toInt(16)), signed = signed,
                )
            },
            line = f.getJSONObject("block").getString("line"),
            nowNanos = NOW,
            marks = emptyList(),
            footnote = footnote(f),
        )
    }

    /* -------------------------------------------------------------- «Журнал» */

    private val JOURNAL_BOARDS = setOf("scanner-journal", "scanner-journal-first", "scanner-journal-compare", "scanner-journal-records")

    /** «Журнал» from its board (`journal()`, `journalPanel()`): every moment on the day axis, as a fraction of it. */
    private fun journal(f: JSONObject): JournalView {
        val days = strings(f.getJSONArray("days"))
        fun at(s: String) = along(s, days)
        val block = f.getJSONObject("block")
        val values = f.getJSONObject("values")
        val lv = values.getJSONObject("lv")
        val full = values.getJSONObject("full")
        val lvPoints = arrays(lv.getJSONArray("points")).map { at(it.getString(0)) to it.getDouble(1).toFloat() }
        val fullPoints = arrays(full.getJSONArray("points"))
        val m = f.getJSONObject("compare")
        val from = m.optStringOrNull("from")
        return JournalView(
            aside = block.getString("aside"), line = block.getString("line"),
            counters = arrays(block.getJSONArray("counters")).map { HeadCounter(it.getString(0), it.getString(1)) },
            days = days,
            scans = objects(f.getJSONArray("scans")).mapIndexed { i, s -> JournalTick(at(s.getString("at")), s.getString("kind") != "block", NOW_MILLIS + i) },
            software = objects(f.getJSONArray("software")).map { JournalSoftwareTick(at(it.getString("at")), "${it.getString("block")} ${it.getString("from")} → ${it.getString("to")}") },
            cells = objects(f.getJSONArray("cellShots")).map { s ->
                val soc = s.getInt("soc")
                JournalCellTick(at(s.getString("at")), soc >= 95, if (soc >= 95) "SOC $soc %" else null)
            },
            codes = objects(f.getJSONArray("codes")).map { c ->
                JournalCodeLane(c.getString("addr").toInt(16), "${c.getString("block")} ${c.getString("addr")}", c.getInt("count"), Mark.HISTORY, strings(c.getJSONArray("seen")).map(::at))
            },
            quantities = listOf(
                JournalQuantityLane(
                    lv.getString("name"), lvPoints, lv.getJSONArray("scale").let { it.getDouble(0).toFloat()..it.getDouble(1).toFloat() },
                    lvPoints.lastOrNull()?.second?.let { numText(it.toDouble(), 1, false) } ?: "—", pointsOnly = false,
                ),
                JournalQuantityLane(
                    full.getString("name"), fullPoints.map { at(it.getString(0)) to it.getDouble(1).toFloat() }, 0f..20f,
                    fullPoints.lastOrNull()?.let { "${it.getInt(1)} мВ" } ?: "—", pointsOnly = true,
                    pointLabels = fullPoints.map { "${it.getInt(1)} мВ" },
                ),
            ),
            compare = ScanCompareView(
                title = if (from != null) "$from → ${m.getString("to")}" else m.getString("to"),
                codes = arrays(m.getJSONArray("codes")).map { Field(it.getString(0), it.get(1).toString()) },
                software = objects(m.getJSONArray("software")).map { Field(it.getString("block"), "${it.getString("from")} → ${it.getString("to")}") },
                quantities = arrays(m.getJSONArray("values")).map { v ->
                    val unit = v.optString(3)
                    Field(v.getString(0), (if (v.isNull(1)) v.getString(2) else "${v.getString(1)} → ${v.getString(2)}") + if (unit.isNotEmpty()) " $unit" else "")
                },
            ),
        )
    }

    /* --------------------------------------------------------------- «Отчёт» */

    /**
     * «Отчёт» from its board (`report()`): the scan of 20.10 19:31 as a report's input - its codes are
     * «Сводка»'s, its blocks «Блоки»' - and what goes in as the board ticks it.
     */
    private fun report(f: JSONObject, scanBoard: JSONObject, view: SummaryView): ReportState {
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val at = java.time.ZonedDateTime.of(2026, 10, 20, 19, 31, 29, 0, zone)
        val ecus = objects(scanBoard.getJSONArray("groups")).flatMap { objects(it.getJSONArray("ecus")) }
        val records = view.columns.flatten().flatMap { sys -> sys.blocks.flatMap { b -> b.records.map { b to it } } }
        val input = ReportInput(
            car = "Denza Z9 GT DM", vin = null, odometerKm = 13199.7, scanAt = at, madeAt = at, parked = true, lowVoltage = 13.7,
            summary = ReportSummary(68, 68, 68, 0, 0, 18, 6),
            codes = records.map { (b, r) ->
                ReportCode(b.request, b.abbreviation, b.address, r.code, r.code.substringAfter('-'), r.words, null, 0x08, ReportCodeKind.HISTORY)
            },
            pack = ReportPack(99.0, 74.0, 3333.0, 3337.0, 14429.0, 19675.0, 13.0, 18.0),
            engine = ReportEngine(mil = false, ready = 4, supported = 9, software = "4.00.10 · 19031900", calibration = "Z31L07AB01"),
            blocks = ecus.map { e ->
                val req = e.optStringOrNull("req")?.toInt(16)
                val n = records(e)
                ReportBlock(req, e.getString("abbr"), e.optStringOrNull("req") ?: "—", e.optStringOrNull("f194"), null, if (n > 0) "история $n" else "—", if (n > 0) "HISTORY" else "OK")
            },
            exchanges = ReportExchanges(listOf("22 F194", "19 01 09", "19 02 09", "OBD 01/03/07/09/0A"), 148, 19_900),
            recording = ReportRecording("/data/stream-20261020-1925.csv", 42_000, 4),
        )
        val sections = ReportSection.entries
        return ReportState(
            input = input,
            options = objects(f.getJSONArray("include")).mapIndexed { i, o -> ReportOption(sections[i], true, o.getString("detail"), o.getBoolean("on")) },
        )
    }

    /** The footer's first mono note: the sweep's age. */
    private fun firstNote(f: JSONObject): String {
        val notes = f.getJSONObject("footer").optJSONArray("notes") ?: JSONArray()
        return (0 until notes.length()).map { notes.get(it) }.filterIsInstance<JSONArray>().firstOrNull()?.getString(0) ?: ""
    }

    /** The journal's scans by their end, for «Выбрать сканы»: any distinct moments do. */
    private const val NOW_MILLIS = 1_792_000_000_000L

    /* ------------------------------------------------------------- systems */

    /** Each system's blocks (architecture, 7.2), the body by its group. */
    private val BLOCKS = mapOf(
        CarSystem.HV to listOf(0x7E7, 0x7E3, 0x744, 0x745, 0x7E6, 0x7E0, 0x7E4, 0x766, 0x762),
        CarSystem.PACK to listOf(0x7E7),
        CarSystem.DRIVES to listOf(0x7E2, 0x743, 0x7E3, 0x744, 0x745, 0x7E6),
        CarSystem.ENGINE to listOf(ECM),
        CarSystem.CHASSIS to listOf(ESP, BSCM, 0x783, 0x582, 0x796, 0x597),
        CarSystem.SAFETY to listOf(0x7F1, 0x742, 0x713, 0x5E0),
        CarSystem.ADAS to listOf(0x712, 0x7F2, 0x760, 0x761, 0x7C2, 0x7D4, 0x701),
        CarSystem.CLIMATE to listOf(0x7B3, 0x7B4, 0x766, 0x590),
        CarSystem.LV to listOf(0x762),
    )

    /** The cabin's two boards: a trip and the parked sweep; any other board's cabin is the trip's. */
    private val CABIN_BOARDS = setOf("scanner-sys-cabin", "scanner-sys-cabin-parked")

    /** A state's second board, illustrative: shown, it is its system's «Состояние». */
    private val LIT_BOARDS = mapOf(CarSystem.BODY to "scanner-sys-body-state-lit", CarSystem.SAFETY to "scanner-sys-safety-trip")

    /**
     * The board that shows a system's head and «Состояние»; the cabin's is the board shown when it is
     * one of [CABIN_BOARDS], the body's and the safety's their [LIT_BOARDS] when shown.
     */
    private val STATE_BOARD = mapOf(
        CarSystem.HV to "scanner-sys-hv", CarSystem.PACK to "scanner-sys-pack-health", CarSystem.DRIVES to "scanner-sys-drives",
        CarSystem.ENGINE to "scanner-sys-engine-scheme", CarSystem.CHASSIS to "scanner-sys-chassis", CarSystem.SAFETY to "scanner-sys-safety",
        CarSystem.BODY to "scanner-sys-body-state", CarSystem.ADAS to "scanner-sys-adas",
        CarSystem.CLIMATE to "scanner-sys-climate", CarSystem.LV to "scanner-sys-lv", CarSystem.CABIN to "scanner-sys-cabin",
    )

    /** The cabin's blocks: the autoscan's «Салон» without the child-presence radar, which stands in «Безопасность». */
    private const val CPD = 0x5E0

    private const val CODES = "19 01 09 · 19 02 09 · снимок 19:31:29"

    /** Every system's page; the board shown lends the pack its counters when it is a cells board (its sweep's moment). */
    private fun pages(all: JSONObject, groups: List<ModuleGroupView>, id: String): Map<CarSystem, SystemPage> {
        val rows = groups.flatMap { it.rows }
        fun opened(r: ModuleRowView) = r.copy(target = r.request?.let { Target.Module(it) })
        return STATE_BOARD.mapValues { (system, stateBoard) ->
            val board = if ((system == CarSystem.CABIN && id in CABIN_BOARDS) || LIT_BOARDS[system] == id) id else stateBoard
            val f = fixture(all, board)
            // the head of the board shown when it is this system's page (its moment, its counters), else its «Состояние»'s
            val block = fixture(all, id).optJSONObject("block")?.takeIf { it.optString("title") == system.title } ?: f.getJSONObject("block")
            val blocks = when (system) {
                CarSystem.BODY -> groups.first { it.id == "body" }.rows
                CarSystem.CABIN -> groups.first { it.id == "salon" }.rows.filter { it.request != CPD }
                else -> BLOCKS.getValue(system).mapNotNull { req -> rows.firstOrNull { it.request == req } }
            }
            SystemPage(
                system = system,
                aside = block.getString("aside"),
                line = block.getString("line"),
                counters = arrays(block.getJSONArray("counters")).map(::headCounter),
                tabs = strings(f.getJSONObject("tabs").getJSONArray("items")).mapNotNull { label ->
                    SystemTab.entries.firstOrNull { it.label == label } ?: SystemTab.STATE.takeIf { label == "Здоровье" }
                },
                blocks = blocks.map(::opened),
                dtc = if (system == CarSystem.BODY) bodyCodes(fixture(all, "scanner-sys-body")) else emptyList(),
                dtcFootnote = when (system) {
                    CarSystem.ENGINE -> ""
                    // the board's own moment: the parked sweep was read at 07:36:12
                    CarSystem.CABIN -> "19 01 09 · 19 02 09 · " + f.getJSONObject("footer").getJSONArray("notes").getJSONArray(0).getString(0)
                    else -> CODES
                },
                scheme = scheme(system, f, rows),
            )
        }
    }

    /** A head's counter `[label, value, tone]`: 'on' red, 'out' orange, a record kind its sign (`blockHead()`). */
    private fun headCounter(a: JSONArray): HeadCounter {
        val tone = a.optString(2)
        val mark = when (tone) {
            "active" -> Mark.ACTIVE
            "pending" -> Mark.PENDING
            "hist" -> Mark.HISTORY
            else -> null
        }
        return HeadCounter(a.getString(0), a.getString(1), on = tone == "on", mark = mark, out = tone == "out")
    }

    private fun bodyCodes(f: JSONObject): List<SystemDtcRow> = objects(f.getJSONArray("codes")).map { c ->
        SystemDtcRow(
            request = c.getString("addr").toInt(16), block = c.getString("block"), address = c.getString("addr"),
            code = c.getString("code"), failureType = c.getString("ftb"), failureName = c.getString("ftbName"),
            description = c.getString("desc"), status = c.getString("status"), word = c.getString("word"), active = false,
        )
    }

    /** A scheme's tile as the board draws it: `u(abbr, req, short, kind, history)`. */
    private fun tile(u: JSONObject?, rows: List<ModuleRowView>): ModuleRowView? {
        if (u == null) return null
        val req = u.getString("req").toInt(16)
        val kind = u.optString("kind").ifEmpty { "ok" }
        val row = rows.firstOrNull { it.request == req }
        return ModuleRowView(
            request = req, abbreviation = u.getString("abbr"), unit = row?.unit ?: u.getString("abbr"), name = row?.name ?: u.getString("abbr"),
            short = u.getString("short"), inferred = false, route = ScannerWords.route(req), protocol = row?.protocol ?: "UDS",
            active = "0", history = if (u.has("history")) u.get("history").toString() else "0",
            status = status(kind), target = Target.Module(req),
        )
    }

    private fun callout(o: JSONObject) = Callout(o.getString("label"), strings(o.getJSONArray("rows")))

    private fun corner(side: String, axle: String): Corner = when (axle + side) {
        "FL" -> Corner.FL
        "FR" -> Corner.FR
        "RL" -> Corner.RL
        else -> Corner.RR
    }

    private fun scheme(system: CarSystem, f: JSONObject, rows: List<ModuleRowView>): Scheme? {
        val units = f.optJSONObject("units")
        fun t(key: String) = tile(units?.optJSONObject(key), rows)
        fun plate(key: String) = f.optJSONArray(key)?.let(::wordPlate)
        return when (system) {
            CarSystem.HV -> f.getJSONObject("charge").let { Scheme.Hv(it.getString("state"), it.optBoolean("plugged")) }
            CarSystem.PACK -> if (!f.has("pack")) null else {
                val p = f.getJSONObject("pack")
                Scheme.Pack(
                    soc = p.getDouble("soc").toFloat(), socText = p.getString("socText"), soh = p.getString("soh"), cells = p.getString("cells"),
                    volts = p.getString("volts"), current = p.getString("current"),
                    insulation = arrays(p.getJSONArray("insulation")).map { Field(it.getString(0), it.getString(1)) },
                    isoNorm = p.getString("isoNorm"),
                    ranges = objects(f.getJSONArray("ranges")).map { r ->
                        val scale = r.getJSONArray("scale")
                        val span = r.getJSONArray("span")
                        RangeView(
                            r.getString("head"), r.getString("value"),
                            scale.getDouble(0).toFloat()..scale.getDouble(1).toFloat(),
                            span.getDouble(0).toFloat()..span.getDouble(1).toFloat(),
                            if (r.has("mark")) r.getDouble("mark").toFloat() else null,
                            r.getString("lo"), r.getString("hi"), r.optString("note"),
                        )
                    },
                )
            }
            CarSystem.DRIVES -> Scheme.Drives(
                vcu = t("vcu"), front = callout(f.getJSONObject("front")), isg = callout(f.getJSONObject("isg")),
                rearLeft = callout(f.getJSONObject("rearLeft")), rearRight = callout(f.getJSONObject("rearRight")),
                engineRunning = false, powerDomain = t("pdc"),
            )
            CarSystem.ENGINE -> Scheme.Engine(
                objects(f.getJSONArray("points")).map { p ->
                    val place = when (p.getString("place")) {
                        "inlet" -> EnginePlace.INLET
                        "throttle" -> EnginePlace.THROTTLE
                        "manifold" -> EnginePlace.MANIFOLD
                        "blockTop" -> EnginePlace.BLOCK_TOP
                        "blockBottom" -> EnginePlace.BLOCK_BOTTOM
                        "catalyst" -> EnginePlace.CATALYST
                        else -> EnginePlace.OXYGEN
                    }
                    EnginePoint(place, p.getString("label"), strings(p.getJSONArray("rows")))
                },
            )
            CarSystem.CHASSIS -> Scheme.Chassis(
                tyres = objects(f.getJSONArray("tyres")).map {
                    Tyre(corner(it.getString("side"), it.getString("axle")), it.getString("label"), it.getString("pressure"), it.getString("temp"),
                        it.optJSONArray("states")?.let(::arrays)?.map(::cabinRow).orEmpty())
                },
                esp = t("esp"), eps = t("eps"), bscm = t("bscm"),
                rearSteerLeft = t("rwsL"), rearSteerRight = t("rwsR"), motion = t("idc"),
                suspension = plate("suspension"),
                epb = f.optJSONObject("epb")?.let { Epb(it.optStringOrNull("look")?.uppercase()?.let(EpbLook::valueOf), cabinRow(it.getJSONArray("row"))) },
                tpms = f.optJSONObject("tpms")?.let { WordPlate(it.getString("label"), listOf(cabinRow(it.getJSONArray("row")))) },
            )
            CarSystem.BODY -> Scheme.Body(
                doors = objects(f.getJSONArray("doors")).map {
                    Door(corner(it.getString("side"), it.getString("axle")), it.getString("label"), it.getString("state"), it.getString("detail"), open = it.optBoolean("open"))
                },
                front = t("front"), back = t("back"), left = t("left"), right = t("right"), lampLeft = t("lampL"), lampRight = t("lampR"), uwb = t("uwb"),
                anchors = listOf(0x7F4, 0x7F5, 0x7F6, 0x7F7).map { req -> rows.firstOrNull { it.request == req }?.status ?: ModuleStatus.NO_ROUTE },
                doorRadars = f.optJSONObject("doorRadars")?.let { r ->
                    mapOf(Corner.FL to "fl", Corner.FR to "fr", Corner.RL to "rl", Corner.RR to "rr")
                        .mapNotNull { (corner, key) -> tile(r.optJSONObject(key), rows)?.let { corner to it } }.toMap()
                }.orEmpty(),
                keyReceiver = t("hfrm"), smartEntry = t("sec"), nfc = t("nfc"), tailgate = t("tailgate"),
                hood = f.optJSONObject("hood")?.let(::lid), trunk = f.optJSONObject("trunk")?.let(::lid),
                lamps = f.optJSONObject("lamps")?.let { m ->
                    fun flag(k: String): Boolean? = if (!m.has(k) || m.isNull(k)) null else m.getBoolean(k)
                    BodyLamps(flag("low"), flag("high"), m.optBoolean("turnLeft"), m.optBoolean("turnRight"), flag("stop"), flag("reverse"))
                },
                lights = plate("lights"), tail = plate("tail"),
            )
            CarSystem.ADAS -> Scheme.Adas(
                front = t("mrr"),
                corners = listOfNotNull(t("fl")?.let { Corner.FL to it }, t("fr")?.let { Corner.FR to it }, t("rl")?.let { Corner.RL to it }, t("rr")?.let { Corner.RR to it }).toMap(),
                controller = t("adc"), parking = t("ipa"),
            )
            CarSystem.CLIMATE -> {
                fun zone(o: JSONObject) = ClimateZone(o.getString("label"), o.getString("temp"), o.getString("pm"), o.optBoolean("inside"))
                Scheme.Climate(zone(f.getJSONObject("outside")), zone(f.getJSONObject("cabin")), t("acc"), t("ptc"), t("tmim"), air = t("aqdm"))
            }
            CarSystem.SAFETY -> Scheme.Safety(
                airbags = t("srs"), driverBelt = t("driverBelt"), passengerBelt = t("passengerBelt"), childRadar = t("cpd"),
                seats = f.optJSONArray("seats")?.let(::objects).orEmpty().map { s ->
                    fun flag(k: String): Boolean? = if (!s.has(k) || s.isNull(k)) null else s.getBoolean(k)
                    SeatBelt(CABIN_PLACES.getValue(s.getString("place")), s.getString("label"),
                        CabinSeat(flag("occupied"), flag("belt"), null, null, null), cabinRow(s.getJSONArray("row")))
                },
            )
            CarSystem.LV -> Scheme.Lv(
                hv = f.getString("hv"), lv = f.getString("lv"), dcdc = t("dcdc"), battery = t("lbms"),
                loads = objects(units!!.getJSONArray("loads")).mapNotNull { tile(it, rows) },
                dcdcMode = f.getString("dcdcMode"),
            )
            CarSystem.CABIN -> cabin(f)
            else -> null
        }
    }

    /* ------------------------------------------------------ the schemes' words */

    /**
     * A board's word `[text, style, ink]` in the model's tones: a row's own ink (white in `cell`, dim
     * in `cellSmall`) is plain, dim in a first row quiet, orange an alert, `orangeInk` warm.
     */
    private fun cabinRun(a: JSONArray): CabinRun {
        val small = a.getString(1) == "cellSmall"
        val tone = when (a.getString(2)) {
            "orange" -> CabinTone.ALERT
            "orangeInk" -> CabinTone.WARM
            "dim" -> if (small) CabinTone.PLAIN else CabinTone.QUIET
            else -> CabinTone.PLAIN
        }
        return CabinRun(a.getString(0), tone)
    }

    /** A board's row: one word or runs of them on one baseline, small in `cellSmall`. */
    private fun cabinRow(r: JSONArray): CabinRow {
        val runs = if (r.get(0) is JSONArray) arrays(r) else listOf(r)
        return CabinRow(runs.map(::cabinRun), small = runs.first().getString(1) == "cellSmall")
    }

    /** A board's plate `P(label, rows)`: its label over its rows. */
    private fun wordPlate(a: JSONArray): WordPlate {
        val rows = arrays(a)
        return WordPlate(rows.first().getString(0), rows.drop(1).map(::cabinRow))
    }

    /** The hood's or the tailgate's `{ open, row }`. */
    private fun lid(o: JSONObject) = Lid(if (o.isNull("open")) null else o.getBoolean("open"), cabinRow(o.getJSONArray("row")))

    /* ------------------------------------------------------------- the cabin */

    private val CABIN_PLACES = mapOf(
        "cluster" to CabinPlace.CLUSTER, "wheel" to CabinPlace.WHEEL, "fl" to CabinPlace.FL, "fr" to CabinPlace.FR,
        "rl" to CabinPlace.RL, "rc" to CabinPlace.RC, "rr" to CabinPlace.RR, "pads" to CabinPlace.PADS, "fridges" to CabinPlace.FRIDGES,
    )

    /**
     * «Салон · Состояние» from its board (`sysCabin()`): the seats as drawn, the wheel, the pads and the
     * fridges by the board's words for them, and each plate's rows - `[text, style, ink]` or runs of
     * them - in the model's tones: a row's own ink (white in `cell`, dim in `cellSmall`) is plain,
     * dim in a first row quiet, orange an alert, `orangeInk` warm.
     */
    private fun cabin(f: JSONObject): Scheme.Cabin {
        fun level(o: JSONObject, key: String): Int? = if (!o.has(key) || o.isNull(key)) null else o.getInt(key)
        fun flag(o: JSONObject, key: String): Boolean? = if (!o.has(key) || o.isNull(key)) null else o.getBoolean(key)
        val seats = f.getJSONObject("seats").let { o ->
            o.keys().asSequence().associate { key ->
                val seat = o.getJSONObject(key)
                CABIN_PLACES.getValue(key) to CabinSeat(flag(seat, "occupied"), flag(seat, "belt"), level(seat, "heat"), level(seat, "vent"), flag(seat, "lamp"))
            }
        }
        fun pad(o: JSONObject, key: String): PadState? = when (o.optStringOrNull(key)) {
            "charging" -> PadState.CHARGING
            "full" -> PadState.FULL
            "fault" -> PadState.FAULT
            "standby" -> PadState.STANDBY
            else -> null
        }
        fun fridge(o: JSONObject, key: String): FridgeMode? = when (o.optStringOrNull(key)) {
            "cold" -> FridgeMode.COLD
            "hot" -> FridgeMode.HOT
            "off" -> FridgeMode.OFF
            "fresh" -> FridgeMode.FRESH
            else -> null
        }
        val text = f.getJSONObject("text")
        fun plate(key: String): CabinPlate = wordPlate(text.getJSONArray(key)).let { CabinPlate(CABIN_PLACES.getValue(key), it.label, it.rows) }
        val plates = f.getJSONObject("plates")
        val pads = f.getJSONObject("pads")
        val fridges = f.getJSONObject("fridges")
        return Scheme.Cabin(
            seats = seats,
            wheelHeat = flag(f, "wheelHeat"),
            padLeft = pad(pads, "left"), padRight = pad(pads, "right"),
            fridgeFront = fridge(fridges, "front"), fridgeRear = fridge(fridges, "rear"),
            left = strings(plates.getJSONArray("left")).map(::plate),
            right = strings(plates.getJSONArray("right")).map(::plate),
        )
    }

    /* ------------------------------------------------------------- ECUs */

    private fun modules(all: JSONObject, id: String): Map<Int, ModuleView> {
        val body = fixture(all, "scanner-body-dtc")
        val esp = fixture(all, "scanner-esp-dtc")
        return mapOf(
            R_ZCU to blockModule(R_ZCU, body.getJSONObject("block"), ModuleStatus.HISTORY).copy(
                dtc = DtcView(
                    asks = emptyList(),
                    records = objects(body.getJSONArray("codes")).map { c ->
                        DtcRow(
                            c.getString("code"), c.getString("ftb"), c.getString("ftbName"), c.getString("group"), c.optString("note"),
                            c.getInt("status"), body.getInt("availability"), description = c.optString("desc").ifEmpty { null },
                            reads = objects(body.getJSONArray("reads")).map { readLine(it.getString("ask"), it.getString("answer"), c.getString("code")) },
                        )
                    },
                    quality = DataQuality.COMPLETE, outsideMask = false, footnote = footnote(body), source = source(body),
                    availability = body.getInt("availability"),
                ),
            ),
            ECM to ecm(all, id),
            BMS to updated(fixture(all, "scanner-ecu-ident-updated")),
            ESP to blockModule(ESP, esp.getJSONObject("block"), ModuleStatus.OK).copy(
                dtc = DtcView(
                    asks = arrays(esp.getJSONArray("asks")).map { a -> ask(a.getString(0), a.getString(1), a.getString(2)) },
                    records = emptyList(), quality = DataQuality.COMPLETE_EMPTY, outsideMask = false, footnote = footnote(esp),
                    availability = esp.getJSONObject("mask").getInt("availability"), mask = esp.getJSONObject("mask").getInt("value"),
                ),
            ),
        )
    }

    /** The BMS after an update (architecture 5.5): its head and «Идентификация» with «ПО обновлено». */
    private fun updated(f: JSONObject): ModuleView = blockModule(BMS, f.getJSONObject("block"), ModuleStatus.OK).copy(
        ident = IdentView(fields(f.getJSONArray("own")), fields(f.getJSONArray("cache")), strings(f.getJSONArray("pids"))),
    )

    /** A block from a board's head: title (≈ when inferred), «· unit», the mono line. */
    private fun blockModule(request: Int, block: JSONObject, status: ModuleStatus): ModuleView {
        val title = block.getString("title")
        return ModuleView(
            request = request, name = title.removePrefix("≈ "), unit = block.getString("aside").removePrefix("· "),
            inferred = title.startsWith("≈ "), line = block.getString("line"), status = status,
            dtc = DtcView(emptyList(), emptyList(), DataQuality.COMPLETE_EMPTY, false, ""),
            ident = IdentView(emptyList(), emptyList(), pids = emptyList()), obd = null,
        )
    }

    /** The footer's mono note: what was asked and when. */
    private fun footnote(f: JSONObject): String {
        val notes = f.getJSONObject("footer").optJSONArray("notes") ?: JSONArray()
        return (0 until notes.length()).map { notes.get(it) }.filterIsInstance<JSONArray>().lastOrNull()?.getString(0) ?: ""
    }

    /** The footer's plain note, the descriptions' source; the mono ones are what was asked, «|» a divider. */
    private fun source(f: JSONObject): String? = f.getJSONObject("footer").optJSONArray("notes")
        ?.let { n -> (0 until n.length()).map { n.get(it) }.filterIsInstance<String>().firstOrNull { it != "|" } }

    private fun ask(name: String, service: String, count: String) = AskRow(name, service, count, asked = count.isNotEmpty() && count.all(Char::isDigit))

    /** The ECM: the DTC board's lists and monitors, or the fault's; live data; identification. */
    private fun ecm(all: JSONObject, id: String): ModuleView {
        val fault = id == "scanner-ecu-fault"
        val dtcBoard = fixture(all, if (fault) "scanner-ecu-fault" else "scanner-ecu-dtc")
        val ecmBoards = setOf("scanner-ecu-dtc", "scanner-ecu-fault", "scanner-ecu-live", "scanner-ecu-ident")
        val block = fixture(all, if (id in ecmBoards) id else "scanner-ecu-dtc").getJSONObject("block")
        val counters = arrays(block.getJSONArray("counters")).associate { it.getString(0) to it.getString(1) }
        val pid01 = dtcBoard.getJSONObject("pid01")
        val lists = arrays(dtcBoard.getJSONArray("lists")).map { a ->
            if (fault) ask(a.getString(0), a.getString(2), a.getString(1)) else ask(a.getString(0), a.getString(1), a.getString(2))
        }
        val records = if (!fault) emptyList() else dtcBoard.getJSONObject("fault").let { f ->
            // the board's title is the catalog's description; the row has no failure type of its own
            listOf(DtcRow(f.getString("code"), "", "", "", f.optString("note"), 0xAF, 0xFF, description = f.getString("title")))
        }
        val source = source(dtcBoard)
        val live = fixture(all, "scanner-ecu-live")
        val liveLine = live.getJSONObject("block").getString("line")
        val rate = Regex("опрос ([^·]+?) ·").find(liveLine)?.groupValues?.get(1) ?: "—"
        val ident = fixture(all, "scanner-ecu-ident")
        return ModuleView(
            request = ECM, name = block.getString("title"), unit = block.getString("aside").removePrefix("· "),
            inferred = false, line = block.getString("line"), status = if (fault) ModuleStatus.ACTIVE else ModuleStatus.OK,
            dtc = DtcView(emptyList(), records, if (fault) DataQuality.COMPLETE else DataQuality.COMPLETE_EMPTY, false, "", source = source),
            ident = IdentView(
                own = fields(ident.getJSONArray("own")),
                cache = fields(ident.getJSONArray("cache")),
                pids = strings(ident.getJSONArray("pids")),
            ),
            obd = ObdView(
                mil = mil(counters.getValue("MIL")),
                milLine = pid01.getString("line"),
                raw = pid01.getString("raw"),
                readiness = counters.getValue("ГОТОВН."),
                monitors = arrays(dtcBoard.getJSONArray("monitors")).map { m ->
                    MonitorRow(
                        m.getString(0), m.getString(1), m.getString(2),
                        when (m.getString(3)) { "ready" -> MonitorState.READY; "waiting" -> MonitorState.NOT_READY; else -> MonitorState.UNSUPPORTED },
                    )
                },
                lists = lists,
                live = LiveView(
                    running = true, rate = rate, focus = emptyList(),
                    rows = objects(live.getJSONArray("rows")).map { r ->
                        val trace = r.getJSONArray("trace")
                        LiveRow(
                            pid = r.getString("pid").toInt(16), name = r.getString("name"), abbreviation = r.getString("abbr"),
                            value = r.getString("value"), unit = r.getString("unit"), min = r.getString("min"), max = r.getString("max"),
                            norm = r.getString("norm"), outOfNorm = false, source = "PID",
                            samples = (0 until trace.length()).map { Sample(it * 1_000_000_000L, trace.getDouble(it).toFloat()) },
                            key = ParamKey.Pid(ECM, r.getString("pid").toInt(16)),
                        )
                    },
                    recording = false,
                    graphs = if (id == GRAPHS) graphs(fixture(all, id)) else null,
                ),
            ),
        )
    }

    /* ------------------------------------------------- the systems' data */

    private fun systems(all: JSONObject, id: String): SystemsView {
        val hv = fixture(all, "scanner-sys-hv")
        val drives = fixture(all, "scanner-sys-drives-compare")
        val adas = fixture(all, "scanner-sys-adas-compare")
        val packBox = hv.getJSONObject("pack")
        return SystemsView(
            hv = HvView(
                pack = arrays(packBox.getJSONArray("rows")).map { Field(it.getString(0), it.getString(1)) },
                packStatus = ModuleStatus.OK,
                busVoltages = objects(hv.getJSONArray("units")).filter { it.has("volts") }.map { "${it.getInt("volts")} В" }.distinct(),
                units = objects(hv.getJSONArray("units")).map { u ->
                    val addr = u.getString("addr")
                    val volts = if (u.has("volts")) u.getInt("volts") else null
                    val state = listOfNotNull(volts?.let { "$it В" }, u.optString("reading").ifEmpty { null }).joinToString(" · ")
                    // an inverter's bus voltage and its difference to the pack's (`hvBody()`)
                    val delta = volts?.let { it - packBox.getInt("u") }?.let { d -> (if (d > 0) "+" else if (d < 0) "−" else "±") + kotlin.math.abs(d) }
                    HvUnit(
                        u.getString("abbr"), addr, u.getString("name"), state, status(u.getString("kind")), addr.toIntOrNull(16)?.let { Target.Module(it) },
                        busVolts = volts?.let { "$it В" }, busDelta = delta, reading = u.optString("reading").ifEmpty { null }, off = u.optBoolean("off"),
                    )
                },
                machines = objects(hv.getJSONArray("machines")).map { m -> Field(m.getString("name"), m.getString("values"), muted = !m.optBoolean("live")) },
                contactorClosed = if (hv.has("contactorClosed")) hv.getBoolean("contactorClosed") else null,
            ),
            live = LIVE_BOARDS.entries.associate { (board, system) -> system to systemLive(fixture(all, board)) },
            drives = CompareView(
                // [«FMCU · 7E3», «передний»]: the block heads its column, its role under it
                columns = arrays(drives.getJSONArray("units")).map { u ->
                    val (named, role) = u.getString(0) to u.getString(1)
                    val block = (if (" · " in named) named else role).split(" · ")
                    CompareColumn(block[0], if (" · " in named) role else named, block.getOrNull(1)?.toIntOrNull(16))
                },
                rows = arrays(drives.getJSONArray("rows")).map { r ->
                    CompareRow(r.getString(0), r.getString(1), r.getString(2), strings(r.getJSONArray(3)).map { CompareCell(it, muted = it in DIM_CELL) })
                },
                notes = emptyList(),
            ),
            adas = adas(adas),
            brakeModules = listOf(ESP, BSCM),
            gbFlags = gbFlags(fixture(all, if (id in FLAGS_BOARDS) id else "scanner-sys-pack-flags")),
        )
    }

    private val DIM_CELL = setOf("—", "н/д", "нет адреса", "не читали")

    /** Each system's «Потоковые данные» board ([SystemStreams.ROWS]). */
    private val LIVE_BOARDS: Map<String, CarSystem> = SystemStreams.ROWS.keys.associateBy { "scanner-sys-${it.name.lowercase()}-live" }

    /**
     * A system's «Потоковые данные» from its board (`sysStream()`): a row is the device, the parameter,
     * its FID, the value and its unit, the minute's extremes, the norm, 60 s of trace (none for a state
     * or a count) and whether its value is orange; the footer's note is the round.
     */
    private fun systemLive(f: JSONObject): SystemLiveView {
        val footer = f.getJSONObject("footer")
        return SystemLiveView(
            rows = objects(f.getJSONArray("rows")).map { r ->
                val trace = r.getJSONArray("trace")
                val device = r.getString("pid")
                val source = if (r.getString("abbr").isEmpty()) device else "$device·${r.getString("abbr")}"
                LiveRow(
                    pid = SystemLive.NOT_A_PID, name = r.getString("name"), abbreviation = r.getString("abbr"),
                    value = r.getString("value"), unit = r.getString("unit"), min = r.getString("min"), max = r.getString("max"),
                    norm = r.getString("norm"), outOfNorm = r.optBoolean("out"), source = source,
                    samples = (0 until trace.length()).map { Sample(it * 1_000_000_000L, trace.getDouble(it).toFloat()) },
                    // a reading opens alone; a calculation has nothing to poll
                    key = SystemStreams.ROWS.values.flatten().firstOrNull { it.source == source }?.key?.let(ParamKey::Signal),
                    device = device,
                )
            },
            paused = "Продолжить" in arrays(footer.getJSONArray("buttons")).map { it.getString(0) },
            round = arrays(footer.getJSONArray("notes")).firstOrNull()?.getString(0),
        )
    }

    private fun adas(f: JSONObject): CompareView {
        val rows = objects(f.getJSONArray("rows"))
        val routes = objects(rows.first().getJSONArray("cells")).map { it.getString("t").substringBefore(" → ").toInt(16) }
        return CompareView(
            columns = arrays(f.getJSONArray("units")).mapIndexed { i, u -> CompareColumn(u.getString(0), u.getString(1), routes.getOrNull(i)) },
            rows = rows.map { r ->
                CompareRow(
                    r.getString("name"), "", "",
                    objects(r.getJSONArray("cells")).map { c ->
                        val t = c.getString("t")
                        CompareCell(t, muted = t in DIM_CELL, shared = c.optBoolean("shared"), warn = c.optString("ink") == "orangeInk")
                    },
                )
            },
            notes = emptyList(),
        )
    }

    /* -------------------------------------------------------------- json */

    private fun fixture(all: JSONObject, id: String): JSONObject = all.getJSONArray(id).getJSONObject(1)

    private fun mil(word: String): MilState = when (word) {
        "ВКЛ" -> MilState.ON
        "ВЫКЛ" -> MilState.OFF
        else -> MilState.UNKNOWN
    }

    private fun fields(a: JSONArray): List<Field> = arrays(a).map { Field(it.getString(0), it.getString(1), muted = it.optString(2) == "dim") }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun arrays(a: JSONArray): List<JSONArray> = (0 until a.length()).map { a.getJSONArray(it) }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
}
