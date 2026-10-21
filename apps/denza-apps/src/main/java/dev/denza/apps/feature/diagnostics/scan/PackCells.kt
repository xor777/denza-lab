package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.BmsPage
import kotlin.math.roundToLong

/**
 * One of the BMS's paged arrays (architecture 6.3), assembled by the rules of
 * `research/vehicle-diagnostics/decode_battery_pages.py`: a page is byte 0 and [slots] words of
 * [width] bytes, little-endian; position = page × [slots] + slot, the BMS's own order. A word of
 * all ones ([fill]) is a fill or missing marker, never a value. A page past the array - `FD` of
 * the cells, which carries the stock policy's metadata - never enters it.
 *
 * Unlike the research decoder, which keeps only complete sweeps, a screen shows what came: every
 * page that is whole writes its slots with its arrival time, in or out of order. Whether a sweep
 * is complete is judged as the decoder judges it - pages 0, 1, … in order up to the count, page 0
 * starting over, any other order, a page past the array, a bad length or (43C) a broken byte sum
 * ending the sweep under way - and gives the last complete sweep's start and length.
 */
internal class PageArray(
    private val width: Int,
    private val slots: Int,
    /** 43C pages sum to 255 mod 256 in every recorded frame (an observed rule; 43D does not follow it). */
    private val checksum: Boolean,
) {
    private val fill = (1 shl (8 * width)) - 1
    private val codes = IntArray(MAX_COUNT) { -1 }
    private val times = LongArray(MAX_COUNT)
    private var expected = -1
    private var startedNanos = 0L

    /** The array's length by `43A`; null before it came. */
    var count: Int? = null
        private set

    /** Complete sweeps seen; their last one's first and last page's arrival. */
    var sweeps = 0
        private set
    var sweepStartNanos: Long? = null
        private set
    var sweepSpanNanos: Long? = null
        private set
    /** The newest page that entered the array. */
    var newestNanos: Long? = null
        private set

    /** The length `43A` gave; a different one starts the sweep over and forgets what lies beyond it. */
    fun count(n: Int) {
        if (n !in 1..MAX_COUNT || n == count) return
        count = n
        expected = -1
        for (i in n until MAX_COUNT) codes[i] = -1
    }

    fun accept(payload: BmsPage) {
        if (payload.size != PAGE_BYTES) return broken()
        val page = payload[0]
        val pages = count?.let { (it + slots - 1) / slots } ?: MAX_PAGES
        if (page >= pages) return broken()
        if (checksum && (0 until PAGE_BYTES).sumOf { payload[it] } % 256 != 255) return broken()
        val at = payload.receivedNanos
        val limit = count ?: MAX_COUNT
        for (slot in 0 until slots) {
            val position = page * slots + slot
            if (position >= limit) break
            var word = 0
            for (b in 0 until width) word = word or (payload[1 + slot * width + b] shl (8 * b))
            codes[position] = word
            times[position] = at
        }
        newestNanos = at
        if (page == 0) {
            expected = 0
            startedNanos = at
        }
        if (page != expected) {
            expected = -1
            return
        }
        expected++
        if (count != null && expected == pages) {
            sweeps++
            sweepStartNanos = startedNanos
            sweepSpanNanos = at - startedNanos
            expected = -1
        }
    }

    private fun broken() {
        expected = -1
    }

    /**
     * The array as it stands: [count] long once `43A` said it, before that up to the last
     * position that held a value. Null where no page came or the BMS sent its fill.
     */
    fun codes(): List<Int?> {
        val n = count ?: (codes.indexOfLast { it >= 0 && it != fill } + 1)
        return (0 until n).map { i -> codes[i].takeIf { it >= 0 && it != fill } }
    }

    /** When each position's page came; 0 where none did. */
    fun times(): List<Long> {
        val n = count ?: (codes.indexOfLast { it >= 0 && it != fill } + 1)
        return (0 until n).map { times[it] }
    }

    companion object {
        const val PAGE_BYTES = 8
        /** The research decoder's bound on a count. */
        const val MAX_COUNT = 300
        private const val MAX_PAGES = 0xFD
    }
}

/**
 * «Тяговая батарея · Ячейки» (architecture 6.3, board `scanner-sys-pack-cells`): the BMS's pages
 * as they come, [view] as the screen draws them. Pure; the session feeds it pages and the time.
 */
internal class PackCells {
    val cells = PageArray(width = 2, slots = 3, checksum = false)
    val probes = PageArray(width = 1, slots = 6, checksum = true)
    private var newestNanos: Long? = null

    fun accept(page: BmsPage) {
        when (page.canId) {
            BmsPage.COUNTS -> if (page.size >= 2) {
                cells.count(page[0])
                probes.count(page[1])
            }
            BmsPage.CELLS -> cells.accept(page)
            BmsPage.PROBES -> probes.accept(page)
            else -> return
        }
        newestNanos = maxOf(newestNanos ?: page.receivedNanos, page.receivedNanos)
    }

