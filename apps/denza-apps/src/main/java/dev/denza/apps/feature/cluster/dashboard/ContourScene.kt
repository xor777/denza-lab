package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehiclePoll
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs

/**
 * Every quantity the Contour draws, so each one can go stale on its own.
 *
 * A single null and a dead bus are the same rule at two scales, which is why there is no separate
 * notion of either: a value is removed one horizon after its last sample and its caption stays.
 *
 * **The horizon is the cadence that fills it**, which is what [poll] carries: a temperature that
 * answers every ten seconds cannot be held to the two seconds a pack power is. Derived quantities -
 * what the trip has cost, how long the engine ran - arrive with the packet that computed them and
 * are therefore [VehiclePoll.HOT] whatever the signals behind them are.
 */
internal enum class ContourValue(val poll: VehiclePoll) {
    POWER(VehiclePoll.HOT),
    VOLTS(VehiclePoll.HOT),
    PACK_TEMP(VehiclePoll.COLD),
    MOTOR_TEMPS(VehiclePoll.COLD),
    INVERTER_TEMP(VehiclePoll.COLD),
    SPREAD(VehiclePoll.COLD),
    RPM(VehiclePoll.HOT),
    ENGINE_MINUTES(VehiclePoll.HOT),
    GENERATION(VehiclePoll.HOT),
    TRIP_NET(VehiclePoll.HOT),
    TRIP_KM(VehiclePoll.HOT),
    TRIP_REGEN(VehiclePoll.HOT),
    TRIP_ENGINE(VehiclePoll.HOT),
    PETAL(VehiclePoll.HOT),
    CHARGE_LEFT(VehiclePoll.COLD),
}

/**
 * What the renderer is told about the panel as a whole.
 *
 * ### Why there is no scene name here
 *
 * There was one - a seven-word `ContourMode` naming starting, driving, the engine, park, charging,
 * link loss and an unreachable shell - and the panel read two of those words. It could not read the
 * rest, because it does not have arrangements for them: **there is no "link lost" picture.** Link
 * loss is the staleness rule firing on every value at once, park is a seat count and a decimal
 * place, the engine's box is a shape, and starting is the skeleton with nothing on it yet. Each of
 * those is already decided somewhere the renderer asks, and a word restating it was a second way to
 * answer a question with one answer.
 *
 * So what is left is what the panel actually branches on, and both of them are things it *draws*
 * rather than states it is in: [unavailable] puts an instruction where the petal's figure goes, and
 * [charging] puts a countdown there.
 */
internal data class ContourStage(
    /**
     * The shell is closed to us, and [message] says what to do about it.
     *
     * The one place this panel prints a sentence, and it is an instruction rather than an error:
     * "the ADB key is not confirmed" is something the driver can act on and «нет данных» is not.
     */
    val unavailable: Boolean = false,

    /**
     * A gun is in and the charger has agreed. The petal counts down instead of counting
     * consumption.
     *
     * False while the shell is closed, while nothing has answered yet and while the bus is quiet -
     * a countdown is a reading like any other and cannot outlive the link that fills it.
     */
    val charging: Boolean = false,
    val parked: Boolean = false,
    /**
     * Whether the engine's box owns the right shelf, which is not the same as the trace being warm.
     *
     * One shelf, two true things, and one of them has to give way. **Standing still wins.** A car
     * that has stopped is the one moment its driver can read three numbers instead of glancing at
     * one, and what the box has to show then is the last two minutes of a drive that has ended -
     * against the trip's own arithmetic, which is what P is for. The box comes back the moment the
     * car moves, with the trace it has been keeping all along.
     */
    val engineBox: Boolean = false,
    val engineRunning: Boolean = false,
    val message: String = "",
)

