package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The v4 boards' scan, out of the boards' own data (fixtures.json): the parked scan of 2026-10-20 19:31 as
 * `scanner-blocks` lists it - every block's F194 and median answer - with the records `scanner-summary` lists
 * by system and block. Each block answers what the autoscan asks it, every exchange taking its block's
 * median, the 148 exchanges spread over the board's 19,9 s up to the snapshot at 19:31:29. The boards' clock
 * is read in UTC. What makes this scan is the board; what the views say of it is the engine's.
 */
internal object BoardScan {
    val clock = ScanClock(ZoneOffset.UTC)

    /** «20.10 19:31», «23.10 09:12:40» in 2026, UTC. */
    fun at(text: String): Long {
        val (date, time) = text.split(" ").let { it[0] to it.getOrElse(1) { "00:00" } }
        val (d, m) = date.split(".").map(String::toInt)
        val t = time.split(":").map(String::toInt)
        return LocalDateTime.of(2026, m, d, t[0], t[1], t.getOrElse(2) { 0 }).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    val FINISHED: Long = at("20.10 19:31:29")
    const val SECONDS = 19_900L

    /** Every block of `scanner-blocks` in its order: the request, F194 (null where the scan had none) and its median ms. */
    @Suppress("UNCHECKED_CAST")
    val blocks: List<Triple<Int, String?, Int>> by lazy {
        (SpecJson.fixture("scanner-blocks")["groups"] as List<Map<String, Any?>>).flatMap { g ->
            (g["ecus"] as List<Map<String, Any?>>).map { e ->
                Triple((e["req"] as String).toInt(16), e["f194"] as String?, (e["ms"] as Number).toInt())
            }
        }
    }

    /** A board's records by block: code and status, in its systems' order. */
    @Suppress("UNCHECKED_CAST")
    fun records(fixture: String): Map<Int, List<Pair<String, Int>>> =
        (SpecJson.fixture(fixture)["systems"] as List<Map<String, Any?>>).flatMap { s -> s["blocks"] as List<Map<String, Any?>> }
            .associate { b ->
                (b["addr"] as String).toInt(16) to (b["codes"] as List<Map<String, Any?>>).map { it["code"] as String to (it["status"] as Number).toInt() }
            }

    /** «C1160-06» → «516006». */
    fun dtcHex(code: String): String {
        val hi = ("PCBU".indexOf(code[0]) shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        return "%02X".format(hi) + code.substring(3, 5) + code.substring(6, 8)
    }

    /**
     * The scan's results: [records] by block, each block's availability 09 but those [availability] names
     * (IDC's 29, as it answered). [finished] is the last answer; [overrides] replace a block's whole read.
     */
    fun results(
        records: Map<Int, List<Pair<String, Int>>>,
        availability: Map<Int, Int> = mapOf(0x597 to 0x29),
        finished: Long = FINISHED,
    ): Map<Int, ModuleResult> {
        val asks = blocks.map { (request, f194, ms) -> Triple(request, exchanges(request, f194, records[request].orEmpty(), availability[request] ?: 0x09), ms) }
        val total = asks.sumOf { it.second.size }
        val begin = finished - SECONDS
        val lastMs = asks.last().third
        val step = (SECONDS - lastMs).toDouble() / (total - 1)
        var k = 0
        return asks.associate { (request, list, ms) ->
            val replies = list.map { (uds, answer) ->
                val end = if (k == total - 1) finished else begin + Math.round(k * step) + ms
                k++
                DiagReply.Answer(DiagRequest(request, hexBytes(uds)), hexBytes(answer), 1, end, ms, end * 1_000_000L)
            }
            request to ModuleResult(EcuCatalog.byRequest(request)!!, ModuleRead(request, replies.last().atMillis, replies))
        }
    }

    /** What the autoscan of 19:31 asked [request] and what came back. */
    private fun exchanges(request: Int, f194: String?, records: List<Pair<String, Int>>, availability: Int): List<Pair<String, String>> {
        val ecu = EcuCatalog.byRequest(request)!!
        val out = ArrayList<Pair<String, String>>()
        if (!ecu.uds) {
            // BSCM: its name is its gate, then OBD alone.
            out += "090A" to BSCM_NAME
            out += obd()
            return out
        }
        if (f194 != null) out += "22F194" to "62F194$f194"
        val avail = "%02X".format(availability)
        val list = "5902$avail" + records.joinToString("") { (code, status) -> dtcHex(code) + "%02X".format(status) }
        if (!ecu.dtcCountAnswers) {
            out += "190209" to list
        } else {
            out += "190109" to "5901${avail}00%04X".format(records.size)
            if (records.isNotEmpty()) out += "190209" to list
        }
        if (ecu.obd) out += obd()
        return out
    }

    private fun obd() = listOf("0101" to "41010007F5F4", "03" to "4300", "07" to "4700", "0A" to "4A00")

    /** BSCM's own `49 0A` (IdentityGateTest). */
    private const val BSCM_NAME = "490A014253434D2D4272616B6553797374656D00000000"

    /** The parked car of the boards: P at 0 km/h, the pack at 553 V and 74 %, 12 V at [lv]. */
    fun car(lv: Double = 13.7, packVolts: Double = 553.0, extra: Map<DiagnosticExtra, Double> = emptyMap()) = CarReadings(
        hub = mapOf(
            VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.ODOMETER_KM to 13_199.7,
            VehicleSignal.PACK_VOLT to packVolts, VehicleSignal.CELL_MIN_MV to 3333.0, VehicleSignal.CELL_MAX_MV to 3337.0,
        ),
        sweep = 1,
        extras = mapOf(
            DiagnosticExtra.LV to lv, DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0,
            DiagnosticExtra.INSULATION to 14_429.0, DiagnosticExtra.INSULATION_2 to 19_675.0,
            DiagnosticExtra.TEMP_MIN to 13.0, DiagnosticExtra.TEMP_MAX to 18.0,
        ) + extra,
        extrasFresh = true,
    )

    /** The section after the scan, as the session would hand it to [ModelBuilder]. */
    fun snapshot(
        results: Map<Int, ModuleResult>,
        car: CarReadings = car(),
        journal: JournalData = JournalData(),
        now: Long = FINISHED,
        finished: Long = FINISHED,
        openCode: Pair<Int, String>? = null,
        codeReads: Map<Int, Map<String, CodeReadsData>> = emptyMap(),
        packCells: PackCellsView? = null,
        journalVisible: Boolean = false,
        streams: Map<Int, LiveStream> = emptyMap(),
        live: Int? = null,
        graphs: GraphsSnapshot? = null,
        compare: Pair<Long?, Long?>? = null,
        idents: Map<Int, IdentResult> = emptyMap(),
        recording: dev.denza.apps.feature.diagnostics.report.ReportRecording? = null,
    ) = SessionSnapshot(
        link = LinkState.OK, phase = ScanPhase.DONE, done = EcuCatalog.routed.size, finishedAtMillis = finished, notice = null,
        polling = null, results = results, idents = idents, open = emptySet(), streams = streams, live = live,
        streaming = true, recording = null, car = car, nowMillis = now, packCells = packCells, journal = journal,
        journalVisible = journalVisible, openCode = openCode, codeReads = codeReads, graphs = graphs, compare = compare,
        reportRecording = recording,
    )

    /** The manual's public catalog, as the app carries it. */
    val catalog: OemDtcIndex by lazy {
        val research = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "research/vehicle-diagnostics").exists() }.resolve("research/vehicle-diagnostics")
        OemDtcAssets.parse(research.resolve(OemDtcAssets.INDEX).readText(), research.resolve(OemDtcAssets.TRANSLATION).readText())
    }

