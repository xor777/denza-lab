package dev.denza.apps.feature.vehicle

/**
 * What one trip cost, in the pack's own units, as the Contour's right shelf reads it.
 *
 * Three quantities and a distance, and the first of them is the one the other two are already
 * inside:
 *
 *  - [netKwh] is `∫P dt` over the trip - **the net that left the battery**. Regeneration is a
 *    negative pack flow, so it is subtracted by the integral itself, and so is whatever the engine
 *    put back while it ran. That is the figure the shelf prints against the kilometres;
 *  - [recoveredKwh] is `∫P⁻ dt` **over intervals with the engine off only**. Under generation a
 *    negative pack flow is indistinguishable from braking, and claiming the engine's charge as
 *    regeneration would be a lie the driver cannot check. The engine's share is [engineKwh];
 *  - [engineKwh] is `∫GENERATION_KW dt`, what the engine handed the pack;
 *  - [engineSeconds] is how long the engine actually ran, which is the answer to the question a
 *    hybrid's driver asks and nothing on this panel used to give.
 *
 * Whichever way the unlogged question about `GENERATION_KW ⊂ POWER_KW` lands (see
 * [VehicleSignal.GENERATION_KW] and docs/instrument-display-findings.md), the first figure does not
 * double-count: it is one integral of one signal.
 */
internal data class TripEnergy(
    val netKwh: Double = 0.0,
    val recoveredKwh: Double = 0.0,
    val engineKwh: Double = 0.0,
    val engineSeconds: Double = 0.0,
    val kilometres: Double = 0.0,
) {
    val engineMinutes: Double get() = engineSeconds / 60.0

    /** Whether the engine ran at all this trip. A quantity that did not happen has no cell. */
    val engineRan: Boolean get() = engineSeconds > 0.0
}

/**
 * One trip, as it survives a restart: the energy so far, where the odometer stood, and whether the
 * next movement starts a new trip.
 */
internal data class TripRecord(
    val energy: TripEnergy,
    val odometerKm: Double,
    val armed: Boolean,
)

/**
 * Integrates [TripEnergy] from the same sample stream [ConsumptionLog] reads, and decides where one
 * trip ends and the next begins.
 *
 * ### What a trip is
 *
 * A trip **starts with the first movement after P** - or after the process started, which is the
 * same rule with no history behind it - and **ends at the next first movement after P**. So the
 * whole time the car stands in P the completed trip is still on the shelf, in full, which is
 * precisely when a driver reads it; the numbers are replaced only when the car sets off again.
 *
 * The alternative - clearing on P - would blank the shelf at the one moment it is worth looking at,
 * and the alternative to that - clearing on ignition - is not something this app can observe.
 *
 * [armed] is that rule in one boolean: P arms the reset, the next movement performs it - and it is
 * also the answer to "is this trip still open", which is what decides whether anything is
 * integrated at all. A car standing on P is not driving whatever the pack is doing.
 *
 * ### Why the odometer and not a clock
 *
 * Movement is the odometer advancing, the same source [ConsumptionLog] measures road with, so the
 * panel needs no location permission and the trip's kilometres and its energy come from one stream.
 * A reading that jumps further than a sample interval can explain is a re-anchor rather than road
 * and is dropped, exactly as the consumption log drops it.
 *
 * Pure Kotlin, no Android imports: [TripJournal] is what knows about files.
 */
