package dev.denza.apps.feature.trip

/**
 * What the car's page says, and when it says nothing.
 *
 * These decisions are not statements about pixels, so they are here rather than inside a `Canvas`
 * call where nothing could read them back. The Contour keeps `ContourReadout` for the same reason
 * and after the same lesson: the sentence, not the drawing, is what a reader was getting wrong.
 *
 * The direction of the pack's flow, the consumption, its window and the engine's own cell all left
 * this file with the energy display contract: they are the same quantities the cluster prints, and
 * they are decided once for both screens in `EnergyReadouts`. The engine's cell was the last to go
 * - it lived here on the trace and on the cluster on the trip, so the same cold engine had a corner
 * on one screen and none on the other. What is left is what only this page says: the names it gives
 * two readings, and one unit the cluster has no room for.
 */
internal object VehiclePageWords {

    const val TITLE_VOLTS = "НАПРЯЖЕНИЕ"
    const val TITLE_SPEND = "РАСХОД"

    /** The spread's unit, which the shelf's own row carries. */
    const val UNIT_MV = "мВ"
    const val UNIT_V = "В"
}
