package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.ConsumptionWindow

/**
 * Every number the Contour prints, remembered by the value it was printed from.
 *
 * ### Why a panel needs one
 *
 * The Contour redraws at [ContourPace.FAST_FPS] because its band, hero, glow and revolutions are
 * critically damped followers, and a follower has to be integrated: the whole point of it is the
 * frames between two readings. Twenty-odd numbers are printed in each of those frames and almost
 * none of them have moved - a temperature answers once every ten seconds, an odometer once a
 * kilometre, a trip cell four times a second at most. Formatting each of them again is a
 * `String.format` and a fresh string per number per frame, which is over a thousand a second of
 * garbage on a view drawn over the vehicle's own instruments.
 *
 * So each number gets a **slot**, and a slot holds the last value it was asked about and the string
 * that came out. Ask again with the same value and the same string comes back; ask with a different
 * one and it is printed once more. Nothing is invalidated and nothing expires: the key is the
 * reading, and a reading that came back is the same answer.
 *
 * ### The rule the call sites owe this class
 *
 * A slot keys on a number and one flag, and nothing else - not on which function was called. **One
 * slot, one format, one call site.** A slot asked for [whole] in one frame and [tenth] in the next
 * would answer with the previous frame's string, and the panel would print «9» where it means
 * «9,3». `ContourFiguresTest` writes that rule down; the renderer is where it is kept.
 *
 * Not thread safe, and it does not need to be: it lives in `ClusterDashboardRenderer`, which is
 * only ever touched from the frame callback.
 */
internal class ContourFigures {

    /**
     * One printed number on the panel.
     *
     * The temperature row and the trip's seats are drawn through one function each, so they take a
     * run of slots addressed by index rather than a name apiece - [cell] and [seat].
     */
    enum class Slot {
        VOLTS,
        RPM,
        ENGINE_MINUTES,
        SPREAD,
        ODOMETER,
        GENERATION,

        /** The five cells of the temperature row, in the order [cell] indexes them. */
        CELL_0,
        CELL_1,
        CELL_2,
        CELL_3,
        CELL_4,

        /** The trip's three seats, in the order [seat] indexes them. */
        SEAT_0,
        SEAT_1,
        SEAT_2,

        /** The petal's figure, its unit, and what replaces the figure while a gun is in. */
        PETAL,
        PETAL_UNIT,
        CHARGE_LEFT,

        /** The engine box's own window, «ПОСЛЕДНИЕ 1:22». */
        WINDOW,
        ;
    }

    private val held = BooleanArray(Slot.entries.size)
    private val number = DoubleArray(Slot.entries.size)
    private val flag = BooleanArray(Slot.entries.size)
    private val printed = arrayOfNulls<String>(Slot.entries.size)

    /** A whole number: a voltage, a temperature, a revolution count, an odometer. */
    fun whole(slot: Slot, value: Double): String =
        hit(slot, value, false) ?: keep(slot, value, false, ContourReadout.whole(value))

    /** One cell of the temperature row, `0` being the pack and `4` the inverter. */
    fun cell(index: Int, celsius: Double): String =
        whole(Slot.entries[Slot.CELL_0.ordinal + index], celsius)

    /** One seat of the trip's shelf, always a tenth. */
    fun seat(index: Int, kwh: Double): String {
        val slot = Slot.entries[Slot.SEAT_0.ordinal + index]
        return hit(slot, kwh, false) ?: keep(slot, kwh, false, ContourReadout.tenth(kwh))
    }

    /** The petal's figure, which is a whole number on the move and a tenth standing still. */
    fun consumption(perHundredKm: Double, parked: Boolean): String =
        hit(Slot.PETAL, perHundredKm, parked)
            ?: keep(Slot.PETAL, perHundredKm, parked, ContourReadout.consumption(perHundredKm, parked))

    /**
     * The petal's unit, naming the road behind the figure.
     *
     * The window it is measured against is [ConsumptionWindow.KM] and cannot change under the
     * panel, so the covered distance is the whole key.
     */
    fun perHundredKm(coveredKm: Double): String =
        hit(Slot.PETAL_UNIT, coveredKm, false)
            ?: keep(
                Slot.PETAL_UNIT,
                coveredKm,
                false,
                ContourReadout.perHundredKm(coveredKm, ConsumptionWindow.KM),
            )

    /** What is left of a charge, in the petal's own seat. */
    fun chargeLeft(minutes: Int): String {
        val value = minutes.toDouble()
        return hit(Slot.CHARGE_LEFT, value, false)
            ?: keep(Slot.CHARGE_LEFT, value, false, ContourReadout.chargeLeft(minutes))
    }

    /** The engine box's sentence, whose window is the box's own reach in seconds. */
    fun intoPack(seconds: Int, short: Boolean): String {
        val value = seconds.toDouble()
        return hit(Slot.WINDOW, value, short)
            ?: keep(Slot.WINDOW, value, short, ContourReadout.intoPack(seconds, short))
    }

    private fun hit(slot: Slot, value: Double, mark: Boolean): String? {
        val index = slot.ordinal
        if (!held[index] || number[index] != value || flag[index] != mark) return null
        return printed[index]
    }

    private fun keep(slot: Slot, value: Double, mark: Boolean, text: String): String {
        val index = slot.ordinal
        held[index] = true
        number[index] = value
        flag[index] = mark
        printed[index] = text
        return text
    }

    companion object {
        /** How many cells the temperature row has, which is how many [cell] will answer for. */
        const val CELLS = 5
    }
}
