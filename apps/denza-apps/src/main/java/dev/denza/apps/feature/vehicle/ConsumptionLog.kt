package dev.denza.apps.feature.vehicle

/**
 * Turns a stream of (odometer, pack power) samples into the closed buckets both screens draw their
 * consumption from.
 *
 * Each bucket is one fixed slice of road, closed one at a time. They are the honest record:
 * everything the car spent over that slice counts, including the minutes it stood still inside it.
 *
 * Distance comes from the vehicle's own odometer rather than GNSS, so the dashboard needs no
 * location permission. Energy is integrated from pack power over real elapsed time, including
 * regeneration, which is why a downhill slice can read negative.
 *
 * ### A bucket carries its road, and how much of that road it knows the energy for
 *
 * `docs/energy-display-contract.md` §2.6. Until 2026-09-07 a bucket was one number - kWh per 100 km
 * - and an interval the log could not integrate silently contributed *zero* energy over real road,
 * which is an invented reading rather than a missing one. A dropped link on the motorway drew a
 * hundred metres of "nothing spent" in the middle of a drive.
 *
 * So a bucket records four things: where the odometer stood when it closed, the signed energy, the
 * road, and the road the energy is actually known over. [ConsumptionSample.value] is energy over
 * *known* road, and a bucket whose known road is under half its road is a hole - drawn as nothing,
 * counted for the axis, excluded from the figure.
 *
 * Pure Kotlin, no Android imports: the accumulation rules are unit tested. What happens to a closed
 * bucket afterwards is the caller's business - [onBucketClosed] is how the journal on disk hears
 * about one without this class learning what a file is.
 */
/**
 * One closed bucket.
 *
 * @param odometerKm where the odometer stood when it closed, which is what anchors it to a bin and
 *   what lets a journal decide later whether it is still part of the retained road
 * @param kwh the signed integral of pack power over it; negative where the road gave energy back
 * @param km the road it covers - never the record count, because one bucket can carry a longer
 *   odometer step than one tick
 * @param knownKm how much of that road the energy is known over
 */
internal data class ConsumptionSample(
    val odometerKm: Double,
    val kwh: Double,
    val km: Double,
    val knownKm: Double,
) {

    /** Whether enough of the road is known for this bucket to be a reading rather than a hole. */
    val known: Boolean
        get() = isKnown(km, knownKm)

    /** kWh per 100 km over the road the energy is known for, or `NaN` where it is a hole. */
    val value: Double
        get() = valueOf(kwh, km, knownKm)

    companion object {
        /**
         * The half-known rule, stated once.
         *
         * A bin of [ConsumptionChart] is the same question asked of a sum rather than of one
         * record - «is enough of this road known for the figure over it to be a reading» - and it
         * had a second copy of the arithmetic. One rule, two callers.
         */
        fun isKnown(km: Double, knownKm: Double): Boolean =
            knownKm > 0.0 && knownKm * 2.0 >= km - OdometerGate.KM_EPSILON

        /** kWh per 100 km over known road, or `NaN` where there is not enough of it. */
        fun valueOf(kwh: Double, km: Double, knownKm: Double): Double =
            if (!isKnown(km, knownKm)) Double.NaN else kwh / knownKm * 100.0
    }
}

