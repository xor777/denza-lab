package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How fast the hub asks the car, and the rule is about who is watching.
 *
 * `docs/energy-display-contract.md` §2.7. The loop used to run only while a screen held a claim,
 * so the ten-kilometre history existed only while somebody was looking at it; the ledger's claim
 * is what keeps it running, and this is what keeps that from costing the shell a hundred
 * milliseconds at a time for the life of the process.
 *
 * Tested here rather than through the loop because the loop is a coroutine over a live shell: the
 * cadence is a function of a set, and a function of a set is testable.
 */
class VehicleSweepCadenceTest {

    @Test
    fun theLedgerAloneSweepsOnceASecond() {
        assertEquals(
            1_000L,
            VehicleSweepCadence.intervalMs(setOf(VehicleWatcher.LEDGER)),
        )
        assertEquals(1_000L, VehicleSweepCadence.LEDGER_INTERVAL_MS)
    }

    @Test
    fun anyScreenOnTopOfItIsTheScreensOwnHundredMilliseconds() {
        assertEquals(100L, VehicleSweepCadence.HOT_INTERVAL_MS)
        listOf(
            setOf(VehicleWatcher.CLUSTER),
            setOf(VehicleWatcher.STRIP),
            setOf(VehicleWatcher.LEDGER, VehicleWatcher.CLUSTER),
            setOf(VehicleWatcher.LEDGER, VehicleWatcher.STRIP),
            setOf(VehicleWatcher.LEDGER, VehicleWatcher.CLUSTER, VehicleWatcher.STRIP),
            setOf(VehicleWatcher.LEDGER, VehicleWatcher.TRIP, VehicleWatcher.STRIP),
            setOf(VehicleWatcher.TRIP, VehicleWatcher.CLUSTER),
        ).forEach { watchers ->
            assertEquals(
                "$watchers",
                VehicleSweepCadence.HOT_INTERVAL_MS,
                VehicleSweepCadence.intervalMs(watchers),
            )
        }
    }

    /**
     * The trip clock reads P from the sweep, and wants it about once a second - the cadence its own
     * reader had. Its claim is not a screen: with the ledger it changes nothing, and alone (a
     * process where the ledger's claim were ever dropped) it still keeps the loop at a second.
     */
    @Test
    fun theTripClocksClaimIsTheLedgersSecondNotAScreensHundredMilliseconds() {
        assertFalse(VehicleWatcher.TRIP.onScreen)
        assertEquals(1_000L, VehicleSweepCadence.intervalMs(setOf(VehicleWatcher.TRIP)))
        assertEquals(
            1_000L,
            VehicleSweepCadence.intervalMs(setOf(VehicleWatcher.LEDGER, VehicleWatcher.TRIP)),
        )
    }

    /** What the trip clock asks for is in every sweep the loop makes, at either cadence. */
    @Test
    fun theParkSwitchIsInEverySweep() {
        assertTrue(VehicleSignal.GEARBOX_PARK in VehicleSignal.HOT)
    }

    /**
     * A screen that appears is owed its temperatures at once; a claim that draws nothing is not,
     * so the trip clock coming up costs no cold batch.
     */
    @Test
    fun onlyAScreenIsOwedAColdSweepAtOnce() {
        assertTrue(VehicleSweepCadence.coldAtOnce(VehicleWatcher.CLUSTER))
        assertTrue(VehicleSweepCadence.coldAtOnce(VehicleWatcher.STRIP))
        assertFalse(VehicleSweepCadence.coldAtOnce(VehicleWatcher.TRIP))
        assertFalse(VehicleSweepCadence.coldAtOnce(VehicleWatcher.LEDGER))
    }

    /** Nobody watching at all is the same cadence as the ledger; the loop is not running anyway. */
    @Test
    fun nothingWatchingIsNotTheScreensCadence() {
        assertEquals(
            VehicleSweepCadence.LEDGER_INTERVAL_MS,
            VehicleSweepCadence.intervalMs(emptySet()),
        )
    }
}
