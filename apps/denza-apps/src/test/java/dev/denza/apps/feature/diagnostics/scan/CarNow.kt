package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * «Машина сейчас»: the fake bus answers what the car answered in the research runs
 * (the JSON files of `research/vehicle-diagnostics/`), byte for byte where the run asked what the product asks.
 *
 * Where a run asked another mask, its answer is carried to mask `09`, and each such step is named:
 * - `720` and `747` were read with `FF`; every record they hold has status `28`, inside `09`, so the
 *   count and the list are the same bytes;
 * - `722`, `726` (`FF`), `7E3`, `7E6`, `7E7` (`FF`, availability `09`) and `7E0`, `7E2` (`08`) counted
 *   zero, and nothing the product's mask holds that theirs did not was ever seen there.
 * OBD lists that counted zero are `43 00`, `47 00`, `4A 00`. Every other request gets what an
 * unanswered address gets on the car, the gateway's 3: the 49 identified modules whose memory the
 * research never read (22 of them the factory-map candidates) answer their gate and nothing else.
 */
internal object CarNow {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    fun research(name: String) = JSONObject(File(root, "research/vehicle-diagnostics/$name").readText())
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private val obd = research("obd-20261020.json")
    private val remaining = research("remaining-ecus-20261020.json")
    private val remainingRows = remaining.getJSONArray("runs").objects().flatMap { it.getJSONArray("rows").objects() }
    private val candidateRows = research("factory-candidates-20261020.json").getJSONArray("runs").objects()
        .flatMap { it.getJSONArray("rows").objects() }

    /** Every F194 the car returned, by address: the remaining-ECU and factory-candidate runs and run 4 of the OBD runs. */
    val f194: Map<Int, String> by lazy {
        (remainingRows + candidateRows).filter { it.getString("uds") == "22F194" && it.optString("result") == "ok" }
            .associate { it.getString("request").toInt(16) to it.getString("payload") } +
            obd.getJSONArray("run4_ident2").objects().filter { it.optString("result") == "ok" }
                .associate { it.getString("request").toInt(16) to "62F194" + it.getString("value") }
    }

    fun payload(request: Int, uds: String): String = remainingRows
        .first { it.getString("request").toInt(16) == request && it.getString("uds") == uds && it.has("payload") }
        .getString("payload")

    /** A J2012 record of the decoded runs back to its four bytes: `C1170-09` / `28` → `51700928`. */
    private fun record(code: String, status: String): String {
        val letter = "PCBU".indexOf(code[0])
        val base = (letter shl 14) or (code[1].digitToInt() shl 12) or code.substring(2, 5).toInt(16)
        return "%04X%s%s".format(base, code.substringAfter('-'), status)
    }

