package dev.denza.apps.feature.vehicle

/**
 * Turns a stream of (odometer, pack power) samples into consumption per fixed
 * slice of road — the bars on the panel's histogram.
 *
 * Distance comes from the vehicle's own odometer rather than GNSS, so the page
 * needs no location permission and keeps working with the trip panel closed.
 * Energy is integrated from pack power over real elapsed time, including
 * regeneration, which is why a downhill slice can read near zero or negative.
 *
 * Each closed bar is `kWh / km * 100` over the distance the odometer actually
 * advanced, so the 0.1 km quantisation of the odometer cannot bias the figure —
 * it only decides when a bar closes.
 *
 * Pure Kotlin, no Android imports: the accumulation rules are unit tested.
 */
internal class ConsumptionLog(
    private val bucketKm: Double = DEFAULT_BUCKET_KM,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val closed = ArrayDeque<Double>()
    private var lastOdometerKm: Double? = null
    private var pendingKm = 0.0
    private var pendingKwh = 0.0

    /** Closed bars, oldest first. */
    val buckets: List<Double> get() = closed.toList()

    /**
     * The open bar once it covers enough road to mean anything, otherwise the
     * last closed one. Null until the first bar closes, so a fresh session shows
     * a dash instead of a number invented from a few metres.
     */
    val current: Double?
        get() = when {
            pendingKm >= MIN_PARTIAL_KM - KM_EPSILON -> pendingKwh / pendingKm * 100.0
            closed.isNotEmpty() -> closed.last()
            else -> null
        }

    /**
     * @param odometerKm the vehicle odometer; null while the read failed
     * @param powerKw pack power, positive out of the battery; null skips energy
     * @param dtSeconds real time since the previous sample
     */
    fun sample(odometerKm: Double?, powerKw: Double?, dtSeconds: Double) {
        if (odometerKm == null) return
        val previous = lastOdometerKm
        lastOdometerKm = odometerKm
        if (previous == null) return

        val deltaKm = odometerKm - previous
        if (deltaKm < -KM_EPSILON || deltaKm > MAX_JUMP_KM) {
            // The car was driven with the panel closed, or the reading moved in a
            // way a sample interval cannot explain. Drop the open bar rather than
            // spreading unknown energy across unknown distance.
            pendingKm = 0.0
            pendingKwh = 0.0
            return
        }

        pendingKm += deltaKm.coerceAtLeast(0.0)
        if (powerKw != null && dtSeconds > 0.0 && dtSeconds <= MAX_GAP_SECONDS) {
            pendingKwh += powerKw * dtSeconds / 3600.0
        }
        if (pendingKm >= bucketKm - KM_EPSILON) {
            closed.addLast(pendingKwh / pendingKm * 100.0)
            while (closed.size > capacity) closed.removeFirst()
            pendingKm = 0.0
            pendingKwh = 0.0
        }
    }

    fun reset() {
        closed.clear()
        lastOdometerKm = null
        pendingKm = 0.0
        pendingKwh = 0.0
    }

    companion object {
        const val DEFAULT_BUCKET_KM = 0.5
        const val DEFAULT_CAPACITY = 14

        /** Below this the open bar is too short to read as consumption. */
        private const val MIN_PARTIAL_KM = 0.1

        /** A longer sample gap means the panel was asleep; do not integrate it. */
        private const val MAX_GAP_SECONDS = 8.0

        /** More road than any sample interval can cover; treat as a re-anchor. */
        private const val MAX_JUMP_KM = 5.0

        /**
         * The odometer arrives in tenths of a kilometre and the differences are
         * accumulated in doubles, so five 0.1 km steps land a hair under 0.5.
         * Every distance threshold is compared with this slack.
         */
        private const val KM_EPSILON = 1e-6
    }
}