    fun model(s: SessionSnapshot): DiagnosticsModel = ModelBuilder(clock) { catalog }.build(s)

    /** [result] read again by hand at [atMillis]: the same answers, at that time. */
    fun again(result: ModuleResult, atMillis: Long): ModuleResult {
        val replies = result.read.replies.map { r ->
            r as DiagReply.Answer
            DiagReply.Answer(r.request, r.uds, 1, atMillis, r.latencyMs, atMillis * 1_000_000L)
        }
        return ModuleResult(result.ecu, ModuleRead(result.read.request, atMillis, replies))
    }
}

/**
 * The journal the v4 boards share (fixtures.js, «The journal's moments»): LA_BCM read alone on 18.10, R_ZCU at
 * 15:42 on 20.10, then the seven scans from 20.10 19:31 to 23.10 08:50, each of them the boards' scan, with
 * 12 V, the insulation and the cells' spread by scan as the boards draw them and the BMS's F195 3.48.06 until
 * the update of 22.10, 3.48.07 after it; the nine cell snapshots of the battery's page with their charge and
 * spread, cell 9 the lowest in seven of them.
 */
internal object BoardJournal {
    val FULL = listOf("20.10 19:31", "21.10 08:05", "21.10 18:40", "22.10 07:55", "22.10 14:27", "22.10 19:12", "23.10 08:50")
    private val V12 = listOf(13.7, 13.6, 13.7, 13.5, 13.7, 13.6, 13.6)
    /** The BMS channel by scan, 14,4 at the first and 14,2 at the last, as «Журнал» compares them. */
    private val ISO = listOf(14.4, 14.4, 14.3, 14.5, 14.4, 14.4, 14.2)
    private val ISO2 = listOf(19.7, 19.6, 19.7, 19.8, 19.7, 19.7, 19.5)
    private val CELL_MAX = listOf(3337.0, 3337.0, 3338.0, 3337.0, 3338.0, 3337.0, 3338.0)
    /** «3.48.06 · 1A011402», «3.48.07 · 1A011402» as the BMS sends F195. */
    const val BMS_OLD = "87F61A011402"
    const val BMS_NEW = "87F71A011402"
    private const val UPDATE = "22.10 03:10"

