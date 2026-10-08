package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the hub tells the screens the car is closed to them, and when it lets the panel's own
 * staleness rule speak instead (`docs/energy-display-contract.md` §4).
 *
 * The times are the loop's own: a hot read times out after three seconds, a cold one after eight,
 * the first backoff is four seconds, and a quiet bus fails a sweep every hundred milliseconds.
 */
class VehicleLinkTest {

    @Test
    fun theHorizonIsTwoHotHorizons() {
        assertEquals(4_000L, VehicleLink.CLOSE_AFTER_MS)
    }

    @Test
    fun oneTimedOutReadIsADroppedReadAndNotAClosedCar() {
        val link = VehicleLink()
        link.answered(0L)

        // Was: the Contour became a sentence for the four seconds of the backoff, and came back.
        assertFalse(link.failed(VehicleReadFailure.CHANNEL, 3_100L))

        link.answered(7_300L)
        assertFalse(link.failed(VehicleReadFailure.CHANNEL, 10_400L))
    }

    @Test
    fun aColdReadsLongTimeoutAloneIsStillADroppedRead() {
        val link = VehicleLink()
        link.answered(0L)
        assertFalse(link.failed(VehicleReadFailure.CHANNEL, 8_100L))
    }

    @Test
    fun theRetryAfterTheBackoffFailingTooClosesIt() {
        val link = VehicleLink()
        link.answered(0L)
        assertFalse(link.failed(VehicleReadFailure.CHANNEL, 3_100L))
        assertTrue(link.failed(VehicleReadFailure.CHANNEL, 7_150L))
    }

    @Test
    fun aMissingKeyClosesItAtOnce() {
        val link = VehicleLink()
        link.answered(0L)
        assertTrue(link.failed(VehicleReadFailure.AUTHORIZATION, 120L))
    }

    @Test
    fun aShellThatNeverAnsweredClosesOnItsSecondAttempt() {
        // The process came up before adbd did: refused at once, and again after the backoff.
        val link = VehicleLink()
        assertFalse(link.failed(VehicleReadFailure.CHANNEL, 50_000L))
        assertTrue(link.failed(VehicleReadFailure.CHANNEL, 54_000L))
    }

    @Test
    fun aQuietBusIsAnOrdinaryFailedReadUntilItHasBeenQuietForTheHorizon() {
        val link = VehicleLink()
        link.answered(0L)
        var at = 100L
        while (at < VehicleLink.CLOSE_AFTER_MS) {
            assertFalse("at $at ms", link.failed(VehicleReadFailure.NO_ANSWER, at))
            at += 100L
        }
        assertTrue(link.failed(VehicleReadFailure.NO_ANSWER, at))
    }

    @Test
    fun closedStaysClosedUntilSomethingAnswers() {
        val link = VehicleLink()
        link.answered(0L)
        assertTrue(link.failed(VehicleReadFailure.AUTHORIZATION, 100L))
        // The key was the reason; a socket that then drops does not reopen the panel.
        assertTrue(link.failed(VehicleReadFailure.CHANNEL, 4_200L))

        link.answered(9_000L)
        assertFalse("an answer reopens it", link.failed(VehicleReadFailure.CHANNEL, 12_100L))
    }

    @Test
    fun aDroppedReadIsNothingTheCarSaidAfterTheFailure() {
        val cold = mapOf(VehicleSignal.PACK_TEMP_AVG to 28.0)
        val trip = TripEnergy(netKwh = 9.3, kilometres = 42.0)
        val read = VehicleDroppedRead.snapshot(
            previous = VehicleAccess.READY,
            cold = cold,
            consumption = emptyList(),
            chart = ConsumptionChartSnapshot.EMPTY,
            engineTrace = EngineTraceSnapshot.EMPTY,
            trip = trip,
        )

        assertTrue(read.dropped)
        assertEquals(VehicleAccess.READY, read.access)
        assertEquals("", read.message)
        assertEquals("the cold values carry, no hot one does", cold, read.values)
        assertEquals(trip, read.trip)

        val starting = VehicleDroppedRead.snapshot(
            previous = VehicleAccess.STARTING,
            cold = emptyMap(),
            consumption = emptyList(),
            chart = ConsumptionChartSnapshot.EMPTY,
            engineTrace = EngineTraceSnapshot.EMPTY,
            trip = TripEnergy(),
        )
        assertEquals("a failure is not an answer", VehicleAccess.STARTING, starting.access)
    }
}
