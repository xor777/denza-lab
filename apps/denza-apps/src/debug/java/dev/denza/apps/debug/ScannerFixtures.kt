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
import dev.denza.apps.feature.diagnostics.scan.RailItem
import dev.denza.apps.feature.diagnostics.scan.RailTone
import dev.denza.apps.feature.diagnostics.scan.RangeView
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

    private val scan = ScannerRoute.Scan()

    /** Where each board's screen is: the stack the section opens with, the autoscan at its bottom. */
    fun route(id: String): List<ScannerRoute> {
        fun sys(s: CarSystem, tab: SystemTab) = listOf(scan, ScannerRoute.System(s, tab))
        return when (id) {
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
            "scanner-body-dtc" -> listOf(scan, ScannerRoute.Module(R_ZCU, 0))
            "scanner-ecu-dtc", "scanner-ecu-fault" -> listOf(scan, ScannerRoute.Module(ECM, 0))
            "scanner-ecu-live" -> listOf(scan, ScannerRoute.Module(ECM, 1))
            "scanner-ecu-ident" -> listOf(scan, ScannerRoute.Module(ECM, 2))
            "scanner-ecu-ident-updated" -> listOf(scan, ScannerRoute.Module(BMS, 1))
            "scanner-esp-dtc" -> listOf(scan, ScannerRoute.System(CarSystem.CHASSIS), ScannerRoute.Module(ESP, 0))
            else -> listOf(scan)
        }
    }

    /** The scenes' live traces are already 0..1 of their own range. */
    @Suppress("UNUSED_PARAMETER")
    fun sample(all: JSONObject, id: String): ScannerSample = ScannerSample(chartRange = 0f..1f)

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
        return DiagnosticsModel(
            header = Header(
                link = if (top.has("link")) LinkState.OK else LinkState.LOST,
                car = listOf(top.getString("car"), top.optString("mode")).filter { it.isNotEmpty() }.joinToString(" · "),
                clock = top.getString("time"),
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
            rail = rail(board.optJSONObject("rail") ?: fixture(all, "scanner-summary").getJSONObject("rail")),
            systemPages = pages(all, groups, id),
            param = PARAM_KEYS[id]?.let { param(fixture(all, id), it) },
            packCells = if (id in CELLS_BOARDS) cells(fixture(all, id)) else null,
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

    private fun rail(r: JSONObject): List<RailItem> {
        val items = r.getJSONArray("items")
        return (0 until items.length()).mapNotNull { i -> items.get(i) as? JSONObject }.map { it ->
            val name = it.getString("name")
            val active = it.optInt("active")
            val records = active + it.optInt("pending") + it.optInt("history")
            val (mark, tone) = when {
                it.has("count") -> it.getInt("count").toString() to RailTone.COUNT
                records == 0 -> null to RailTone.NONE
                active > 0 -> records.toString() to RailTone.ACTIVE
                else -> records.toString() to RailTone.HISTORY
            }
            RailItem(CarSystem.entries.firstOrNull { s -> s.railName == name }, name, mark, tone)
        }
    }

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
            val block = (if (system == CarSystem.PACK && id in CELLS_BOARDS) fixture(all, id) else f).getJSONObject("block")
            val blocks = when (system) {
                CarSystem.BODY -> groups.first { it.id == "body" }.rows
                CarSystem.CABIN -> groups.first { it.id == "salon" }.rows.filter { it.request != CPD }
                else -> BLOCKS.getValue(system).mapNotNull { req -> rows.firstOrNull { it.request == req } }
            }
            SystemPage(
                system = system,
                aside = block.getString("aside"),
                line = block.getString("line"),
                counters = arrays(block.getJSONArray("counters")).map { HeadCounter(it.getString(0), it.getString(1), on = it.optString(2) == "on") },
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
                            c.getInt("status"), 0xFF, description = c.optString("desc").ifEmpty { null },
                        )
                    },
                    quality = DataQuality.COMPLETE, outsideMask = false, footnote = footnote(body), source = source(body),
                ),
            ),
            ECM to ecm(all, id),
            BMS to updated(fixture(all, "scanner-ecu-ident-updated")),
            ESP to blockModule(ESP, esp.getJSONObject("block"), ModuleStatus.OK).copy(
                dtc = DtcView(
                    asks = arrays(esp.getJSONArray("asks")).map { a -> ask(a.getString(0), a.getString(1), a.getString(2)) },
                    records = emptyList(), quality = DataQuality.COMPLETE_EMPTY, outsideMask = false, footnote = footnote(esp),
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
            inferred = false, line = block.getString("line"), status = ModuleStatus.OK,
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
                    val state = listOfNotNull(u.optInt("volts").takeIf { u.has("volts") }?.let { "$it В" }, u.optString("reading").ifEmpty { null }).joinToString(" · ")
                    HvUnit(u.getString("abbr"), addr, u.getString("name"), state, status(u.getString("kind")), addr.toIntOrNull(16)?.let { Target.Module(it) })
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
