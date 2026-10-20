package dev.denza.apps.debug

import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.AskRow
import dev.denza.apps.feature.diagnostics.scan.CompareCell
import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.scan.CompareRow
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.Header
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
import dev.denza.apps.feature.diagnostics.scan.Sample
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.SignalRow
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.SystemsView
import dev.denza.apps.feature.diagnostics.scan.Target
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
 * board does. Every board shares the autoscan of `scanner-scan`; each adds its own header and the
 * ECUs and systems it shows. What a scene draws that a session would never say (its values are
 * illustrative) comes back as a [ScannerSample].
 */
internal object ScannerFixtures {

    private const val ECM = 0x7E0
    private const val R_ZCU = 0x747
    private const val BMS = 0x7E7
    private const val ESP = 0x782
    private const val BSCM = 0x7E5

    /** Where each board's screen is. */
    fun route(id: String): ScannerRoute = when (id) {
        "scanner-topology" -> ScannerRoute.Scan(topology = true)
        "scanner-body-dtc" -> ScannerRoute.Module(R_ZCU, 0)
        "scanner-ecu-dtc", "scanner-ecu-fault" -> ScannerRoute.Module(ECM, 0)
        "scanner-ecu-live" -> ScannerRoute.Module(ECM, 1)
        "scanner-ecu-ident" -> ScannerRoute.Module(ECM, 2)
        "scanner-bms" -> ScannerRoute.System(CarSystem.PACK, 1)
        "scanner-drives" -> ScannerRoute.System(CarSystem.DRIVES, 0)
        "scanner-hv" -> ScannerRoute.System(CarSystem.HV, 0)
        "scanner-brakes" -> ScannerRoute.System(CarSystem.CHASSIS, 0)
        "scanner-adas" -> ScannerRoute.System(CarSystem.ADAS, 0)
        else -> ScannerRoute.Scan()
    }

    /** The scenes' live traces are already 0..1 of their own range. */
    @Suppress("UNUSED_PARAMETER")
    fun sample(all: JSONObject, id: String): ScannerSample = ScannerSample(chartRange = 0f..1f)

    fun model(all: JSONObject, id: String): DiagnosticsModel {
        val scan = fixture(all, "scanner-scan")
        val here = fixture(all, id)
        val top = here.getJSONObject("top")
        val counters = scan.getJSONArray("counters")
        fun counter(i: Int) = counters.getJSONArray(i).getString(1)
        return DiagnosticsModel(
            header = Header(
                link = if (top.getString("link") == "Связь OK") LinkState.OK else LinkState.LOST,
                car = top.getString("car"),
                clock = top.getString("time"),
            ),
            scan = ScanSummary(
                phase = ScanPhase.DONE, done = 46, total = 46, finishedAt = scan.getJSONObject("top").getString("time").take(5),
                answered = counter(0), active = counter(1).toIntOrNull(), history = counter(2).toIntOrNull(),
                memoryRead = counter(3), mil = mil(counter(4)), notice = null,
            ),
            groups = groups(scan.getJSONArray("groups")),
            modules = modules(all, id),
            systems = systems(all),
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
            target = e.optStringOrNull("opens")?.let { target(it, req) },
        )
    }

    private fun status(kind: String): ModuleStatus = when (kind) {
        "ok" -> ModuleStatus.OK
        "hist" -> ModuleStatus.HISTORY
        "unread" -> ModuleStatus.UNREAD
        else -> ModuleStatus.NO_ROUTE
    }

    private fun target(board: String, request: Int?): Target? = when (board) {
        "scanner-ecu-dtc" -> Target.Module(ECM)
        "scanner-body-dtc" -> Target.Module(R_ZCU)
        "scanner-drives" -> Target.System(CarSystem.DRIVES)
        "scanner-bms" -> Target.System(CarSystem.PACK)
        "scanner-hv" -> Target.System(CarSystem.HV)
        "scanner-brakes" -> Target.System(CarSystem.CHASSIS)
        "scanner-adas" -> Target.System(CarSystem.ADAS)
        else -> request?.let { Target.Module(it) }
    }

    /* ------------------------------------------------------------- ECUs */

    private fun modules(all: JSONObject, id: String): Map<Int, ModuleView> {
        val body = fixture(all, "scanner-body-dtc")
        val bms = fixture(all, "scanner-bms")
        val brakes = fixture(all, "scanner-brakes")
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
            BMS to blockModule(BMS, bms.getJSONObject("block"), ModuleStatus.OK),
            ESP to blockModule(ESP, brakes.getJSONObject("block"), ModuleStatus.OK).copy(
                dtc = DtcView(
                    asks = arrays(brakes.getJSONArray("asks")).map { a -> ask(a.getString(0), a.getString(1), a.getString(2)) },
                    records = emptyList(), quality = DataQuality.COMPLETE_EMPTY, outsideMask = false, footnote = footnote(brakes),
                ),
            ),
            BSCM to ModuleView(
                request = BSCM, name = "Тормозная система · интерфейс OBD", unit = "BSCM", inferred = false,
                line = "${ScannerWords.route(BSCM)} · OBD-II", status = ModuleStatus.OK,
                dtc = DtcView(emptyList(), emptyList(), DataQuality.COMPLETE_EMPTY, false, ""),
                ident = IdentView(fields(brakes.getJSONArray("obd")), emptyList(), matched = null, pids = emptyList()),
                obd = ObdView(MilState.OFF, "", "", "", emptyList(), emptyList(), LiveView(false, "—", emptyList(), emptyList(), false)),
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

    /** The footer's mono note less its «только чтение»: what was asked and when. */
    private fun footnote(f: JSONObject): String {
        val notes = f.getJSONObject("footer").optJSONArray("notes") ?: JSONArray()
        val mono = (0 until notes.length()).map { notes.get(it) }.filterIsInstance<JSONArray>().lastOrNull()?.getString(0) ?: ""
        return mono.removeSuffix("только чтение").removeSuffix(" · ")
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

    /* ----------------------------------------------------------- systems */

    private fun systems(all: JSONObject): SystemsView {
        val hv = fixture(all, "scanner-hv")
        val bms = fixture(all, "scanner-bms")
        val drives = fixture(all, "scanner-drives")
        val adas = fixture(all, "scanner-adas")
        val pack = hv.getJSONObject("pack")
        return SystemsView(
            hv = HvView(
                pack = arrays(pack.getJSONArray("rows")).map { Field(it.getString(0), it.getString(1)) },
                packStatus = ModuleStatus.OK,
                busVoltages = strings(hv.getJSONArray("volts")),
                units = objects(hv.getJSONArray("units")).map { u ->
                    val addr = u.getString("addr")
                    HvUnit(
                        u.getString("abbr"), addr, u.getString("name"), u.getString("state"), status(u.getString("kind")),
                        u.optStringOrNull("opens")?.let { target(it, addr.toIntOrNull(16)) },
                    )
                },
                machines = objects(hv.getJSONArray("machines")).map { m -> Field(m.getString("name"), m.getString("values"), muted = !m.optBoolean("live")) },
            ),
            pack = arrays(bms.getJSONArray("rows")).map { r ->
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
                notes = strings(drives.getJSONObject("footer").optJSONArray("notes") ?: JSONArray()),
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
            notes = arrays(f.optJSONArray("notes") ?: JSONArray()).map { "${it.getString(0)} ${it.getString(1)}" },
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
