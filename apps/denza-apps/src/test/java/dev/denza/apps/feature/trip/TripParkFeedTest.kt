package dev.denza.apps.feature.trip

import dev.denza.apps.feature.vehicle.AutoserviceShell
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import dev.denza.apps.feature.vehicle.VehicleWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trip clock's P, read from the vehicle hub's sweep.
 *
 * The clock's own reader parsed `service call autoservice 5 i32 1011 i32 89129008` itself until
 * 2026-10-09 (`TripParkSignalTest`). The same live-proven answers go through the hub's parser and
 * its snapshot here, and must reach the engine as they did: `1` is P, `0` is not, and an empty
 * answer or a third value is no answer at all. And they must reach it whether or not the strip is
 * drawing frames: the claim, the second's tick, the frame and the fix all go through here.
 */
class TripParkFeedTest {
    private val engine = TripEngine()

    /** The vehicle hub as the feed sees it: its latest sweep, and the claims made on it. */
    private var hubSweep = VehicleTelemetry()
    private val claims = ArrayList<Pair<VehicleWatcher, Boolean>>()

    /** The main looper as the feed sees it: what is waiting, and a clock to run it on. */
    private var now = 0L
    private val waiting = ArrayList<Pair<Runnable, Long>>()

    private val feed = TripParkFeed(
        engine = engine,
        telemetry = { hubSweep },
        claim = { watcher, value -> claims += watcher to value },
        clock = { now },
        schedule = { block, delayMs -> waiting += block to now + delayMs },
        unschedule = { block -> waiting.removeAll { it.first === block } },
    )

    /** Lets [ms] pass on the main looper with no frame drawn, running whatever falls due. */
    private fun idle(ms: Long) {
        val until = now + ms
        while (true) {
            val due = waiting.filter { it.second <= until }.minByOrNull { it.second } ?: break
            waiting.remove(due)
            now = due.second
            due.first.run()
        }
        now = until
    }

    /** One sweep of the hub that asked only for the park switch and got [answer]. */
    private fun sweep(answer: String): VehicleTelemetry {
        val values = AutoserviceShell.parse("@@0\n$answer", listOf(VehicleSignal.GEARBOX_PARK))
        return VehicleTelemetry(access = VehicleAccess.READY, values = values)
    }

    private fun drive(fromMs: Long) {
        repeat(5) { second ->
            val t = fromMs + second * 1_000L
            engine.onLocation(
                nowElapsedMs = t,
                wallMs = t,
                tzOffsetMinutes = 180,
                latitude = 55.0,
                longitude = 37.0 + second * 0.002,
                altitude = 0.0,
                hasAltitude = false,
                verticalAccuracyMeters = 5.0,
                hasVerticalAccuracy = true,
                speed = 10.0,
            )
        }
    }

    @Test
    fun theLiveProvenParkAndDriveWordsReachTheEngine() {
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000001   '........')"), 0L)
        assertEquals(true, engine.parked)

        feed.onTelemetry(sweep("Result: Parcel(00000000 00000000   '........')"), 1_000L)
        assertEquals(false, engine.parked)
    }

    @Test
    fun missingAndUnknownAnswersAreNoAnswer() {
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000001   '........')"), 0L)

        feed.onTelemetry(sweep(""), 1_000L)
        assertNull(engine.parked)

        feed.onTelemetry(sweep("Result: Parcel(00000000 00000003   '........')"), 2_000L)
        assertNull(engine.parked)
    }

    @Test
    fun aFailedSweepIsNoAnswerAndLeavesTheTripRunning() {
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000000   '........')"), 0L)
        drive(fromMs = 0L)
        assertTrue(engine.tripStarted)

        // A dropped read carries no hot value, the park switch included.
        feed.onTelemetry(VehicleTelemetry(access = VehicleAccess.READY, dropped = true), 5_000L)

        assertNull(engine.parked)
        assertTrue(engine.tripStarted)
    }

    @Test
    fun enteringPEndsTheTripOnTheSweepThatSaysSo() {
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000000   '........')"), 0L)
        drive(fromMs = 0L)
        assertTrue(engine.tripStarted)

        feed.onTelemetry(sweep("Result: Parcel(00000000 00000001   '........')"), 5_000L)

        assertFalse(engine.tripStarted)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
    }

    @Test
    fun oneSweepIsHandedOnceHoweverManyFramesDrawIt() {
        val parked = sweep("Result: Parcel(00000000 00000001   '........')")
        feed.onTelemetry(parked, 0L)
        assertEquals(true, engine.parked)

        // The engine has moved on since; the frames still drawing that same sweep are not news.
        engine.onParkState(false, 1_000L)
        repeat(30) { frame -> feed.onTelemetry(parked, 1_000L + frame * 33L) }
        assertEquals(false, engine.parked)

        // The next sweep is, even with the same answer.
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000001   '........')"), 2_000L)
        assertEquals(true, engine.parked)
    }

    @Test
    fun pHoldsTheMovementGateShut() {
        feed.onTelemetry(sweep("Result: Parcel(00000000 00000001   '........')"), 0L)

        drive(fromMs = 0L)

        assertFalse(engine.tripStarted)
    }

    @Test
    fun theStripClaimsTheHubForTheTripWhileItRunsAndOnlyThen() {
        feed.start()
        feed.start()
        assertEquals(listOf(VehicleWatcher.TRIP to true), claims)

        feed.stop()
        feed.stop()
        assertEquals(listOf(VehicleWatcher.TRIP to true, VehicleWatcher.TRIP to false), claims)
    }

    @Test
    fun startingHandsOnWhatTheHubAlreadyKnows() {
        hubSweep = sweep("Result: Parcel(00000000 00000001   '........')")

        feed.start()

        assertEquals(true, engine.parked)
    }

    /**
     * The strip's frames stall - the screen dark, the activity still up - while the car leaves P and
     * comes back. The second's tick still hands every sweep on, as the old reader did.
     */
    @Test
    fun pReachesTheEngineEverySecondWithNoFrameDrawn() {
        hubSweep = sweep("Result: Parcel(00000000 00000001   '........')")
        feed.start()

        hubSweep = sweep("Result: Parcel(00000000 00000000   '........')")
        idle(999L)
        assertEquals("not yet", true, engine.parked)
        idle(1L)
        assertEquals(false, engine.parked)

        drive(fromMs = now)
        assertTrue(engine.tripStarted)

        hubSweep = sweep("Result: Parcel(00000000 00000001   '........')")
        idle(1_000L)
        assertEquals(true, engine.parked)
        assertFalse("P ended the trip without a frame", engine.tripStarted)
    }

    @Test
    fun aStoppedFeedHandsNothingOnAndLeavesNothingWaiting() {
        hubSweep = sweep("Result: Parcel(00000000 00000000   '........')")
        feed.start()
        feed.stop()

        hubSweep = sweep("Result: Parcel(00000000 00000001   '........')")
        idle(5_000L)

        assertEquals(false, engine.parked)
        assertTrue(waiting.isEmpty())
    }

    /** `TripSensorHub.tick` on every frame and `onLocationChanged` on every fix call [TripParkFeed.feed]. */
    @Test
    fun aFrameOrAFixHandsTheLatestSweepOnAtOnce() {
        hubSweep = sweep("Result: Parcel(00000000 00000000   '........')")
        feed.start()
        hubSweep = sweep("Result: Parcel(00000000 00000001   '........')")

        now += 100L
        feed.feed()

        assertEquals("sooner than the second's tick", true, engine.parked)
    }
}
