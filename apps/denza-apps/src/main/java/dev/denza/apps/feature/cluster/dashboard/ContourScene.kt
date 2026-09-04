package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehiclePoll
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs

/**
 * What the panel is doing, as one word.
 *
 * These are scenes, not messages. Every one of them is the same panel with different things on it,
 * and none of them is a sentence apologising for the others - the one exception, [UNAVAILABLE],
 * carries an instruction rather than an error, because "the ADB key is not confirmed" is something
 * the driver can act on and "нет данных" is not.
 */
internal enum class ContourMode {
    /** Nothing has answered yet: the band's skeleton, and nothing else (m4). */
    STARTING,

    /** The ordinary state, and the one the panel is in almost all of the time. */
    DRIVING,

    /** The engine's own two minutes are on the right shelf, where the trip's phrase stands. */
    ENGINE,

    /** Standing in P, where reading three numbers costs nothing. */
    PARKED,

    /** A gun is in. The petal counts down instead of counting consumption. */
    CHARGING,

    /** The bus went quiet: every value is removed and every caption stays (M5). */
    LINK_LOST,

    /** The shell is closed to us, and the panel says what to do about it. */
    UNAVAILABLE,
}

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
 * [mode] is the headline and the two flags are not folded into it, because they are not alternatives
 * to it. A car standing in P with the engine's box still up is `ENGINE` **and** parked: the box owns
 * the right shelf, and the petal still grows its tenth. Folding one into the other would make the
 * panel's behaviour depend on which of two true things was checked first.
 */
internal data class ContourStage(
    val mode: ContourMode,
    val parked: Boolean = false,
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

    var stage: ContourStage = ContourStage(ContourMode.STARTING)
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

    private fun decide(t: VehicleTelemetry): ContourStage {
        val parked = t.parked == true
        val engineBox = !t.engineTrace.isEmpty
        val engineRunning = t.engineRunning == true
        val mode = when {
            t.access == VehicleAccess.UNAVAILABLE -> ContourMode.UNAVAILABLE
            !everAnswered -> ContourMode.STARTING
            packetAge >= STALE_SECONDS -> ContourMode.LINK_LOST
            chargeDwell >= CHARGE_DWELL_SECONDS -> ContourMode.CHARGING
            engineBox -> ContourMode.ENGINE
            parked -> ContourMode.PARKED
            else -> ContourMode.DRIVING
        }
        return ContourStage(
            mode = mode,
            parked = parked,
            engineBox = engineBox,
            engineRunning = engineRunning,
            message = if (mode == ContourMode.UNAVAILABLE) t.message else "",
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
