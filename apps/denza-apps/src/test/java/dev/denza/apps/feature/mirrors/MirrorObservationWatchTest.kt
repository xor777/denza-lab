package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera on the cluster while the monitor can read nothing: the window poll's shell and the
 * lamp feed both gone with adbd. Before the deadline existed every ending of a camera (lamps,
 * card, frame stall, session timeout) was decided on a poll that succeeded, so none ran.
 */
class MirrorObservationWatchTest {
    @Test
    fun aCameraTheMonitorCanNoLongerReadClosesAtTheDeadlineThroughTheLampsOffStep() {
        // Left turn: the card and the lamps open the camera; the poll at 400 ms sees it ready.
        val watch = MirrorObservationWatch(startedAtMs = 0L)
        val started = MirrorTransitionReducer.reduce(
            MirrorTransitionState(),
            observation(MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.IDLE), 100L),
        )
        watch.read(100L)
        val shown = MirrorTransitionReducer.reduce(
            started.state,
            observation(MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.READY, MirrorSide.LEFT), 400L),
        )
        watch.read(400L)
        assertEquals(MirrorTransitionPhase.SHOWING, shown.state.phase)

        // adbd drops: every later poll throws, no lamp event comes. The driver cancels the turn.
        watch.failed("SocketTimeoutException Read timed out")
        assertFalse("one read that timed out is not yet a lost car", watch.lost(400L + 2_600L))
        assertTrue(watch.lost(400L + MirrorObservationWatch.DEADLINE_MS))

        // The deadline's teardown is the lamps-off step: an ordinary ending, not a failure.
        val closed = MirrorTransitionReducer.lampsLeft(
            shown.state,
            runtime(CameraRuntimePhase.READY, MirrorSide.LEFT),
            4_400L,
            "car not read",
        )
        assertEquals(MirrorTransitionPhase.IDLE, closed.phase)
        assertEquals(null, closed.failedSide)

        // The car is read again with the turn still on: the camera comes back after the settle.
        watch.read(6_000L)
        assertFalse(watch.lost(6_000L))
        val first = MirrorTransitionReducer.reduce(
            closed,
            observation(MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.IDLE), 6_000L, lampAtMs = 5_900L),
        )
        assertEquals(MirrorTransitionCommand.None, first.command)
        val second = MirrorTransitionReducer.reduce(
            first.state,
            observation(MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.IDLE), 6_100L, lampAtMs = 5_900L),
        )
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.LEFT), second.command)
    }

    @Test
    fun aReadThatSucceedsKeepsTheDeadlineAwayAndClearsTheError() {
        val watch = MirrorObservationWatch(startedAtMs = 0L)
        watch.failed("IOException")
        watch.read(3_900L)
        assertEquals(null, watch.lastError)
        assertFalse(watch.lost(3_900L + MirrorObservationWatch.DEADLINE_MS - 1L))
    }

    @Test
    fun aMonitorThatNeverReadTheCarIsLostFromItsStart() {
        val watch = MirrorObservationWatch(startedAtMs = 10_000L)
        assertFalse(watch.lost(10_000L + MirrorObservationWatch.DEADLINE_MS - 1L))
        assertTrue(watch.lost(10_000L + MirrorObservationWatch.DEADLINE_MS))
    }

    private fun observation(
        side: MirrorSide?,
        lamp: MirrorLamp,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        lampAtMs: Long = 50L,
    ) = MirrorTransitionObservation(
        stockSide = side,
        lamp = lamp,
        lampObservedAtMs = lampAtMs,
        runtime = runtime,
        nowMs = nowMs,
        frameAgeMs = if (runtime.phase == CameraRuntimePhase.READY) 30L else null,
    )

    private fun runtime(phase: CameraRuntimePhase, side: MirrorSide? = null) = CameraRuntimeSnapshot(
        generation = 1L,
        phase = phase,
        side = side,
    )
}
