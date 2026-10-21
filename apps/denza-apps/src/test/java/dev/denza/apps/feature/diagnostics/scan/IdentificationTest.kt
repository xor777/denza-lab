package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An opened block's identification (architecture 5.4, 6): Mode 09 by the block's own `09 00`, and the
 * software codes of the modules behind a parent, on the bytes the car answered on 2026-10-21.
 */
class IdentificationTest {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    /** The eight child codes as the parents answered them (child-identities-live-20261021.json). */
    private val live: List<JSONObject> = CarNow.research("child-identities-live-20261021.json").getJSONArray("identities").objects()

    /** The UDS inside a logged frame: `0A <reply id> <length> <frames> <index> <result>`, then `<length>` bytes. */
    private fun uds(frame: String): String {
        val b = hexBytes(frame)
        return hex(b.copyOfRange(7, 7 + (b[3].toInt() and 0xFF)))
    }

    private fun read(request: Int, bus: FakeDiagnosticBus): IdentResult = runBlocking {
        val ecu = EcuCatalog.byRequest(request)!!
        IdentResult(ecu, IdentProcedure.read(ecu, { Fixtures.EPOCH }) { bus.exchange(it) })
    }

    /** The «Идентификация» rows of each opened block, key to value. */
    private fun own(vararg idents: IdentResult): Map<Int, List<Field>> {
        val requests = idents.map { it.ecu.request!! }
        val model = ModelBuilder(ScanClock(ZoneOffset.UTC)).build(SessionSnapshot(
            link = LinkState.OK, phase = ScanPhase.NEVER, done = 0, finishedAtMillis = null, notice = null, polling = null,
            results = emptyMap(), idents = idents.associateBy { it.ecu.request!! }, open = requests.toSet(), streams = emptyMap(),
            live = null, streaming = false, recording = null, car = CarReadings(), nowMillis = Fixtures.EPOCH,
        ))
        return requests.associateWith { model.modules.getValue(it).ident.own }
    }

    private fun List<Field>.at(key: String) = first { it.key == key }

    /** The four parents' F194 as recorded, and each child's answer as the car sent it. */
    private fun carAnswers() = FakeDiagnosticBus().apply {
        listOf(0x750, 0x7B5, 0x7D2).forEach { on(it, "22F194").answer(CarNow.f194.getValue(it)) }
        live.forEach { on(it.getJSONObject("route").getString("requestIdHex").toInt(16), "22" + it.getString("didHex")).answer(uds(it.getString("responseRawHex"))) }
    }

    @Test
    fun everyChildIsReadBehindItsParentAndShownAsItAnswered() {
        val bus = carAnswers()
        val idents = listOf(0x750, 0x7B5, 0x7D2, 0x726).map { read(it, bus) }
        // Each parent: its four DIDs, then its children in the catalog's order, and nothing else.
        assertEquals(listOf("750 22F194", "750 22F195", "750 22F193", "750 22F18A", "750 22F197", "750 22F8C4",
            "7B5 22F194", "7B5 22F195", "7B5 22F193", "7B5 22F18A", "7B5 22F197", "7B5 22F8D4",
            "7D2 22F194", "7D2 22F195", "7D2 22F193", "7D2 22F18A", "7D2 22F197", "7D2 22F8E4", "7D2 22F8F4",
            // B_ZCU is named by the stock route and has no gate: its four lamps are read as its memory is.
            "726 22F194", "726 22F195", "726 22F193", "726 22F18A", "726 22F197", "726 22F204", "726 22F214", "726 22F064", "726 22F234"),
            bus.requests)
        val rows = own(*idents.toTypedArray())
        live.forEach { c ->
            val parent = c.getJSONObject("route").getString("requestIdHex").toInt(16)
            val child = EcuCatalog.byRequest(parent)!!.children.single { it.hex == c.getString("didHex") }
            val row = rows.getValue(parent).at("${child.shortName} · 22 ${child.hex}")
            assertEquals(c.getString("softwareCodeHex"), row.value)
            assertFalse(row.muted)
        }
        assertEquals("47380011DFE3000102", rows.getValue(0x750).at("Массаж перед. левого · 22 F8C4").value)
        assertEquals("48330011DFD5000204", rows.getValue(0x726).at("Фонарь TL правый · 22 F234").value)
        // The children's rows come last, after the parent's own.
        assertEquals(listOf("Массаж задн. левого · 22 F8E4", "Массаж задн. правого · 22 F8F4"), rows.getValue(0x7D2).takeLast(2).map { it.key })
    }

