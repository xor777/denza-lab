package dev.denza.apps.debug

import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.AskRow
import dev.denza.apps.feature.diagnostics.scan.Callout
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
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.Header
import dev.denza.apps.feature.diagnostics.scan.HeadCounter
import dev.denza.apps.feature.diagnostics.scan.HvUnit
import dev.denza.apps.feature.diagnostics.scan.HvView
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.LiveRow
import dev.denza.apps.feature.diagnostics.scan.LiveView
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.MonitorRow
import dev.denza.apps.feature.diagnostics.scan.MonitorState
import dev.denza.apps.feature.diagnostics.scan.ObdView
import dev.denza.apps.feature.diagnostics.scan.RailItem
import dev.denza.apps.feature.diagnostics.scan.RailTone
import dev.denza.apps.feature.diagnostics.scan.RangeView
import dev.denza.apps.feature.diagnostics.scan.Sample
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.Scheme
import dev.denza.apps.feature.diagnostics.scan.SignalRow
import dev.denza.apps.feature.diagnostics.scan.SystemDtcRow
import dev.denza.apps.feature.diagnostics.scan.SystemPage
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.SystemsView
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.scan.Tyre
import dev.denza.apps.feature.diagnostics.ui.ScannerRoute
import dev.denza.apps.feature.diagnostics.ui.ScannerSample
import dev.denza.apps.feature.diagnostics.ui.ScannerWords
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Luminofor `scanner-*` scenes (`fixtures.js`, exported to `luminofor/fixtures.json`) as the
 * [DiagnosticsModel] the screens draw, so a fixture's screenshot and its board show one model.
 *
 * A scene is data, not strings laid out: an ECU is its name, unit, request address, protocol and
 * kind, and the screens derive the rest - the reply address, the chips, the summaries - as the
 * board does. Every board shares the autoscan and the rail of `scanner-sys-scan` and every
 * system's page from the `scanner-sys-*` scenes; each adds its own header. What a scene draws that
 * a session would never say (its values are illustrative) comes back as a [ScannerSample].
 */
internal object ScannerFixtures {

    private const val ECM = 0x7E0
    private const val R_ZCU = 0x747
    private const val ESP = 0x782
    private const val BSCM = 0x7E5

    private val scan = ScannerRoute.Scan()

    /** Where each board's screen is: the stack the section opens with, the autoscan at its bottom. */
    fun route(id: String): List<ScannerRoute> {
        fun sys(s: CarSystem, tab: SystemTab) = listOf(scan, ScannerRoute.System(s, tab))
        return when (id) {
            "scanner-sys-topology" -> listOf(ScannerRoute.Scan(topology = true))
            "scanner-sys-engine" -> sys(CarSystem.ENGINE, SystemTab.LIVE)
            "scanner-sys-engine-scheme" -> sys(CarSystem.ENGINE, SystemTab.STATE)
            "scanner-sys-engine-dtc" -> sys(CarSystem.ENGINE, SystemTab.DTC_READINESS)
            "scanner-sys-body" -> sys(CarSystem.BODY, SystemTab.DTC)
            "scanner-sys-body-state" -> sys(CarSystem.BODY, SystemTab.STATE)
            "scanner-sys-body-blocks" -> sys(CarSystem.BODY, SystemTab.BLOCKS)
            "scanner-sys-chassis" -> sys(CarSystem.CHASSIS, SystemTab.STATE)
            "scanner-sys-adas" -> sys(CarSystem.ADAS, SystemTab.STATE)
            "scanner-sys-adas-compare" -> sys(CarSystem.ADAS, SystemTab.COMPARE)
            "scanner-sys-drives" -> sys(CarSystem.DRIVES, SystemTab.STATE)
            "scanner-sys-drives-compare" -> sys(CarSystem.DRIVES, SystemTab.COMPARE)
            "scanner-sys-hv" -> sys(CarSystem.HV, SystemTab.STATE)
            "scanner-sys-pack" -> sys(CarSystem.PACK, SystemTab.STATE)
            "scanner-sys-pack-live" -> sys(CarSystem.PACK, SystemTab.LIVE)
            "scanner-sys-pack-flags" -> sys(CarSystem.PACK, SystemTab.GB_FLAGS)
            "scanner-sys-climate" -> sys(CarSystem.CLIMATE, SystemTab.STATE)
            "scanner-sys-lv" -> sys(CarSystem.LV, SystemTab.STATE)
            "scanner-body-dtc" -> listOf(scan, ScannerRoute.Module(R_ZCU, 0))
            "scanner-ecu-dtc", "scanner-ecu-fault" -> listOf(scan, ScannerRoute.Module(ECM, 0))
            "scanner-ecu-live" -> listOf(scan, ScannerRoute.Module(ECM, 1))
            "scanner-ecu-ident" -> listOf(scan, ScannerRoute.Module(ECM, 2))
            "scanner-esp-dtc" -> listOf(scan, ScannerRoute.System(CarSystem.CHASSIS), ScannerRoute.Module(ESP, 0))
            else -> listOf(scan)
        }
    }

