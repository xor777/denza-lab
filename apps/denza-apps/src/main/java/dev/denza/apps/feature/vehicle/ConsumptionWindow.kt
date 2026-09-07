package dev.denza.apps.feature.vehicle

/**
 * The fixed ten-kilometre consumption window: the cluster's petal and the head unit's car page.
 *
 * There is no selector and no alternate runtime window: the wider history exists only in
 * [ConsumptionLog] for restart continuity.
 *
 * ### The window is ten kilometres of road, not a hundred records
 *
 * `docs/energy-display-contract.md` §2.2 and §2.6. A bucket is usually one odometer tick, but an
 * odometer step no tick can explain closes one bucket carrying that whole step - so counting
 * records was counting the wrong axis. The tail is taken by road.
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
     * The buckets visible on the chart, oldest first: the newest tail whose road reaches [KM].
     *
     * A list that is already the window comes back *as itself*, which is what the panel relies on:
     * the snapshot carries the tail rather than the journal's thirty kilometres, so a frame that
     * asks for the window three times allocates nothing to get it. See [ConsumptionLog.window].
     */
    fun raw(all: List<ConsumptionSample>): List<ConsumptionSample> {
        var km = 0.0
        var from = all.size
        while (from > 0 && km < KM - OdometerGate.KM_EPSILON) {
            from--
            km += all[from].km
        }
        return if (from == 0) all else all.subList(from, all.size)
    }

    /** All the road in the window, known energy or not - which is what the axis is. */
    fun roadKm(all: List<ConsumptionSample>): Double {
        val window = raw(all)
        var km = 0.0
        for (index in window.indices) km += window[index].km
        return km
    }

    /**
     * How much road the figure is actually the mean of, which is what the unit names.
     *
     * The *known* road rather than the road: a stretch with no power reading is under the chart
     * and out of the figure, so «за 3,7 км» is a promise about the number beside it.
     */
    fun coveredKm(all: List<ConsumptionSample>): Double {
        val window = raw(all)
        var km = 0.0
        for (index in window.indices) km += window[index].knownKm
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