internal class TripEnergyLedger(
    /**
     * The longest sample interval this will integrate.
     *
     * A property of the cadence rather than of the trip, so it is a constructor parameter the way
     * [ConsumptionLog]'s bucket size is: a gap longer than this means the dashboard was asleep, and
     * multiplying one stale power reading by the minutes nobody was watching is the one way this
     * ledger could invent a number.
     */
    private val maxGapSeconds: Double = MAX_GAP_SECONDS,
) {

    private var net = 0.0
    private var recovered = 0.0
    private var engine = 0.0
    private var engineSeconds = 0.0
    private var kilometres = 0.0
    private var lastOdometerKm: Double? = null

    /**
     * Whether the next movement starts a new trip.
     *
     * True at construction: a process that has just started has no trip behind it, and the first
     * road it sees is the beginning of one.
     */
    private var armed = true

    val trip: TripEnergy
        get() = TripEnergy(net, recovered, engine, engineSeconds, kilometres)

    /** Everything the journal needs, and nothing it does not. */
    fun record(): TripRecord? {
        val odometer = lastOdometerKm ?: return null
        return TripRecord(trip, odometer, armed)
    }

    /**
     * One sweep's answer.
     *
     * @param odometerKm the vehicle odometer; null skips the sample entirely, because without it
     *   there is no way to tell standing still from a lost reading
     * @param powerKw pack power, positive out of the battery
     * @param generationKw what the engine is putting back, if it is running
     * @param engineRunning null while nothing has answered, which is not the same as stopped
     * @param parked whether the selector is in P
     * @param dtSeconds real time since the previous sample
     */
    fun sample(
        odometerKm: Double?,
        powerKw: Double?,
        generationKw: Double?,
        engineRunning: Boolean?,
        parked: Boolean?,
        dtSeconds: Double,
    ) {
        if (odometerKm == null) return
        val previous = lastOdometerKm
        lastOdometerKm = odometerKm
        if (previous == null) return

        val deltaKm = odometerKm - previous
        if (deltaKm < -KM_EPSILON || deltaKm > MAX_JUMP_KM) return

        val moved = deltaKm > KM_EPSILON
        if (moved && armed) {
            clear()
            armed = false
        }
        // Arming after the reset, not before it: a car that rolls a metre inside its own parking
        // manoeuvre would otherwise start a trip and then be told to start another one.
        if (parked == true) armed = true

        // Nothing grows while the trip is closed. `armed` is the whole of that: it is true from P
        // until the next movement, and a car standing on P is not driving whatever the pack is
        // doing. A gun in with the panel up used to add seven kilowatt-hours an hour to
        // РЕКУПЕРАЦИЯ, take the same off the net, and journal the result every ten seconds.
        if (armed) return

        // One guard over both halves of the figure. The kilometres used to be committed a statement
        // above it, so an interval the ledger refused to integrate still counted as road - and the
        // shelf prints one against the other, so the two halves ended up describing different
        // drives with nothing bounding the drift.
        if (dtSeconds <= 0.0 || dtSeconds > maxGapSeconds) return

        kilometres += deltaKm.coerceAtLeast(0.0)

        val hours = dtSeconds / 3600.0
        if (powerKw != null) {
            net += powerKw * hours
            // Only where the engine is known to be off. Under generation a negative pack flow is
            // the engine charging, not a hill, and the two are indistinguishable on this bus.
            if (powerKw < 0.0 && engineRunning == false) recovered += -powerKw * hours
        }
        if (engineRunning == true) {
            engineSeconds += dtSeconds
            if (generationKw != null && generationKw > 0.0) engine += generationKw * hours
        }
    }

    /**
     * Seed from a journal, or refuse it.
     *
     * The odometer is the test, the way it is for the consumption journal: a record from behind the
     * car's own reading belongs to another vehicle, and one further back than [RESTART_GAP_KM]
     * describes road this process never saw - its kilometres and its kilowatt-hours no longer
     * describe the same drive, and a trip figure that is wrong is worse than one that starts again.
     */
    fun restore(record: TripRecord, odometerKm: Double): Boolean {
        if (record.odometerKm > odometerKm + KM_EPSILON) return false
        if (odometerKm - record.odometerKm > RESTART_GAP_KM) return false
        net = record.energy.netKwh
        recovered = record.energy.recoveredKwh
        engine = record.energy.engineKwh
        engineSeconds = record.energy.engineSeconds
        kilometres = record.energy.kilometres
        armed = record.armed
        lastOdometerKm = odometerKm
        return true
    }

    private fun clear() {
        net = 0.0
        recovered = 0.0
        engine = 0.0
        engineSeconds = 0.0
        kilometres = 0.0
    }

    companion object {
        /** A journal from further back than this describes a drive nobody was integrating. */
        const val RESTART_GAP_KM = 1.0

        /** A longer sample gap means the dashboard was asleep; do not integrate it. */
        const val MAX_GAP_SECONDS = 8.0

        /** More road than any sample interval can cover; treat as a re-anchor. */
        private const val MAX_JUMP_KM = 5.0

        /** The odometer arrives in tenths and the differences accumulate in doubles. */
        private const val KM_EPSILON = 1e-6
    }
}
