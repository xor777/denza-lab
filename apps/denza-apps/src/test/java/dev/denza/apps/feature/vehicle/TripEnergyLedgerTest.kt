package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Contour's right shelf is allowed to say, proved on the arithmetic rather than on the car.
 *
 * Two rules carry the whole design of that shelf and both are here: **the first figure is the net**,
 * so the other two cells are what has already come back out of it rather than what adds to it; and
 * **regeneration is only counted while the engine is off**, because under generation a negative pack
 * flow is the engine charging and nothing on this bus can tell the two apart.
 */
class TripEnergyLedgerTest {

    /** One hour at a round power, so a kilowatt-hour is a kilowatt. */
    private val hour = 3600.0

    /**
     * A ledger that will integrate an hour-long step, so a test can state a round kilowatt-hour.
     *
     * The production gap of eight seconds is what stops a sleeping dashboard from multiplying one
     * stale reading by the minutes nobody watched, and it has a test of its own below.
     */
    private fun ledger() = TripEnergyLedger(maxGapSeconds = 2.0 * hour)

    private fun moving(
        ledger: TripEnergyLedger,
        odometerKm: Double,
        powerKw: Double? = null,
        generationKw: Double? = null,
        engineRunning: Boolean? = false,
        parked: Boolean? = false,
        seconds: Double = 1.0,
    ) = ledger.sample(odometerKm, powerKw, generationKw, engineRunning, parked, seconds)

