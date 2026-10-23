package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.DtcStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boards `scanner-journal` and `-journal-first` against [JournalViewBuilder] through [ModelBuilder]: the
 * boards' journal ([BoardJournal]) - or its first scan alone - shown. The head, the days, the scans, the
 * update, the cells' snapshots with the full charges named, every block's records from their first read - a block
 * whose records a later reading no longer held ending there - every code a row of «Коды», the
 * values by scan and the comparison of the first scan with the last are what the boards draw of the same
 * journal (scanner.js `journal`). Where the board puts the update - 03:10 on 22.10 - is illustrative: the
 * journal knows it by the first scan that read the new version.
 */
@Suppress("UNCHECKED_CAST")
class JournalBoardContractTest {
    private val scan = BoardScan.results(BoardScan.records("scanner-summary"))

    private fun view(data: JournalData, now: Long) =
        BoardScan.model(BoardScan.snapshot(scan, journal = data, now = now, journalVisible = true)).journal!!

    private fun check(id: String, data: JournalData, now: Long) {
        val f = SpecJson.fixture(id)
        val v = view(data, now)
        val block = f["block"] as Map<String, Any?>
        assertEquals(block["aside"], v.aside)
        assertEquals(block["line"], v.line)
        assertEquals((block["counters"] as List<List<Any?>>).map { it[0] to it[1] }, v.counters.map { it.label to it.value })
        assertEquals(f["days"], v.days)

        val scans = f["scans"] as List<Map<String, Any?>>
        assertEquals(scans.map { it["kind"] == "full" }, v.scans.map { it.full })
        assertEquals(scans.map { BoardScan.at(it["at"] as String) }, v.scans.map { it.atMillis })
        assertEquals((f["software"] as List<Map<String, Any?>>).map { "${it["block"]} ${it["from"]} → ${it["to"]}" }, v.software.map { it.text })
        val shots = f["cellShots"] as List<Map<String, Any?>>
        assertEquals(shots.map { (it["soc"] as Number).toInt() >= 95 }, v.cells.map { it.full })
        assertEquals(shots.map { s -> (s["soc"] as Number).toInt().takeIf { it >= 95 }?.let { "SOC $it %" } }, v.cells.map { it.label })
        assertTrue(v.scans.zipWithNext().all { (a, b) -> a.at <= b.at })

        val codes = f["codes"] as List<Map<String, Any?>>
        assertEquals(codes.map { "${it["block"]} ${it["addr"]}" }, v.codes.map { it.block })
        assertEquals(codes.map { (it["count"] as Number).toInt() }, v.codes.map { it.count })
        assertEquals(codes.map { (it["seen"] as List<*>).size }, v.codes.map { it.seen.size })
        assertEquals(codes.map { it["gone"] == true }, v.codes.map { it.gone })
        assertTrue(v.codes.all { it.mark == Mark.HISTORY })

        // «Коды», row by row: the present ones with their sign, then the gone with none
        val rows = (f["codeRows"] as List<Map<String, Any?>>).map { r ->
            val gone = r["gone"] == true
            JournalCodeRow(
                request = (r["addr"] as String).toInt(16), code = r["code"] as String, block = "${r["block"]} ${r["addr"]}",
                mark = if (gone) null else BlockRecords.mark(DtcStatus((r["status"] as Number).toInt())),
                first = r["first"] as String, last = r["last"] as String, seen = r["seen"] as String, now = r["now"] as String, gone = gone,
            )
        }
        assertEquals(rows, v.codeRows)

        val values = f["values"] as Map<String, Any?>
        val lv = values["lv"] as Map<String, Any?>
        val full = values["full"] as Map<String, Any?>
        assertEquals(listOf(lv["name"], full["name"]), v.quantities.map { it.name })
        val lvPoints = lv["points"] as List<List<Any?>>
        assertEquals(lvPoints.size, v.quantities[0].points.size)
        assertEquals(Figures.number((lvPoints.last()[1] as Number).toDouble(), 1), v.quantities[0].last)
        val fullPoints = full["points"] as List<List<Any?>>
        assertEquals(fullPoints.size, v.quantities[1].points.size)
        assertEquals(fullPoints.lastOrNull()?.let { "${(it[1] as Number).toInt()} мВ" } ?: "—", v.quantities[1].last)
        assertTrue(v.quantities[1].pointsOnly)
        // each snapshot at a full charge says its spread by its point; 12 V only its last
        assertEquals(fullPoints.map { "${(it[1] as Number).toInt()} мВ" }, v.quantities[1].pointLabels)
        assertTrue(v.quantities[0].pointLabels.isEmpty())

        val c = f["compare"] as Map<String, Any?>
        val shown = v.compare!!
        assertEquals(listOfNotNull(c["from"], c["to"]).joinToString(" → "), shown.title)
        assertEquals((c["codes"] as List<List<Any?>>).map { it[0] to (it[1] as Number).toInt().toString() }, shown.codes.map { it.key to it.value })
        assertEquals((c["software"] as List<Map<String, Any?>>).map { it["block"] to "${it["from"]} → ${it["to"]}" }, shown.software.map { it.key to it.value })
        assertEquals((c["values"] as List<List<Any?>>).map { (k, a, b, unit) -> k to ((if (a != null) "$a → $b" else b as String) + " " + unit) },
            shown.quantities.map { it.key to it.value })
    }

    @Test
    fun theJournalOfFiveDaysIsWhatItsBoardShows() = check("scanner-journal", BoardJournal.data(scan), BoardScan.at("23.10 08:50:31"))

    @Test
    fun theJournalOfOneScanIsWhatItsBoardShows() {
        val all = EcuCatalog.routed.map { scan.getValue(it.request!!) }
        val first = JournalEntries.scan(JournalScanKind.FULL, all, emptyMap(), BoardScan.car(), BoardScan.at("20.10 19:31"))
        check("scanner-journal-first", JournalData().with(first), BoardScan.FINISHED)
    }

    @Test
    fun aCodeAWholeReadingNoLongerHoldsIsGoneAndLast() {
        val data = BoardJournal.data(scan)
        val latest = data.latest!!
        val later = latest.copy(atMillis = BoardScan.at("23.10 18:00"), modules = latest.modules.map { m ->
            if (m.address == 0x720) m.copy(records = m.records.filter { it.code != "U0229-87" }) else m
        })
        val rows = view(data.with(later), BoardScan.at("23.10 18:00")).codeRows
        // the newest gone first: then the board's EPS, gone since 22.10
        assertEquals(JournalCodeRow(0x720, "U0229-87", "LA_BCM 720", null, "18.10 15:42", "23.10 08:50", "8 из 9", "ушёл 23.10", gone = true), rows[17])
        assertEquals(listOf("U0229-87", "U0100-87"), rows.filter { it.gone }.map { it.code })
        assertEquals(17, rows.count { !it.gone })
        assertEquals("23.10 18:00", rows.first().last)
    }

    @Test
    fun twoScansNamedAreComparedAndThePreviousOneByDefaultOfOne() {
        val data = BoardJournal.data(scan)
        val shown = BoardScan.model(BoardScan.snapshot(scan, journal = data, journalVisible = true,
            compare = null to BoardScan.at("23.10 08:50"))).journal!!
        // «Сравнить с прошлым»: the last against the full scan before it - no update between them
        assertEquals("22.10 19:12 → 23.10 08:50", shown.compare!!.title)
        assertTrue(shown.counters.isEmpty())
        assertTrue(shown.compare!!.software.isEmpty())
    }
}