    /**
     * The view at [nowNanos] on the car's clock. A reading is fresh when its page is no older than
     * the start of its array's last complete sweep - before the first complete sweep everything
     * that came is - and came within [staleNanos] of now, so a stream that stopped fades.
     * [stopped]: the subscription ended, and the age line says so.
     */
    fun view(nowNanos: Long, stopped: Boolean = false): PackCellsView {
        val cellCodes = cells.codes()
        val probeCodes = probes.codes()
        val cellReadings = readings(cells, cellCodes, nowNanos) { Figures.number(it.toDouble(), 0) }
        val probeReadings = readings(probes, probeCodes, nowNanos) { Figures.number((it - PROBE_OFFSET).toDouble(), 0) }
        val cellValues = cellCodes.filterNotNull()
        val probeValues = probeCodes.map { it?.minus(PROBE_OFFSET) }.filterNotNull()
        val cellMin = cellValues.minOrNull()
        val cellMax = cellValues.maxOrNull()
        val probeMin = probeValues.minOrNull()
        val probeMax = probeValues.maxOrNull()
        val scale = ParamScale.of(cellMin?.toDouble(), cellMax?.toDouble(), null)
        return PackCellsView(
            cells = cellReadings,
            probes = probeReadings,
            cellStats = listOf(
                HeadCounter("МИН", cellMin?.let { Figures.number(it.toDouble(), 0) } ?: NONE),
                HeadCounter("МАКС", cellMax?.let { Figures.number(it.toDouble(), 0) } ?: NONE),
                HeadCounter("СРЕДН.", cellValues.mean()?.let { Figures.number(it, 1) } ?: NONE),
                HeadCounter("РАЗБРОС", if (cellMin != null && cellMax != null) Figures.number((cellMax - cellMin).toDouble(), 0) else NONE),
            ),
            probeStats = listOf(
                HeadCounter("МИН", probeMin?.let { "${Figures.number(it.toDouble(), 0)} °C" } ?: NONE),
                HeadCounter("МАКС", probeMax?.let { "${Figures.number(it.toDouble(), 0)} °C" } ?: NONE),
                HeadCounter("СРЕДН.", probeValues.mean()?.let { "${Figures.number(it, 1)} °C" } ?: NONE),
            ),
            cellScale = scale.low.toFloat()..scale.high.toFloat(),
            cellGrid = scale.lines.map(Double::toFloat),
            cellGridText = scale.lines.map { Figures.number(it, scale.decimals) },
            cellMean = cellValues.mean()?.toFloat(),
            cellMin = cellMin?.let(cellCodes::indexOf),
            cellMax = cellMax?.let(cellCodes::indexOf),
            probeMin = probeMin?.let { probeCodes.indexOf(it + PROBE_OFFSET) },
            probeMax = probeMax?.let { probeCodes.indexOf(it + PROBE_OFFSET) },
            age = age(nowNanos, stopped),
            cellsHead = "НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · ${cellCodes.size.takeIf { it > 0 } ?: NONE}",
            probesHead = "ТЕМПЕРАТУРА ДАТЧИКОВ · ${probeCodes.size.takeIf { it > 0 } ?: NONE}",
        )
    }

    private fun readings(array: PageArray, codes: List<Int?>, now: Long, text: (Int) -> String): List<CellReading> {
        val times = array.times()
        val since = array.sweepStartNanos
        return codes.mapIndexed { i, code ->
            val at = times[i]
            val fresh = code != null && (since == null || at >= since) && now - at <= staleNanos(array)
            CellReading(
                value = code?.let { (if (array === probes) it - PROBE_OFFSET else it).toFloat() },
                text = code?.let(text) ?: NONE,
                fresh = fresh,
            )
        }
    }

    /** Two sweeps of the array, and never less than [MIN_STALE_NANOS]. */
    private fun staleNanos(array: PageArray): Long = maxOf(MIN_STALE_NANOS, 2 * (array.sweepSpanNanos ?: 0L))

    /** «обход 5,5 с · 0,4 с назад»: the cells' last complete sweep and the newest page's age. */
    private fun age(now: Long, stopped: Boolean): String {
        val newest = newestNanos
        val ago = newest?.let { "${seconds(now - it)} назад" }
        if (stopped) return listOfNotNull("поток BMS прерван", ago).joinToString(" · ")
        if (ago == null) return "ждём страницы BMS"
        val sweep = cells.sweepSpanNanos?.let { "обход ${Figures.number(it / 1e9, 1)} с" } ?: "обход не завершён"
        return "$sweep · $ago"
    }

    private fun List<Int>.mean(): Double? = if (isEmpty()) null else sum().toDouble() / size

    companion object {
        /** The probes' codes are °C + 40, as the qualified GETs of the same probes (architecture 6.3). */
        const val PROBE_OFFSET = 40
        const val MIN_STALE_NANOS = 10_000_000_000L
        const val NONE = "—"

        /** «0,4 с» under ten seconds, «12 с» under a minute, «3 мин» after. */
        fun seconds(nanos: Long): String {
            val s = nanos.coerceAtLeast(0) / 1e9
            return when {
                s < 10 -> "${Figures.number((s * 10).roundToLong() / 10.0, 1)} с"
                s < 60 -> "${Figures.number(s, 0)} с"
                else -> "${(s / 60).toLong()} мин"
            }
        }
    }
}