    /** The scenes' live traces are already 0..1 of their own range. */
    @Suppress("UNUSED_PARAMETER")
    fun sample(all: JSONObject, id: String): ScannerSample = ScannerSample(chartRange = 0f..1f)

    fun model(all: JSONObject, id: String): DiagnosticsModel {
        val scanBoard = fixture(all, "scanner-sys-scan")
        val top = fixture(all, id).getJSONObject("top")
        val counters = arrays(scanBoard.getJSONObject("block").getJSONArray("counters")).associate { it.getString(0) to it.getString(1) }
        val groups = groups(scanBoard.getJSONArray("groups"))
        return DiagnosticsModel(
            header = Header(
                link = if (top.getString("link") == "Связь OK") LinkState.OK else LinkState.LOST,
                car = top.getString("car"),
                clock = top.getString("time"),
            ),
            scan = ScanSummary(
                phase = ScanPhase.DONE, done = 68, total = 68,
                finishedAt = Regex("опрос (\\d\\d:\\d\\d)").find(scanBoard.getJSONObject("block").getString("line"))?.groupValues?.get(1),
                answered = counters.getValue("ОТВЕТИЛО"), active = counters.getValue("DTC АКТ.").toIntOrNull(),
                history = counters.getValue("DTC ИСТ.").toIntOrNull(), memoryRead = counters.getValue("ПРОЧИТАНО"),
                mil = MilState.OFF, notice = null,
            ),
            groups = groups,
            modules = modules(all, id),
            systems = systems(all),
            rail = rail(scanBoard.getJSONObject("rail")),
            systemPages = pages(all, groups),
        )
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
            val mark = it.optJSONArray("mark")
            val tone = when (mark?.getString(1)) {
                null -> RailTone.NONE
                "faint" -> RailTone.COUNT
                "ink4" -> RailTone.HISTORY
                else -> RailTone.ACTIVE
            }
            RailItem(CarSystem.entries.firstOrNull { s -> s.railName == name }, name, mark?.getString(0), tone)
        }
    }

    /* ------------------------------------------------------------- systems */

    /** Each system's blocks (architecture, 7.2), the body by its group. */
    private val BLOCKS = mapOf(
        CarSystem.HV to listOf(0x7E7, 0x7E3, 0x744, 0x745, 0x7E6, 0x7E0, 0x7E4, 0x766, 0x762),
        CarSystem.PACK to listOf(0x7E7),
        CarSystem.DRIVES to listOf(0x7E2, 0x7E3, 0x744, 0x745, 0x7E6),
        CarSystem.ENGINE to listOf(ECM),
        CarSystem.CHASSIS to listOf(ESP, BSCM, 0x783),
        CarSystem.ADAS to listOf(0x712, 0x7F2, 0x760, 0x761, 0x7C2, 0x7D4, 0x701),
        CarSystem.CLIMATE to listOf(0x7B3, 0x7B4, 0x766),
        CarSystem.LV to listOf(0x762),
    )

    /** The board that shows a system's head and «Состояние». */
    private val STATE_BOARD = mapOf(
        CarSystem.HV to "scanner-sys-hv", CarSystem.PACK to "scanner-sys-pack", CarSystem.DRIVES to "scanner-sys-drives",
        CarSystem.ENGINE to "scanner-sys-engine-scheme", CarSystem.CHASSIS to "scanner-sys-chassis",
        CarSystem.BODY to "scanner-sys-body-state", CarSystem.ADAS to "scanner-sys-adas",
        CarSystem.CLIMATE to "scanner-sys-climate", CarSystem.LV to "scanner-sys-lv",
    )

    private const val CODES = "19 01 09 · 19 02 09 · снимок 19:31:29"

    private fun pages(all: JSONObject, groups: List<ModuleGroupView>): Map<CarSystem, SystemPage> {
        val rows = groups.flatMap { it.rows }
        fun opened(r: ModuleRowView) = r.copy(target = r.request?.let { Target.Module(it) })
        return STATE_BOARD.mapValues { (system, board) ->
            val f = fixture(all, board)
            val block = f.getJSONObject("block")
            val blocks = if (system == CarSystem.BODY) groups.first { it.id == "body" }.rows else {
                BLOCKS.getValue(system).mapNotNull { req -> rows.firstOrNull { it.request == req } }
            }
            SystemPage(
                system = system,
                aside = block.getString("aside"),
                line = block.getString("line"),
                counters = arrays(block.getJSONArray("counters")).map { HeadCounter(it.getString(0), it.getString(1), on = it.optString(2) == "on") },
                tabs = strings(f.getJSONObject("tabs").getJSONArray("items")).map { label -> SystemTab.entries.first { it.label == label } },
                blocks = blocks.map(::opened),
                dtc = if (system == CarSystem.BODY) bodyCodes(fixture(all, "scanner-sys-body")) else emptyList(),
                dtcFootnote = if (system == CarSystem.ENGINE) "" else CODES,
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
        return when (system) {
            CarSystem.HV -> Scheme.Hv
            CarSystem.PACK -> {
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
                rearBlocks = f.getString("rearBlocks"), engineRunning = false,
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
                tyres = objects(f.getJSONArray("tyres")).map { Tyre(corner(it.getString("side"), it.getString("axle")), it.getString("label"), it.getString("pressure"), it.getString("temp")) },
                esp = t("esp"), eps = t("eps"), bscm = t("bscm"),
            )
            CarSystem.BODY -> Scheme.Body(
                doors = objects(f.getJSONArray("doors")).map {
                    Door(corner(it.getString("side"), it.getString("axle")), it.getString("label"), it.getString("state"), it.getString("detail"), open = it.optBoolean("open"))
                },
                front = t("front"), back = t("back"), left = t("left"), right = t("right"), lampLeft = t("lampL"), lampRight = t("lampR"), uwb = t("uwb"),
                anchors = listOf(0x7F4, 0x7F5, 0x7F6, 0x7F7).map { req -> rows.firstOrNull { it.request == req }?.status ?: ModuleStatus.NO_ROUTE },
            )
            CarSystem.ADAS -> Scheme.Adas(
                front = t("mrr"),
                corners = listOfNotNull(t("fl")?.let { Corner.FL to it }, t("fr")?.let { Corner.FR to it }, t("rl")?.let { Corner.RL to it }, t("rr")?.let { Corner.RR to it }).toMap(),
                controller = t("adc"), parking = t("ipa"),
            )
            CarSystem.CLIMATE -> {
                fun zone(o: JSONObject) = ClimateZone(o.getString("label"), o.getString("temp"), o.getString("pm"), o.optBoolean("inside"))
                Scheme.Climate(zone(f.getJSONObject("outside")), zone(f.getJSONObject("cabin")), t("acc"), t("ptc"), t("tmim"))
            }
            CarSystem.LV -> Scheme.Lv(
                hv = f.getString("hv"), lv = f.getString("lv"), dcdc = t("dcdc"), battery = t("lbms"),
                loads = objects(units!!.getJSONArray("loads")).mapNotNull { tile(it, rows) },
            )
            else -> null
        }
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
            ESP to blockModule(ESP, esp.getJSONObject("block"), ModuleStatus.OK).copy(
                dtc = DtcView(
                    asks = arrays(esp.getJSONArray("asks")).map { a -> ask(a.getString(0), a.getString(1), a.getString(2)) },
                    records = emptyList(), quality = DataQuality.COMPLETE_EMPTY, outsideMask = false, footnote = footnote(esp),
                ),
            ),
        )
    }

    /** A block from a board's head: title (≈ when inferred), «· unit», the mono line. */
    private fun blockModule(request: Int, block: JSONObject, status: ModuleStatus): ModuleView {
        val title = block.getString("title")
        return ModuleView(
            request = request, name = title.removePrefix("≈ "), unit = block.getString("aside").removePrefix("· "),
            inferred = title.startsWith("≈ "), line = block.getString("line"), status = status,
            dtc = DtcView(emptyList(), emptyList(), DataQuality.COMPLETE_EMPTY, false, ""),
            ident = IdentView(emptyList(), emptyList(), matched = null, pids = emptyList()), obd = null,
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
        val match = ident.getJSONArray("match")
        return ModuleView(
            request = ECM, name = block.getString("title"), unit = block.getString("aside").removePrefix("· "),
            inferred = false, line = block.getString("line"), status = ModuleStatus.OK,
            dtc = DtcView(emptyList(), records, if (fault) DataQuality.COMPLETE else DataQuality.COMPLETE_EMPTY, false, "", source = source),
            ident = IdentView(
                own = fields(ident.getJSONArray("own")),
                cache = fields(ident.getJSONArray("cache")),
                matched = match.getString(1) == "СОВПАЛ",
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
                        )
                    },
                    recording = false,
                ),
            ),
        )
    }

    /* ------------------------------------------------- the systems' data */

    private fun systems(all: JSONObject): SystemsView {
        val hv = fixture(all, "scanner-sys-hv")
        val pack = fixture(all, "scanner-sys-pack-live")
        val drives = fixture(all, "scanner-sys-drives-compare")
        val adas = fixture(all, "scanner-sys-adas-compare")
        val packBox = hv.getJSONObject("pack")
        return SystemsView(
            hv = HvView(
                pack = arrays(packBox.getJSONArray("rows")).map { Field(it.getString(0), it.getString(1)) },
                packStatus = ModuleStatus.OK,
                busVoltages = strings(hv.getJSONArray("volts")),
                units = objects(hv.getJSONArray("units")).map { u ->
                    val addr = u.getString("addr")
                    HvUnit(u.getString("abbr"), addr, u.getString("name"), u.getString("state"), status(u.getString("kind")), addr.toIntOrNull(16)?.let { Target.Module(it) })
                },
                machines = objects(hv.getJSONArray("machines")).map { m -> Field(m.getString("name"), m.getString("values"), muted = !m.optBoolean("live")) },
            ),
            pack = arrays(pack.getJSONArray("rows")).map { r ->
                SignalRow(r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4), ok = if (r.getString(5) == "OK") true else null, stale = false)
            },
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
        )
    }

    private val DIM_CELL = setOf("—", "н/д", "нет адреса", "не читали")

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
