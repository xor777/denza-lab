package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.BmsPage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack's cells and probes (architecture 6.3): the page rules of
 * `research/vehicle-diagnostics/decode_battery_pages.py` and its tests, ported, and one real sweep of
 * each array from the correlation window of 2026-10-21 (`battery-page-telemetry-20261021.json`).
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

    private fun counts(cells: Int, probes: Int, at: Long = 0) =
        BmsPage(BmsPage.COUNTS, byteArrayOf(cells.toByte(), probes.toByte(), 0x34, 0x12, 0, 0, 0, 0), at)

    private fun PageArray.feed(vararg pages: BmsPage) = apply { pages.forEach(::accept) }
    private fun probes(count: Int) = PageArray(1, 6, true).apply { count(count) }
    private fun cells(count: Int) = PageArray(2, 3, false).apply { count(count) }

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
            page(0, listOf(0x1234, 0x4567, 0x89AB), BmsPage.CELLS),
            page(1, listOf(0xABCD), BmsPage.CELLS),
            page(0xFD, listOf(1, 2, 3), BmsPage.CELLS),
        )
        assertEquals(listOf(0x1234, 0x4567, 0x89AB, 0xABCD), a.codes())
        assertEquals(1, a.sweeps)
    }

    @Test
    fun aPrefixIsShownButNotMergedIntoTheSweep() {
        val a = probes(7)
        a.feed(page(1, listOf(90)))
        assertEquals("what came is shown", 90, a.codes()[6])
        assertEquals(0, a.sweeps)
        a.feed(page(0, listOf(1, 2, 3, 4, 5, 6), at = 2), page(1, listOf(7), at = 3))
        assertEquals((1..7).toList(), a.codes())
        assertEquals(1, a.sweeps)
        assertEquals(2L, a.sweepStartNanos)
        assertEquals(1L, a.sweepSpanNanos)
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
        val p = page(0, listOf(3300), BmsPage.CELLS)
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
        val a = PageArray(2, 3, false).feed(page(0, listOf(3300, 3301, 3302), BmsPage.CELLS), page(1, listOf(3303), BmsPage.CELLS))
        assertNull(a.count)
        assertEquals(0, a.sweeps)
        assertEquals(listOf(3300, 3301, 3302, 3303), a.codes())
        a.count(4)
        a.feed(page(0, listOf(1, 2, 3), BmsPage.CELLS), page(1, listOf(4), BmsPage.CELLS))
        assertEquals(1, a.sweeps)
    }

    // ------------------------------------------------------------------ the real sweep

    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    /** The correlation window: its 43A records and its first complete 43D and 43C sweeps. */
    private val window: JSONObject by lazy {
        JSONObject(File(root, "research/vehicle-diagnostics/battery-page-telemetry-20261021.json").readText())
            .getJSONArray("inputs").getJSONObject(1)
    }

    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }

    /** A cycle's pages back as the BMS sent them, each at its own time; 43D's last byte was not kept. */
    private fun replay(kind: Int): Pair<JSONObject, List<BmsPage>> {
        val cycle = window.getJSONObject("arrays").getJSONObject(if (kind == BmsPage.CELLS) "0x43d" else "0x43c")
            .getJSONArray("cycles").getJSONObject(0)
        val slots = if (kind == BmsPage.CELLS) 3 else 6
        val fill = if (kind == BmsPage.CELLS) 0xFFFF else 0xFF
        val words = cycle.getJSONArray("rawCodes").ints() + cycle.getJSONArray("padding").ints()
        val records = cycle.getJSONArray("pageRecords")
        val pages = (0 until records.length()).map { i ->
            val r = records.getJSONObject(i)
            val n = r.getInt("page")
            page(n, words.subList(n * slots, minOf(words.size, (n + 1) * slots)).ifEmpty { listOf(fill) }, kind, r.getLong("tNs"))
        }
        return cycle to pages
    }

    @Test
    fun aRealSweepOfEachArray() {
        val (cellCycle, cellPages) = replay(BmsPage.CELLS)
        val (probeCycle, probePages) = replay(BmsPage.PROBES)
        val meta = window.getJSONObject("metadata43A").getJSONArray("records").getJSONObject(0)
        val pack = PackCells()
        pack.accept(BmsPage(BmsPage.COUNTS, meta.getString("payload").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), meta.getLong("tNs")))
        (cellPages + probePages).sortedBy { it.receivedNanos }.forEach(pack::accept)

        assertEquals(166, pack.cells.count)
        assertEquals(14, pack.probes.count)
        assertEquals(cellCycle.getJSONArray("rawCodes").ints(), pack.cells.codes())
        assertEquals(probeCycle.getJSONArray("rawCodes").ints(), pack.probes.codes())
        assertEquals(1, pack.cells.sweeps)
        assertEquals(cellCycle.getLong("startNs"), pack.cells.sweepStartNanos)

        val end = cellCycle.getLong("endNs")
        val view = pack.view(end + 400_000_000L)
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
        assertEquals(raw.average().toFloat(), view.cellMean!!, 1e-3f)
        assertEquals(Figures.number(raw[0].toDouble(), 0), view.cells[0].text)
        assertEquals(probeCycle.getJSONArray("rawCodes").ints()[0] - 40f, view.probes[0].value)
        assertTrue(view.cellScale.start <= raw.min() && view.cellScale.endInclusive >= raw.max())
        assertTrue(view.cellGrid.size in 2..5)
        assertEquals(view.cellGrid.map { Figures.number(it.toDouble(), 0) }, view.cellGridText)
    }

    // ------------------------------------------------------------------ the view's words

    @Test
    fun theBoardsWords() {
        val pack = PackCells()
        val cellValues = listOf(3284, 3283, 3280, 3285, 3282, 3282)
        val probeValues = listOf(50, 53, 56, 51, 54, 53, 56)
        pack.accept(counts(6, 7))
        pack.accept(page(0, cellValues.take(3), BmsPage.CELLS, at = 1_000_000_000))
        pack.accept(page(1, cellValues.drop(3), BmsPage.CELLS, at = 6_500_000_000))
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
        assertEquals(3278f..3286f, v.cellScale)
        assertEquals(listOf("3 278", "3 280", "3 282", "3 284", "3 286"), v.cellGridText)
    }

    @Test
    fun beforeAnyPageAndAfterTheEnd() {
        val pack = PackCells()
        val empty = pack.view(0)
        assertEquals("ждём страницы BMS", empty.age)
        assertEquals("НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · —", empty.cellsHead)
        assertEquals(emptyList<CellReading>(), empty.cells)
        assertNull(empty.cellMean)
        assertNull(empty.cellMin)
        assertEquals("—", empty.cellStats[0].value)
        assertEquals("поток BMS прерван", pack.view(0, stopped = true).age)

        pack.accept(page(3, listOf(3300), BmsPage.CELLS, at = 1_000_000_000))
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
