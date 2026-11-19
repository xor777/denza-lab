package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Сводка» never says more than the scan read: «Активных неисправностей нет» only where every block's memory
 * came back whole and no MIL is lit; a memory that did not come back is counted, not counted as clean. PID 01
 * is the newest of the autoscan's and the identification's reads, on the block's page, the summary and the
 * report alike.
 */
class SummaryCoverageTest {
    private val esp = 0x782
    private val ecm = 0x7E0

    private fun answer(request: Int, uds: String, reply: String, at: Long) =
        DiagReply.Answer(DiagRequest(request, hexBytes(uds)), hexBytes(reply), 1, at, 18, at * 1_000_000L)

    private fun clean() = BoardScan.results(emptyMap())

    private fun model(results: Map<Int, ModuleResult>, idents: Map<Int, IdentResult> = emptyMap()) =
        BoardScan.model(BoardScan.snapshot(results, idents = idents))

    @Test
    fun aCleanScanOfEveryMemoryIsTheAllClear() {
        val view = model(clean()).summary!!
        assertEquals("Активных неисправностей нет", view.verdict)
        assertFalse(view.restAttention)
    }

    @Test
    fun aMemoryThatDidNotComeBackIsNoAllClear() {
        val results = clean().toMutableMap()
        val at = BoardScan.FINISHED - 5_000
        // ESP/IPB says who it is, then its count runs out of time
        val gate = results.getValue(esp).read.replies.first { it.request.uds.contentEquals(hexBytes("22F194")) } as DiagReply.Answer
        val read = ModuleRead(esp, at, listOf(gate, DiagReply.NoReply(DiagRequest(esp, hexBytes("190109")), 0, at, 4_000, at * 1_000_000L)))
        results[esp] = ModuleResult(EcuCatalog.byRequest(esp)!!, read)
        assertFalse(results.getValue(esp).memoryRead)

        val view = model(results).summary!!
        val routed = EcuCatalog.routed.size
        assertEquals("Память прочитана у ${routed - 1} из $routed", view.verdict)
        assertNull(view.verdictMark)
        // the block that answered and whose memory did not come back is not among the blocks without records
        assertEquals("${Words.count(routed - 1, "блок", "блока", "блоков")} без записей · все ответили · память не прочитана 1", view.rest)
        assertTrue(view.restAttention)
    }

    @Test
    fun aMilNotReadIsNoAllClear() {
        val results = clean().toMutableMap()
        val ecmRead = results.getValue(ecm).read
        // every memory reads clean, and the ECM's 01 01 runs out of time
        val lost = ecmRead.replies.map { r ->
            if (r.request.uds.contentEquals(hexBytes("0101"))) DiagReply.NoReply(r.request, 0, r.atMillis, 4_000, r.atMillis * 1_000_000L) else r
        }
        results[ecm] = ModuleResult(EcuCatalog.byRequest(ecm)!!, ecmRead.copy(replies = lost))
        assertTrue(results.getValue(ecm).memoryRead)

        val view = model(results).summary!!
        assertEquals("MIL н/д", view.verdict)
        assertNull(view.verdictMark)
    }

    @Test
    fun aScanThatHasNotAskedEveryBlockDoesNotSayTheyAllAnswered() {
        val asked = EcuCatalog.routed.take(12).mapNotNull { it.request }.toSet()
        val results = clean().filterKeys { it in asked }
        val quiet = results.values.count { it.memoryRead }
        val unasked = EcuCatalog.routed.size - 12
        val catalogued = EcuCatalog.all.size
        val m = model(results)

        assertEquals("${Words.count(quiet, "блок", "блока", "блоков")} без записей · не опрошены $unasked", m.summary!!.rest)
        assertEquals("· $catalogued · не опрошены $unasked", m.blocks!!.aside)
        // «без записей» is a memory read whole and empty: a block not asked yet is not among them
        assertEquals(listOf("БЕЗ ЗАПИСЕЙ · $quiet", "ПАМЯТЬ НЕ ПРОЧИТАНА · ${catalogued - quiet}"), m.blocks!!.groups.map { it.title })
        assertEquals("${catalogued - unasked}/$catalogued", m.railLines.single { it.place == RailPlace.Blocks }.plain)
    }

    @Test
    fun aLitMilWithNoRecordIsNoAllClear() {
        val results = clean().toMutableMap()
        val ecmRead = results.getValue(ecm).read
        val lit = ecmRead.replies.map { r -> if (r.request.uds.contentEquals(hexBytes("0101"))) answer(ecm, "0101", "41018007F5F4", r.atMillis) else r }
        results[ecm] = ModuleResult(EcuCatalog.byRequest(ecm)!!, ecmRead.copy(replies = lit))

        val view = model(results).summary!!
        assertEquals("MIL горит", view.verdict)
        assertEquals(Mark.ACTIVE, view.verdictMark)
    }

    @Test
    fun theNewerPid01IsTheOneThePageTheSummaryAndTheReportSay() {
        val results = clean().toMutableMap()
        val ecmRead = results.getValue(ecm).read
        val scanAt = ecmRead.atMillis
        // the block opened before the scan: its identification read MIL off; the scan after it reads it lit
        val lit = ecmRead.replies.map { r -> if (r.request.uds.contentEquals(hexBytes("0101"))) answer(ecm, "0101", "41018007F5F4", r.atMillis) else r }
        results[ecm] = ModuleResult(EcuCatalog.byRequest(ecm)!!, ecmRead.copy(replies = lit))
        val earlier = IdentResult(EcuCatalog.byRequest(ecm)!!, ModuleRead(ecm, scanAt - 60_000, listOf(answer(ecm, "0101", "41010007F5F4", scanAt - 60_000))))

        val m = model(results, mapOf(ecm to earlier))
        assertEquals(MilState.ON, m.modules.getValue(ecm).obd!!.mil)
        assertEquals(MilState.ON, m.scan.mil)
        assertEquals("MIL горит", m.summary!!.verdict)
        assertEquals(true, m.report.input!!.engine!!.mil)

        // and the other way round: the block opened after the scan says it is off now
        val later = IdentResult(EcuCatalog.byRequest(ecm)!!, ModuleRead(ecm, scanAt + 60_000, listOf(answer(ecm, "0101", "41010007F5F4", scanAt + 60_000))))
        val n = model(results, mapOf(ecm to later))
        assertEquals(MilState.OFF, n.modules.getValue(ecm).obd!!.mil)
        assertEquals(MilState.OFF, n.scan.mil)
        assertEquals(false, n.report.input!!.engine!!.mil)
    }
}
