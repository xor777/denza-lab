package dev.denza.apps.feature.vehicle

/**
 * The fixed ten-kilometre consumption window: the cluster's petal and the head unit's car page.
 *
 * The log keeps one hundred metres per bucket, so this view is simply the last hundred buckets in
 * order. There is no selector and no alternate runtime window: the wider history exists only in
 * [ConsumptionLog] for restart continuity.
 *
 * Three kilometres until the first drive. Thirty steps in the petal's box read from the seat as
 * «крупные ступеньки», and a mean over the last three traffic lights is not a consumption anybody
 * plans by; the owner asked for ten on both screens. A hundred buckets in the same box is 2.32
 * units a step - under the eye's resolution from 750 mm - so the history reads as a line with a
 * grain rather than as a staircase.
 */
internal object ConsumptionWindow {
    const val KM = 10.0

    val buckets: Int get() = (KM / ConsumptionLog.DEFAULT_BUCKET_KM).toInt()

    /**
     * The buckets visible on the chart, oldest first.
     *
     * A list that is already the window comes back *as itself*, which is what the panel relies on:
     * the snapshot carries the tail rather than the journal's thirty kilometres, so a frame that
     * asks for the window three times allocates nothing to get it. See [ConsumptionLog.window].
     * The retention is three windows, which is what a restart has to bridge, not what is drawn.
     */
    fun raw(all: List<Double>): List<Double> =
        if (all.size <= buckets) all else all.subList(all.size - buckets, all.size)

    /** How much road the chart actually has, which is less than [KM] until it fills. */
    fun coveredKm(all: List<Double>): Double = raw(all).size * ConsumptionLog.DEFAULT_BUCKET_KM

    /**
     * The mean of what was *spent* over the window, which is the figure beside the chart.
     *
     * A bucket that read negative gave energy back, and averaging that in reports a consumption
     * nobody had. Null when nothing was spent at all: the panel draws no figure rather than a zero.
     *
     * Written as a loop over indices because it is the arithmetic behind a number on a live panel:
     * the filter-and-average it replaces built two lists every time it was asked.
     */
    fun mean(all: List<Double>): Double? {
        val window = raw(all)
        var sum = 0.0
        var count = 0
        for (index in window.indices) {
            val value = window[index]
            if (value < 0.0) continue
            sum += value
            count++
        }
        return if (count == 0) null else sum / count
    }
}
