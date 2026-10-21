package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.BmsPage
import java.io.File
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack's cells and probes (architecture 6.3): the page rules of
 * `research/vehicle-diagnostics/decode_battery_pages.py` and its tests, ported; what is drawn changing
 * once a sweep; the reference and the scale held between sweeps; plausibility; the pause; and three
 * real sweeps of the cells from the correlation window of 2026-10-21 (`battery-page-telemetry-20261021.json`).
 */
class PackCellsTest {
    /** A page as the decoder's tests build one: words little-endian, the rest fill, 43C's byte sum 255. */
    private fun page(page: Int, values: List<Int>, kind: Int = BmsPage.PROBES, at: Long = page * 100_000_000L, last: Int? = null): BmsPage {
        val width = if (kind == BmsPage.PROBES) 1 else 2
        val slots = if (width == 1) 6 else 3
        val fill = (1 shl (8 * width)) - 1
        val words = values + List(slots - values.size) { fill }
        val body = mutableListOf(page)
        words.forEach { w -> for (b in 0 until width) body += (w shr (8 * b)) and 0xFF }
        body += last ?: if (width == 1) (255 - body.sum()).mod(256) else 0x47
        return BmsPage(kind, ByteArray(body.size) { body[it].toByte() }, at)
    }

    private fun cell(page: Int, values: List<Int>, at: Long = page * 100_000_000L) = page(page, values, BmsPage.CELLS, at)

    private fun counts(cells: Int, probes: Int, at: Long = 0) =
        BmsPage(BmsPage.COUNTS, byteArrayOf(cells.toByte(), probes.toByte(), 0x34, 0x12, 0, 0, 0, 0), at)

    private fun PageArray.feed(vararg pages: BmsPage) = apply { pages.forEach(::accept) }
    private fun probes(count: Int) = PageArray(1, 6, true, PackCells::plausibleProbe).apply { count(count) }
    private fun cells(count: Int) = PageArray(2, 3, false, PackCells::plausibleCell).apply { count(count) }
    private fun pack() = PackCells(ScanClock(ZoneOffset.UTC))

    // ------------------------------------------------------------------ the decoder's cases

    @Test
    fun probesCompleteWithTheirPadding() {
        val a = probes(8).feed(page(0, listOf(50, 51, 52, 53, 54, 55)), page(1, listOf(56, 57)))
        assertEquals((50..57).toList(), a.codes())
        assertEquals(1, a.sweeps)
    }

    @Test
    fun cellWordsAreLittleEndianAndFdStaysOut() {
        val a = cells(4).feed(
            cell(0, listOf(0x0CEE, 0x0CEF, 0x0D02)),
            cell(1, listOf(0x0CE4)),
            cell(0xFD, listOf(1, 2, 3)),
        )
        assertEquals(listOf(3310, 3311, 3330, 3300), a.codes())
        assertEquals(1, a.sweeps)
    }