internal class ConsumptionLog(
    private val bucketKm: Double = DEFAULT_BUCKET_KM,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onBucketClosed: (ConsumptionSample) -> Unit = {},
) {

    private val closed = ArrayDeque<ConsumptionSample>()

    /** What a reading of the road is worth, which is not this class's own arithmetic. */
    private val odometer = OdometerGate()
    private var pendingKm = 0.0
    private var pendingKnownKm = 0.0
    private var pendingKwh = 0.0

    /** Closed buckets, oldest first. All of them - the journal's own thirty kilometres. */
    val buckets: List<ConsumptionSample> get() = closed.toList()

    /**
     * And the tail the screens are ever shown, which is what a snapshot carries.
     *
     * The tail is measured in **road** rather than in buckets, because a bucket is not always a
     * hundred metres: an odometer step no tick can explain closes one bucket carrying that whole
     * step. The walk itself is [ConsumptionWindow.firstIndex], which the snapshot's own reader
     * shares, so the two records of "the last ten kilometres" cannot disagree.
     *
     * **And the odometer bounds it as well as the road does.** A journal restored twenty
     * kilometres behind the car, or a re-anchor after a drive with the panel closed, leaves buckets
     * whose road is real and whose *place* is yesterday's; adding them up to ten kilometres printed
     * a figure over a road the car is nowhere near. The gate's own newest reading is the bound.
     */
    val window: List<ConsumptionSample>
        get() {
            val from = ConsumptionWindow.firstIndex(closed, odometer.lastKm)
            val out = ArrayList<ConsumptionSample>(closed.size - from)
            for (index in from until closed.size) out.add(closed[index])
            return out
        }

    /**
     * @param odometerKm the vehicle odometer; null while the read failed
     * @param powerKw pack power, positive out of the battery; null makes the interval's energy
     *   unknown rather than zero
     * @param dtSeconds real time since the previous sample
     */
    fun sample(odometerKm: Double?, powerKw: Double?, dtSeconds: Double) {
        when (odometer.step(odometerKm)) {
            OdometerGate.Step.UNREAD, OdometerGate.Step.SEEDED -> return
            // The car was driven with the dashboard closed, or the reading moved in a
            // way a sample interval cannot explain. Drop the open work rather than
            // spreading unknown energy across unknown distance.
            OdometerGate.Step.REANCHORED -> {
                dropOpenWork()
                return
            }
            OdometerGate.Step.ROAD -> Unit
        }

        // ROAD is only ever reached with a reading behind it; this is the compiler's proof of
        // that rather than a case the car can be in.
        val reading = odometer.lastKm ?: return
        val km = odometer.deltaKm
        // An interval with no power reading, or longer than the cadence can explain, is energy
        // this log does not know - not energy it knows to be zero.
        val knows = powerKw != null && dtSeconds > 0.0 && dtSeconds <= MAX_GAP_SECONDS

        pendingKm += km
        if (knows) {
            pendingKnownKm += km
            pendingKwh += powerKw!! * dtSeconds / 3600.0
        }
        if (pendingKm >= bucketKm - KM_EPSILON) {
            val sample = ConsumptionSample(reading, pendingKwh, pendingKm, pendingKnownKm)
            closed.addLast(sample)
            while (closed.size > capacity) closed.removeFirst()
            dropOpenWork()
            onBucketClosed(sample)
        }
    }

    fun reset() {
        closed.clear()
        odometer.forget()
        dropOpenWork()
    }

    /**
     * Seed the buckets from a journal, dropping anything the odometer says is older
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
     *
     * The gate is anchored on the way out, the way [TripEnergyLedger.restore] anchors it: without
     * that the log has restored road it cannot place, and [window] would hand a journal that ends
     * twenty kilometres back to a screen that says «за 10 км».
     */
    fun restore(samples: List<ConsumptionSample>, odometerKm: Double, windowKm: Double): Boolean {
        if (samples.any { it.odometerKm > odometerKm + KM_EPSILON }) return false
        val floor = odometerKm - windowKm
        closed.clear()
        samples.asSequence()
            .filter { it.odometerKm > floor + KM_EPSILON }
            .toCollection(closed)
        while (closed.size > capacity) closed.removeFirst()
        odometer.anchor(odometerKm)
        return true
    }

    private fun dropOpenWork() {
        pendingKm = 0.0
        pendingKnownKm = 0.0
        pendingKwh = 0.0
    }

    companion object {
        /**
         * One odometer tick per bucket, which is as fine as this car can be asked.
         *
         * The chart draws half-kilometre bins over it (`ConsumptionChart`), so a tick is the
         * resolution the bins are averaged from rather than the resolution anybody reads.
         */
        const val DEFAULT_BUCKET_KM = 0.1

        /**
         * Thirty kilometres of road retained for restart continuity.
         *
         * Three hundred records is a few kilobytes; the cost of this decision is not memory, it is
         * the journal write that keeps it across a restart. `ConsumptionWindowTest` holds the
         * display and retention sizes apart explicitly.
         */
        const val DEFAULT_CAPACITY = 300

        const val RETENTION_KM = DEFAULT_CAPACITY * DEFAULT_BUCKET_KM

        /**
         * The two thresholds the road is read with, which are [OdometerGate]'s.
         *
         * They were a copy here and a copy in `TripEnergyLedger`, in two files whose subjects are
         * kilowatt-hours and trips. Neither owns them: they are about this car's odometer and this
         * app's cadence.
         */
        private const val MAX_GAP_SECONDS = OdometerGate.MAX_GAP_SECONDS
        private const val KM_EPSILON = OdometerGate.KM_EPSILON
    }
}