    @Test
    fun aParentWithNewerSoftwareStillReadsItsChildren() {
        // DUCU after an update: a code the catalog's snapshot does not have. The gate holds (5.1), and the
        // massage module behind it is read as before.
        val bus = FakeDiagnosticBus()
        bus.on(0x750, "22F194").answer("62F1944B310011DF016A0002")
        bus.on(0x750, "22F8C4").answer("62F8C447380011DFE3000102")
        val rows = own(read(0x750, bus)).getValue(0x750)
        assertEquals(listOf("750 22F194", "750 22F195", "750 22F193", "750 22F18A", "750 22F197", "750 22F8C4"), bus.requests)
        assertEquals("4B310011DF016A0002", rows.at("Номер ПО поставщика · 22 F194").value)
        assertEquals("47380011DFE3000102", rows.at("Массаж перед. левого · 22 F8C4").value)
    }

    @Test
    fun aParentWithoutASoftwareCodeKeepsItsChildrenUnread() {
        // A zero placeholder, a code cut short, a refusal, an answer to another DID, no answer: no gate passed.
        listOf("62F194000000000000000000", "62F1944B310011DF016A", "7F2231", "62F1954B310011DF016A0001", null).forEach { f194 ->
            val bus = FakeDiagnosticBus()
            f194?.let { bus.on(0x750, "22F194").answer(it) }
            val ident = read(0x750, bus)
            assertEquals("$f194", listOf("750 22F194", "750 22F195", "750 22F193", "750 22F18A", "750 22F197"), bus.requests)
            val row = own(ident).getValue(0x750).at("Массаж перед. левого · 22 F8C4")
            assertEquals("not read behind $f194", "не читали", row.value)
            assertTrue(row.muted)
        }
    }

    @Test
    fun anAnswerThatIsNotACodeShowsItsBytesAndAnotherCodeSaysSo() {
        val bus = FakeDiagnosticBus()
        // A zero placeholder, a code cut short, a refusal, another code.
        bus.on(0x726, "22F204").answer("62F204000000000000000000")
        bus.on(0x726, "22F214").answer("62F21448330011DFC6")
        bus.on(0x726, "22F064").answer("7F2231")
        bus.on(0x726, "22F234").answer("62F23448330011DFD5000299")
        val rows = own(read(0x726, bus)).getValue(0x726)
        assertEquals("62 F2 04 00 00 00 00 00 00 00 00 00", rows.at("Комб. фонарь левый · 22 F204").value)
        assertEquals("62 F2 14 48 33 00 11 DF C6", rows.at("Комб. фонарь правый · 22 F214").value)
        assertEquals("не отдаёт · NRC 31", rows.at("Фонарь TL левый · 22 F064").value)
        assertTrue(rows.at("Фонарь TL левый · 22 F064").muted)
        assertEquals("another code is shown as it came", "48330011DFD5000299", rows.at("Фонарь TL правый · 22 F234").value)
        // The massage row of a parent the gateway left unanswered.
        val silent = FakeDiagnosticBus()
        silent.on(0x7D2, "22F194").answer(CarNow.f194.getValue(0x7D2))
        val massage = own(read(0x7D2, silent)).getValue(0x7D2)
        assertEquals("нет ответа", massage.at("Массаж задн. левого · 22 F8E4").value)
        assertTrue(massage.at("Массаж задн. левого · 22 F8E4").muted)
    }

    @Test
    fun thePassengerComputerSaysItsCombinedCodeAndBuildVersion() {
        // 5E7 as it answered on 2026-10-21 (ota-assy-fields-live-20261021.json): F294 and F295 after its own DIDs.
        val bus = FakeDiagnosticBus()
        bus.on(0x5E7, "22F194").answer(CarNow.f194.getValue(0x5E7))
        CarNow.research("ota-assy-fields-live-20261021.json").getJSONArray("fields").objects()
            .forEach { bus.on(0x5E7, it.getString("request")).answer(it.getString("responseHex")) }
        val rows = own(read(0x5E7, bus)).getValue(0x5E7)
        assertEquals(listOf("5E7 22F194", "5E7 22F195", "5E7 22F193", "5E7 22F18A", "5E7 22F197", "5E7 22F294", "5E7 22F295"), bus.requests)
        assertEquals("4B450011DF99004201", rows.at("Код сборки · 22 F294").value)
        assertEquals("42.1.8.2605219.1.42.2.3.2605250.2", rows.at("Версия сборки · 22 F295").value)
        assertFalse(rows.at("Версия сборки · 22 F295").muted)
        // After the block's own rows, before the memory's format.
        val keys = rows.map { it.key }
        assertEquals(keys.indexOf("Имя системы · 22 F197") + 1, keys.indexOf("Код сборки · 22 F294"))

        // A version the stock reader refuses (a slash), zeros for a code, a refusal: bytes as they came, or words.
        val odd = FakeDiagnosticBus()
        odd.on(0x5E7, "22F294").answer("62F294000000000000000000")
        odd.on(0x5E7, "22F295").answer("62F295342E312F32")
        val r = own(read(0x5E7, odd)).getValue(0x5E7)
        assertEquals("62 F2 94 00 00 00 00 00 00 00 00 00", r.at("Код сборки · 22 F294").value)
        assertEquals("62 F2 95 34 2E 31 2F 32", r.at("Версия сборки · 22 F295").value)
        val refused = FakeDiagnosticBus().apply { on(0x5E7, "22F295").answer("7F2231") }
        assertEquals("не отдаёт · NRC 31", own(read(0x5E7, refused)).getValue(0x5E7).at("Версия сборки · 22 F295").value)
        // Another block has no such rows and is not asked.
        val ecm = FakeDiagnosticBus()
        own(read(0x7B5, ecm))
        assertTrue(ecm.requests.none { "22F29" in it })
    }

