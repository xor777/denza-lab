package dev.denza.apps.feature.vehicle

/**
 * What one odometer reading is worth to an integral taken over the road.
 *
 * [ConsumptionLog] and [TripEnergyLedger] integrate different quantities over the same axis and
 * were reaching the same four conclusions about a reading with two copies of the arithmetic and six
 * copies of the constants. The rules are the same because the *car* is the same: they are about the
 * odometer this vehicle reports and the cadence this app reads it at, not about kilowatt-hours or
 * about trips.
 *
 *  - **A reading that did not answer is not a sample.** Not zero road: without the odometer there
 *    is no way to tell standing still from a failed read, and inventing either is inventing a
 *    number.
 *  - **The first reading seeds and nothing else.** There is no previous one to take a difference
 *    from, and a difference against zero is the whole odometer.
 *  - **A step no interval can explain re-anchors.** Backwards is a cluster swap or a journal
 *    carried between cars; more than [MAX_JUMP_KM] forward is a drive that happened with the panel
 *    closed. Either way the reading is believed and the road between is not.
 *  - **Anything else is road**, and [deltaKm] is how much of it.
 *
 * The gap in *time* is the caller's own guard, because the two integrate different things over it -
 * but the threshold is the same fact about the same cadence, so [MAX_GAP_SECONDS] lives here too.
 */
internal class OdometerGate {

    /** What one call to [step] found. */
    enum class Step {
        /** The odometer did not answer. Nothing about the road is known. */
        UNREAD,

        /** The first reading. It is remembered and it measures nothing. */
        SEEDED,

        /** A step no sample interval can explain: believe the reading, drop the road. */
        REANCHORED,

        /** An ordinary interval, worth [OdometerGate.deltaKm] of road. */
        ROAD,
    }

    private var last: Double? = null

    /** The road the last [Step.ROAD] covered, never negative. Zero after anything else. */
    var deltaKm: Double = 0.0
        private set

    /** The newest reading believed, which is what a journal records. */
    val lastKm: Double? get() = last

    fun step(odometerKm: Double?): Step {
        deltaKm = 0.0
        if (odometerKm == null) return Step.UNREAD
        val previous = last
        last = odometerKm
        if (previous == null) return Step.SEEDED
        val delta = odometerKm - previous
        if (delta < -KM_EPSILON || delta > MAX_JUMP_KM) return Step.REANCHORED
        deltaKm = delta.coerceAtLeast(0.0)
        return Step.ROAD
    }

    /** Forget where the car was, so the next reading seeds. */
    fun forget() {
        last = null
        deltaKm = 0.0
    }

    /** Believe a reading without measuring against it: what restoring from a journal does. */
    fun anchor(odometerKm: Double) {
        last = odometerKm
        deltaKm = 0.0
    }

    companion object {
        /** A longer sample gap means the dashboard was asleep; do not integrate it. */
        const val MAX_GAP_SECONDS = 8.0

        /** More road than any sample interval can cover; treat as a re-anchor. */
        const val MAX_JUMP_KM = 5.0

        /**
         * The odometer arrives in tenths of a kilometre and the differences are accumulated in
         * doubles, so two 0.1 km steps land a hair under 0.2. Every distance threshold in this
         * package is compared with this slack.
         */
        const val KM_EPSILON = 1e-6

        /**
         * No odometer on this car reaches this.
         *
         * Three copies of this number: the plausibility gate every decoded distance passes
         * through, and one in each journal's parser. They are one statement about one vehicle -
         * a bigger figure is a bad read or a file this app did not write.
         */
        const val MAX_ODOMETER_KM = 2_000_000.0
    }
}
