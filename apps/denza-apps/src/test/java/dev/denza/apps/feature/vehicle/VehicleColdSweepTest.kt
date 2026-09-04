package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slow half of a sweep, and the one thing it must do: forget.
 *
 * The Contour has a single rule for a stale reading - the value goes two seconds after its last
 * sample and its caption stays - and that rule is only meaningful if "absent from the snapshot"
 * means the same thing at 300 ms and at ten seconds. Carrying the previous cold answers forward
 * would make a temperature that stopped answering read as fresh forever, which is the one way this
 * panel could show a number that is no longer true.
 */
class VehicleColdSweepTest {

    private fun cold() = LinkedHashMap<VehicleSignal, Double>()

    @Test
    fun aColdValueThatStoppedAnsweringLeavesTheMap() {
        val cold = cold()
        VehicleColdSweep.rebuild(
            cold,
            mapOf(VehicleSignal.PACK_TEMP_AVG to 31.0, VehicleSignal.INVERTER_C to 44.0),
        )
        assertEquals(31.0, cold[VehicleSignal.PACK_TEMP_AVG]!!, 1e-9)

        // The next cold sweep answered on one of the two ids and not the other.
        VehicleColdSweep.rebuild(cold, mapOf(VehicleSignal.INVERTER_C to 46.0))

        assertNull("the pack temperature is gone, not remembered", cold[VehicleSignal.PACK_TEMP_AVG])
        assertEquals(46.0, cold[VehicleSignal.INVERTER_C]!!, 1e-9)
    }

    @Test
    fun aSweepThatAnsweredNothingLeavesNothing() {
        val cold = cold()
        VehicleColdSweep.rebuild(cold, mapOf(VehicleSignal.PACK_TEMP_AVG to 31.0))
        VehicleColdSweep.rebuild(cold, emptyMap())

        assertTrue(cold.isEmpty())
    }

    @Test
    fun theHotHalfOfASweepIsNotCarriedInTheColdMap() {
        val cold = cold()
        // The batch answers both halves at once. Hot values are either fresh or absent and are
        // merged per sweep; carrying one here is exactly the stale kilowatt figure the panel
        // promises never to draw.
        VehicleColdSweep.rebuild(
            cold,
            mapOf(
                VehicleSignal.POWER_KW to 34.0,
                VehicleSignal.ODOMETER_KM to 12_345.0,
                VehicleSignal.PACK_TEMP_AVG to 31.0,
            ),
        )

        assertFalse(cold.containsKey(VehicleSignal.POWER_KW))
        assertFalse(cold.containsKey(VehicleSignal.ODOMETER_KM))
        assertEquals(1, cold.size)
        cold.keys.forEach { assertEquals(VehiclePoll.COLD, it.poll) }
    }
}
