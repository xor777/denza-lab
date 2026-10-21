package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An opened block's identification (architecture, 6): Mode 09 by the block's own `09 00` and the
 * odometer, on the bytes the car answered on 2026-10-21.
 */
class IdentificationTest {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

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
        assertEquals("ECM-EngineControl", rows.at("Имя блока · 09 0A").value)
        assertEquals("Z31L07AB01", rows.at("Калибровка · 09 04").value)
        assertEquals("13 199,7 км", rows.at("Пробег (ECM) · PID A6").value)
        assertTrue(bus.requests.indexOf("7E0 01A6") > bus.requests.indexOf("7E0 01A0"))
    }
}
