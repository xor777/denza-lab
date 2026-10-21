package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.BmsPage
import dev.denza.apps.feature.vehicle.VehicleKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One of the BMS's paged arrays (architecture 6.3), assembled by the rules of
 * `research/vehicle-diagnostics/decode_battery_pages.py`: a page is byte 0 and [slots] words of
 * [width] bytes, little-endian; position = page × [slots] + slot, the BMS's own order. A page past
 * the array - `FD` of the cells, which carries the stock policy's metadata - never enters it; a word
 * that is not [plausible] (a fill or marker, a cell outside the scalar readers' range) is no value.
 *
 * **A sweep at a time.** Under load the whole pack moves a few millivolts a sweep, so pages drawn as
 * they come put two sweeps side by side. What [codes] gives changes once a sweep instead: a sweep is
 * a run of pages each numbered above the one before, and a page numbered not above the previous
 * (page 0 again, or any wrap) ends it. The ended sweep's pages are laid over the snapshot before;
 * a position it did not refresh keeps its value and is not [refreshed]. Before the first sweep ends,
 * what came is drawn as it comes.
 *
 * Separately, a complete sweep as the decoder judges it - pages 0, 1, … in order up to the count -
 * gives the age line its sweep's span.
 */
internal class PageArray(
    private val width: Int,
    private val slots: Int,
    /** 43C pages sum to 255 mod 256 in every recorded frame (an observed rule; 43D does not follow it). */
    private val checksum: Boolean,
    /** Whether a word is a reading. */
    private val plausible: (Int) -> Boolean,
) {
    // the sweep under way
    private val liveCodes = IntArray(MAX_COUNT)
    private val liveTimes = LongArray(MAX_COUNT)
    private val came = BooleanArray(MAX_COUNT)
    private var lastPage = -1

    // the snapshot drawn
    private val codes = IntArray(MAX_COUNT) { NOTHING }
    private val times = LongArray(MAX_COUNT)
    private val fresh = BooleanArray(MAX_COUNT)

    // the decoder's complete sweep
    private var expected = -1
    private var startedNanos = 0L

    /** The array's length by `43A`; null before it came. */
    var count: Int? = null
        private set

    /** Sweeps ended and laid over the snapshot; 0 while the first one fills. */
    var snapshots = 0
        private set

    /** Pages of the sweep under way. */
    var pagesUnderWay = 0
        private set

    /** Complete sweeps seen; their last one's first page's arrival and its span to the last. */
    var sweeps = 0
        private set
    var sweepStartNanos: Long? = null
        private set
    var sweepSpanNanos: Long? = null
        private set

    /** The newest page that entered the array, drawn or under way. */
    var newestNanos: Long? = null
        private set

    /** The length `43A` gave, 1..253; a different one starts the sweep over and forgets what lies beyond it. */
    fun count(n: Int): Boolean {
        if (n !in 1..MAX_COUNT || n == count) return false
        count = n
        expected = -1
        for (i in n until MAX_COUNT) {
            codes[i] = NOTHING
            fresh[i] = false
            came[i] = false
        }
        return true
    }

    /** Takes a page; true when it ended a sweep and the snapshot changed. */
    fun accept(payload: BmsPage): Boolean {
        if (payload.size != PAGE_BYTES) return broken()
        val page = payload[0]
        val pages = count?.let(::pagesOf) ?: MAX_PAGES
        if (page >= pages) return broken()
        if (checksum && (0 until PAGE_BYTES).sumOf { payload[it] } % 256 != 255) return broken()
        val at = payload.receivedNanos
        val ended = lastPage >= 0 && page <= lastPage
        if (ended) publish()
        val limit = count ?: MAX_COUNT
        for (slot in 0 until slots) {
            val position = page * slots + slot
            if (position >= limit) break
            var word = 0
            for (b in 0 until width) word = word or (payload[1 + slot * width + b] shl (8 * b))
            liveCodes[position] = word
            liveTimes[position] = at
            came[position] = true
        }
        lastPage = page
        pagesUnderWay++
        newestNanos = at
        complete(page, pages, at)
        return ended
    }

    /** The decoder's rule: pages 0, 1, … in order up to the count make a complete sweep. */
    private fun complete(page: Int, pages: Int, at: Long) {
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

    private fun broken(): Boolean {
        expected = -1
        return false
    }

    /** The sweep under way laid over the snapshot: what it refreshed is fresh, the rest is not. */
    private fun publish() {
        for (i in 0 until MAX_COUNT) {
            fresh[i] = came[i]
            if (came[i]) {
                codes[i] = liveCodes[i]
                times[i] = liveTimes[i]
            }
            came[i] = false
        }
        lastPage = -1
        pagesUnderWay = 0
        snapshots++
    }

    /**
     * Paused: what is drawn stays - the first sweep's pages so far become the snapshot - and the
     * sweep under way is dropped, so nothing from before the pause joins a sweep after it.
     */
    fun hold() {
        if (snapshots == 0 && came.any { it }) publish()
        for (i in 0 until MAX_COUNT) came[i] = false
        lastPage = -1
        pagesUnderWay = 0
        expected = -1
    }

    /** Resumed: the held snapshot is old until a sweep refreshes it. */
    fun release() {
        for (i in 0 until MAX_COUNT) fresh[i] = false
    }

    private fun drawn(i: Int): Int = if (snapshots > 0) codes[i] else if (came[i]) liveCodes[i] else NOTHING

    private fun code(i: Int): Int? = drawn(i).takeIf { it != NOTHING && plausible(it) }

    /** [count] long once `43A` said it, before that up to the last position that holds a value. */
    private fun length(): Int = count ?: ((MAX_COUNT - 1 downTo 0).firstOrNull { code(it) != null }?.plus(1) ?: 0)

    /** The array as drawn; null where no page came or the word is no value. */
    fun codes(): List<Int?> = (0 until length()).map(::code)

    /** Whether the sweep that ended last refreshed each drawn position; before the first, whether it came. */
    fun refreshed(): List<Boolean> = (0 until length()).map { if (snapshots > 0) fresh[it] else came[it] }

    /** The newest page among those drawn. */
    fun drawnNanos(): Long? = (0 until length())
        .filter { drawn(it) != NOTHING }
        .maxOfOrNull { if (snapshots > 0) times[it] else liveTimes[it] }

    /** The sweep under way as a share of the array's pages; null before `43A`. */
    fun progress(): Float? = count?.let { (pagesUnderWay.toFloat() / pagesOf(it)).coerceAtMost(1f) }

    private fun pagesOf(n: Int) = (n + slots - 1) / slots

    companion object {
        const val PAGE_BYTES = 8
        /** The largest count `43A` gives as a count: 254 and 255 are the firmware's invalid (architecture 6.3). */
        const val MAX_COUNT = 253
        private const val MAX_PAGES = 0xFD
        private const val NOTHING = -1
    }
}

/**
 * The cells' chart (architecture 6.3, board `scanner-sys-pack-cells`): millivolts from a reference
 * held to a whole millivolt, on a grid symmetric about it. Updated when what is drawn changes;
 * `sweep` is true when a sweep's snapshot did it.
 *
 * - The reference is the drawn cells' mean rounded to a millivolt, and stays until that mean moves
 *   more than [HOLD_MV] away from it. The first snapshot sets it afresh.
 * - The grid is −2s, −s, 0, +s, +2s with s from [STEPS], the smallest whose 2s reaches the cell
 *   furthest from the reference. A wider s is taken at once; a narrower one only when it has
 *   sufficed for [NARROW_AFTER] snapshots in a row, and then the widest of those.
 */
internal class CellChart {
    var ref: Int? = null
        private set
    var step: Int? = null
        private set
    private var settled = false
    private var narrowing = 0
    private var narrowTo = 0

    fun update(values: List<Int>, sweep: Boolean) {
        if (values.isEmpty()) {
            ref = null
            step = null
            settled = false
            narrowing = 0
            return
        }
        val first = sweep && !settled
        val mean = values.average()
        val held = ref
        val r = if (first || held == null || abs(mean - held) > HOLD_MV) mean.roundToInt() else held
        ref = r
        val far = values.maxOf { abs(it - r) }
        val fit = STEPS.firstOrNull { 2 * it >= far } ?: STEPS.last()
        val s = step
        when {
            first || s == null || fit > s -> {
                step = fit
                narrowing = 0
            }
            !sweep -> Unit
            fit == s -> narrowing = 0
            else -> {
                narrowTo = if (narrowing == 0) fit else maxOf(narrowTo, fit)
                narrowing++
                if (narrowing >= NARROW_AFTER) {
                    step = narrowTo
                    narrowing = 0
                }
            }
        }
        if (sweep) settled = true
    }

    /** −2s, −s, 0, +s, +2s; empty before a cell. */
    fun grid(): List<Int> = step?.let { s -> listOf(-2 * s, -s, 0, s, 2 * s) }.orEmpty()

    companion object {
        val STEPS = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500)
        const val HOLD_MV = 1.0
        const val NARROW_AFTER = 3
    }
}

/**
 * «Тяговая батарея · Ячейки» (architecture 6.3, board `scanner-sys-pack-cells`): the BMS's pages
 * a sweep at a time, [view] as the screen draws them. Pure; the session feeds it pages and the time.
 * [clock] prints the paused snapshot's time.
 */
internal class PackCells(private val clock: ScanClock) {
    val cells = PageArray(width = 2, slots = 3, checksum = false, plausible = ::plausibleCell)
    val probes = PageArray(width = 1, slots = 6, checksum = true, plausible = ::plausibleProbe)
    private val chart = CellChart()
    private var newestNanos: Long? = null
    private var held: Held? = null

    /** When «Пауза» was pressed: on the car's clock and on the wall's. */
    private class Held(val atNanos: Long, val wallMillis: Long)

    val paused: Boolean get() = held != null

    /** Takes a page; true when a sweep ended and what is drawn changed. Nothing is taken while paused. */
    fun accept(page: BmsPage): Boolean {
        if (held != null) return false
        val ended = when (page.canId) {
            BmsPage.COUNTS -> {
                if (page.size >= 2) {
                    if (cells.count(page[0])) chart.update(cellValues(), sweep = false)
                    probes.count(page[1])
                }
                false
            }
            BmsPage.CELLS -> cells.accept(page).also { ended ->
                if (ended || cells.snapshots == 0) chart.update(cellValues(), sweep = ended)
            }
            BmsPage.PROBES -> probes.accept(page)
            else -> return false
        }
        newestNanos = maxOf(newestNanos ?: page.receivedNanos, page.receivedNanos)
        return ended
    }

    /** «Пауза»: what is drawn stays as it was at [atNanos]; [wallMillis] is the wall clock then. */
    fun pause(atNanos: Long, wallMillis: Long) {
        if (held != null) return
        held = Held(atNanos, wallMillis)
        cells.hold()
        probes.hold()
    }

    /** «Продолжить»: pages are taken again; the held snapshot is old until a sweep refreshes it. */
    fun resume() {
        if (held == null) return
        held = null
        cells.release()
        probes.release()
    }

    private fun cellValues(): List<Int> = cells.codes().filterNotNull()

    /**
     * The view at [nowNanos] on the car's clock (while paused, at the pause). A reading is fresh
     * when the sweep that ended last refreshed it - before the first sweep ends, everything that
     * came is - and its array's newest page is within [staleNanos] of now, so a stream that stopped
     * fades. [stopped]: the subscription ended, and the age line says so.
     */
    fun view(nowNanos: Long, stopped: Boolean = false): PackCellsView {
        val now = held?.atNanos ?: nowNanos
        val cellCodes = cells.codes()
        val probeCodes = probes.codes()
        val cellFresh = fresh(cells, cellCodes, now)
        val probeFresh = fresh(probes, probeCodes, now)
        val cellValues = cellCodes.filterNotNull()
        // The stock aggregate skips a probe's raw 0; the tile still shows what came.
        val probeTemps = probeCodes.map { code -> code?.takeIf { it != PROBE_SKIPPED }?.minus(PROBE_OFFSET) }
        val probeValues = probeTemps.filterNotNull()
        val cellMin = cellValues.minOrNull()
        val cellMax = cellValues.maxOrNull()
        val probeMin = probeValues.minOrNull()
        val probeMax = probeValues.maxOrNull()
        val ref = chart.ref
        val grid = if (ref != null) chart.grid() else emptyList()
        return PackCellsView(
            cells = cellCodes.mapIndexed { i, code ->
                CellReading(code?.toFloat(), code?.let { Figures.number(it.toDouble(), 0) } ?: NONE, cellFresh[i])
            },
            probes = probeCodes.mapIndexed { i, code ->
                val c = code?.minus(PROBE_OFFSET)
                CellReading(c?.toFloat(), c?.let { Figures.number(it.toDouble(), 0) } ?: NONE, probeFresh[i])
            },
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
            cellRef = ref,
            cellGrid = grid,
            cellGridText = grid.map { d ->
                if (d == 0) Figures.number(ref!!.toDouble(), 0) else Figures.number(d.toDouble(), 0, signed = true)
            },
            cellMin = cellMin?.let(cellCodes::indexOf),
            cellMax = cellMax?.let(cellCodes::indexOf),
            probeMin = probeMin?.let(probeTemps::indexOf),
            probeMax = probeMax?.let(probeTemps::indexOf),
            sweep = if (held != null || stopped) null else cells.progress(),
            paused = held != null,
            age = age(now, stopped),
            cellsHead = "НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · ${cellCodes.size.takeIf { it > 0 } ?: NONE}",
            probesHead = "ТЕМПЕРАТУРА ДАТЧИКОВ · ${probeCodes.size.takeIf { it > 0 } ?: NONE}",
        )
    }

    private fun fresh(array: PageArray, codes: List<Int?>, now: Long): List<Boolean> {
        val newest = array.newestNanos
        val flowing = newest != null && now - newest <= staleNanos(array)
        val refreshed = array.refreshed()
        return codes.indices.map { codes[it] != null && flowing && refreshed[it] }
    }

    /** Two sweeps of the array, and never less than [MIN_STALE_NANOS]. */
    private fun staleNanos(array: PageArray): Long = maxOf(MIN_STALE_NANOS, 2 * (array.sweepSpanNanos ?: 0L))

    /**
     * «обход 5,5 с · 0,4 с назад»: the cells' last complete sweep and the newest page's age;
     * paused, «пауза · снимок 07:36:12» - the wall-clock time of the newest page drawn.
     */
    private fun age(now: Long, stopped: Boolean): String {
        held?.let { h ->
            val drawn = listOfNotNull(cells.drawnNanos(), probes.drawnNanos()).maxOrNull() ?: return PAUSED
            return "$PAUSED · снимок ${clock.seconds(h.wallMillis - (h.atNanos - drawn) / 1_000_000L)}"
        }
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
        /** A probe's raw 0 (−40 °C): the stock aggregate leaves it out of the minimum, maximum and mean. */
        const val PROBE_SKIPPED = 0
        const val MIN_STALE_NANOS = 10_000_000_000L
        const val NONE = "—"
        const val PAUSED = "пауза"

        /** A cell's word: not the markers FFFE/FFFF and within the scalar readers' millivolts. */
        fun plausibleCell(code: Int): Boolean = code != 0xFFFE && code != 0xFFFF && VehicleKind.MILLIVOLT.accepts(code.toDouble())

        /** A probe's code: not the markers FE/FF. */
        fun plausibleProbe(code: Int): Boolean = code != 0xFE && code != 0xFF

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
