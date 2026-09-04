package dev.denza.apps.feature.vehicle

/**
 * Turns a stream of (odometer, pack power) samples into the consumption bars
 * the cluster dashboard draws.
 *
 * Each bar is energy per fixed slice of road, closed one at a time. They
 * are the honest record: everything the car spent over that slice counts,
 * including the minutes it stood still inside it.
 *
 * Distance comes from the vehicle's own odometer rather than GNSS, so the
 * dashboard needs no location permission.
 * Energy is integrated from pack power over real elapsed time, including
 * regeneration, which is why a downhill slice can read negative.
 *
 * Pure Kotlin, no Android imports: the accumulation rules are unit tested. What
 * happens to a closed bar afterwards is the caller's business - [onBucketClosed]
 * is how the journal on disk hears about one without this class learning what a
 * file is.
 */
/** One closed bar and the odometer reading it closed at. */
internal data class ConsumptionSample(val odometerKm: Double, val value: Double)

internal class ConsumptionLog(
    private val bucketKm: Double = DEFAULT_BUCKET_KM,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onBucketClosed: (ConsumptionSample) -> Unit = {},
) {

    private val closed = ArrayDeque<Double>()
    private var lastOdometerKm: Double? = null
    private var pendingKm = 0.0
    private var pendingKwh = 0.0

    /** Closed bars, oldest first. */
    val buckets: List<Double> get() = closed.toList()

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
            // The car was driven with the dashboard closed, or the reading moved in a
            // way a sample interval cannot explain. Drop the open work rather than
            // spreading unknown energy across unknown distance.
            dropOpenWork()
            return
        }

        val km = deltaKm.coerceAtLeast(0.0)
        val kwh = if (powerKw != null && dtSeconds > 0.0 && dtSeconds <= MAX_GAP_SECONDS) {
            powerKw * dtSeconds / 3600.0
        } else {
            0.0
        }

        pendingKm += km
        pendingKwh += kwh
        if (pendingKm >= bucketKm - KM_EPSILON) {
            val value = pendingKwh / pendingKm * 100.0
            closed.addLast(value)
            while (closed.size > capacity) closed.removeFirst()
            pendingKm = 0.0
            pendingKwh = 0.0
            onBucketClosed(ConsumptionSample(odometerKm, value))
        }
    }

    fun reset() {
        closed.clear()
        lastOdometerKm = null
        dropOpenWork()
    }

    /**
     * Seed the bars from a journal, dropping anything the odometer says is older
     * than [windowKm] of road.
     *
     * The odometer is what makes this safe rather than the clock. A car that was
     * driven for two hundred kilometres with the app closed leaves a journal whose
     * newest entry is nowhere near the last thirty kilometres, and time cannot
     * tell you that - the entries could be five minutes old and still describe a
     * different piece of road.
     *
     * Returns false when the journal describes a car this is not: an entry ahead
     * of the current odometer means the reading went backwards, which happens on a
     * cluster swap or a journal carried between vehicles, and there is nothing
     * sensible to salvage.
     */
    fun restore(samples: List<ConsumptionSample>, odometerKm: Double, windowKm: Double): Boolean {
        if (samples.any { it.odometerKm > odometerKm + KM_EPSILON }) return false
        val floor = odometerKm - windowKm
        closed.clear()
        samples.asSequence()
            .filter { it.odometerKm > floor + KM_EPSILON }
            .map { it.value }
            .toCollection(closed)
        while (closed.size > capacity) closed.removeFirst()
        return true
    }

    private fun dropOpenWork() {
        pendingKm = 0.0
        pendingKwh = 0.0
    }

    companion object {
        /**
         * One odometer tick per bar, which is as fine as this car can be asked.
         *
         * The dashboard shows three kilometres, while the journal retains thirty
         * at the same resolution so recent history survives a restart.
         */
        const val DEFAULT_BUCKET_KM = 0.1

        /**
         * Thirty kilometres of road retained for restart continuity.
         *
         * Three hundred doubles is about two and a half kilobytes; the cost of
         * this decision is not memory, it is the journal write that keeps it
         * across a restart. `ConsumptionWindowTest` holds the display and
         * retention sizes apart explicitly.
         */
        const val DEFAULT_CAPACITY = 300

        const val RETENTION_KM = DEFAULT_CAPACITY * DEFAULT_BUCKET_KM

        /** A longer sample gap means the dashboard was asleep; do not integrate it. */
        private const val MAX_GAP_SECONDS = 8.0

        /** More road than any sample interval can cover; treat as a re-anchor. */
        private const val MAX_JUMP_KM = 5.0

        /**
         * The odometer arrives in tenths of a kilometre and the differences are
         * accumulated in doubles, so two 0.1 km steps land a hair under 0.2.
         * Every distance threshold is compared with this slack.
         */
        private const val KM_EPSILON = 1e-6
    }
}
