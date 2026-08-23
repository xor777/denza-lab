package dev.denza.apps.feature.vehicle

/**
 * Turns a stream of (odometer, pack power) samples into the two consumption
 * figures the panel draws, which are deliberately not the same quantity.
 *
 * The **bars** are energy per fixed slice of road, closed one at a time. They
 * are the honest record: everything the car spent over that slice counts,
 * including the minutes it stood still inside it.
 *
 * The **live figure** is a rolling window over the last [WINDOW_KM] of road,
 * and it stops counting once the car has been standing for [STALL_SECONDS].
 * That split is the fix for a real defect: kWh per 100 km has no value at zero
 * speed, so folding standstill energy into it made a parked car's reading crawl
 * — upward on load, downward on charge — as if the car were still driving.
 * Standing still now reads as no live figure at all, and the bars keep the
 * energy.
 *
 * Distance comes from the vehicle's own odometer rather than GNSS, so the page
 * needs no location permission and keeps working with the trip panel closed.
 * Energy is integrated from pack power over real elapsed time, including
 * regeneration, which is why a downhill slice can read negative.
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

    // The live window, oldest first, as two parallel deques so a sample is not
    // boxed into a Pair on every poll.
    private val windowKm = ArrayDeque<Double>()
    private val windowKwh = ArrayDeque<Double>()
    private var rollingKm = 0.0
    private var rollingKwh = 0.0
    private var stillSeconds = 0.0

    /** Closed bars, oldest first. */
    val buckets: List<Double> get() = closed.toList()

    /** True once the odometer has not advanced for [STALL_SECONDS]. */
    val stationary: Boolean get() = stillSeconds >= STALL_SECONDS

    /**
     * Consumption over the last few hundred metres, or null when the number
     * would be a fiction: while the car stands, and before the window holds
     * enough road to divide by. A fresh window falls back to the last closed
     * bar rather than blanking mid-drive.
     */
    val current: Double?
        get() = when {
            stationary -> null
            rollingKm >= MIN_WINDOW_KM - KM_EPSILON -> rollingKwh / rollingKm * 100.0
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
            // way a sample interval cannot explain. Drop the open work rather than
            // spreading unknown energy across unknown distance.
            dropOpenWork()
            return
        }

        val moved = deltaKm > KM_EPSILON
        stillSeconds = if (moved) 0.0 else stillSeconds + dtSeconds.coerceAtLeast(0.0)

        val km = deltaKm.coerceAtLeast(0.0)
        val kwh = if (powerKw != null && dtSeconds > 0.0 && dtSeconds <= MAX_GAP_SECONDS) {
            powerKw * dtSeconds / 3600.0
        } else {
            0.0
        }

        pendingKm += km
        pendingKwh += kwh
        if (pendingKm >= bucketKm - KM_EPSILON) {
            closed.addLast(pendingKwh / pendingKm * 100.0)
            while (closed.size > capacity) closed.removeFirst()
            pendingKm = 0.0
            pendingKwh = 0.0
        }

        if (stationary) return
        windowKm.addLast(km)
        windowKwh.addLast(kwh)
        rollingKm += km
        rollingKwh += kwh
        while (windowKm.size > 1 && rollingKm - windowKm.first() >= WINDOW_KM - KM_EPSILON) {
            rollingKm -= windowKm.removeFirst()
            rollingKwh -= windowKwh.removeFirst()
        }
    }

    fun reset() {
        closed.clear()
        lastOdometerKm = null
        dropOpenWork()
    }

    private fun dropOpenWork() {
        pendingKm = 0.0
        pendingKwh = 0.0
        windowKm.clear()
        windowKwh.clear()
        rollingKm = 0.0
        rollingKwh = 0.0
        stillSeconds = 0.0
    }

    companion object {
        /**
         * Two odometer ticks per bar. Short enough that a hill and the descent
         * after it are separate columns instead of one averaged block, and an
         * exact multiple of the odometer's own 0.1 km step.
         */
        const val DEFAULT_BUCKET_KM = 0.2
        const val DEFAULT_CAPACITY = 24

        /** Road held by the live figure. */
        private const val WINDOW_KM = 0.3

        /** Below this the window is too short to divide by. */
        private const val MIN_WINDOW_KM = 0.1

        /** Standing this long stops the live figure; the bars keep counting. */
        private const val STALL_SECONDS = 5.0

        /** A longer sample gap means the panel was asleep; do not integrate it. */
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