    val CELL_SHOTS = listOf(
        Triple("18.10 16:02", 81, 4), Triple("20.10 15:50", 78, 4), Triple("20.10 19:35", 74, 4), Triple("21.10 08:10", 72, 5),
        Triple("21.10 18:44", 96, 11), Triple("22.10 07:58", 69, 4), Triple("22.10 14:30", 64, 5), Triple("22.10 23:40", 97, 12),
        Triple("23.10 07:36", 74, 5),
    )
    /** The lowest cell of each snapshot: 9 in seven of the nine. */
    private val MIN_CELL = listOf(9, 9, 40, 9, 9, 9, 120, 9, 9)
    /** Each snapshot's probes' spread: «обычно Δ 4–7». */
    private val PROBE_SPREAD = listOf(4, 5, 6, 7, 5, 6, 4, 7, 6)

    fun data(results: Map<Int, ModuleResult>, extra: List<JournalScan> = emptyList()): JournalData {
        fun alone(at: String, request: Int) =
            JournalEntries.scan(JournalScanKind.MODULE, listOf(results.getValue(request)), emptyMap(), CarReadings(), BoardScan.at(at))
        var d = JournalData().with(alone("18.10 15:42", 0x720)).with(alone("20.10 15:42", 0x747))
        val all = EcuCatalog.routed.map { results.getValue(it.request!!) }
        FULL.forEachIndexed { i, at ->
            val millis = BoardScan.at(at)
            val scan = JournalEntries.scan(JournalScanKind.FULL, all, emptyMap(), CarReadings(), millis)
            val f195 = if (millis < BoardScan.at(UPDATE)) BMS_OLD else BMS_NEW
            d = d.with(scan.copy(
                odometerKm = 13_199.7,
                vitals = JournalVitals(lvVolts = V12[i], soc = 74.0, soh = 99.0, cellMinMv = 3333.0, cellMaxMv = CELL_MAX[i],
                    insulationMohm = ISO[i], insulation2Mohm = ISO2[i], probeMinC = 13.0, probeMaxC = 18.0, mil = false),
                modules = scan.modules.map { if (it.address == 0x7E7) it.copy(f195 = f195) else it },
            ))
        }
        extra.forEach { d = d.with(it) }
        CELL_SHOTS.forEachIndexed { i, (at, soc, spread) ->
            val mv = MutableList<Int?>(166) { 3282 }
            val min = MIN_CELL[i]
            mv[min - 1] = 3283 - spread
            mv[min % 166] = 3283
            val probes = List(14) { 10 }.toMutableList<Int?>().also { it[6] = 10 + PROBE_SPREAD[i] }
            d = d.with(JournalCells(
                atMillis = BoardScan.at(at), soc = soc.toDouble(), currentA = 0.0, odometerKm = 13_199.7,
                cellsMv = mv, probesC = probes, minCell = min, maxCell = (min % 166) + 1, minProbe = 1, maxProbe = 7,
                fullCharge = soc >= JournalCells.FULL_SOC,
            ))
        }
        return d
    }
}
