package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.BmsPage
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The «Ячейки» boards (`scanner-sys-pack-cells`, `-partial`, `-paused`) against [PackCells]: the
 * board's values go in as the BMS sends them, a sweep at a time, and every figure and word the
 * board prints - the heads, the counters, the reference and its grid, the marked extremes, the
 * faint positions, the sweep under way, the age and the footer's button - is what the assembler
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

    /** The board's arrays as the BMS sends them, and a pack fed them sweep by sweep. */
    private inner class Feed(f: Map<String, Any?>) {
        val cells = ints(f, "cells")
        val probes = ints(f, "probes").map { it + PackCells.PROBE_OFFSET }
        val pack = PackCells(ScanClock(ZoneOffset.UTC))
        private val cellPages = cells.chunked(3)
        private val probePages = probes.chunked(6)
        var newest = 0L

        init {
            pack.accept(BmsPage(BmsPage.COUNTS, byteArrayOf(cells.size.toByte(), probes.size.toByte(), 0, 0, 0, 0, 0, 0), 0))
        }

        /**
         * Sweep [k], cells' pages 100 ms apart from its start, the probes' between them: [cellsCame]
         * and [probesCame] of its pages in order, each word [shift] from the board's.
         */
        fun sweep(k: Int, cellsCame: Int = cellPages.size, probesCame: Int = probePages.size, shift: Int = 0) {
            val start = START + k * PERIOD
            val pages = (0 until cellsCame).map { cellPage(it, cellPages[it].map { v -> v + shift }, start + it * STEP) } +
                (0 until probesCame).map { probePage(it, probePages[it], start + it * STEP + STEP / 2) }
            pages.sortedBy { it.receivedNanos }.forEach(pack::accept)
            newest = pages.maxOf { it.receivedNanos }
        }
    }

    /** A whole sweep drawn; the next one, 4 mV lower under load, 34 pages in. */
    private fun whole(f: Map<String, Any?>) = Feed(f).apply {
        sweep(0)
        sweep(1, cellsCame = 34, shift = -4)
    }

    /**
     * A whole sweep, then one that missed the cells' last six pages and the probes' last; the
     * next, 4 mV lower, 50 pages in when the stream paused for 2.6 s.
     */
    private fun missed(f: Map<String, Any?>) = Feed(f).apply {
        sweep(0)
        sweep(1, cellsCame = 50, probesCame = 2)
        sweep(2, cellsCame = 50, shift = -4)
    }

    private fun check(id: String, v: PackCellsView, f: Map<String, Any?>) {
        assertEquals(id, f["cellsHead"], v.cellsHead)
        assertEquals(id, f["probesHead"], v.probesHead)
        assertEquals("$id: the cells drawn", ints(f, "cells"), v.cells.map { it.value!!.toInt() })
        assertEquals("$id: the probes drawn", ints(f, "probes"), v.probes.map { it.value!!.toInt() })
        assertEquals(id, pairs(f, "cellsStats"), v.cellStats.map { it.label to it.value })
        assertEquals(id, pairs(f, "probesStats"), v.probeStats.map { it.label to it.value })
        assertEquals("$id: the reference", (f["cellRef"] as Number).toInt(), v.cellRef)
        assertEquals("$id: the grid", ints(f, "cellGrid"), v.cellGrid)
        assertEquals("$id: the grid's words", f["cellGridText"], v.cellGridText)
        assertEquals(id, f["minIndex"].let { (it as Number).toInt() }, v.cellMin)
        assertEquals(id, f["maxIndex"].let { (it as Number).toInt() }, v.cellMax)
        assertEquals(id, f["probeMin"].let { (it as Number).toInt() }, v.probeMin)
        assertEquals(id, f["probeMax"].let { (it as Number).toInt() }, v.probeMax)
        val sweep = f["sweep"] as Number?
        if (sweep == null) assertNull("$id: no sweep under way", v.sweep) else assertEquals("$id: the sweep under way", sweep.toFloat(), v.sweep!!, 1e-6f)
        assertEquals(id, f["cellsAge"], v.age)
        @Suppress("UNCHECKED_CAST")
        val footer = f["footer"] as Map<String, Any?>
        assertEquals("$id: the footer's age", v.age, ((footer["notes"] as List<*>)[0] as List<*>)[0])
        assertEquals("$id: the footer's button", listOf(listOf(if (v.paused) "Продолжить" else "Пауза", "primary")), footer["buttons"])
        assertEquals("$id: the faint cells", ints(f, "staleCells"), v.cells.indices.filter { !v.cells[it].fresh })
        assertEquals("$id: the faint probes", ints(f, "staleProbes"), v.probes.indices.filter { !v.probes[it].fresh })
    }

    @Test
    fun aWholeSweep() {
        val id = "scanner-sys-pack-cells"
        val f = SpecJson.fixture(id)
        val feed = whole(f)
        check(id, feed.pack.view(feed.newest + 400_000_000L), f)
    }

    @Test
    fun aSweepWithItsLastPagesMissed() {
        val id = "scanner-sys-pack-cells-partial"
        val f = SpecJson.fixture(id)
        val feed = missed(f)
        check(id, feed.pack.view(feed.newest + 2_600_000_000L), f)
    }

    @Test
    fun paused() {
        val id = "scanner-sys-pack-cells-paused"
        val f = SpecJson.fixture(id)
        val feed = whole(f)
        val at = feed.newest + 400_000_000L
        // The sweep drawn ended with its page 55; the wall clock read 07:36:12.4 then.
        val drawn = START + 55 * STEP
        val wall = LocalDateTime.of(2026, 10, 21, 7, 36, 12, 400_000_000).toInstant(ZoneOffset.UTC).toEpochMilli() + (at - drawn) / 1_000_000L
        feed.pack.pause(at, wall)
        check(id, feed.pack.view(at + 60_000_000_000L), f)
    }

    private companion object {
        const val START = 1_000_000_000L
        const val STEP = 100_000_000L
        /** 56 pages of cells 100 ms apart, the first to the last 5.5 s, as on the car; the next sweep 100 ms on. */
        const val PERIOD = 56 * STEP
    }
}