    @Test
    fun theFirstFigureIsTheNetThatLeftTheBattery() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1)
        // Ten kilowatts out for an hour, then four kilowatts back for an hour.
        moving(ledger, 100.2, powerKw = 10.0, seconds = hour)
        moving(ledger, 100.3, powerKw = -4.0, seconds = hour)

        val trip = ledger.trip
        assertEquals("net", 6.0, trip.netKwh, 1e-9)
        assertEquals("recovered", 4.0, trip.recoveredKwh, 1e-9)
        // Six is what left for good; four is what came back and is already out of the six. The two
        // do not add up to anything, which is why the captions carry verbs.
        assertEquals(10.0, trip.netKwh + trip.recoveredKwh, 1e-9)
    }

    @Test
    fun regenerationIsNotCountedWhileTheEngineIsRunning() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = -6.0, engineRunning = true, generationKw = 6.0, seconds = hour)

        val trip = ledger.trip
        assertEquals("the pack still gained it", -6.0, trip.netKwh, 1e-9)
        assertEquals("but it was the engine, not a hill", 0.0, trip.recoveredKwh, 1e-9)
        assertEquals("and the engine's own cell says so", 6.0, trip.engineKwh, 1e-9)
    }

    @Test
    fun anEngineWhoseStateNeverAnsweredCountsNothingEitherWay() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = -6.0, engineRunning = null, generationKw = 6.0, seconds = hour)

        // Unknown is not "off": a figure that might be the engine's charge must not be printed
        // under the word РЕКУПЕРАЦИЯ.
        assertEquals(0.0, ledger.trip.recoveredKwh, 1e-9)
        assertEquals(0.0, ledger.trip.engineKwh, 1e-9)
        assertFalse(ledger.trip.engineRan)
    }

    @Test
    fun theEngineIsTimedInSecondsAndReadInMinutes() {
        val ledger = ledger()
        moving(ledger, 100.0)
        repeat(6) { moving(ledger, 100.1 + it * 0.1, engineRunning = true, seconds = 60.0) }

        assertEquals(360.0, ledger.trip.engineSeconds, 1e-9)
        assertEquals(6.0, ledger.trip.engineMinutes, 1e-9)
        assertTrue(ledger.trip.engineRan)
    }

    @Test
    fun kilometresComeFromTheOdometerAndNothingElse() {
        val ledger = ledger()
        moving(ledger, 1000.0)
        repeat(20) { moving(ledger, 1000.1 + it * 0.1) }

        assertEquals(2.0, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun aTripEndsAtTheFirstMovementAfterParkAndNotBefore() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = 10.0, seconds = hour)
        assertEquals(10.0, ledger.trip.netKwh, 1e-9)

        // In P: the finished trip stays on the shelf in full, which is exactly when it is read.
        repeat(5) { ledger.sample(100.1, 0.0, null, false, true, 60.0) }
        assertEquals("the trip is still there on P", 10.0, ledger.trip.netKwh, 1e-6)
        assertEquals(0.1, ledger.trip.kilometres, 1e-6)

        // And it is replaced only when the car sets off again.
        moving(ledger, 100.2, powerKw = 4.0, seconds = hour)
        assertEquals("a new trip begins with the movement", 4.0, ledger.trip.netKwh, 1e-9)
        assertEquals(0.1, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun standingStillWithoutParkKeepsTheTripGoing() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = 10.0, seconds = hour)
        // A long traffic light is not the end of a trip, and the odometer cannot tell it from one.
        repeat(20) { ledger.sample(100.1, 2.0, null, false, false, 60.0) }
        moving(ledger, 100.2)

        assertTrue("the trip carried on through the stop", ledger.trip.netKwh > 10.0)
        assertEquals(0.2, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun theFirstMovementOfTheProcessStartsATripRatherThanExtendingNothing() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = 10.0, seconds = hour)

        assertEquals(0.1, ledger.trip.kilometres, 1e-6)
        assertEquals(10.0, ledger.trip.netKwh, 1e-9)
    }

    @Test
    fun aReadingThatJumpedFurtherThanASampleCanExplainIsNotRoad() {
        val ledger = ledger()
        moving(ledger, 100.0)
        moving(ledger, 100.1)
        // The car was driven with the dashboard closed, or the reading re-anchored.
        moving(ledger, 400.0, powerKw = 10.0, seconds = hour)

        assertEquals("no invented kilometres", 0.1, ledger.trip.kilometres, 1e-6)
        assertEquals("and no invented energy", 0.0, ledger.trip.netKwh, 1e-9)
    }

    @Test
    fun aSampleGapLongerThanTheDashboardWasAwakeIsNotIntegrated() {
        val ledger = TripEnergyLedger()
        moving(ledger, 100.0)
        moving(ledger, 100.1, powerKw = 30.0, seconds = 600.0)

        assertEquals(0.0, ledger.trip.netKwh, 1e-9)
        assertEquals("the road it covered still counts", 0.1, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun aRestoredTripCarriesOnWhereTheProcessLeftIt() {
        val ledger = ledger()
        val record = TripRecord(
            energy = TripEnergy(9.3, 3.1, 1.1, 360.0, 42.0),
            odometerKm = 1000.0,
            armed = false,
        )
        assertTrue(ledger.restore(record, 1000.2))

        moving(ledger, 1000.3, powerKw = 1.0, seconds = hour)
        assertEquals(10.3, ledger.trip.netKwh, 1e-9)
        assertEquals(3.1, ledger.trip.recoveredKwh, 1e-9)
        assertEquals(1.1, ledger.trip.engineKwh, 1e-9)
        assertEquals(6.0, ledger.trip.engineMinutes, 1e-9)
        assertEquals(42.1, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun aRestoredTripThatWasArmedStillEndsAtTheNextMovement() {
        val ledger = ledger()
        val record = TripRecord(TripEnergy(9.3, 3.1, 1.1, 360.0, 42.0), 1000.0, armed = true)
        assertTrue(ledger.restore(record, 1000.0))

        assertEquals("still the finished trip while it stands", 9.3, ledger.trip.netKwh, 1e-9)
        moving(ledger, 1000.1, powerKw = 2.0, seconds = hour)
        assertEquals(2.0, ledger.trip.netKwh, 1e-9)
        assertEquals(0.1, ledger.trip.kilometres, 1e-6)
    }

    @Test
    fun aJournalFromRoadThisProcessNeverSawIsRefused() {
        val ledger = ledger()
        val record = TripRecord(TripEnergy(9.3, 3.1, 1.1, 360.0, 42.0), 1000.0, armed = false)

        // Driven with the app closed: the kilometres and the kilowatt-hours no longer describe the
        // same drive, and a trip figure that is wrong is worse than one that starts again.
        assertFalse(ledger.restore(record, 1050.0))
        // And a record from ahead of the car is another vehicle's, or another cluster's.
        assertFalse(ledger.restore(record, 999.0))
        assertEquals(0.0, ledger.trip.netKwh, 1e-9)
    }

    @Test
    fun thereIsNothingToJournalUntilTheOdometerHasAnswered() {
        val ledger = TripEnergyLedger()
        assertNull(ledger.record())
        ledger.sample(100.0, 10.0, null, false, false, 1.0)
        assertEquals(100.0, ledger.record()!!.odometerKm, 1e-9)
    }
}
