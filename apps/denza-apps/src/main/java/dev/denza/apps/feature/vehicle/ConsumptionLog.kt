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
            val value = pendingKwh / pendingKm * 100.0
            closed.addLast(value)
            while (closed.size > capacity) closed.removeFirst()
            pendingKm = 0.0
            pendingKwh = 0.0
            onBucketClosed(ConsumptionSample(odometerKm, value))
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
        windowKm.clear()
        windowKwh.clear()
        rollingKm = 0.0
        rollingKwh = 0.0
        stillSeconds = 0.0
    }

    companion object {
        /**
         * One odometer tick per bar, which is as fine as this car can be asked.
         *
         * It was two ticks while the chart had one window of 4.8 km. Now that the
         * window is chosen - three, ten or thirty kilometres - the log has to hold
         * the finest resolution any of them wants, because a window is a view of
         * this and folding down is possible while folding back up is not.
         */
        const val DEFAULT_BUCKET_KM = 0.1

        /**
         * Thirty kilometres of road, which is the longest window offered.
         *
         * Three hundred doubles is about two and a half kilobytes; the cost of
         * this decision is not memory, it is the journal write that keeps it
         * across a restart. `ConsumptionWindowTest` holds this against
         * `ConsumptionWindow.LONG`.
         */
        const val DEFAULT_CAPACITY = 300

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