    @Test
    fun aMissingPageIsNoSweep() {
        val a = probes(14).feed(page(0, List(6) { 1 }), page(2, List(6) { 1 }))
        assertEquals(0, a.sweeps)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, null, null, null, null, null, null, 1, 1), a.codes())
    }

    @Test
    fun aRepeatedPageStartsOver() {
        val a = probes(14).feed(page(0, List(6) { 1 }), page(1, List(6) { 1 }), page(1, List(6) { 1 }), page(2, listOf(1, 1)))
        assertEquals(0, a.sweeps)
    }

    @Test
    fun aFillOrMissingMarkerIsNoValue() {
        val a = probes(2).feed(page(0, listOf(50, 255)))
        assertEquals(listOf(50, null), a.codes())
        assertEquals("the sweep is complete, the marker only has no value", 1, a.sweeps)
    }

    @Test
    fun aBrokenProbeSumIsNoPage() {
        val a = probes(1).feed(page(0, listOf(1), last = 0))
        assertEquals(listOf<Int?>(null), a.codes())
        assertEquals(0, a.sweeps)
    }

    @Test
    fun theProbeSumIsNotACellRule() {
        val p = cell(0, listOf(3300))
        assertTrue((0 until 8).sumOf { p[it] } % 256 != 255)
        assertEquals(1, cells(1).feed(p).sweeps)
    }

    @Test
    fun aPageOfAnotherLengthIsNoPage() {
        val short = page(0, listOf(1)).let { BmsPage(it.canId, it.payload.copyOf(7), it.receivedNanos) }
        assertEquals(0, probes(1).feed(short).sweeps)
        assertEquals(listOf<Int?>(null), probes(1).feed(short).codes())
    }

    @Test
    fun anUnknownPageDoesNotBridgeASweep() {
        val a = probes(14).feed(page(0, List(6) { 1 }), page(10, List(6) { 2 }), page(1, List(6) { 3 }), page(2, listOf(4, 4)))
        assertEquals(0, a.sweeps)
    }

    @Test
    fun noCountNoSweepAndTheLengthIsWhatCame() {
        val a = PageArray(2, 3, false, PackCells::plausibleCell).feed(cell(0, listOf(3300, 3301, 3302)), cell(1, listOf(3303)))
        assertNull(a.count)
        assertEquals(0, a.sweeps)
        assertNull("no count, no share of it", a.progress())
        assertEquals(listOf(3300, 3301, 3302, 3303), a.codes())
        a.count(4)
        a.feed(cell(0, listOf(3310, 3311, 3312)), cell(1, listOf(3313)))
        assertEquals(1, a.sweeps)
        assertEquals("the first sweep is drawn; the one after it is under way", listOf(3300, 3301, 3302, 3303), a.codes())
        assertEquals(1f, a.progress())
    }

    // ------------------------------------------------------------------ a sweep at a time

    @Test
    fun whatIsDrawnChangesOnceASweep() {
        val a = cells(6)
        a.feed(cell(0, listOf(3300, 3301, 3302)))
        assertEquals("before the first sweep ends, what came", listOf(3300, 3301, 3302, null, null, null), a.codes())
        assertEquals(listOf(true, true, true, false, false, false), a.refreshed())
        assertEquals(0.5f, a.progress())
        a.feed(cell(1, listOf(3303, 3304, 3305)))
        assertEquals((3300..3305).toList(), a.codes())
        assertEquals(1f, a.progress())

        // The next sweep, 4 mV lower: page 0 ends the first, and nothing of it is drawn until it ends too.
        assertTrue(a.accept(cell(0, listOf(3296, 3297, 3298), at = 600_000_000)))
        assertEquals((3300..3305).toList(), a.codes())
        assertEquals(List(6) { true }, a.refreshed())
        assertEquals(0.5f, a.progress())
        assertFalse(a.accept(cell(1, listOf(3299, 3300, 3301), at = 700_000_000)))
        assertEquals((3300..3305).toList(), a.codes())
        assertTrue(a.accept(cell(0, listOf(3290, 3290, 3290), at = 1_200_000_000)))
        assertEquals((3296..3301).toList(), a.codes())
        assertEquals(2, a.snapshots)
    }

    @Test
    fun aWrapIsAnyPageNotAboveThePrevious() {
        val a = cells(9).feed(cell(0, listOf(3300, 3300, 3300)), cell(1, listOf(3301, 3301, 3301)))
        assertEquals(0, a.snapshots)
        a.feed(cell(1, listOf(3302, 3302, 3302)))
        assertEquals("a page again is a wrap", 1, a.snapshots)
        a.feed(cell(2, listOf(3303, 3303, 3303)))
        a.feed(cell(1, listOf(3304, 3304, 3304)))
        assertEquals("a page back is a wrap", 2, a.snapshots)
        assertEquals(listOf(3300, 3300, 3300, 3302, 3302, 3302, 3303, 3303, 3303), a.codes())
        assertEquals("the sweep that ended missed page 0", listOf(false, false, false, true, true, true, true, true, true), a.refreshed())
    }

    @Test
    fun aPositionTheSweepMissedKeepsItsValueFaint() {
        val pack = pack()
        pack.accept(counts(4, 14))
        fun probeSweep(start: Long, pages: Int, base: Int) = (0 until pages).forEach {
            pack.accept(page(it, List(if (it < 2) 6 else 2) { k -> base + k }, at = start + it * 100_000_000L))
        }
        probeSweep(1_000_000_000, 3, base = 50)
        probeSweep(1_300_000_000, 2, base = 60)
        probeSweep(1_600_000_000, 1, base = 70)
        val v = pack.view(1_700_000_000)
        assertEquals(listOf(20, 21, 22, 23, 24, 25, 20, 21, 22, 23, 24, 25, 10, 11), v.probes.map { it.value!!.toInt() })
        assertEquals("the last page missed: the sweep before's, faint", listOf(12, 13), v.probes.indices.filter { !v.probes[it].fresh })
        assertEquals("the faint ones still count", listOf("МИН" to "10 °C", "МАКС" to "25 °C"), v.probeStats.take(2).map { it.label to it.value })
    }

    @Test
    fun theSweepUnderWayIsAShareOfThePages() {
        val pack = pack()
        pack.accept(counts(166, 14))
        assertEquals(0f, pack.view(0).sweep)
        (0 until 56).forEach { pack.accept(cell(it, listOf(3300, 3300, 3300), at = it * 100_000_000L)) }
        (0 until 34).forEach { pack.accept(cell(it, listOf(3296, 3296, 3296), at = 5_600_000_000L + it * 100_000_000L)) }
        val v = pack.view(9_300_000_000)
        assertEquals(34f / 56, v.sweep!!, 1e-6f)
        assertNull("a stream that ended has no sweep under way", pack.view(9_300_000_000, stopped = true).sweep)
        assertTrue("the sweep drawn is the first", v.cells.all { it.value == 3300f })
    }

    @Test
    fun aStreamThatStoppedFades() {
        val pack = pack()
        pack.accept(counts(3, 1))
        pack.accept(cell(0, listOf(3300, 3301, 3302), at = 0))
        pack.accept(cell(0, listOf(3300, 3301, 3302), at = 1_000_000_000))
        assertTrue("the newest page within 10 s", pack.view(11_000_000_000).cells.all { it.fresh })
        assertTrue("older, all faint", pack.view(11_000_000_001).cells.none { it.fresh })
    }

    // ------------------------------------------------------------------ the reference and the scale

    private fun CellChart.sweep(vararg values: Int) = apply { update(values.toList(), sweep = true) }

    @Test
    fun theReferenceIsHeldWithinAMillivolt() {
        val c = CellChart()
        c.sweep(3282, 3283, 3282, 3283, 3283)
        assertEquals("the first snapshot's mean, rounded", 3283, c.ref)
        c.sweep(3282, 3282, 3282, 3283)
        assertEquals("3 282,25 is within 1 mV", 3283, c.ref)
        c.sweep(3282, 3282)
        assertEquals("exactly 1 mV away still holds", 3283, c.ref)
        c.sweep(3281, 3282)
        assertEquals("1,5 mV away moves it to the mean", 3282, c.ref)
        c.sweep(3279, 3279, 3278)
        assertEquals(3279, c.ref)
        c.update(emptyList(), sweep = true)
        assertNull(c.ref)
        assertEquals(emptyList<Int>(), c.grid())
    }

    @Test
    fun theFirstSnapshotSetsTheReferenceAfresh() {
        val c = CellChart()
        // the first sweep filling: the mean of what came, held
        c.update(listOf(3290, 3290), sweep = false)
        assertEquals(3290, c.ref)
        c.update(listOf(3290, 3290, 3289), sweep = false)
        assertEquals(3290, c.ref)
        c.update(listOf(3290, 3290, 3289, 3289, 3289, 3289), sweep = false)
        assertEquals("3 289,3 is within 1 mV of 3 290", 3290, c.ref)
        c.update(listOf(3290, 3290, 3289, 3289, 3289, 3289), sweep = true)
        assertEquals("the sweep drawn: its mean, rounded", 3289, c.ref)
    }

    @Test
    fun theScaleWidensAtOnceAndNarrowsAfterThreeSweeps() {
        val c = CellChart()
        c.sweep(3280, 3282)
        assertEquals(3281, c.ref)
        assertEquals("1 mV off: s = 1", listOf(-2, -1, 0, 1, 2), c.grid())
        c.sweep(3278, 3284)
        assertEquals(3281, c.ref)
        assertEquals("3 mV off: s = 2", 2, c.step)
        c.sweep(3274, 3288)
        assertEquals("7 mV off: s = 5 at once", 5, c.step)
        c.sweep(3280, 3282)
        c.sweep(3280, 3282)
        assertEquals("narrower twice: still 5", 5, c.step)
        c.sweep(3276, 3286)
        assertEquals("5 mV off: s = 5 sufficed again, the count starts over", 5, c.step)
        c.sweep(3278, 3284)
        c.sweep(3280, 3282)
        c.sweep(3281, 3281)
        assertEquals("three narrower sweeps: the widest of them", 2, c.step)
        assertEquals(listOf(-4, -2, 0, 2, 4), c.grid())
        // while the first sweep fills, the scale only widens
        val f = CellChart()
        f.update(listOf(3270, 3290), sweep = false)
        assertEquals(5, f.step)
        repeat(5) { f.update(listOf(3280, 3280), sweep = false) }
        assertEquals(5, f.step)
        f.update(listOf(3280, 3281), sweep = true)
        assertEquals("the first snapshot sets it afresh", 1, f.step)
    }

    @Test
    fun theGridIsOffsetsAndTheReferenceByItsValue() {
        val pack = pack()
        pack.accept(counts(3, 1))
        pack.accept(cell(0, listOf(3282, 3285, 3279)))
        pack.accept(cell(0, listOf(3282, 3285, 3279), at = 1_000_000_000))
        val v = pack.view(1_100_000_000)
        assertEquals(3282, v.cellRef)
        assertEquals(listOf(-4, -2, 0, 2, 4), v.cellGrid)
        assertEquals(listOf("−4", "−2", "3 282", "+2", "+4"), v.cellGridText)
        assertEquals(listOf(1, 2, 5, 10, 20, 50, 100, 200, 500), CellChart.STEPS)
        val far = CellChart().sweep(1000, 5000)
        assertEquals("never wider than 2 × 500", 500, far.step)
        assertEquals(listOf("−1 000", "−500", "3 000", "+500", "+1 000"), pack().let { p ->
            p.accept(counts(2, 1))
            p.accept(cell(0, listOf(2500, 3500)))
            p.accept(cell(0, listOf(2500, 3500), at = 1))
            p.view(2).cellGridText
        })
    }

    // ------------------------------------------------------------------ what is a value

    @Test
    fun aCountIsOneTo253() {
        val a = PageArray(1, 6, true, PackCells::plausibleProbe)
        assertFalse(a.count(0))
        assertFalse("254 is the firmware's invalid", a.count(254))
        assertFalse("255 is the firmware's invalid", a.count(255))
        assertNull(a.count)
        assertTrue(a.count(253))
        assertEquals(253, a.count)
        val pack = pack()
        pack.accept(counts(166, 14))
        pack.accept(BmsPage(BmsPage.COUNTS, byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0, 0, 0, 0, 0), 1))
        assertEquals("an invalid count leaves the one before", 166, pack.cells.count)
        assertEquals(14, pack.probes.count)
    }

    @Test
    fun aCellOutsideTheScalarReadersRangeIsNoValue() {
        val a = cells(9).feed(
            cell(0, listOf(0xFFFE, 0xFFFF, 0)),
            cell(1, listOf(999, 1000, 5000)),
            cell(2, listOf(5001, 3300, 0x8000)),
        )
        assertEquals(listOf(null, null, null, null, 1000, 5000, null, 3300, null), a.codes())
        assertTrue(PackCells.plausibleCell(3282))
        assertFalse(PackCells.plausibleCell(0xFFFE))
    }

    @Test
    fun aProbesMarkersAreNoValueAndItsZeroIsLeftOutOfTheAggregate() {
        val pack = pack()
        pack.accept(counts(1, 6))
        pack.accept(cell(0, listOf(3300)))
        pack.accept(page(0, listOf(0xFE, 0xFF, 0, 50, 56, 53)))
        val v = pack.view(100_000_000)
        assertEquals(listOf("—", "—", "−40", "10", "16", "13"), v.probes.map { it.text })
        assertEquals(listOf(null, null, -40f, 10f, 16f, 13f), v.probes.map { it.value })
        assertEquals(listOf("МИН" to "10 °C", "МАКС" to "16 °C", "СРЕДН." to "13,0 °C"), v.probeStats.map { it.label to it.value })
        assertEquals("the lowest the aggregate counts", 3, v.probeMin)
        assertEquals(4, v.probeMax)
    }

    // ------------------------------------------------------------------ the pause

    @Test
    fun pausedTheDrawnSweepStays() {
        val pack = pack()
        pack.accept(counts(6, 1))
        pack.accept(cell(0, listOf(3300, 3300, 3300), at = 0))
        pack.accept(cell(1, listOf(3301, 3301, 3301), at = 100_000_000))
        pack.accept(cell(0, listOf(3296, 3296, 3296), at = 600_000_000))
        val running = pack.view(700_000_000)
        assertEquals(0.5f, running.sweep)
        assertFalse(running.paused)

        // 07:36:12.6 on the wall at the pause; the page drawn last came 0,6 s before it.
        pack.pause(700_000_000, wallMillis = (7 * 3600 + 36 * 60 + 12) * 1000L + 600)
        assertFalse("nothing is taken while paused", pack.accept(cell(1, listOf(3200, 3200, 3200), at = 800_000_000)))
        val held = pack.view(60_000_000_000)
        assertTrue(held.paused)
        assertNull(held.sweep)
        assertEquals("пауза · снимок 07:36:12", held.age)
        assertEquals("the view as at the pause", running.cells, held.cells)

        // Resumed: the held sweep is old until one refreshes it; the one under way at the pause is gone.
        pack.resume()
        pack.accept(cell(1, listOf(3290, 3290, 3290), at = 70_000_000_000))
        val resumed = pack.view(70_100_000_000)
        assertFalse(resumed.paused)
        assertEquals(0.5f, resumed.sweep)
        assertTrue(resumed.cells.none { it.fresh })
        assertEquals(List(3) { 3300f } + List(3) { 3301f }, resumed.cells.map { it.value })
        pack.accept(cell(0, listOf(3291, 3291, 3291), at = 70_500_000_000))
        val next = pack.view(70_600_000_000)
        assertEquals("the tail after the pause refreshed page 1 only",
            listOf(3300f, 3300f, 3300f, 3290f, 3290f, 3290f), next.cells.map { it.value })
        assertEquals(listOf(false, false, false, true, true, true), next.cells.map { it.fresh })
    }

    @Test
    fun pausedWhileTheFirstSweepFillsWhatCameStays() {
        val pack = pack()
        pack.accept(counts(6, 1))
        pack.accept(cell(0, listOf(3300, 3300, 3300), at = 0))
        pack.pause(100_000_000, wallMillis = 100)
        val held = pack.view(100_000_000)
        assertEquals(listOf(3300f, 3300f, 3300f, null, null, null), held.cells.map { it.value })
        assertEquals(listOf(true, true, true, false, false, false), held.cells.map { it.fresh })
        assertEquals("пауза · снимок 00:00:00", held.age)
        assertEquals("nothing drawn, no time", "пауза", pack().apply { pause(0, 0) }.view(0).age)
    }

    // ------------------------------------------------------------------ the real sweeps

    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    /** The correlation window: its 43A records and its complete 43D and 43C sweeps. */
    private val window: JSONObject by lazy {
        JSONObject(File(root, "research/vehicle-diagnostics/battery-page-telemetry-20261021.json").readText())
            .getJSONArray("inputs").getJSONObject(1)
    }

    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }

    private fun cycle(kind: Int, index: Int): JSONObject =
        window.getJSONObject("arrays").getJSONObject(if (kind == BmsPage.CELLS) "0x43d" else "0x43c").getJSONArray("cycles").getJSONObject(index)

    /** A cycle's pages back as the BMS sent them, each at its own time; 43D's last byte was not kept. */
    private fun replay(kind: Int, index: Int = 0): List<BmsPage> {
        val cycle = cycle(kind, index)
        val slots = if (kind == BmsPage.CELLS) 3 else 6
        val fill = if (kind == BmsPage.CELLS) 0xFFFF else 0xFF
        val words = cycle.getJSONArray("rawCodes").ints() + cycle.getJSONArray("padding").ints()
        val records = cycle.getJSONArray("pageRecords")
        return (0 until records.length()).map { i ->
            val r = records.getJSONObject(i)
            val n = r.getInt("page")
            page(n, words.subList(n * slots, minOf(words.size, (n + 1) * slots)).ifEmpty { listOf(fill) }, kind, r.getLong("tNs"))
        }
    }

    private fun counts43A(): BmsPage {
        val meta = window.getJSONObject("metadata43A").getJSONArray("records").getJSONObject(0)
        return BmsPage(BmsPage.COUNTS, meta.getString("payload").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), meta.getLong("tNs"))
    }

    @Test
    fun aRealSweepOfEachArray() {
        val cellCycle = cycle(BmsPage.CELLS, 0)
        val probeCycle = cycle(BmsPage.PROBES, 0)
        val pack = pack()
        pack.accept(counts43A())
        (replay(BmsPage.CELLS) + replay(BmsPage.PROBES)).sortedBy { it.receivedNanos }.forEach(pack::accept)
        // the next sweep's first page ends the first
        val next = replay(BmsPage.CELLS, 1).first()
        pack.accept(next)

        assertEquals(166, pack.cells.count)
        assertEquals(14, pack.probes.count)
        assertEquals(cellCycle.getJSONArray("rawCodes").ints(), pack.cells.codes())
        assertEquals(probeCycle.getJSONArray("rawCodes").ints(), pack.probes.codes())
        assertEquals(1, pack.cells.sweeps)
        assertEquals(cellCycle.getLong("startNs"), pack.cells.sweepStartNanos)

        val view = pack.view(next.receivedNanos + 400_000_000L)
        assertEquals("НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · 166", view.cellsHead)
        assertEquals("ТЕМПЕРАТУРА ДАТЧИКОВ · 14", view.probesHead)
        assertEquals("обход 5,5 с · 0,4 с назад", view.age)
        assertEquals(166, view.cells.size)
        assertTrue(view.cells.all { it.fresh })
        // The window's first sweep: 3310…3313 mV; probes 54…56 raw, 14…16 °C.
        assertEquals(listOf("МИН" to "3 310", "МАКС" to "3 313"), view.cellStats.take(2).map { it.label to it.value })
        assertEquals("РАЗБРОС" to "3", view.cellStats[3].let { it.label to it.value })
        assertEquals(listOf("МИН" to "14 °C", "МАКС" to "16 °C"), view.probeStats.take(2).map { it.label to it.value })
        val raw = cellCycle.getJSONArray("rawCodes").ints()
        assertEquals(raw.indexOf(raw.min()), view.cellMin)
        assertEquals(raw.indexOf(raw.max()), view.cellMax)
        assertEquals(Figures.number(raw.average(), 1), view.cellStats[2].value)
        assertEquals(Figures.number(raw[0].toDouble(), 0), view.cells[0].text)
        assertEquals(probeCycle.getJSONArray("rawCodes").ints()[0] - 40f, view.probes[0].value)
        assertEquals("the mean 3 311,4, rounded", 3311, view.cellRef)
        assertEquals("2 mV the furthest: s = 1", listOf(-2, -1, 0, 1, 2), view.cellGrid)
    }

    /**
     * Three sweeps in a row as they came, the pack moving 5 mV between the first two: half-way
     * through a sweep the chart is all the sweep before - no step where one meets the other - and
     * the reference follows the mean only when a sweep ends.
     */
    @Test
    fun threeRealSweepsDrawnOneAtATime() {
        val cycles = (0 until 3).map { cycle(BmsPage.CELLS, it).getJSONArray("rawCodes").ints() }
        val pages = (0 until 3).map { replay(BmsPage.CELLS, it) }
        val pack = pack()
        pack.accept(counts43A())
        pages[0].forEach(pack::accept)
        pages[1].take(31).forEach(pack::accept)
        val half = pack.view(pages[1][30].receivedNanos + 100_000_000L)
        assertEquals("half-way through the second, the first", cycles[0], half.cells.map { it.value!!.toInt() })
        assertEquals(31f / 56, half.sweep!!, 1e-6f)
        assertEquals(3311, half.cellRef)
        assertTrue(half.cells.all { it.fresh })

        pages[1].drop(31).forEach(pack::accept)
        pages[2].take(1).forEach(pack::accept)
        val second = pack.view(pages[2][0].receivedNanos + 100_000_000L)
        assertEquals(cycles[1], second.cells.map { it.value!!.toInt() })
        assertEquals("3 306,1 is 4,9 mV from 3 311: the reference moves", 3306, second.cellRef)
        assertEquals(listOf(-2, -1, 0, 1, 2), second.cellGrid)
        assertEquals("обход 5,5 с · 0,1 с назад", second.age)
        assertTrue(second.cells.all { it.fresh })
        assertTrue("every cell lower than in the first", cycles[1].indices.all { cycles[1][it] < cycles[0][it] })
    }

    // ------------------------------------------------------------------ the view's words

    @Test
    fun theBoardsWords() {
        val pack = pack()
        val cellValues = listOf(3284, 3283, 3280, 3285, 3282, 3282)
        val probeValues = listOf(50, 53, 56, 51, 54, 53, 56)
        pack.accept(counts(6, 7))
        pack.accept(cell(0, cellValues.take(3), at = 1_000_000_000))
        pack.accept(cell(1, cellValues.drop(3), at = 6_500_000_000))
        pack.accept(page(0, probeValues.take(6), at = 6_600_000_000))
        pack.accept(page(1, probeValues.drop(6), at = 6_700_000_000))
        val v = pack.view(7_100_000_000)
        assertEquals(listOf("3 284", "3 283", "3 280", "3 285", "3 282", "3 282"), v.cells.map { it.text })
        assertEquals(listOf("МИН" to "3 280", "МАКС" to "3 285", "СРЕДН." to "3 282,7", "РАЗБРОС" to "5"),
            v.cellStats.map { it.label to it.value })
        assertEquals(listOf("10", "13", "16", "11", "14", "13", "16"), v.probes.map { it.text })
        assertEquals(listOf("МИН" to "10 °C", "МАКС" to "16 °C", "СРЕДН." to "13,3 °C"), v.probeStats.map { it.label to it.value })
        assertEquals(2, v.cellMin)
        assertEquals(3, v.cellMax)
        assertEquals("the first of equal extremes", 0, v.probeMin)
        assertEquals(2, v.probeMax)
        assertEquals("обход 5,5 с · 0,4 с назад", v.age)
        assertEquals("the first sweep filling: its first page's mean, held", 3282, v.cellRef)
        assertEquals(listOf("−4", "−2", "3 282", "+2", "+4"), v.cellGridText)
    }

    @Test
    fun beforeAnyPageAndAfterTheEnd() {
        val pack = pack()
        val empty = pack.view(0)
        assertEquals("ждём страницы BMS", empty.age)
        assertEquals("НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · —", empty.cellsHead)
        assertEquals(emptyList<CellReading>(), empty.cells)
        assertNull(empty.cellRef)
        assertEquals(emptyList<Int>(), empty.cellGrid)
        assertNull(empty.cellMin)
        assertNull(empty.sweep)
        assertEquals("—", empty.cellStats[0].value)
        assertEquals("поток BMS прерван", pack.view(0, stopped = true).age)

        pack.accept(cell(3, listOf(3300), at = 1_000_000_000))
        val partial = pack.view(1_200_000_000)
        assertEquals("before 43A: the length that came", 10, partial.cells.size)
        assertEquals("обход не завершён · 0,2 с назад", partial.age)
        assertEquals("—", partial.cells[0].text)
        assertNull(partial.cells[0].value)
        assertFalse("no value, nothing fresh", partial.cells[0].fresh)
        assertTrue(partial.cells[9].fresh)
        assertEquals("поток BMS прерван · 12 с назад", pack.view(13_000_000_000, stopped = true).age)
        assertFalse("a stream that stopped fades", pack.view(13_000_000_000).cells[9].fresh)
    }

    @Test
    fun ageWords() {
        assertEquals("0,4 с", PackCells.seconds(400_000_000))
        assertEquals("9,9 с", PackCells.seconds(9_900_000_000))
        assertEquals("12 с", PackCells.seconds(12_300_000_000))
        assertEquals("3 мин", PackCells.seconds(200_000_000_000))
        assertEquals("0,0 с", PackCells.seconds(-5))
    }
}