/**
 * The arbiter: which scene the panel is in, and which of its values are still true.
 *
 * ### One rule for a stale value
 *
 * Alpha used to mean seven things on this panel - night, jam, sleeping engine, link loss, a single
 * null, an area fill, a history fade - and a driver cannot tell "dim because it is dark" from "dim
 * because the bus died" (CRITIQUE M5). So nothing here dims anything. **A value is removed one
 * [ContourValue.poll] horizon after its last sample and its caption stays.** Link loss is that rule
 * applied to every value at once, a single null is that rule applied to one, and a heading appears
 * with its first value rather than standing over emptiness through the first seconds of a drive
 * (m4).
 *
 * ### Why the ages are per value rather than per sweep
 *
 * The hot set answers about four times a second and the cold set every ten, so "absent from the
 * snapshot" has to mean the same thing on both cadences before an age means anything. It does: the
 * hub rebuilds its cold map from each cold sweep, so a temperature that stopped answering leaves the
 * snapshot the same way a power reading does - and because it does, the *horizon* has to be the
 * cadence's own, or one flaky cold read would blank a standing figure for the eight seconds until
 * the next sweep. That is [VehiclePoll.staleSeconds], and it is the one place the rule is written.
 *
 * ### And why the followed values are held here
 *
 * [held] is the newest reading of a quantity the panel *follows*, and it is the scene that keeps it
 * rather than the view reading the current snapshot behind a [fresh] gate. Those two are not the
 * same thing: a sweep that dropped one signal on a sentinel word still publishes the rest, so the
 * snapshot's field is null while the value is very much still true. Reading the field made a single
 * dropped sample look to [ContourMotion] like the two-second rule firing - the band settled to zero,
 * the peak and the hero's figure were reseeded, and the next good sweep teleported instead of
 * travelling.
 *
 * ### Dwell
 *
 * [CHARGE_DWELL_SECONDS] on the gun, because a gun that has just gone in reports itself for a moment
 * before the charger has agreed to anything, and a panel that swaps scene and back is worse than one
 * that waits. Everything else here is instantaneous by design: the engine box has 120 seconds of
 * hysteresis of its own in the trace's length, and P is a switch.
 *
 * Deterministic in `dt`, and free of Android: [frame] is the whole surface.
 */
internal class ContourScene {

    private val age = FloatArray(ContourValue.entries.size) { Float.MAX_VALUE }
    private val seen = BooleanArray(ContourValue.entries.size)

    /** The newest reading of every quantity the panel follows, `NaN` where there has been none. */
    private val last = FloatArray(ContourValue.entries.size) { Float.NaN }

    private var packetAge = Float.MAX_VALUE
    private var everAnswered = false
    private var chargeDwell = 0f

    var stage: ContourStage = ContourStage()
        private set

    /**
     * One frame.
     *
     * @param telemetry the newest snapshot, fresh or not
     * @param arrived whether this is a snapshot the panel has not seen before
     */
    fun frame(telemetry: VehicleTelemetry, arrived: Boolean, dt: Float) {
        val step = dt.coerceAtLeast(0f)
        for (index in age.indices) age[index] = add(age[index], step)
        packetAge = add(packetAge, step)

        if (arrived && telemetry.access == VehicleAccess.READY) {
            everAnswered = true
            packetAge = 0f
            ContourValue.entries.forEach { value ->
                if (!present(telemetry, value)) return@forEach
                age[value.ordinal] = 0f
                seen[value.ordinal] = true
                reading(telemetry, value)?.let { last[value.ordinal] = it }
            }
        }

        chargeDwell = if (telemetry.charging && telemetry.access == VehicleAccess.READY) {
            add(chargeDwell, step)
        } else {
            0f
        }

        stage = decide(telemetry)
    }

    /** Whether this quantity has ever arrived, which is what puts its caption on the panel. */
    fun known(value: ContourValue): Boolean = seen[value.ordinal]

    /** Whether it is still true, which is what puts the figure there. */
    fun fresh(value: ContourValue): Boolean =
        seen[value.ordinal] && age[value.ordinal] < value.poll.staleSeconds

    /**
     * The newest reading of a quantity the panel follows, or null once it has gone stale.
     *
     * Only [ContourValue.POWER] and [ContourValue.RPM] have one: they are the two the followers are
     * driven from, and a follower needs the number rather than the fact that it arrived.
     */
    fun held(value: ContourValue): Float? =
        if (!fresh(value)) null else last[value.ordinal].takeUnless { it.isNaN() }

