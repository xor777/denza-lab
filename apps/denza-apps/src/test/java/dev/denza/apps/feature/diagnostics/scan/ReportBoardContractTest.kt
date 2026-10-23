package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.diagnostics.report.ReportCodeKey
import dev.denza.apps.feature.diagnostics.report.ReportDocuments
import dev.denza.apps.feature.diagnostics.report.ReportRecording
import dev.denza.apps.feature.diagnostics.report.ReportSection
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board `scanner-report` against the report the model carries: the boards' scan, ECM opened with its
 * calibration read, the last recording 42 s of four signals. What goes in - each section's figures and its
 * tick - and the sheet's head, summary, pack and footer are what the board shows.
 */
@Suppress("UNCHECKED_CAST")
class ReportBoardContractTest {
    private val scan = BoardScan.results(BoardScan.records("scanner-summary"))

    /** ECM opened: its calibration as it answered `09 04` (IdentificationTest). */
    private val ecm = IdentResult(EcuCatalog.byRequest(0x7E0)!!, ModuleRead(0x7E0, BoardScan.FINISHED, listOf(
        DiagReply.Answer(DiagRequest(0x7E0, hexBytes("0904")), hexBytes("4904015A33314C303741423031000000000000"), 1, BoardScan.FINISHED, 20, 0),
    )))

    @Test
    fun theReportIsWhatItsBoardShows() {
        val f = SpecJson.fixture("scanner-report")
        val m = BoardScan.model(BoardScan.snapshot(scan, idents = mapOf(0x7E0 to ecm),
            recording = ReportRecording("/data/stream-20261020-1930.csv", 42_000, 4)))
        val report = m.report
        val input = report.input!!
        val include = f["include"] as List<Map<String, Any?>>
        assertEquals(include.map { it["name"] }, report.options.map { it.section.title })
        assertEquals(include.map { it["detail"] }, report.options.map { it.detail })
        assertEquals(include.map { it["on"] }, report.options.map { it.byDefault })

        val paper = f["paper"] as Map<String, Any?>
        val doc = ReportDocuments.of(input, ReportSection.entries.toSet())
        val head = doc.pieces.first() as dev.denza.apps.feature.diagnostics.report.ReportPiece.Head
        assertEquals(paper["car"], head.lines[0].first)
        assertEquals(paper["when"], head.lines[1].first)
        val sections = paper["sections"] as List<Map<String, Any?>>
        assertEquals((sections.first { it["head"] == "ИТОГ" }["lines"] as List<String>).single(), ReportDocuments.summary(input))
        assertEquals((sections.first { it["head"] == "ТЯГОВАЯ БАТАРЕЯ" }["lines"] as List<String>).single(), ReportDocuments.pack(input.pack))
        assertEquals((paper["foot"] as String).substringBefore(". Стр."), doc.footer)
    }

    @Test
    fun aScanWithoutDurationSaysNoTime() {
        // every exchange of the scan at one instant: «148 обменов», no «за 0,0 с»
        val flat = scan.mapValues { (_, r) -> BoardScan.again(r, BoardScan.FINISHED) }.mapValues { (_, r) ->
            ModuleResult(r.ecu, ModuleRead(r.read.request, r.read.atMillis, r.read.replies.map {
                it as DiagReply.Answer
                DiagReply.Answer(it.request, it.uds, 1, BoardScan.FINISHED, 0, 0)
            }))
        }
        val input = BoardScan.model(BoardScan.snapshot(flat)).report.input!!
        assertEquals(0L, input.exchanges.durationMs)
        val footer = ReportDocuments.of(input, emptySet()).footer
        assertTrue(footer, footer.endsWith("— 148 обменов"))
        assertFalse(footer.contains(" за "))
    }

    @Test
    fun theJournalGivesTheFirstReadsAndTheFullChargeTheReadsTheVin() {
        val journal = BoardJournal.data(scan)
        val vin = "LGXCH4CB8R0012345"
        val withVin = scan + (0x7E2 to scan.getValue(0x7E2).let { r ->
            ModuleResult(r.ecu, ModuleRead(0x7E2, r.read.atMillis, r.read.replies + DiagReply.Answer(DiagRequest(0x7E2, hexBytes("22F190")),
                hexBytes("62F190" + vin.toByteArray().joinToString("") { "%02X".format(it) }), 1, r.read.atMillis, 20, 0)))
        })
        val input = BoardScan.model(BoardScan.snapshot(withVin, journal = journal)).report.input!!
        assertEquals(LocalDate.of(2026, 10, 18), input.journal!!.firstSeen[ReportCodeKey(0x720, "U0121-87")])
        assertEquals(LocalDate.of(2026, 10, 20), input.codes.first { it.request == 0x597 }.firstSeen)
        assertEquals(12.0, input.pack.fullChargeSpreadMv!!, 0.0)
        assertTrue(input.journal!!.excerptJson!!.contains("\"code\":\"U0121-87\""))
        assertEquals(vin, input.vin)
        assertEquals("Denza Z9 GT DM · VIN $vin · пробег 13 199,7 км",
            (ReportDocuments.of(input, emptySet()).pieces.first() as dev.denza.apps.feature.diagnostics.report.ReportPiece.Head).lines[0].first)
    }
}
