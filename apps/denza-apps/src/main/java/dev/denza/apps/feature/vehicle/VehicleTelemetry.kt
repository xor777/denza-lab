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
    /** The bar in progress, or the last closed one. */
    val currentConsumption: Double? = null,
    /** Milliseconds the last shell sweep took; kept for diagnostics, not shown. */
    val sweepMillis: Int = 0,
) {

    operator fun get(signal: VehicleSignal): Double? = values[signal]

    val charging: Boolean get() = (this[VehicleSignal.CHARGE_GUN] ?: 0.0) >= 1.0

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

    val tyrePressures: List<Double?>
        get() = listOf(
            this[VehicleSignal.TYRE_PRESSURE_LF],
            this[VehicleSignal.TYRE_PRESSURE_RF],
            this[VehicleSignal.TYRE_PRESSURE_LR],
            this[VehicleSignal.TYRE_PRESSURE_RR],
        )

    val tyreTemperatures: List<Double?>
        get() = listOf(
            this[VehicleSignal.TYRE_TEMP_LF],
            this[VehicleSignal.TYRE_TEMP_RF],
            this[VehicleSignal.TYRE_TEMP_LR],
            this[VehicleSignal.TYRE_TEMP_RR],
        )

    /** The hotter of the two rear sensors; the pair is per-side, not per-axle. */
    val motorRearC: Double?
        get() {
            val left = this[VehicleSignal.MOTOR_REAR_LEFT_C]
            val right = this[VehicleSignal.MOTOR_REAR_RIGHT_C]
            return when {
                left != null && right != null -> maxOf(left, right)
                else -> left ?: right
            }
        }
}
