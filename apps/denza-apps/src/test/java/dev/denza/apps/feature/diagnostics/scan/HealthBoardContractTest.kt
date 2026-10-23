package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.BmsPage
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The boards `scanner-sys-pack-health` and `-health-first` against [HealthBuilder] through [ModelBuilder]: the
 * board's sweep as the BMS sends it (one whole, the next 34 pages in, as «Ячейки»), the car at 545 V and 74 %,
 * every GB/T flag read and none raised, the boards' scan for the BMS's memory, and the boards' journal - or
 * none for the first. Each row's name, its sub-line, its value and unit, its reference - a norm, this car's
 * usual, a note - and whether it has 30 days to draw are what the board shows.
 */
@Suppress("UNCHECKED_CAST")
class HealthBoardContractTest {
    private val now = BoardScan.at("23.10 07:36:12")

    /** The board's sweep, whole, and the next one 34 pages in. */
    private fun cells(f: Map<String, Any?>): PackCellsView {
        val cells = (f["cells"] as List<Number>).map { it.toInt() }
        val probes = (f["probes"] as List<Number>).map { it.toInt() + PackCells.PROBE_OFFSET }
        val pack = PackCells(ScanClock(ZoneOffset.UTC))
        val start = 1_000_000_000L
        pack.accept(BmsPage(BmsPage.COUNTS, byteArrayOf(cells.size.toByte(), probes.size.toByte(), 0, 0, 0, 0, 0, 0), start))
        fun cellPage(page: Int, values: List<Int>, at: Long): BmsPage {
            val body = mutableListOf(page)
            (values + List(3 - values.size) { 0xFFFF }).forEach { w -> body += w and 0xFF; body += w shr 8 }
            body += 0x47
            return BmsPage(BmsPage.CELLS, ByteArray(8) { body[it].toByte() }, at)
        }
        fun probePage(page: Int, values: List<Int>, at: Long): BmsPage {
            val body = mutableListOf(page)
            body += values + List(6 - values.size) { 0xFF }
            body += (255 - body.sum()).mod(256)
            return BmsPage(BmsPage.PROBES, ByteArray(8) { body[it].toByte() }, at)
        }
        var t = start
        fun sweep(pages: Int, shift: Int) {
            cells.chunked(3).take(pages).forEachIndexed { i, v -> t += 100_000_000L; pack.accept(cellPage(i, v.map { it + shift }, t)) }
            probes.chunked(6).forEachIndexed { i, v -> pack.accept(probePage(i, v, t)) }
        }
        sweep(56, 0)
        sweep(34, -4)
        return pack.view(t + 400_000_000L)
    }

    private fun check(id: String, journal: Boolean) {
        val f = SpecJson.fixture(id)
        val scan = BoardScan.results(BoardScan.records("scanner-summary"))
        val flags = GbFlags.ALL.associate { it.extra to 0.0 }
        val car = BoardScan.car(packVolts = 545.0, extra = flags)
        val m = BoardScan.model(BoardScan.snapshot(scan, car = car, journal = if (journal) BoardJournal.data(scan) else JournalData(),
            now = now, packCells = cells(f)))
        val view = m.health!!
        val rows = f["rows"] as List<Map<String, Any?>>
        assertEquals(rows.map { it["name"] }, view.rows.map { it.name })
        rows.zip(view.rows).forEach { (b, r) ->
            val name = "${b["name"]} · ${b["sub"]}"
            assertEquals(name, b["sub"], r.sub)
            assertEquals(name, b["value"], r.value)
            assertEquals(name, b["unit"], r.unit)
            assertEquals(name, b["out"] == true, r.outOfNorm)
            val reference = when {
                b["norm"] != null -> HealthReference(b["norm"] as String, ReferenceKind.NORM)
                b["usual"] != null -> HealthReference(b["usual"] as String, ReferenceKind.USUAL)
                b["note"] != null -> HealthReference(b["note"] as String, ReferenceKind.NOTE)
                else -> null
            }
            assertEquals(name, reference, r.reference)
            val trend = b["trend"] as Map<String, Any?>?
            assertEquals("$name: 30 days where the board draws them", trend != null, r.trend.isNotEmpty())
            if (trend != null) assertEquals(name, trend["points"] == true, r.trendPoints)
        }
        assertEquals(((f["footer"] as Map<String, Any?>)["notes"] as List<List<Any?>>).first()[0], view.age)
        assertEquals((f["cellRef"] as Number).toInt(), view.cells!!.cellRef)
    }

    @Test
    fun theHealthOfAPackWithAJournalIsWhatItsBoardShows() = check("scanner-sys-pack-health", journal = true)

    @Test
    fun theHealthOfTheFirstScanIsWhatItsBoardShows() = check("scanner-sys-pack-health-first", journal = false)

    @Test
    fun aLowInsulationAndAWornPackAreOrange() {
        val scan = BoardScan.results(BoardScan.records("scanner-summary"))
        val car = BoardScan.car(packVolts = 553.0, extra = mapOf(DiagnosticExtra.INSULATION to 270.0, DiagnosticExtra.SOH to 79.0))
        val view = BoardScan.model(BoardScan.snapshot(scan, car = car, now = now)).health!!
        assertEquals(listOf(true, false), view.rows.filter { it.name == "Изоляция" }.map { it.outOfNorm })
        assertEquals("0,27", view.rows.first { it.name == "Изоляция" }.value)
        assertEquals(true, view.rows.first().outOfNorm)
    }
}
