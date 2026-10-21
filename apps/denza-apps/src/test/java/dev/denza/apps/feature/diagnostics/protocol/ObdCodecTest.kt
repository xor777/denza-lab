package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OBD against the engine-stopped, parked replies of 2026-10-20 (captures/diagnostics-obd-20261020/run2). */
class ObdCodecTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }
    private val obd = JSONObject(File(root, "research/vehicle-diagnostics/obd-20261020.json").readText())

    private fun answer(address: Int, request: String, reply: String) =
        DiagReply.Answer(DiagRequest(address, hexBytes(request)), hexBytes(reply), 1, 1_000)

    private fun <T> ok(d: Decoded<T>): T {
        assertTrue("expected Ok, got $d", d is Decoded.Ok)
        return (d as Decoded.Ok).value
    }

    private fun pid(address: Int, pid: Int, reply: String): PidReading =
        ok(DiagDecoder.obdPids(answer(address, "01%02X".format(pid), reply))).readings.single()

    private fun number(r: PidReading, i: Int = 0) = (r.value as ObdValue.Numbers).quantities[i]

    /** The ECM's single-PID replies of run 2, raw from the trace. */
    private val ecmRun2 = mapOf(
        0x03 to "41030000", 0x04 to "410400", 0x05 to "410531", 0x06 to "410680", 0x07 to "410780",
        0x0B to "410B65", 0x0C to "410C0000", 0x0D to "410D00", 0x0E to "410E80", 0x0F to "410F34",
        0x11 to "41112B", 0x13 to "411303", 0x15 to "4115FFFF", 0x1C to "411C29", 0x1F to "411F0000",
    )

    @Test
    fun ecmRun2ReadsAsTheResearchDecoderRead() {
        assertEquals("9 °C", number(pid(0x7E0, 0x05, "410531")).text)
        assertEquals("Температура ОЖ", pid(0x7E0, 0x05, "410531").name)
        assertEquals("12 °C", number(pid(0x7E0, 0x0F, "410F34")).text)
        assertEquals("101 кПа", number(pid(0x7E0, 0x0B, "410B65")).text)
        assertEquals("Абс. давление во впуске", pid(0x7E0, 0x0B, "410B65").name)
        assertEquals("16,9 %", number(pid(0x7E0, 0x11, "41112B")).text)
        assertEquals("0,0 %", number(pid(0x7E0, 0x04, "410400")).text)
        assertEquals("0,0 %", number(pid(0x7E0, 0x06, "410680")).text)
        assertEquals("0,0 %", number(pid(0x7E0, 0x07, "410780")).text)
        assertEquals("0 об/мин", number(pid(0x7E0, 0x0C, "410C0000")).text)
        assertEquals("0,0 °", number(pid(0x7E0, 0x0E, "410E80")).text)
        assertEquals("Угол опережения зажигания", pid(0x7E0, 0x0E, "410E80").name)
        assertEquals(ObdValue.Text("B1S1, B1S2"), pid(0x7E0, 0x13, "411303").value)
        val o2 = pid(0x7E0, 0x15, "4115FFFF")
        assertEquals("Датчик кислорода B1S2", o2.name)
        assertEquals("1,275 В", number(o2).text)
        assertEquals(1, (o2.value as ObdValue.Numbers).quantities.size)
        assertEquals("в коррекции не участвует", (o2.value as ObdValue.Numbers).note)
        assertEquals(ObdValue.Text("тип 41"), pid(0x7E0, 0x1C, "411C29").value)
        assertEquals("0 с", number(pid(0x7E0, 0x1F, "411F0000")).text)
        assertEquals(ObdValue.Text("не работает", "—", "не работает"), pid(0x7E0, 0x03, "41030000").value)
        // a table takes the loop's code and a few words, not the sentence (on the car 2026-10-20 it ran over the name)
        assertEquals(ObdValue.Text("замкнутый контур по датчику O2", "CL", "замкн. петля"), pid(0x7E0, 0x03, "41030200").value)
        assertEquals(ObdValue.Text("разомкнутый контур: не прогрет", "OL", "не прогрет"), pid(0x7E0, 0x03, "41030100").value)

        // Every numeric value the research decoder stored for 7E0 is the same here.
        val rows = obd.getJSONArray("run2_detail").objects().filter { it.getString("request") == "07E0" && it.getString("uds").startsWith("01") }
        var compared = 0
        rows.forEach { row ->
            val p = row.getString("pid").toInt(16)
            val raw = ecmRun2[p] ?: return@forEach
            val expected = row.get("value")
            val reading = pid(0x7E0, p, raw)
            if (reading.value !is ObdValue.Numbers) return@forEach
            when {
                expected is Number -> { assertEquals(row.toString(), expected.toDouble(), number(reading).value, 0.05); compared++ }
                expected is JSONObject && expected.has("volts") -> { assertEquals(expected.getDouble("volts"), number(reading).value, 1e-9); compared++ }
            }
        }
        assertEquals(12, compared)
    }

    @Test
    fun ecmReadinessIsTheReferenceAnswer() {
        val r = pid(0x7E0, 0x01, "41010007F5F4")
        val s = (r.value as ObdValue.Monitors).status
        assertEquals(false, s.mil)
        assertEquals(0, s.dtcCount)
        assertEquals(Ignition.SPARK, s.ignition)
        val ready = listOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS, Monitor.CATALYST)
        val notReady = listOf(Monitor.EVAP, Monitor.GPF, Monitor.O2_SENSOR, Monitor.O2_HEATER, Monitor.EGR_VVT)
        val unsupported = listOf(Monitor.HEATED_CATALYST, Monitor.SECONDARY_AIR)
        ready.forEach { assertEquals(it.name, MonitorState.READY, s.state(it)) }
        notReady.forEach { assertEquals(it.name, MonitorState.NOT_READY, s.state(it)) }
        unsupported.forEach { assertEquals(it.name, MonitorState.NOT_SUPPORTED, s.state(it)) }
        assertEquals((ready + notReady + unsupported).toSet(), s.monitors.map { it.monitor }.toSet())
        // VCU and the brake interface: MIL off, nothing but components offered.
        val vcu = (pid(0x7E2, 0x01, "410100040000").value as ObdValue.Monitors).status
        assertEquals(MonitorState.READY, vcu.state(Monitor.COMPONENTS))
        assertEquals(MonitorState.NOT_SUPPORTED, vcu.state(Monitor.MISFIRE))
        assertEquals(MonitorState.NOT_SUPPORTED, vcu.state(Monitor.CATALYST))
        // One bit at a time, so each spark monitor sits on its own bit of C and D.
        val sparkOrder = listOf(Monitor.CATALYST, Monitor.HEATED_CATALYST, Monitor.EVAP, Monitor.SECONDARY_AIR,
            Monitor.GPF, Monitor.O2_SENSOR, Monitor.O2_HEATER, Monitor.EGR_VVT)
        sparkOrder.forEachIndexed { bit, monitor ->
            val one = Monitors.decode(intArrayOf(0x00, 0x00, 1 shl bit, 1 shl bit), thisCycle = false)
            assertEquals(listOf(monitor), one.monitors.filter { it.state == MonitorState.NOT_READY }.map { it.monitor })
        }
        listOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS).forEachIndexed { bit, monitor ->
            val one = Monitors.decode(intArrayOf(0x00, (1 shl bit) or (0x10 shl bit), 0x00, 0x00), thisCycle = false)
            assertEquals(listOf(monitor), one.monitors.filter { it.state == MonitorState.NOT_READY }.map { it.monitor })
        }
        // A lit MIL with three codes, compression ignition.
        val diesel = Monitors.decode(intArrayOf(0x83, 0x08, 0x01, 0x01), thisCycle = false)
        assertEquals(true, diesel.mil)
        assertEquals(3, diesel.dtcCount)
        assertEquals(Ignition.COMPRESSION, diesel.ignition)
        assertEquals(MonitorState.NOT_READY, diesel.state(Monitor.NMHC_CATALYST))
        assertNull(diesel.state(Monitor.EVAP))
    }

    @Test
    fun thisDriveCycleMonitorsHaveNoMilByte() {
        val r = ok(ObdCodec.pids(hexBytes("41410007F5F4"))).single()
        val s = (r.value as ObdValue.Monitors).status
        assertTrue(s.thisCycle)
        assertNull(s.mil)
        assertNull(s.dtcCount)
        assertEquals(MonitorState.DISABLED, s.state(Monitor.HEATED_CATALYST))
        assertEquals(MonitorState.READY, s.state(Monitor.CATALYST))
        assertEquals(MonitorState.NOT_READY, s.state(Monitor.GPF))
    }

    @Test
    fun supportedBitmapsListEveryPidTheEcmOffers() {
        val offered = listOf("4100BE3EA813", "4120A01FB011", "4140FED01401", "416002200041").flatMap { reply ->
            (ok(ObdCodec.pids(hexBytes(reply))).single().value as ObdValue.Supported).pids
        }
        val expected = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20,
            0x21, 0x23, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x3C, 0x40,
            0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x54, 0x56, 0x60,
            0x67, 0x6B, 0x7A, 0x80,
        )
        assertEquals(expected, offered)
        assertEquals(46, offered.size)
        // Each one has a definition: name, length, decoder.
        offered.forEach { assertTrue("%02X".format(it), ObdPids.of(it) != null) }
        assertTrue(ObdPids.of(0x00) != null)
        // The research JSON lists the same PIDs, block by block.
        val listed = obd.getJSONArray("run1").objects().first { it.getString("request") == "07E0" && it.getString("uds") == "0100" }
            .getJSONArray("value").strings() +
            obd.getJSONArray("run2_detail").objects()
                .filter { it.getString("request") == "07E0" && it.getString("uds") in setOf("0120", "0140", "0160") }
                .flatMap { it.getJSONArray("value").strings() }
        assertEquals(listed.map { it.toInt(16) }, offered)
        // VCU and brake interface bitmaps.
        assertEquals(listOf(0x21, 0x40), (pid(0x7E2, 0x20, "412080000001").value as ObdValue.Supported).pids)
        assertEquals(listOf(0x42, 0x49), (pid(0x7E5, 0x40, "414040800000").value as ObdValue.Supported).pids)
    }

    @Test
    fun everyDefinedPidDecodesItsOwnLength() {
        ObdPids.all.forEach { def ->
            val reply = "41%02X".format(def.pid) + "80".repeat(def.length)
            val reading = ok(ObdCodec.pids(hexBytes(reply))).single()
            assertEquals(def.pid, reading.pid)
            assertTrue("%02X".format(def.pid), reading.value != ObdValue.Raw || def.pid == 0x6B)
            assertTrue(def.name.isNotBlank())
        }
    }

    @Test
    fun pidFormulasFollowJ1979() {
        fun q(reply: String, i: Int = 0) = (ok(ObdCodec.pids(hexBytes(reply))).single().value as ObdValue.Numbers).quantities[i]
        assertEquals(1726.0, q("410C1AF8").value, 1e-9)
        assertEquals(13.8, q("4142" + "35E8").value, 1e-9)
        assertEquals("13,80 В", q("414235E8").text)
        assertEquals(360.0, q("413C" + "0FA0").value, 1e-9) // 4000 / 10 - 40
        assertEquals(1.0, q("41548000").value, 0.0)
        assertEquals(1.0, q("41348000" + "8000").value, 1e-9)
        assertEquals(0.0, q("413480008000", 1).value, 1e-9)
        assertEquals("1,000", q("41448000").text)
        assertEquals(-40.0, q("414600").value, 0.0)
        assertEquals(990.0, q("4123" + "0063").value, 0.0)
        assertEquals("перепад", q("417A07FF9C00640190").label)
        assertEquals(-1.0, q("417A07FF9C00640190").value, 1e-9)
        assertEquals(4.0, q("417A07FF9C00640190", 2).value, 1e-9)
        val coolant = ok(ObdCodec.pids(hexBytes("4167013100"))).single().value as ObdValue.Numbers
        assertEquals(listOf("датчик 1"), coolant.quantities.map { it.label })
        assertEquals(9.0, coolant.quantities[0].value, 0.0)
    }

    @Test
    fun evapVaporPressureUsesOffsetForTheCapturedEcmReply() {
        // Z9GT, engine stopped, 2026-10-21: 41 54 E8 B6. PID54 is
        // unsigned16 - 32767 Pa (CSS Electronics table / python-OBD), not int16.
        fun pressure(reply: String) =
            (ok(ObdCodec.pids(hexBytes(reply))).single().value as ObdValue.Numbers).quantities.single()
        assertEquals(26807.0, pressure("4154E8B6").value, 0.0)
        assertEquals("Па", pressure("4154E8B6").unit)
        assertEquals(-32767.0, pressure("41540000").value, 0.0)
        assertEquals(0.0, pressure("41547FFF").value, 0.0)
        assertEquals(1.0, pressure("41548000").value, 0.0)
        assertEquals(32768.0, pressure("4154FFFF").value, 0.0)
    }

    @Test
    fun singleSecondaryOxygenTrimDoesNotInventBankThree() {
        // Live Z9GT response. J1979 table B67 makes byte B conditional on bank3.
        val read = ok(DiagDecoder.obdPids(answer(0x7E0, "0156", "415680"))).readings.single()
        val values = (read.value as ObdValue.Numbers).quantities
        assertEquals("80", read.rawHex)
        assertEquals(listOf("B1"), values.map { it.label })
        assertEquals(0.0, values.single().value, 0.0)
        val both = (ok(ObdCodec.pids(hexBytes("41568090"))).single().value as ObdValue.Numbers).quantities
        assertEquals(listOf("B1", "B3"), both.map { it.label })
        assertEquals(12.5, both[1].value, 0.0)
        assertTrue(ObdCodec.pids(hexBytes("4156")) is Decoded.Malformed)
        // Without bank topology, do not guess a one-byte length inside a batch.
        assertTrue(DiagDecoder.obdPids(answer(0x7E0, "01560C", "4156800C0000")) is Decoded.Malformed)
    }

    @Test
    fun aMultiPidAnswerSplitsIntoValues() {
        val a = ok(DiagDecoder.obdPids(answer(0x7E0, "010C0D05", "410C00000D000531")))
        assertEquals(listOf(0x0C, 0x0D, 0x05), a.readings.map { it.pid })
        assertEquals("9 °C", (a[0x05]!!.value as ObdValue.Numbers).quantities[0].text)
        assertEquals("0 км/ч", (a[0x0D]!!.value as ObdValue.Numbers).quantities[0].text)
        assertEquals(emptyList<Int>(), a.unanswered)
        // The ECU leaves out what it does not offer.
        val partial = ok(DiagDecoder.obdPids(answer(0x7E0, "010C0D0B", "410C0000")))
        assertEquals(listOf(0x0D, 0x0B), partial.unanswered)
        // A PID that was not asked, a cut-short PID, a repeated PID.
        assertTrue(DiagDecoder.obdPids(answer(0x7E0, "010C", "410D00")) is Decoded.Malformed)
        assertTrue(ObdCodec.pids(hexBytes("410C00")) is Decoded.Malformed)
        assertTrue(ObdCodec.pids(hexBytes("410D000D00")) is Decoded.Malformed)
    }

    @Test
    fun anUnknownPidKeepsItsBytes() {
        val r = ok(ObdCodec.pids(hexBytes("41FF0102"))).single()
        assertEquals(0xFF, r.pid)
        assertEquals("0102", r.rawHex)
        assertEquals(ObdValue.Raw, r.value)
        assertEquals("PID FF", r.name)
        assertTrue(ObdCodec.pids(hexBytes("410D00FF0102")) is Decoded.Malformed)
    }

    @Test
    fun storedPendingAndPermanentCodes() {
        listOf("03" to "4300", "07" to "4700", "0A" to "4A00").forEach { (req, reply) ->
            val p = Polled.of(answer(0x7E0, req, reply)) { DiagDecoder.obdDtcs(it) }
            assertEquals(req, DataQuality.COMPLETE_EMPTY, p.quality(1_000, 60_000))
        }
        val two = ok(DiagDecoder.obdDtcs(answer(0x7E0, "03", "43020420C121")))
        assertEquals(ObdDtcMode.STORED, two.mode)
        assertEquals(listOf("P0420", "U0121"), two.codes)
        assertTrue(DiagDecoder.obdDtcs(answer(0x7E0, "03", "430204")) is Decoded.Malformed)
        assertTrue(DiagDecoder.obdDtcs(answer(0x7E0, "07", "4300")) is Decoded.Malformed)
        val brake = ok(DiagDecoder.obdDtcs(answer(0x7E5, "03", "4300")))
        assertTrue(brake.isEmpty)
    }

    @Test
    fun theBrakeInterfaceNamesItselfThroughMode09() {
        // remaining-ecus obd-name run, payloads as recorded.
        val rows = JSONObject(File(root, "research/vehicle-diagnostics/remaining-ecus-20261020.json").readText())
            .getJSONArray("runs").objects().first { it.getString("name") == "obd-name" }.getJSONArray("rows").objects()
            .filter { it.getString("request") == "07E5" }
        fun payload(uds: String) = rows.first { it.getString("uds") == uds }.getString("payload")
        val supported = ok(DiagDecoder.vehicleInfo(answer(0x7E5, "0900", payload("0900")))) as VehicleInfo.Supported
        assertEquals(listOf(0x04, 0x06, 0x0A), supported.infoTypes)
        val calId = ok(DiagDecoder.vehicleInfo(answer(0x7E5, "0904", payload("0904")))) as VehicleInfo.CalibrationIds
        assertEquals(listOf("4000825042253153"), calId.ids)
        val name = ok(DiagDecoder.vehicleInfo(answer(0x7E5, "090A", payload("090A")))) as VehicleInfo.EcuName
        assertEquals("BSCM-BrakeSystem", name.name)
        assertEquals(rows.first { it.getString("uds") == "090A" }.getString("value"), name.name)
        // An info type that was not asked.
        assertTrue(DiagDecoder.vehicleInfo(answer(0x7E5, "0904", payload("090A"))) is Decoded.Malformed)
    }

    @Test
    fun theEngineNamesItselfTheJ1979Way() {
        // ecm-completion 2026-10-21, run 3: 09 00, 09 04, 09 0A as the car answered them.
        val ecm = JSONObject(File(root, "research/vehicle-diagnostics/ecm-completion-20261021.json").readText())
        val wire = ecm.getJSONArray("runs").objects().flatMap { it.getJSONArray("wireResponses").objects() }
        fun reply(uds: String) = wire.last { it.getString("requestHex") == uds }.getString("responseHex")
        val supported = ok(DiagDecoder.vehicleInfo(answer(0x7E0, "0900", reply("0900")))) as VehicleInfo.Supported
        assertEquals(listOf(0x02, 0x04, 0x06, 0x08, 0x0A, 0x12), supported.infoTypes)
        val calId = ok(DiagDecoder.vehicleInfo(answer(0x7E0, "0904", reply("0904")))) as VehicleInfo.CalibrationIds
        assertEquals(listOf(ecm.getJSONObject("route").getString("calibrationId")), calId.ids)
        assertEquals(listOf("Z31L07AB01"), calId.ids)
        // The acronym is NUL-padded inside the name: «ECM\0-EngineControl\0\0», not a broken string.
        assertEquals("ECM\\0-EngineControl\\0\\0", ecm.getJSONObject("route").getString("ecuNameAsciiEscaped"))
        val name = ok(DiagDecoder.vehicleInfo(answer(0x7E0, "090A", reply("090A")))) as VehicleInfo.EcuName
        assertEquals("ECM-EngineControl", name.name)
        // Not the J1979 layout: plain ASCII, or nothing when a NUL sits inside.
        assertEquals("ENGINECONTROLMODULE1", ObdCodec.ecuName("ENGINECONTROLMODULE1".toByteArray()))
        assertEquals(null, ObdCodec.ecuName(hexBytes("45434D00" + "45" + "6E67696E65436F6E74726F6C0000")))
        assertEquals(null, ObdCodec.ecuName(hexBytes("00000000" + "2D" + "456E67696E65436F6E74726F6C0000")))
    }

    @Test
    fun obdServiceTheEcuDoesNotOfferIsUnsupported() {
        // Run 1: 7E3 01 00 → 7F 01 11.
        val p = Polled.of(answer(0x7E3, "0100", "7F0111")) { DiagDecoder.obdPids(it) }
        assertTrue(p is Polled.Unsupported)
        assertEquals(DataQuality.UNSUPPORTED, p.quality(1_000, 60_000))
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
