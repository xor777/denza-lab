package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.vehicle.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The systems' heads, the HV scheme's blocks and a block's DTC strip against the boards (7.3): the boards'
 * scan ([BoardScan]) and the parked car's readings as the boards print them. Each head's counters - a
 * reading, the records' count with its kind's sign, the orange of a value outside its norm - the HV
 * blocks' third row - an inverter's own bus and its difference to the pack, then its reading - and the
 * mask and availability a block's memory was read with are what the boards show.
 */
@Suppress("UNCHECKED_CAST")
class SystemsBoardContractTest {
    /** The parked car of 2026-10-20 as the system boards print it. */
    private val car = BoardScan.car(extra = mapOf(
        DiagnosticExtra.POWER_LEVEL to 2.0, DiagnosticExtra.CHARGE_CURRENT to -4.5, DiagnosticExtra.GEAR_SELECTOR to 1.0,
        DiagnosticExtra.BUS_FRONT to 555.0, DiagnosticExtra.BUS_REAR to 556.0, DiagnosticExtra.IGBT_REAR_LEFT to 5.0,
        DiagnosticExtra.IGBT_REAR_RIGHT to 5.0, DiagnosticExtra.DCDC_MODE to 2.0, DiagnosticExtra.CABIN_TEMP to 21.0,
        DiagnosticExtra.OUTSIDE_TEMP to 12.0, DiagnosticExtra.FL_PRESSURE to 2.72, DiagnosticExtra.FR_PRESSURE to 2.60,
        DiagnosticExtra.RL_PRESSURE to 2.66, DiagnosticExtra.RR_PRESSURE to 2.70,
    )).let { it.copy(hub = it.hub + mapOf(VehicleSignal.INVERTER_C to 9.0, VehicleSignal.ENGINE_RUNNING to 0.0, VehicleSignal.ENGINE_RPM to 1250.0)) }

    private val scan = BoardScan.results(BoardScan.records("scanner-summary"), availability = mapOf(0x597 to 0x29, 0x782 to 0xFF))
    private val model = BoardScan.model(BoardScan.snapshot(scan, car = car))

    /** A board's counters in the model's terms: [label, value, «hist» a record's sign | «out» orange | true lit]. */
    private fun counters(id: String): List<HeadCounter> =
        ((SpecJson.fixture(id)["block"] as Map<String, Any?>)["counters"] as List<List<Any?>>).map { c ->
            val third = c.getOrNull(2)
            HeadCounter(c[0] as String, c[1] as String, on = third == true,
                mark = when (third) { "hist" -> Mark.HISTORY; "pending" -> Mark.PENDING; "active" -> Mark.ACTIVE; else -> null },
                out = third == "out")
        }

    @Test
    fun everySystemsHeadIsWhatItsBoardShows() {
        val boards = mapOf(
            CarSystem.HV to "scanner-sys-hv", CarSystem.PACK to "scanner-sys-pack-live",CarSystem.DRIVES to "scanner-sys-drives",
            CarSystem.CHASSIS to "scanner-sys-chassis", CarSystem.SAFETY to "scanner-sys-safety", CarSystem.ADAS to "scanner-sys-adas",
            CarSystem.BODY to "scanner-sys-body", CarSystem.CABIN to "scanner-sys-cabin", CarSystem.CLIMATE to "scanner-sys-climate",
            CarSystem.LV to "scanner-sys-lv",
        )
        boards.forEach { (system, id) -> assertEquals(id, counters(id), model.systemPages.getValue(system).counters) }
        // the engine's readiness is the scan's PID 01; its rpm the hub's at idle
        assertEquals(counters("scanner-sys-engine").map { it.label }, model.systemPages.getValue(CarSystem.ENGINE).counters.map { it.label })
    }

    @Test
    fun aRecordFailingNowLightsItsSystemsCountAndALowValueIsOrange() {
        val active = BoardScan.results(BoardScan.records("scanner-summary-active"), availability = mapOf(0x597 to 0x29, 0x747 to 0x0D))
        val low = car.copy(extras = car.extras + mapOf(DiagnosticExtra.LV to 11.9, DiagnosticExtra.INSULATION to 200.0))
        val m = BoardScan.model(BoardScan.snapshot(active, car = low))
        assertEquals(HeadCounter("ЗАПИСИ", "4", on = true, mark = Mark.ACTIVE), m.systemPages.getValue(CarSystem.CHASSIS).counters.last())
        assertEquals(HeadCounter("ЗАПИСИ", "10", mark = Mark.PENDING), m.systemPages.getValue(CarSystem.BODY).counters.last())
        assertEquals(HeadCounter("12 В", "11,9 В", out = true), m.systemPages.getValue(CarSystem.LV).counters[1])
        assertEquals(HeadCounter("ИЗОЛЯЦИЯ", "0,20 МОм", out = true), m.systemPages.getValue(CarSystem.HV).counters[3])
    }

    @Test
    fun theHvBlocksThirdRowIsWhatTheBoardShows() {
        val f = SpecJson.fixture("scanner-sys-hv")
        val pack = ((f["pack"] as Map<String, Any?>)["u"] as Number).toInt()
        val units = f["units"] as List<Map<String, Any?>>
        val shown = model.systems.hv.units
        assertEquals(units.map { it["abbr"] }, shown.map { it.abbreviation })
        units.zip(shown).forEach { (b, u) ->
            val id = b["abbr"] as String
            assertEquals(id, b["addr"], u.address)
            assertEquals(id, b["name"], u.name)
            val volts = (b["volts"] as Number?)?.toInt()
            assertEquals(id, volts?.let { "$it В" }, u.busVolts)
            assertEquals(id, volts?.let { v -> (v - pack).let { d -> (if (d > 0) "+" else if (d < 0) "−" else "±") + Math.abs(d) } }, u.busDelta)
            assertEquals(id, b["reading"], u.reading)
            assertEquals(id, b["off"] == true, u.off)
        }
    }

    @Test
    fun aBlocksStripSaysTheMaskItWasReadWithAndWhatItReports() {
        // 782 counted zero with 09, its availability FF (board scanner-esp-dtc)
        val mask = SpecJson.fixture("scanner-esp-dtc")["mask"] as Map<String, Any?>
        val esp = model.modules.getValue(0x782).dtc
        assertEquals((mask["value"] as Number).toInt(), esp.mask)
        assertEquals((mask["availability"] as Number).toInt(), esp.availability)
        // IDC answers 29: a bit outside it is no bit
        assertEquals(0x29, model.modules.getValue(0x597).dtc.availability)
        // each record's «как прочитан»: the count as it came, the list with the record's four bytes lit
        val rzcu = model.modules.getValue(0x747).dtc
        assertEquals(listOf("19 01 09 → 59 01 09 00 00 02", "19 02 09 → 59 02 09 51 70 09 08 · …"), rzcu.records[0].reads.map { it.text })
        assertEquals("51 70 09 08", rzcu.records[0].reads[1].let { r -> r.text.substring(r.lit!!) })
        assertNull(rzcu.records[0].reads[0].lit)
        assertEquals("59 02 09 · … · 93 0E 01 08", rzcu.records[1].reads[1].text.substringAfter("→ "))
        // a block never read says neither
        val none = BoardScan.model(BoardScan.snapshot(scan - 0x782, car = car))
        assertNull(none.modules[0x782]?.dtc?.mask)
        check(scan.getValue(0x782).read.replies.all { it is DiagReply.Answer })
    }
}
