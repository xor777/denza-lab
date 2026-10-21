package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * «Тяговая батарея · Потоковые данные» read as the engine's stream (architecture 6.3, 7.2; board
 * `scanner-sys-pack-live`). While the tab is in front ([DiagnosticsActions.setPackLiveVisible]) every
 * numeric reading keeps its row in a [LiveSeries]: the last minute on the car's clock and the extremes
 * since shown or since «Сбросить мин/макс». A hub signal takes a point per new hub sweep at the
 * sweep's time, a GET a point per new batch at the batch's time; the cells' spread, the one row
 * computed here, follows the cells' minimum and maximum. States and counts say their value and keep
 * no trace. A batch past its horizon is a gap and «н/д», never a held value.
 *
 * «Пауза» ([pause]) takes nothing more: the table, the traces, the norms and the round stand as they
 * were. The norms are judged against the readings last taken - the insulation's floor is the pack's
 * voltage of the same moment.
 */
internal class PackLive {
    private val series: Map<SystemsBuilder.PackReading, LiveSeries> =
        SystemsBuilder.PACK.associateWith { r -> LiveSeries(initial = NA).also { it.decimals = r.decimals } }
    private val spread = LiveSeries(initial = NA)
    private var lastSweep: Long? = null
    private var lastBatch: Long? = null
    private var batchGone = false
    private var held = CarReadings()
    private var tookMs: Long? = null

    /** «Пауза»: nothing is taken until [resume]. */
    var paused = false
        private set

    /**
     * The tab shown again: the extremes start over, the next [take] samples what is there now - not
     * only what is new - and a pause left with the tab is over. The traces stay.
     */
    fun show() {
        paused = false
        (series.values + spread).forEach { it.clearMinMax() }
        lastSweep = null
        lastBatch = null
        batchGone = false
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** «Сбросить мин/макс»: the extremes start at each value now; the traces stay. */
    fun resetMinMax() = (series.values + spread).forEach { it.resetMinMax() }

    /** What [car] brings that this table has not taken yet: a new hub sweep, a new batch, a batch gone stale. */
    fun take(car: CarReadings) {
        if (paused) return
        held = car
        if (car.sweep != lastSweep) {
            lastSweep = car.sweep
            series.forEach { (r, s) -> r.hub?.let { put(r, s, car.atNanos, car.hub[it]) } }
            val min = car.hub[VehicleSignal.CELL_MIN_MV]
            val max = car.hub[VehicleSignal.CELL_MAX_MV]
            if (min != null && max != null) spread.number(car.atNanos, max - min) else spread.gap(car.atNanos, NA)
        }
        if (car.extrasFresh) {
            if (car.extrasAtNanos != lastBatch) {
                lastBatch = car.extrasAtNanos
                batchGone = false
                tookMs = car.extrasTookMs ?: tookMs
                series.forEach { (r, s) -> r.extra?.let { e -> put(r, s, car.extrasAtNanos, car.extras[e]) } }
            }
        } else if (!batchGone) {
            batchGone = true
            series.forEach { (r, s) -> if (r.extra != null) put(r, s, car.atNanos, null) }
        }
    }

    /** A state says its word, a count its number; a reading takes a point, a gap where nothing came. */
    private fun put(r: SystemsBuilder.PackReading, s: LiveSeries, at: Long, v: Double?) {
        val words = r.words
        when {
            words != null -> s.value = SystemsBuilder.word(words, v)
            !r.trace -> s.value = v?.let { Figures.number(it, r.decimals) } ?: NA
            v == null -> s.gap(at, NA)
            else -> s.number(at, v)
        }
    }

    fun view(): PackLiveView = PackLiveView(
        rows = rows(),
        paused = paused,
        round = tookMs?.let { "обход ${Figures.number(it / 1000.0, 1)} с" },
    )

    private fun rows(): List<LiveRow> {
        val rows = SystemsBuilder.PACK.map { r ->
            val s = series.getValue(r)
            val norm = r.norm?.invoke(held)
            val current = s.current
            LiveRow(
                pid = NOT_A_PID,
                name = r.name,
                abbreviation = r.fid,
                value = s.value,
                unit = r.unit,
                min = if (r.trace) s.minText else "",
                max = if (r.trace) s.maxText else "",
                norm = norm?.text ?: "—",
                // Nothing came, or a code the extra refused as the firmware's invalid: not judged.
                outOfNorm = current != null && norm?.outside?.invoke(current) == true,
                source = r.source,
                samples = if (r.trace) s.samples.toList() else emptyList(),
                key = ParamKey.Signal(r.key),
                device = r.device,
            )
        }
        val calc = LiveRow(
            pid = NOT_A_PID, name = SPREAD_NAME, abbreviation = "", value = spread.value, unit = "мВ",
            min = spread.minText, max = spread.maxText, norm = "—", outOfNorm = false, source = CALCULATION,
            samples = spread.samples.toList(), key = null, device = CALCULATION,
        )
        val at = SystemsBuilder.PACK.indexOfFirst { it.hub == VehicleSignal.CELL_MAX_MV } + 1
        return rows.take(at) + calc + rows.drop(at)
    }

    companion object {
        private const val NA = "н/д"
        /** A row of the pack is not a PID: its first column is its device ([LiveRow.device]). */
        const val NOT_A_PID = -1
        /** The spread's device and source: computed here, not read. */
        const val CALCULATION = "расч."
        const val SPREAD_NAME = "Разброс ячеек"

        /** The table as one look at [car] makes it: what the pack's tab shows before it keeps a minute. */
        fun of(car: CarReadings): PackLiveView = PackLive().also { it.take(car) }.view()
    }
}
