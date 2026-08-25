package dev.denza.apps.feature.vehicle

import kotlin.math.ceil

/**
 * How far back the consumption chart reaches, and how the road folds into bars.
 *
 * Three windows rather than one because they answer three different questions.
 * Three kilometres is "how am I driving right now, on this piece of road".
 * Ten is "how is this stretch going". Thirty is "what kind of drive was this".
 * Each is three times the last, which is enough of a step that switching is
 * worth the tap.
 *
 * The log keeps one resolution - [ConsumptionLog.DEFAULT_BUCKET_KM], a single
 * odometer tick - and a window is a *view* of it. That matters: switching
 * windows must not throw away history or re-record anything, and the average a
 * window states must be computable from the same numbers the bars are drawn
 * from.
 *
 * A window wider than the chart is wide gets folded rather than thinned. Thirty
 * kilometres is three hundred buckets, and three hundred bars in the width a
 * cluster can spare would be three hundred hairlines; ten buckets to a bar makes
 * thirty bars of one kilometre each. Folding is a plain mean because every
 * bucket covers the same distance, which is the whole reason the log stores a
 * fixed slice of road rather than a fixed slice of time.
 */
internal enum class ConsumptionWindow(val km: Double, val label: String) {
    SHORT(3.0, "3 км"),
    MEDIUM(10.0, "10 км"),
    LONG(30.0, "30 км"),
    ;

    /** How many raw buckets this window holds. */
    val buckets: Int get() = (km / ConsumptionLog.DEFAULT_BUCKET_KM).toInt()

    /** How many buckets go into one bar, so the chart stays about [TARGET_BARS] wide. */
    val perBar: Int get() = maxOf(1, ceil(buckets.toDouble() / TARGET_BARS).toInt())

    /** How much road one bar covers. */
    val barKm: Double get() = perBar * ConsumptionLog.DEFAULT_BUCKET_KM

    /** The next window in the cycle, for a control that has room for one tap. */
    val next: ConsumptionWindow get() = entries[(ordinal + 1) % entries.size]

    /**
     * The raw buckets this window covers, oldest first.
     *
     * Fewer than a full window is normal - the journal starts empty and a drive
     * has to fill it - and is not a case worth special handling: a short run
     * simply produces fewer bars.
     */
    fun raw(all: List<Double>): List<Double> =
        if (all.size <= buckets) all else all.subList(all.size - buckets, all.size)

    /**
     * The bars to draw, oldest first.
     *
     * Grouping runs from the newest end, so the newest bar always covers a whole
     * [barKm] of road and any short group is the oldest one. The alternative -
     * grouping from the start - would make the *newest* bar the ragged one, and
     * that is the bar a driver reads.
     */
    fun fold(all: List<Double>): List<Double> {
        val window = raw(all)
        val per = perBar
        if (per == 1 || window.isEmpty()) return window
        val bars = ArrayList<Double>((window.size + per - 1) / per)
        var end = window.size
        while (end > 0) {
            val start = maxOf(0, end - per)
            var sum = 0.0
            for (i in start until end) sum += window[i]
            bars.add(sum / (end - start))
            end = start
        }
        bars.reverse()
        return bars
    }

    /** How much road the chart actually shows, which is less than [km] until it fills. */
    fun coveredKm(all: List<Double>): Double = raw(all).size * ConsumptionLog.DEFAULT_BUCKET_KM

    companion object {
        /**
         * About how many bars a chart should end up with.
         *
         * Set by the narrowest place this chart is drawn - the right third of the
         * cluster, whose chart is a hundred and eighty units wide - rather than by
         * the widest. A bar there is six units, so thirty of them fit with the
         * gaps the design asks for.
         */
        const val TARGET_BARS = 30

        val DEFAULT = SHORT

        fun byName(name: String?): ConsumptionWindow =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
