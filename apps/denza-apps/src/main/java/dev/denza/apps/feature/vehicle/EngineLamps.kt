package dev.denza.apps.feature.vehicle

/** What a lamp is saying right now. */
internal enum class LampState {
    /** Nothing answered; the panel shows a hollow dot, not a green one. */
    UNKNOWN,
    OK,
    ALERT,
}

/**
 * The engine page's warning lamps, each one folded from the several feature ids
 * that carry it.
 *
 * Four separate ids report low oil pressure on this firmware and four report low
 * coolant level. They are generation variants of the same lamp — the same thing
 * the motor temperatures did with `_DM40_464` — so the panel reads all of them
 * and takes the worst answer rather than betting on which one this car uses.
 *
 * Every one of these read `0` on 2026-08-23 with a healthy car. That proves they
 * are readable; it does not prove they light. "Zero means healthy" is what the
 * vendor's own constant names claim, and short of causing a fault there is no
 * way to check it — so a lamp that never answered stays [LampState.UNKNOWN]
 * instead of quietly reading as fine.
 */
internal enum class EngineLamp(val label: String, val signals: List<VehicleSignal>) {
    COOLANT_LEVEL(
        "уровень ОЖ",
        listOf(
            VehicleSignal.COOLANT_LEVEL_LOW_A,
            VehicleSignal.COOLANT_LEVEL_LOW_B,
            VehicleSignal.COOLANT_LEVEL_LOW_C,
            VehicleSignal.COOLANT_LEVEL_LOW_D,
        ),
    ),
    COOLANT_TEMP("перегрев ОЖ", listOf(VehicleSignal.COOLANT_TEMP_HIGH)),
    MOTOR_COOLANT("ОЖ мотора", listOf(VehicleSignal.MOTOR_COOLANT_TEMP_HIGH)),
    OIL_LEVEL(
        "уровень масла",
        listOf(
            VehicleSignal.OIL_LEVEL_LAMP,
            VehicleSignal.OIL_LEVEL_LOW,
            VehicleSignal.OIL_LEVEL_HIGH,
        ),
    ),
    OIL_PRESSURE(
        "давление масла",
        listOf(
            VehicleSignal.OIL_PRESSURE_LOW_A,
            VehicleSignal.OIL_PRESSURE_LOW_B,
            VehicleSignal.OIL_PRESSURE_LOW_C,
            VehicleSignal.OIL_PRESSURE_LOW_D,
        ),
    ),
    OIL_MONITOR("контроль масла", listOf(VehicleSignal.OIL_MONITOR_FAULT)),
    OIL_LIFE("ресурс масла", listOf(VehicleSignal.OIL_LIFE_DUE)),
    TRANSMISSION_OIL("масло КПП", listOf(VehicleSignal.TRANSMISSION_OIL_TEMP_HIGH)),
}