    fun script(bus: FakeDiagnosticBus) {
        f194.forEach { (address, answer) -> bus.on(address, "22F194").answer(answer) }
        bus.on(0x7E5, "090A").answer(payload(0x7E5, "090A"))

        // Read with mask 09 by the remaining-ECU run.
        listOf(0x782, 0x783, 0x7F1, 0x712, 0x7F2).forEach { a ->
            bus.on(a, "190109").answer(payload(a, "190109"))
            bus.on(a, "190209").answer(payload(a, "190209"))
        }
        val lbms = research("lbms-dtc-20261018.json")
        bus.on(0x762, "190109").answer(lbms.getString("count_payload"))
        bus.on(0x762, "190209").answer(lbms.getString("list_payload"))

        // 720, mask FF, eight records of status 28.
        val frames = research("dtc-20261018.json").getJSONObject("frames")
        bus.on(0x720, "190109").answer(frames.getJSONObject("count").getString("uds_hex"))
        bus.on(0x720, "190209").answer(frames.getJSONObject("list").getString("uds_hex"))

        // Run 1: 747 two records, 722/726 none; 7E3/7E6/7E7 none with availability 09.
        val run1 = obd.getJSONArray("run1").objects()
        fun row(request: String, uds: String) = run1.first { it.getString("request") == request && it.getString("uds") == uds }
        val r747 = row("0747", "1902FF").getJSONArray("records").objects()
        assertEquals(2, row("0747", "1901FF").getInt("count"))
        bus.on(0x747, "190109").answer("5901FF0000%02X".format(r747.size))
        bus.on(0x747, "190209").answer("5902FF" + r747.joinToString("") { record(it.getString("code"), it.getString("status")) })
        listOf("0722", "0726", "07E3", "07E6", "07E7").forEach { a ->
            val r = row(a, "1901FF")
            assertEquals(0, r.getInt("count"))
            bus.on(a.toInt(16), "190109").answer("5901%s%02X0000".format(r.getString("mask"), r.getInt("format")))
        }
        // Run 2: ECM and VCU counted zero with mask 08 and availability FF.
        val run2 = obd.getJSONArray("run2_detail").objects()
        listOf("07E0", "07E2").forEach { a ->
            val r = run2.first { it.getString("request") == a && it.getString("uds") == "190108" }
            assertEquals(0, r.getInt("count"))
            bus.on(a.toInt(16), "190109").answer("5901%s000000".format(r.getString("mask")))
        }

        // The rear drives and DCOBC by their factory routes: every answer of that run, as recorded.
        // DCOBC's 19 01 got the gateway's 3, which is what an unscripted request gets here too.
        research("factory-powertrain-dtc-20261020.json").getJSONArray("runs").objects()
            .flatMap { it.getJSONArray("requests").objects() }
            .filter { it.optInt("gatewayResult") == 1 && it.getInt("requestId") in setOf(0x744, 0x745, 0x7E4) }
            .forEach { bus.on(it.getInt("requestId"), it.getString("request")).answer(it.getString("response")) }

        // OBD: PID 01 as the trace recorded it, and the three lists, all empty.
        listOf("07E0", "07E2", "07E5").forEach { a ->
            val address = a.toInt(16)
            val p01 = run2.first { it.getString("request") == a && it.getString("uds") == "0101" }
                .getJSONObject("value").getString("raw").replace(" ", "")
            bus.on(address, "0101").answer("4101$p01")
            assertEquals(0, row(a, "03").getInt("codes"))
            bus.on(address, "03").answer("4300")
            listOf("07" to "4700", "0A" to "4A00").forEach { (uds, answer) ->
                assertEquals(0, run2.first { it.getString("request") == a && it.getString("uds") == uds }.getInt("codes"))
                bus.on(address, uds).answer(answer)
            }
        }
    }

    /** The ECM's replies of 2026-10-21: the maps beyond 60, the odometer, Mode 09 (as the car sent them). */
    val ecm1021: Map<String, String> by lazy {
        val telemetry = research("ecm-obd-telemetry-20261021.json").getJSONArray("runs").objects()
            .flatMap { it.getJSONArray("responses").objects() }
        val completion = research("ecm-completion-20261021.json").getJSONArray("runs").objects()
            .flatMap { it.getJSONArray("wireResponses").objects() }
        (telemetry + completion).associate { it.getString("requestHex") to it.getString("responseHex") }
    }

    /** The ECM's PID maps and single-PID values of run 2, as `ObdCodecTest` has them from the trace, and of 2026-10-21. */
    fun scriptEcmIdent(bus: FakeDiagnosticBus) {
        bus.on(0x7E0, "0100").answer("4100BE3EA813")
        bus.on(0x7E0, "0120").answer("4120A01FB011")
        bus.on(0x7E0, "0140").answer("4140FED01401")
        bus.on(0x7E0, "0160").answer("416002200041")
        bus.on(0x7E0, "011C").answer("411C29")
        bus.on(0x7E0, "22F18A").answer("62F18A55414553202020202020")
        bus.on(0x7E0, "22F195").answer("62F1959C4A19031900")
        bus.on(0x7E0, "22F197").answer("7F2231")
        listOf("0180", "01A0", "01A6", "0900", "0904", "090A").forEach { bus.on(0x7E0, it).answer(ecm1021.getValue(it)) }
    }
}
