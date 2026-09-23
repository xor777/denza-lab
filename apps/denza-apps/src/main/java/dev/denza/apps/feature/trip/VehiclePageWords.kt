package dev.denza.apps.feature.trip

/**
 * What the car's page says that the cluster does not, and nothing else.
 *
 * The direction of the pack's flow, the power, the engine's cell, the trip, the consumption and its
 * window all come from `EnergyReadouts`: they are the same quantities the cluster prints, decided
 * once for both screens, and since the Luminofor strip they are printed there in its sentence case
 * as well. What is left here is what only this page names - the voltage, which the cluster heads
 * with the pack's own name and its unit, the word in front of the consumption, which the cluster has
 * no room for, and the one caption the page has when the car is closed to it.
 */
internal object VehiclePageWords {

    const val VOLTS = "Напряжение"
    const val UNIT_V = "В"
    const val SPEND = "Расход"

    /** Over the instruction, when the shell is closed to us: what the page would have shown. */
    const val CLOSED = "Питание от машины"
}