    /** The brake interface and the VCU as they answered on 2026-10-21 (brake-vcu-obd-completion-20261021.json). */
    private fun brakeAndVcu() = FakeDiagnosticBus().apply {
        CarNow.research("brake-vcu-obd-completion-20261021.json").getJSONArray("wireResponses").objects()
            .filter { it.getString("targetHex") in setOf("7E5", "7E2") }
            .forEach { on(it.getString("targetHex").toInt(16), it.getString("requestHex")).answer(it.getString("responseHex")) }
    }

    @Test
    fun everyObdResponderAsksModeNineByItsOwnMap() {
        val bus = brakeAndVcu()
        val brake = read(0x7E5, bus)
        // 09 00 lists 04, 06 and 0A: the calibration and the name, never 06 (CVN) or 02 (VIN).
        assertEquals(listOf("7E5 0100", "7E5 0120", "7E5 0140", "7E5 0101", "7E5 0900", "7E5 0904", "7E5 090A"),
            bus.requests.filter { it.startsWith("7E5 ") })
        val vcu = read(0x7E2, bus)
        // The VCU's maps end at 40, its 09 00 was never answered: nothing more of Mode 09 is asked.
        assertEquals(listOf("7E2 0100", "7E2 0120", "7E2 0140", "7E2 0101", "7E2 0141", "7E2 0900"),
            bus.requests.filter { it.startsWith("7E2 ") && !it.startsWith("7E2 22") })
        val rows = own(brake, vcu)
        assertEquals("BSCM-BrakeSystem", rows.getValue(0x7E5).at("Имя блока · 09 0A").value)
        assertEquals("4000825042253153", rows.getValue(0x7E5).at("Калибровка · 09 04").value)
        assertEquals("нет ответа", rows.getValue(0x7E2).at("Имя блока · 09 0A").value)
        assertEquals("нет ответа", rows.getValue(0x7E2).at("Калибровка · 09 04").value)
        // No odometer where the maps do not offer it.
        assertTrue(rows.values.flatten().none { "PID A6" in it.key })

        // A 09 00 that lists the calibration and not the name: the name is not asked, and says so.
        val partial = brakeAndVcu()
        partial.on(0x7E2, "0900").answer("490014000000")
        partial.on(0x7E2, "0904").answer("4904015A33314C303741423031000000000000")
        val p = read(0x7E2, partial)
        assertTrue(partial.requests.none { it == "7E2 090A" })
        val r = own(p).getValue(0x7E2)
        assertEquals("Z31L07AB01", r.at("Калибровка · 09 04").value)
        assertEquals("не отдаёт", r.at("Имя блока · 09 0A").value)
        assertTrue(r.at("Имя блока · 09 0A").muted)
    }

    @Test
    fun theEnginesNameCalibrationAndOdometerAreItsOwnRows() {
        val bus = FakeDiagnosticBus().apply { CarNow.scriptEcmIdent(this) }
        bus.on(0x7E0, "22F194").answer(CarNow.f194.getValue(0x7E0))
        val rows = own(read(0x7E0, bus)).getValue(0x7E0)
        // The four DIDs as their text: the supplier in ASCII, the codes in hex, a refusal in words.
        assertEquals("UAES", rows.at("Поставщик · 22 F18A").value)
        assertEquals("32440011E004000000", rows.at("Номер ПО поставщика · 22 F194").value)
        // F195 as the car's updater reads it: 9C4A is 4.00.10, the other four bytes as they came.
        assertEquals("4.00.10 · 19031900", rows.at("Версия ПО · 22 F195").value)
        // F193 as the updater reads it, byte 0 alone: 64 is 1.0.0.
        assertEquals("1.0.0", rows.at("Версия железа · 22 F193").value)
        assertEquals("не отдаёт · NRC 31", rows.at("Имя системы · 22 F197").value)
        assertEquals("ECM-EngineControl", rows.at("Имя блока · 09 0A").value)
        assertEquals("Z31L07AB01", rows.at("Калибровка · 09 04").value)
        assertEquals("13 199,7 км", rows.at("Пробег (ECM) · PID A6").value)
        assertTrue(bus.requests.indexOf("7E0 01A6") > bus.requests.indexOf("7E0 01A0"))
    }
}
