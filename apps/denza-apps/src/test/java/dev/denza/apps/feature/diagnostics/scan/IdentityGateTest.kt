package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.ecu.IdentityBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity gate of architecture 5.1 since 2026-10-21: a block named by its F194 passes on any
 * software code it answers - an over-the-air update changes the code, not the address or the role -
 * and on nothing that is not one; the brake interface's OBD name stays exact.
 */
class IdentityGateTest {
    private val f194Gated: List<Ecu> = EcuCatalog.routed.filter { ScanRequests.gate(it) == ScanRequests.F194 }

    private fun scan(ecu: Ecu, bus: FakeDiagnosticBus): ModuleResult = runBlocking {
        ModuleResult(ecu, ModuleProcedure.read(ecu, { Fixtures.EPOCH }) { bus.exchange(it) })
    }

    /** [code] with its last byte moved on: the same block after an update. */
    private fun updated(code: String): String = code.dropLast(2) + "%02X".format((code.takeLast(2).toInt(16) + 1) % 256 or 1)

    @Test
    fun everyBlockNamedByItsSoftwareCodeIsGatedByF194() {
        // The model's catalog roles, the factory-map 5xx blocks, the corner radars, the UWB anchors among them.
        assertEquals(62, f194Gated.size)
        assertTrue(f194Gated.all { it.identity == IdentityBasis.F194 || it.identity == IdentityBasis.MODEL_CATALOG })
        assertTrue(f194Gated.all { it.uds })
    }

    @Test
    fun aNewerCodePassesAndTheMemoryIsRead() {
        f194Gated.forEach { ecu ->
            val address = ecu.request!!
            val code = updated(ecu.snapshotF194!!)
            val bus = FakeDiagnosticBus()
            bus.on(address, "22F194").answer("62F194$code")
            bus.on(address, "190109").answer("590109000000")
            bus.on(address, "190209").answer("590209")
            val r = scan(ecu, bus)
            assertEquals(ecu.abbreviation, true, r.identified)
            assertTrue(ecu.abbreviation, bus.requests.size > 1)
            assertEquals(ecu.abbreviation, ModuleStatus.OK, r.status)
        }
        // Any code: another supplier's, as a block replaced by another variant of its role would answer.
        val ecm = EcuCatalog.byRequest(0x7E0)!!
        val bus = FakeDiagnosticBus()
        bus.on(0x7E0, "22F194").answer("62F1944B450011E0930034E2")
        assertTrue(ModuleResult.gatePassed(ecm, scan(ecm, bus).read.reply("22F194")!!))
    }

    @Test
    fun anAnswerThatIsNotASoftwareCodeDoesNotPassAndKeepsItsStatus() {
        val fmcu = EcuCatalog.byRequest(0x7E3)!!
        val cases = listOf(
            // a positive answer with no code in it: the block does not say who it is
            "62F194000000000000000000" to ModuleStatus.UNIDENTIFIED,
            "62F19448370011E075" to ModuleStatus.UNIDENTIFIED,
            "62F19448370011E07500000000" to ModuleStatus.UNIDENTIFIED,
            // a refusal: it answered, its memory is not read
            "7F2231" to ModuleStatus.UNREAD,
            "7F2211" to ModuleStatus.UNREAD,
            // malformed: another DID, no data
            "62F19548370011E075000000" to ModuleStatus.INCONSISTENT,
            "62F1" to ModuleStatus.INCONSISTENT,
        )
        cases.forEach { (answer, status) ->
            val bus = FakeDiagnosticBus()
            bus.on(0x7E3, "22F194").answer(answer)
            bus.on(0x7E3, "190109").answer("590109000000")
            val r = scan(fmcu, bus)
            assertEquals(answer, listOf("7E3 22F194"), bus.requests)
            assertEquals(answer, status, r.status)
            assertFalse(answer, ModuleResult.gatePassed(fmcu, r.read.replies.single()))
            assertEquals(answer, false, r.identified ?: false)
        }
        // No answer, and a busy gateway.
        val silent = FakeDiagnosticBus()
        val none = scan(fmcu, silent)
        assertEquals(listOf("7E3 22F194"), silent.requests)
        assertEquals(ModuleStatus.NO_REPLY, none.status)
        assertNull(none.identified)
        val busy = FakeDiagnosticBus().apply { on(0x7E3, "22F194").busy() }
        assertEquals(ModuleStatus.NO_REPLY, scan(fmcu, busy).status)
    }

    @Test
    fun theBrakeInterfacesNameStaysExact() {
        val bscm = EcuCatalog.byRequest(0x7E5)!!
        assertEquals(ScanRequests.ECU_NAME, ScanRequests.gate(bscm))
        // As it answered on 2026-10-20, then the engine's name on the same route.
        val own = "490A014253434D2D4272616B6553797374656D00000000"
        val other = "490A0145434D2D456E67696E65436F6E74726F6C000000"
        val ok = FakeDiagnosticBus().apply { on(0x7E5, "090A").answer(own) }
        val r = scan(bscm, ok)
        assertEquals(true, r.identified)
        assertEquals(listOf("7E5 090A", "7E5 0101", "7E5 03", "7E5 07", "7E5 0A"), ok.requests)

        val wrong = FakeDiagnosticBus().apply { on(0x7E5, "090A").answer(other) }
        val w = scan(bscm, wrong)
        assertEquals(false, w.identified)
        assertEquals(listOf("7E5 090A"), wrong.requests)
        assertEquals(ModuleStatus.UNIDENTIFIED, w.status)
    }

    @Test
    fun aStockRouteHasNoGate() {
        EcuCatalog.routed.filter { it.identity == IdentityBasis.STOCK_ROUTE }.forEach { ecu ->
            assertNull(ecu.abbreviation, ScanRequests.gate(ecu))
            val bus = FakeDiagnosticBus()
            scan(ecu, bus)
            assertEquals(ecu.abbreviation, "190109", bus.requests.first().substringAfter(' ').take(6))
        }
    }
}
