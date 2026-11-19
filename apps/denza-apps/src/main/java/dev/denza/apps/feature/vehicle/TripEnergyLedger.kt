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
 *  - [recoveredKwh] is `∫P⁻ dt` **over intervals with the engine off only**;
 *  - [engineKwh] is `∫P⁻ dt` **over intervals with the engine running** - «ЗАРЯД ОТ ДВС», what the
 *    pack took while the engine ran. With the engine running a negative pack flow is the engine
 *    charging or the road giving back, and the pack cannot tell the two apart, so all of it is the
 *    engine's run (`docs/energy-display-contract.md` §2.5). The two together are every
 *    kilowatt-hour the pack took on the trip, and neither is counted twice;
 *  - [engineSeconds] is how long the engine actually ran, which is the answer to the question a
 *    hybrid's driver asks and nothing on this panel used to give.
 *
 * Until 2026-11-19 [engineKwh] was `∫GENERATION_KW dt`, which on this firmware is above zero only
 * during forced generation standing in P, where the trip integrates nothing: on the move it read
 * zero while the engine charged the pack, so the figure never moved.
 *
 * That is also why [engineKwhKnown] exists. A trip journalled by 0.7.1 carries the old integral in
 * the same field, and adding the new one to it would print a sum of two different quantities under
 * one caption. Such a trip keeps its net, its recuperation, its minutes and its kilometres, which
 * mean what they meant, and its engine charge is unknown - zero, not printed, not added to - until
 * the next trip starts ([TripJournal], 2026-11-19).
 */
internal data class TripEnergy(
    val netKwh: Double = 0.0,
    val recoveredKwh: Double = 0.0,
    val engineKwh: Double = 0.0,
    val engineSeconds: Double = 0.0,
    val kilometres: Double = 0.0,
    /** Whether [engineKwh] is this trip's integral; false for a trip carried over from 0.7.1. */
    val engineKwhKnown: Boolean = true,
    /**
     * Whether the ledger has opened a trip at all: the first movement since the process started,
     * or a journalled trip it restored. It stays true while the car stands in P after the trip,
     * which is when the finished trip is read. False is the empty trip of a ledger whose odometer
     * never moved - a car that answers its pack but not its odometer has no trip, not a trip of
     * nothing.
     */
    val opened: Boolean = false,
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
    private var engineKnown = true
    private var opened = false

    /** What a reading of the road is worth, which is not this class's own arithmetic. */
    private val odometer = OdometerGate()

    /**
     * Whether the next movement starts a new trip.
     *
     * True at construction: a process that has just started has no trip behind it, and the first
     * road it sees is the beginning of one.
     */
    private var armed = true

    val trip: TripEnergy
        get() = TripEnergy(net, recovered, engine, engineSeconds, kilometres, engineKnown, opened)

    /** Everything the journal needs, and nothing it does not. */
    fun record(): TripRecord? {
        val reading = odometer.lastKm ?: return null
        return TripRecord(trip, reading, armed)
    }

    /**
     * One sweep's answer.
     *
     * @param odometerKm the vehicle odometer; null skips the sample - without it there is no way
     *   to tell standing still from a lost reading - except for a P, which ends the trip whatever
     *   the odometer said
     * @param powerKw pack power, positive out of the battery
     * @param engineRunning null while nothing has answered, which is not the same as stopped
     * @param parked whether the selector is in P
     * @param dtSeconds real time since the previous sample
     */
    fun sample(
        odometerKm: Double?,
        powerKw: Double?,
        engineRunning: Boolean?,
        parked: Boolean?,
        dtSeconds: Double,
    ) {
        if (odometer.step(odometerKm) != OdometerGate.Step.ROAD) {
            // P is the gearbox's answer, not the odometer's. A P read in a sweep whose odometer
            // missed used to be dropped with the sample, so a driver who left P before the next
            // sweep carried both had the energy trip run on into the next drive, while the strip's
            // trip clock, reading the same P, had ended its own.
            if (parked == true) armed = true
            return
        }

        val deltaKm = odometer.deltaKm
        val moved = deltaKm > KM_EPSILON
        if (moved && armed) {
            clear()
            armed = false
            opened = true
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

        kilometres += deltaKm

        val hours = dtSeconds / 3600.0
        if (powerKw != null) {
            net += powerKw * hours
            // Only where the engine is known to be off. With it running a negative pack flow is
            // the engine charging or a hill, and the two are indistinguishable on this bus: that
            // is the engine's run, below.
            if (powerKw < 0.0 && engineRunning == false) recovered += -powerKw * hours
        }
        if (engineRunning == true) {
            engineSeconds += dtSeconds
            if (engineKnown && powerKw != null && powerKw < 0.0) engine += -powerKw * hours
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
        engineKnown = record.energy.engineKwhKnown
        engine = if (engineKnown) record.energy.engineKwh else 0.0
        engineSeconds = record.energy.engineSeconds
        kilometres = record.energy.kilometres
        opened = record.energy.opened
        armed = record.armed
        odometer.anchor(odometerKm)
        return true
    }

    private fun clear() {
        net = 0.0
        recovered = 0.0
        engine = 0.0
        engineSeconds = 0.0
        kilometres = 0.0
        engineKnown = true
    }

    companion object {
        /** A journal from further back than this describes a drive nobody was integrating. */
        const val RESTART_GAP_KM = 1.0

        /**
         * The thresholds the road is read with, which are [OdometerGate]'s.
         *
         * They were a copy here and a copy in `ConsumptionLog`, in two files whose subjects are
         * trips and kilowatt-hours. Neither owns them: they are about this car's odometer and this
         * app's cadence.
         */
        const val MAX_GAP_SECONDS = OdometerGate.MAX_GAP_SECONDS
        private const val KM_EPSILON = OdometerGate.KM_EPSILON
    }
}
