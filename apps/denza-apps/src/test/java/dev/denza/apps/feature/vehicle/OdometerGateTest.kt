package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one place either integral decides what a reading of the road is worth.
 *
 * There were two copies of this and six of its constants, in two files whose subjects are
 * kilowatt-hours and trips. The rules belong to neither: they are about the odometer this car
 * reports and the cadence this app reads it at.
 */
class OdometerGateTest {

    @Test
    fun aReadingThatDidNotAnswerIsNotASample() {
        // Not zero road. Without the odometer there is no telling a car standing still from a read
        // that failed, and either answer invented is an invented number.
        val gate = OdometerGate()
        assertEquals(OdometerGate.Step.UNREAD, gate.step(null))
        assertEquals(0.0, gate.deltaKm, 1e-9)
        assertNull(gate.lastKm)
    }

    @Test
    fun theFirstReadingSeedsAndMeasuresNothing() {
        val gate = OdometerGate()
        assertEquals(OdometerGate.Step.SEEDED, gate.step(1_000.0))
        assertEquals(0.0, gate.deltaKm, 1e-9)
        assertEquals(1_000.0, gate.lastKm!!, 1e-9)
        assertEquals(OdometerGate.Step.ROAD, gate.step(1_000.1))
        assertEquals(0.1, gate.deltaKm, 1e-9)
    }

    @Test
    fun aReadingThatWentBackwardsIsAnotherCarAndNotADescent() {
        val gate = OdometerGate()
        gate.step(1_000.0)
        assertEquals(OdometerGate.Step.REANCHORED, gate.step(900.0))
        assertEquals(0.0, gate.deltaKm, 1e-9)
        // The reading is believed even so - it is the car's own - and the road between is not.
        assertEquals(900.0, gate.lastKm!!, 1e-9)
        assertEquals(OdometerGate.Step.ROAD, gate.step(900.1))
    }

    @Test
    fun aJumpNoIntervalCanExplainIsADriveNobodyWasWatching() {
        val gate = OdometerGate()
        gate.step(1_000.0)
        assertEquals(OdometerGate.Step.ROAD, gate.step(1_000.0 + OdometerGate.MAX_JUMP_KM))
        gate.step(2_000.0)
        assertEquals(
            OdometerGate.Step.REANCHORED,
            gate.step(2_000.0 + OdometerGate.MAX_JUMP_KM + 0.001),
        )
    }

    @Test
    fun aStandingCarReadsTheSameNumberAndThatIsRoadOfZeroLength() {
        // The odometer's own resolution is a tenth, so a car in traffic reports the same reading
        // for a minute at a time. That is a legal interval covering no distance, not a re-anchor:
        // the energy spent standing in it is still spent.
        val gate = OdometerGate()
        gate.step(1_000.0)
        assertEquals(OdometerGate.Step.ROAD, gate.step(1_000.0))
        assertEquals(0.0, gate.deltaKm, 1e-9)
        // And a reading a hair below the last one is that same standing car in floating point.
        assertEquals(OdometerGate.Step.ROAD, gate.step(1_000.0 - OdometerGate.KM_EPSILON / 2))
        assertEquals(0.0, gate.deltaKm, 1e-9)
    }

    @Test
    fun forgettingMakesTheNextReadingASeedAgain() {
        val gate = OdometerGate()
        gate.step(1_000.0)
        gate.forget()
        assertNull(gate.lastKm)
        assertEquals(OdometerGate.Step.SEEDED, gate.step(1_000.0))
    }

    @Test
    fun anchoringBelievesAReadingWithoutMeasuringAgainstIt() {
        // What restoring from a journal does: the record says where the car was, and the first
        // sweep after it is an ordinary interval rather than a seed.
        val gate = OdometerGate()
        gate.anchor(1_000.0)
        assertEquals(1_000.0, gate.lastKm!!, 1e-9)
        assertEquals(OdometerGate.Step.ROAD, gate.step(1_000.2))
        assertEquals(0.2, gate.deltaKm, 1e-9)
    }

    @Test
    fun bothIntegralsAreHeldToOneSetOfNumbers() {
        assertEquals(8.0, OdometerGate.MAX_GAP_SECONDS, 1e-9)
        assertEquals(5.0, OdometerGate.MAX_JUMP_KM, 1e-9)
        assertEquals(1e-6, OdometerGate.KM_EPSILON, 1e-12)
    }
}
