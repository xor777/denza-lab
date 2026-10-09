package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a claim on the vehicle hub asks of its loop. `VehicleTelemetryHub.setActive` carries these
 * answers out and decides nothing itself; the hub cannot be built off the car, so the rules are
 * held here.
 */
class VehicleClaimsTest {
    private val claims = VehicleClaims()

    @Test
    fun theLedgerAloneStartsTheLoopAtASecondWithNoColdSweepOwed() {
        val change = claims.set(VehicleWatcher.LEDGER, true)!!

        assertTrue(change.polling)
        assertEquals(VehicleSweepCadence.LEDGER_INTERVAL_MS, change.sweepMs)
        assertTrue(change.wake)
        assertFalse("a new loop's first sweep is cold anyway", change.coldAtOnce)
        assertFalse(change.stop)
    }

    /** The trip clock reads only P, which is hot; its claim wakes the loop and costs no cold batch. */
    @Test
    fun theTripClocksClaimWakesTheLoopAtTheLedgersCadenceAndForcesNoColdSweep() {
        claims.set(VehicleWatcher.LEDGER, true)

        val change = claims.set(VehicleWatcher.TRIP, true)!!

        assertTrue(change.wake)
        assertFalse(change.coldAtOnce)
        assertEquals(VehicleSweepCadence.LEDGER_INTERVAL_MS, change.sweepMs)
    }

    @Test
    fun aScreenIsOwedItsTemperaturesAtOnceAndTheScreensCadence() {
        claims.set(VehicleWatcher.LEDGER, true)
        claims.set(VehicleWatcher.TRIP, true)

        val change = claims.set(VehicleWatcher.STRIP, true)!!

        assertTrue(change.coldAtOnce)
        assertTrue(change.wake)
        assertEquals(VehicleSweepCadence.HOT_INTERVAL_MS, change.sweepMs)
    }

    @Test
    fun aScreenGoingLeavesTheOthersPolling() {
        claims.set(VehicleWatcher.LEDGER, true)
        claims.set(VehicleWatcher.STRIP, true)

        val change = claims.set(VehicleWatcher.STRIP, false)!!

        assertTrue(change.polling)
        assertFalse(change.stop)
        assertFalse(change.wake)
        assertFalse(change.coldAtOnce)
        assertEquals(VehicleSweepCadence.LEDGER_INTERVAL_MS, change.sweepMs)
    }

    /** Were the ledger's claim ever dropped, the trip clock alone would still keep P polled. */
    @Test
    fun theTripClockAloneKeepsTheLoopGoingAndTheLastClaimStopsIt() {
        claims.set(VehicleWatcher.TRIP, true)
        claims.set(VehicleWatcher.LEDGER, true)

        val ledgerGone = claims.set(VehicleWatcher.LEDGER, false)!!
        assertTrue(ledgerGone.polling)
        assertFalse(ledgerGone.stop)

        val tripGone = claims.set(VehicleWatcher.TRIP, false)!!
        assertFalse(tripGone.polling)
        assertTrue(tripGone.stop)
    }

    @Test
    fun aClaimThatAlreadyStandsAsksNothing() {
        claims.set(VehicleWatcher.TRIP, true)

        assertNull(claims.set(VehicleWatcher.TRIP, true))
        assertNull(claims.set(VehicleWatcher.CLUSTER, false))
    }
}
