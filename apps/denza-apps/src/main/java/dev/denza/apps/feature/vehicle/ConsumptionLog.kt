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
 * *known* road, and a bucket whose known road is under half its road is not a reading - out of the
 * figure, out of the road the unit names, and off the chart's axis, where its neighbours close up
 * behind it rather than a hole being drawn for it.
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

    /** Whether enough of the road is known for this bucket to be a reading at all. */
    val known: Boolean
        get() = isKnown(km, knownKm)

    /** kWh per 100 km over the road the energy is known for, or `NaN` where it is not a reading. */
    val value: Double
        get() = valueOf(kwh, km, knownKm)

    companion object {
        /**
         * The half-known rule, stated once.
         *
         * [ConsumptionChart] asks it of every bucket to decide which of them is a point at all,
         * and [ConsumptionWindow] asks it of the same buckets to decide which of them is in the
         * figure and under the unit. One rule, three callers, so the three cannot disagree about
         * what a reading is.
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
     * The tail is measured in the **recorded road** of the buckets that are readings, rather than
     * in buckets: a bucket is not always a hundred metres, and one that is not a reading is off
     * every axis this window feeds. The walk itself is [ConsumptionWindow.firstIndex], which the
     * snapshot's own reader shares, so the two records of "the last ten kilometres" cannot disagree.
     *
     * The odometer used to bound it too, at ten kilometres behind the gate's newest reading. That
     * bound is gone with the grid it belonged to: ten kilometres of readings are ten kilometres of
     * readings wherever the car has been since, and they leave when today's road pushes them out
     * (`docs/energy-display-contract.md` §2.6).
     */
    val window: List<ConsumptionSample>
        get() {
            val from = ConsumptionWindow.firstIndex(closed)
            val out = ArrayList<ConsumptionSample>(closed.size - from)
            for (index in from until closed.size) out.add(closed[index])
            return out
        }

    /**
     * @param odometerKm the vehicle odometer; null while the read failed
     * @param powerKw pack power, positive out of the battery; null makes the interval's energy
     *   unknown rather than zero
     * @param dtSeconds real time since the previous sample
     * @param speedKmh the car's own speed; at or below [STANDING_KMH] the interval's energy is the
     *   trip's and not the road's, and **null counts as moving** because a missing read is not a
     *   stop
     */
    fun sample(
        odometerKm: Double?,
        powerKw: Double?,
        dtSeconds: Double,
        speedKmh: Double? = null,
    ) {
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

        // And energy while the car stands is the trip's, not the road's. Two minutes of the engine
        // charging on P put 0.33 kWh into the pack on 2026-09-18, and a log that files standing
        // energy into the next hundred metres of road draws that as a blue shelf on the cut for the
        // kilometre after it. The road accounting is untouched - a standing sample carries no road
        // anyway - so a stop inside a bucket costs that bucket nothing and hides nothing;
        // `TripEnergyLedger` keeps every joule, which is the figure that is about time as well as
        // road (contract §2.2).
        val moving = speedKmh == null || speedKmh > STANDING_KMH
        pendingKm += km
        if (knows) {
            pendingKnownKm += km
            if (moving) pendingKwh += powerKw!! * dtSeconds / 3600.0
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
         * It is also the chart's own pitch: `ConsumptionChart` stands one point on every bucket
         * that is a reading and makes it the mean of the ten readings ending there, so a tick is
         * both the resolution the means are taken from and the road anybody reads a point as.
         */
        const val DEFAULT_BUCKET_KM = 0.1

        /**
         * At or below this the car is standing, and the energy it spends is not the road's.
         *
         * Half a kilometre an hour rather than zero: the id is a float off the bus and a car held
         * on the brake reports a hair of creep. It is the one threshold in this file that is about
         * the car's motion rather than about its odometer, which is why it is not [OdometerGate]'s.
         */
        const val STANDING_KMH = 0.5

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