    /**
     * Which arrangement the panel is in, as an object that is only replaced when it is wrong.
     *
     * This is asked once per frame at sixty frames a second and answers the same five things for
     * minutes at a time. A fresh [ContourStage] per frame is garbage on a view drawn over the
     * vehicle's own instruments - and it is also a small lie, because "the stage changed" is a
     * thing the renderer could reasonably want to ask.
     */
    private fun decide(t: VehicleTelemetry): ContourStage {
        val parked = t.parked == true
        // The trace is warm for two minutes after the engine stops, and on P the trip's three cells
        // take the shelf from it: see [ContourStage.engineBox].
        val engineBox = !t.engineTrace.isEmpty && !parked
        val engineRunning = t.engineRunning == true
        val unavailable = t.access == VehicleAccess.UNAVAILABLE
        // The order the scene name used to carry, kept as the guards on the one flag that needed
        // it: a closed shell, a panel that has heard nothing yet and a bus that has gone quiet all
        // outrank a gun, because the countdown is a reading and a reading cannot outlive its link.
        val charging = !unavailable && everAnswered && packetAge < STALE_SECONDS &&
            chargeDwell >= CHARGE_DWELL_SECONDS
        val message = if (unavailable) t.message else ""
        val held = stage
        if (held.unavailable == unavailable && held.charging == charging &&
            held.parked == parked && held.engineBox == engineBox &&
            held.engineRunning == engineRunning && held.message == message
        ) {
            return held
        }
        return ContourStage(
            unavailable = unavailable,
            charging = charging,
            parked = parked,
            engineBox = engineBox,
            engineRunning = engineRunning,
            message = message,
        )
    }

    /**
     * Whether this sweep carried the quantity at all.
     *
     * Derived quantities - what the trip has cost, how long the engine ran - are as fresh as the
     * packet they were computed from, which is the same statement as a sampled value's and needs no
     * second rule. A quantity that did not happen this trip is *absent* rather than zero: that is
     * the sixth pass's whole finding, and it is decided here rather than in the renderer, so a cell
     * that should not exist cannot be drawn by accident.
     */
    private fun present(t: VehicleTelemetry, value: ContourValue): Boolean = when (value) {
        ContourValue.POWER -> bandKilowatts(t) != null
        ContourValue.VOLTS -> t[VehicleSignal.PACK_VOLT] != null
        ContourValue.PACK_TEMP -> t[VehicleSignal.PACK_TEMP_AVG] != null
        ContourValue.MOTOR_TEMPS -> t.motorTemps.any { it != null }
        ContourValue.INVERTER_TEMP -> t[VehicleSignal.INVERTER_C] != null
        ContourValue.SPREAD -> t.cellSpreadMv != null
        ContourValue.RPM -> t.engineRunning == true && t.engineRpm != null
        ContourValue.ENGINE_MINUTES -> t.trip.engineRan
        ContourValue.GENERATION -> t.generating && t.generationKw != null
        ContourValue.TRIP_NET -> true
        ContourValue.TRIP_KM -> t.trip.kilometres > 0.0
        ContourValue.TRIP_REGEN -> t.trip.recoveredKwh > 0.0
        ContourValue.TRIP_ENGINE -> t.trip.engineKwh > 0.0
        ContourValue.PETAL -> t.consumption.isNotEmpty()
        ContourValue.CHARGE_LEFT -> t.charging && t.chargeMinutesLeft != null
    }

    /**
     * The number behind a quantity the panel follows, for the two that have one.
     *
     * [ContourValue.POWER] is what the band is drawn from, and a charge reads as energy arriving
     * rather than as a load: a gun in and the pack taking two kilowatts is the same event the band
     * already draws going the other way, so it is drawn going the other way.
     */
    private fun reading(t: VehicleTelemetry, value: ContourValue): Float? = when (value) {
        ContourValue.POWER -> bandKilowatts(t)
        ContourValue.RPM -> t.engineRpm?.toFloat()
        else -> null
    }

    private fun bandKilowatts(t: VehicleTelemetry): Float? {
        if (t.charging) {
            val charge = t.chargeKw
            if (charge != null) return -abs(charge).toFloat()
        }
        return t.loadKw?.toFloat()
    }

    private fun add(seconds: Float, dt: Float): Float =
        if (seconds >= CEILING) CEILING else (seconds + dt).coerceAtMost(CEILING)

    companion object {
        /**
         * A packet is a hot sweep, so the bus going quiet is the hot horizon applied to all of it.
         */
        val STALE_SECONDS: Float = VehiclePoll.HOT.staleSeconds

        /** How long a gun has to stay in before the panel believes it. */
        const val CHARGE_DWELL_SECONDS = 2f

        /** Ages stop counting here rather than drifting toward infinity over a long drive. */
        private const val CEILING = 3_600f
    }
}
