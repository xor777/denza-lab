package dev.denza.apps.feature.vehicle

/**
 * The fixed ten-kilometre consumption window: the cluster's petal and the head unit's car page.
 *
 * There is no selector and no alternate runtime window: the wider history exists only in
 * [ConsumptionLog] for restart continuity.
 *
 * ### The window is ten kilometres of recorded road
 *
 * `docs/energy-display-contract.md` §2.2 and §2.6. Not a hundred records - a bucket is not always a
 * hundred metres, and an odometer step no tick can explain closes one bucket carrying that whole
 * step - and not ten kilometres of odometer either. It is the newest buckets that are **readings**,
 * taken back until their road sums to [KM], whatever the odometer says about the road between them.
 *
 * **The odometer floor is gone.** It bounded the walk at `lastKm − KM` so that a journal restored
 * twenty kilometres behind the car, or the buckets from before a re-anchor, left the window. That
 * was a rule about the odometer's ten kilometres, and the axis is not the odometer any more: ten
 * kilometres of readings from yesterday are ten kilometres of readings, and they stay in the figure
 * and on the chart until today's road pushes them out, which is what a history is. What leaves other
 * than by being pushed out is a journal the gate refuses, which is dropped whole.
 *
 * ### And the figure is net energy over known road
 *
 * `Σ kWh / Σ knownKm × 100`, signed. It used to drop returning buckets from both the sum and the
 * count, so `[10, −8, 30]` printed 20 where the road had cost 10.7 - a definition nobody had
 * written down. Regeneration and the engine's charge reduce this figure, because they reduce what
 * the pack paid, and that is what the figure is.
 */
internal object ConsumptionWindow {
    const val KM = 10.0

    /**
     * Where the window starts inside [all]: the walk both readers of the tail share.
     *
     * **Road, and only the road of readings.** A bucket that is not a reading carries no road into
     * this sum - it is out of the figure, out of the unit and off the chart's axis alike - so what
     * the walk counts back is exactly what [ConsumptionChart] draws points for, and «за 3,7 км» is a
     * promise about the number beside it and about the chart beside that.
     */
    fun firstIndex(all: List<ConsumptionSample>): Int {
        var km = 0.0
        var from = all.size
        while (from > 0 && km < KM - OdometerGate.KM_EPSILON) {
            from--
            val bucket = all[from]
            if (bucket.known) km += bucket.km
        }
        return from
    }

    /**
     * The buckets visible on the chart, oldest first: the newest tail whose road reaches [KM].
     *
     * A list that is already the window comes back *as itself*, which is what the panel relies on:
     * the snapshot carries the tail rather than the journal's thirty kilometres, so a frame that
     * asks for the window three times allocates nothing to get it. See [ConsumptionLog.window].
     */
    fun raw(all: List<ConsumptionSample>): List<ConsumptionSample> {
        val from = firstIndex(all)
        return if (from == 0) all else all.subList(from, all.size)
    }

    /**
     * How much road the figure is actually the mean of, which is what the unit names.
     *
     * The *known* road of the buckets that are **in** the figure, which is one reading bucket per
     * point of [ConsumptionChart] - so the road the unit names and the width of the chart above it
     * are one statement. A bucket that answered for forty of its hundred metres is not a reading:
     * it is out of the mean, out of the unit and off the axis alike, and counting its scrap in one
     * of the three made «за 3,7 км» a promise about road the number beside it was never taken over.
     */
    fun coveredKm(all: List<ConsumptionSample>): Double {
        val window = raw(all)
        var km = 0.0
        for (index in window.indices) {
            val bucket = window[index]
            if (bucket.known) km += bucket.knownKm
        }
        return km
    }

    /**
     * The window's own consumption: net energy over known road, in kWh/100 km, signed.
     *
     * Null when nothing in the window is known - the screens draw no figure rather than a zero.
     *
     * Written as a loop over indices because it is the arithmetic behind a number on a live panel:
     * the filter-and-average it replaces built two lists every time it was asked.
     */
    fun mean(all: List<ConsumptionSample>): Double? {
        val window = raw(all)
        var kwh = 0.0
        var km = 0.0
        for (index in window.indices) {
            val bucket = window[index]
            if (!bucket.known) continue
            kwh += bucket.kwh
            km += bucket.knownKm
        }
        return if (km <= 0.0) null else kwh / km * 100.0
    }
}
