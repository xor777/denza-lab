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
        ).forEach { watchers ->
            assertEquals(
                "$watchers",
                VehicleSweepCadence.HOT_INTERVAL_MS,
                VehicleSweepCadence.intervalMs(watchers),
            )
        }
    }

    /**
     * And the two cadences are an order of magnitude apart, which is the whole of the decision.
     *
     * A ledger sweeping at the screens' rate would keep the shell busy some sixty per cent of the
     * time for the life of the process, and the integral needs no more than a reading a second: a
     * hundred-metre bucket is several seconds of road at any speed worth measuring.
     */
    @Test
    fun theTwoCadencesAreTenToOne() {
        assertEquals(
            10L,
            VehicleSweepCadence.LEDGER_INTERVAL_MS / VehicleSweepCadence.HOT_INTERVAL_MS,
        )
    }

    /** Nobody watching at all is the same cadence as the ledger; the loop is not running anyway. */
    @Test
    fun nothingWatchingIsNotTheScreensCadence() {
        assertEquals(
            VehicleSweepCadence.LEDGER_INTERVAL_MS,
            VehicleSweepCadence.intervalMs(emptySet()),
        )
    }

    /** The watchers say which of them is a screen, so the cadence is not a list of names. */
    @Test
    fun onlyTheTwoScreensAreOnScreen() {
        assertTrue(VehicleWatcher.CLUSTER.onScreen)
        assertTrue(VehicleWatcher.STRIP.onScreen)
        assertFalse("the ledger is nobody looking", VehicleWatcher.LEDGER.onScreen)
        assertEquals(3, VehicleWatcher.entries.size)
    }
}
