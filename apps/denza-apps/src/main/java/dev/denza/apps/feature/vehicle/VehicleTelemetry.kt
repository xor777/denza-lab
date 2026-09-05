package dev.denza.apps.feature.vehicle

/** Why the cluster dashboard has no numbers, when it has none. */
internal enum class VehicleAccess {
    /** Nothing read yet this session. */
    STARTING,

    /** At least one sweep answered. */
    READY,

    /** The shell channel is closed to us; [VehicleTelemetry.message] says why. */
    UNAVAILABLE,
}

/**
 * One immutable reading of the car, published by [VehicleTelemetryHub] and drawn
 * by the cluster dashboard.
 *
 * A signal missing from [values] is missing on purpose: the call went
 * unanswered, or the value could not be true for its unit. The dashboard draws
 * a dash for it. Nothing here is ever defaulted to zero.
 */
internal data class VehicleTelemetry(
    val access: VehicleAccess = VehicleAccess.STARTING,
    val message: String = "",
    val values: Map<VehicleSignal, Double> = emptyMap(),
    /** Closed consumption bars, oldest first, kWh/100 km. */
    val consumption: List<Double> = emptyList(),
    /** The last two minutes of revolutions and generation, on a one-second axis. */
    val engineTrace: EngineTraceSnapshot = EngineTraceSnapshot.EMPTY,
    /** The same two minutes of what the pack itself was doing, which the head unit's strip draws. */
    val powerTrace: PowerTraceSnapshot = PowerTraceSnapshot.EMPTY,
    /** What this trip has cost so far, integrated by [TripEnergyLedger]. */
    val trip: TripEnergy = TripEnergy(),
) {

    operator fun get(signal: VehicleSignal): Double? = values[signal]

    val charging: Boolean get() = (this[VehicleSignal.CHARGE_GUN] ?: 0.0) >= 1.0

    val chargeKw: Double? get() = this[VehicleSignal.CHARGE_KW]

    /**
     * What the car thinks is left of the charge, in minutes.
     *
     * The two ids are one estimate in two words, so either alone is still an answer; only both
     * missing means the car is not estimating.
     */
    val chargeMinutesLeft: Int?
        get() {
            val hours = this[VehicleSignal.CHARGE_HOURS]
            val minutes = this[VehicleSignal.CHARGE_MINUTES]
            if (hours == null && minutes == null) return null
            return ((hours ?: 0.0) * 60 + (minutes ?: 0.0)).toInt()
        }

    /** Pack power as load: positive leaves the battery. See [VehicleConvention]. */
    val loadKw: Double? get() = VehicleConvention.load(this[VehicleSignal.POWER_KW])

    /**
     * Whether the selector is in P, or null while nothing has answered.
     *
     * Null is not "moving": a trip is bounded by a switch we can read, and a switch that did not
     * answer bounds nothing.
     */
    val parked: Boolean? get() = this[VehicleSignal.GEARBOX_PARK]?.let { it >= 1.0 }

    /** Cell spread has no feature id of its own; it is max minus min. */
    val cellSpreadMv: Double?
        get() {
            val min = this[VehicleSignal.CELL_MIN_MV] ?: return null
            val max = this[VehicleSignal.CELL_MAX_MV] ?: return null
            return max - min
        }

    /**
     * The car's three drive motors in layout order: front, rear left, rear
     * right. The rear pair is per-side, not per-axle, so three separate
     * readings is the honest shape — one of them running hotter than the others
     * is exactly what the row is there to show.
     *
     * A field rather than a `get()`. A snapshot arrives four times a second and the cluster reads
     * it sixty, so a property that builds a list is fifteen collections per answer, thrown away
     * inside the frame that made them.
     */
    val motorTemps: List<Double?> = listOf(
        values[VehicleSignal.MOTOR_FRONT_C],
        values[VehicleSignal.MOTOR_REAR_LEFT_C],
        values[VehicleSignal.MOTOR_REAR_RIGHT_C],
    )

    val hottestMotorC: Double? = motorTemps.fold(null as Double?) { hottest, reading ->
        if (reading != null && (hottest == null || reading > hottest)) reading else hottest
    }

    /**
     * The mean of what the consumption window spent, worked out once here rather than per frame.
     *
     * [consumption] is already the window - the hub puts the tail in the snapshot - so this is one
     * pass over thirty numbers per sweep instead of a filter, a list and an average per frame.
     */
    val consumptionMean: Double? = ConsumptionWindow.mean(consumption)

    // ------------------------------------------------------------- combustion

    /**
     * Null when the signal has not answered yet, which is not the same as
     * stopped. Measured on the car across a full start/stop cycle this reads `0`
     * stopped and `3` running, including while spinning down; no other value has
     * been seen.
     */
    val engineRunning: Boolean? get() = this[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }

    val engineRpm: Double? get() = this[VehicleSignal.ENGINE_RPM]

    /** Kilowatts the engine is putting into the pack; 0 with the engine off. */
    val generationKw: Double? get() = this[VehicleSignal.GENERATION_KW]

    /**
     * Measured 2026-08-23: the state reads `1` while generating and `2` through
     * the shutdown, where the kilowatt figure has already fallen to zero and the
     * engine is only spinning down. Only `1` counts, so the dashboard stops claiming
     * generation a second and a half before the engine actually stops.
     */
    val generating: Boolean
        get() = this[VehicleSignal.GENERATION_STATE] == GENERATION_ON ||
            (generationKw ?: 0.0) > GENERATION_FLOOR_KW

    private companion object {
        /** Below this a generation reading is rounding, not the engine working. */
        const val GENERATION_FLOOR_KW = 0.5

        /** `2` is the shutdown transition, not generation. */
        const val GENERATION_ON = 1.0
    }
}
