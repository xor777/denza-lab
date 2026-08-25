package dev.denza.apps.feature.vehicle

/** Why the panel has no numbers, when it has none. */
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
 * by [VehiclePanelRenderer].
 *
 * A signal missing from [values] is missing on purpose: the call went
 * unanswered, or the value could not be true for its unit. The renderer draws a
 * dash for it. Nothing here is ever defaulted to zero.
 */
internal data class VehicleTelemetry(
    val access: VehicleAccess = VehicleAccess.STARTING,
    val message: String = "",
    val values: Map<VehicleSignal, Double> = emptyMap(),
    /** Closed consumption bars, oldest first, kWh/100 km. */
    val consumption: List<Double> = emptyList(),
    /** Consumption over the last few hundred metres; null while standing. */
    val currentConsumption: Double? = null,
    /** True while the odometer is not advancing; the live figure holds off. */
    val stationary: Boolean = false,
    /** The last two minutes of revolutions and generation, on a one-second axis. */
    val engineTrace: EngineTraceSnapshot = EngineTraceSnapshot.EMPTY,
    /** Milliseconds the last shell sweep took; kept for diagnostics, not shown. */
    val sweepMillis: Int = 0,
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

    /** Cell spread has no feature id of its own; it is max minus min. */
    val cellSpreadMv: Double?
        get() {
            val min = this[VehicleSignal.CELL_MIN_MV] ?: return null
            val max = this[VehicleSignal.CELL_MAX_MV] ?: return null
            return max - min
        }

    /** Cell window as the pack actually reports it, in volts. */
    val cellWindowVolt: Pair<Double, Double>?
        get() {
            val min = this[VehicleSignal.CELL_MIN_MV] ?: return null
            val max = this[VehicleSignal.CELL_MAX_MV] ?: return null
            return (min / 1000.0) to (max / 1000.0)
        }

    val cellAverageVolt: Double?
        get() {
            val pack = this[VehicleSignal.PACK_VOLT] ?: return null
            val cells = this[VehicleSignal.CELL_COUNT] ?: return null
            if (cells < 1.0) return null
            return pack / cells
        }

    val insulationMohm: Double?
        get() = this[VehicleSignal.INSULATION_KOHM]?.let { it / 1000.0 }

    /**
     * The car's three drive motors in layout order: front, rear left, rear
     * right. The rear pair is per-side, not per-axle, so three separate
     * readings is the honest shape — one of them running hotter than the others
     * is exactly what the row is there to show.
     */
    val motorTemps: List<Double?>
        get() = listOf(
            this[VehicleSignal.MOTOR_FRONT_C],
            this[VehicleSignal.MOTOR_REAR_LEFT_C],
            this[VehicleSignal.MOTOR_REAR_RIGHT_C],
        )

    val hottestMotorC: Double? get() = motorTemps.filterNotNull().maxOrNull()

    // ------------------------------------------------------------- combustion

    /**
     * Null when the engine set has not been polled yet, which is not the same as
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
     * engine is only spinning down. Only `1` counts, so the page stops claiming
     * generation a second and a half before the engine actually stops.
     */
    val generating: Boolean
        get() = this[VehicleSignal.GENERATION_STATE] == GENERATION_ON ||
            (generationKw ?: 0.0) > GENERATION_FLOOR_KW

    // ------------------------------------------------------------------ tank

    val fuelPercent: Double? get() = this[VehicleSignal.FUEL_PERCENT]

    val fuelRangeKm: Double? get() = this[VehicleSignal.FUEL_RANGE_KM]

    /** Worst answer across the ids that carry this lamp. */
    fun lamp(lamp: EngineLamp): LampState {
        var seen = false
        lamp.signals.forEach { signal ->
            val value = this[signal] ?: return@forEach
            seen = true
            if (value >= 1.0) return LampState.ALERT
        }
        return if (seen) LampState.OK else LampState.UNKNOWN
    }

    val lampAlerts: List<EngineLamp> get() = EngineLamp.entries.filter { lamp(it) == LampState.ALERT }

    private companion object {
        /** Below this a generation reading is rounding, not the engine working. */
        const val GENERATION_FLOOR_KW = 0.5

        /** `2` is the shutdown transition, not generation. */
        const val GENERATION_ON = 1.0
    }
}
