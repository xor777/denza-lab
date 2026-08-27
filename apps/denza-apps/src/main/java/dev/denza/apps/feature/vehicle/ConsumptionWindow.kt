package dev.denza.apps.feature.vehicle

/**
 * The fixed three-kilometre consumption chart shown on the cluster.
 *
 * The log keeps one hundred metres per bucket, so this view is simply the last
 * thirty buckets in order. There is no selector and no alternate runtime
 * window: the wider history exists only in [ConsumptionLog] for restart
 * continuity.
 */
internal object ConsumptionWindow {
    const val KM = 3.0

    val buckets: Int get() = (KM / ConsumptionLog.DEFAULT_BUCKET_KM).toInt()

    /** The buckets visible on the chart, oldest first. */
    fun raw(all: List<Double>): List<Double> =
        if (all.size <= buckets) all else all.subList(all.size - buckets, all.size)

    /** How much road the chart actually has, which is less than [KM] until it fills. */
    fun coveredKm(all: List<Double>): Double = raw(all).size * ConsumptionLog.DEFAULT_BUCKET_KM
}
