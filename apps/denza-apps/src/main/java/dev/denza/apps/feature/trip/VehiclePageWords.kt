package dev.denza.apps.feature.trip

import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * What the car's page says, and when it says nothing.
 *
 * These decisions are not statements about pixels, so they are here rather than inside a `Canvas`
 * call where nothing could read them back. The Contour keeps `ContourReadout` for the same reason
 * and after the same lesson: the sentence, not the drawing, is what a reader was getting wrong.
 *
 *  - **a zero is never drawn, and a quantity that did not happen has no cell.** The engine's cell
 *    is absent until the engine has run, and it leaves no hole that has to be filled with `0`;
 *  - **a caption names what the figure beside it is.**
 *
 * The direction of the pack's flow, the consumption and its window all left this file with the
 * energy display contract: they are the same quantities the cluster prints, and they are decided
 * once for both screens in `EnergyReadouts`. What is here is what only this page says.
 */
internal object VehiclePageWords {

    /**
     * The engine's one cell, and the three things it can say.
     *
     * Turning, it is the revolutions. Just stopped, it is how long it ran this trip - which is
     * [ContourReadout.TITLE_ENGINE_MINUTES], the cluster's own words, in this screen's own case.
     * Otherwise nothing at all: a zero here would be an accountant's way of saying the engine did
     * not run, which is the sentence the Contour's sixth pass exists to have deleted, and a run
     * under a minute is a zero with a unit on it.
     *
     * **«Just stopped» is the trace, not the trip**, and that is the owner's own finding from the
     * first drive: he got into the car, had not started the engine, and the cell said «3 мин за
     * поездку». It was not lying - a trip runs from the first movement after P to the next one, so
     * yesterday's drive was still the trip - but a cell that says «за поездку» beside a cold engine
     * is read as *this* drive, and being technically right is not an answer.
     *
     * So the cell lives as long as the engine's trace holds a slot the engine was alive in, which
     * is a hundred and twenty seconds with no timer of its own. Sit down with a cold engine and
     * there is no cell; stop at a light after the engine has been running and the figure is there
     * while it still means something.
     */
    fun engineCell(telemetry: VehicleTelemetry): Pair<String, String>? {
        val rpm = telemetry.engineRpm
        if (telemetry.engineRunning == true && rpm != null && rpm > 0.0) {
            return TITLE_RPM to ContourReadout.whole(rpm)
        }
        if (telemetry.engineTrace.isEmpty) return null
        val minutes = telemetry.trip.engineMinutes
        if (!telemetry.trip.engineRan || minutes < 1.0) return null
        return TITLE_ENGINE_MINUTES to ContourReadout.whole(minutes)
    }

    /**
     * What the pack is standing at, and it is a reading rather than a footnote now.
     *
     * It used to hang off the end of the shape's own caption - «ПОСЛЕДНИЕ 2 МИНУТЫ · 542 В» - and
     * the owner read it exactly as it was written: «как будто ему нигде место не нашлось, и его
     * пришпилили куда-то вниз. Непонятно, к чему относится». It belongs beside the kilowatts,
     * because on this pack it is the kilowatts that move it: the resting voltage is flat across
     * the whole charge window - 550 V at 43 %, 551 V at 62 % - and what a driver sees change is
     * the sag under load.
     */
    fun volts(telemetry: VehicleTelemetry): Reading? {
        val volts = telemetry[VehicleSignal.PACK_VOLT] ?: return null
        return Reading(TITLE_VOLTS, ContourReadout.whole(volts), unit = UNIT_V)
    }

    /** A named figure: what it is, what it says, and whether it is still ordinary. */
    class Reading(
        val caption: String,
        val figure: String,
        val level: ContourReadout.Level = ContourReadout.Level.NORMAL,
        val unit: String = "",
    )

    const val TITLE_RPM = "ДВС · ОБ/МИН"
    const val TITLE_VOLTS = "НАПРЯЖЕНИЕ"
    const val TITLE_SPEND = "РАСХОД"

    /** The spread's unit, which the shelf's own row carries. */
    const val UNIT_MV = "мВ"
    const val UNIT_V = "В"

    /** The cluster's own words for the same reading, in this screen's own case. */
    val TITLE_ENGINE_MINUTES: String = ContourReadout.TITLE_ENGINE_MINUTES.uppercase()
}
