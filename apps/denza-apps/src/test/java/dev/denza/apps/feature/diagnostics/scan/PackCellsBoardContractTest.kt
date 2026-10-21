package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.BmsPage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The «Ячейки» boards (`scanner-sys-pack-cells`, `-partial`) against [PackCells]: the board's values
 * go in as the BMS sends them, and every figure and word the board prints - the heads, the counters,
 * the scale and its grid, the marked extremes, the faint positions, the age - is what the assembler
 * gives. The debug fixture draws the board's words; this test is what makes them the engine's.
 */
class PackCellsBoardContractTest {
    private fun ints(f: Map<String, Any?>, key: String): List<Int> =
        (f[key] as? List<*>).orEmpty().map { (it as Number).toInt() }

    private fun pairs(f: Map<String, Any?>, key: String): List<Pair<String, String>> =
        (f[key] as List<*>).map { (it as List<*>).let { p -> p[0] as String to p[1] as String } }

    private fun cellPage(page: Int, values: List<Int>, at: Long): BmsPage {
        val body = mutableListOf(page)
        (values + List(3 - values.size) { 0xFFFF }).forEach { w -> body += w and 0xFF; body += w shr 8 }
        body += 0x47
        return BmsPage(BmsPage.CELLS, ByteArray(8) { body[it].toByte() }, at)
    }

    private fun probePage(page: Int, values: List<Int>, at: Long): BmsPage {
        val body = mutableListOf(page)
        body += values + List(6 - values.size) { 0xFF }
        body += (255 - body.sum()).mod(256)
        return BmsPage(BmsPage.PROBES, ByteArray(8) { body[it].toByte() }, at)
    }

    /**
     * The board's sweep through the assembler: one complete sweep of [SWEEP] (cells' pages 100 ms
     * apart, the probes' between them); then, when the board has faint positions, two more sweeps
     * that miss every page holding one, and the view [ago] after the last page that came.
     */
    private fun view(f: Map<String, Any?>, ago: Long): PackCellsView {
        val cells = ints(f, "cells")
        val probes = ints(f, "probes").map { it + PackCells.PROBE_OFFSET }
        val missedCells = ints(f, "staleCells").map { it / 3 }.toSet()
        val missedProbes = ints(f, "staleProbes").map { it / 6 }.toSet()
        val pack = PackCells()
        pack.accept(BmsPage(BmsPage.COUNTS, byteArrayOf(cells.size.toByte(), probes.size.toByte(), 0, 0, 0, 0, 0, 0), 0))
        val cellPages = cells.chunked(3)
        val probePages = probes.chunked(6)
        var newest = 0L
        fun sweep(start: Long, skip: Boolean) {
            val pages = cellPages.indices.filter { !skip || it !in missedCells }.map { cellPage(it, cellPages[it], start + it * STEP) } +
                probePages.indices.filter { !skip || it !in missedProbes }.map { probePage(it, probePages[it], start + it * STEP + STEP / 2) }
            pages.sortedBy { it.receivedNanos }.forEach(pack::accept)
            newest = pages.maxOf { it.receivedNanos }
        }
        sweep(START, skip = false)
        if (missedCells.isNotEmpty() || missedProbes.isNotEmpty()) {
            sweep(START + SWEEP + STEP, skip = true)
            sweep(START + 2 * (SWEEP + STEP), skip = true)
        }
        return pack.view(newest + ago)
    }

    private fun check(id: String, ago: Long) {
        val f = SpecJson.fixture(id)
        val v = view(f, ago)
        assertEquals(id, f["cellsHead"], v.cellsHead)
        assertEquals(id, f["probesHead"], v.probesHead)
        assertEquals(id, pairs(f, "cellsStats"), v.cellStats.map { it.label to it.value })
        assertEquals(id, pairs(f, "probesStats"), v.probeStats.map { it.label to it.value })
        assertEquals(id, f["cellMeanText"], v.cellStats[2].value)
        assertEquals(id, (f["cellMean"] as Number).toFloat(), v.cellMean!!, 1e-3f)
        assertEquals("$id: the chart's range", ints(f, "cellScale"), listOf(v.cellScale.start, v.cellScale.endInclusive).map { it.toInt() })
        assertEquals("$id: the chart's grid", ints(f, "cellGrid"), v.cellGrid.map { it.toInt() })
        assertEquals(id, f["minIndex"].let { (it as Number).toInt() }, v.cellMin)
        assertEquals(id, f["maxIndex"].let { (it as Number).toInt() }, v.cellMax)
        assertEquals(id, f["probeMin"].let { (it as Number).toInt() }, v.probeMin)
        assertEquals(id, f["probeMax"].let { (it as Number).toInt() }, v.probeMax)
        assertEquals(id, f["cellsAge"], v.age)
        assertEquals("$id: the faint cells", ints(f, "staleCells"), v.cells.indices.filter { !v.cells[it].fresh })
        assertEquals("$id: the faint probes", ints(f, "staleProbes"), v.probes.indices.filter { !v.probes[it].fresh })
    }

    @Test
    fun aWholeSweep() = check("scanner-sys-pack-cells", ago = 400_000_000L)

    @Test
    fun aSweepWithItsLastPagesMissed() = check("scanner-sys-pack-cells-partial", ago = 2_600_000_000L)

    private companion object {
        const val START = 1_000_000_000L
        const val STEP = 100_000_000L
        /** 56 pages of cells 100 ms apart: the first to the last 5.5 s, as on the car. */
        const val SWEEP = 55 * STEP
    }
}
