package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTransitionReducerTest {
    @Test
    fun idleRequestsOneShowAndWaitsForRuntimeReady() {
        val started = reduce(
            state = MirrorTransitionState(),
            requested = MirrorSide.LEFT,
            runtime = runtime(CameraRuntimePhase.IDLE),
            nowMs = 100L,
        )

        assertEquals(MirrorTransitionPhase.STARTING, started.state.phase)
        assertEquals(MirrorSide.LEFT, started.state.side)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.LEFT), started.command)

        val ready = reduce(
            state = started.state,
            requested = MirrorSide.LEFT,
            runtime = runtime(CameraRuntimePhase.READY, MirrorSide.LEFT, generation = 2L),
            nowMs = 200L,
        )
        assertEquals(MirrorTransitionPhase.SHOWING, ready.state.phase)
        assertEquals(MirrorTransitionCommand.None, ready.command)
    }

    @Test
    fun directSideSwitchHidesOnceAndQuarantines() {
        val showing = MirrorTransitionState(
            phase = MirrorTransitionPhase.SHOWING,
            side = MirrorSide.LEFT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 2L,
        )

        val switched = reduce(
            state = showing,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.READY, MirrorSide.LEFT, generation = 2L),
            nowMs = 300L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, switched.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, switched.command)

        val stillRight = reduce(
            state = switched.state,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
            nowMs = 400L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, stillRight.state.phase)
        assertEquals(MirrorTransitionCommand.None, stillRight.command)
    }

    @Test
    fun oneNeutralSampleCannotBypassQuarantineBetweenSides() {
        val showing = MirrorTransitionState(
            phase = MirrorTransitionPhase.SHOWING,
            side = MirrorSide.LEFT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 2L,
        )

        val firstNeutral = reduce(
            state = showing,
            requested = null,
            runtime = runtime(CameraRuntimePhase.READY, MirrorSide.LEFT, generation = 2L),
            nowMs = 200L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, firstNeutral.state.phase)
        assertEquals(0, firstNeutral.state.neutralSamples)
        assertEquals(MirrorTransitionCommand.Hide, firstNeutral.command)

        val immediateRight = reduce(
            state = firstNeutral.state,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
            nowMs = 300L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, immediateRight.state.phase)
        assertEquals(MirrorTransitionCommand.None, immediateRight.command)
    }

    @Test
    fun avcFailureQuarantinesWithoutRetry() {
        val starting = MirrorTransitionState(
            phase = MirrorTransitionPhase.STARTING,
            side = MirrorSide.RIGHT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 1L,
        )

        val failed = reduce(
            state = starting,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.FAILED, MirrorSide.RIGHT, generation = 2L),
            nowMs = 200L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, failed.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, failed.command)
    }

    @Test
    fun startTimeoutQuarantines() {
        val starting = MirrorTransitionState(
            phase = MirrorTransitionPhase.STARTING,
            side = MirrorSide.LEFT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 1L,
        )

        val timedOut = reduce(
            state = starting,
            requested = MirrorSide.LEFT,
            runtime = runtime(CameraRuntimePhase.STARTING, MirrorSide.LEFT, generation = 1L),
            nowMs = 1_601L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, timedOut.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, timedOut.command)
    }

    @Test
    fun lostReadyRuntimeWhileWindowStaysVisibleQuarantines() {
        val showing = MirrorTransitionState(
            phase = MirrorTransitionPhase.SHOWING,
            side = MirrorSide.RIGHT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 2L,
        )

        val lost = reduce(
            state = showing,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
            nowMs = 200L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, lost.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, lost.command)
    }

    @Test
    fun ambiguousAvcWindowSetCannotLookLikeNeutralWhileShowing() {
        val showing = MirrorTransitionState(
            phase = MirrorTransitionPhase.SHOWING,
            side = MirrorSide.LEFT,
            phaseStartedAtMs = 100L,
            runtimeGeneration = 2L,
        )

        val ambiguous = MirrorTransitionReducer.reduce(
            showing,
            MirrorTransitionObservation(
                requestedSide = null,
                runtime = runtime(CameraRuntimePhase.READY, MirrorSide.LEFT, generation = 2L),
                nowMs = 200L,
                runtimeWindowAmbiguous = true,
            ),
        )

        assertEquals(MirrorTransitionPhase.QUARANTINED, ambiguous.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, ambiguous.command)
    }

    @Test
    fun sessionTimeoutQuarantinesInsteadOfRestarting() {
        val showing = MirrorTransitionState(
            phase = MirrorTransitionPhase.SHOWING,
            side = MirrorSide.LEFT,
            phaseStartedAtMs = 10L,
            runtimeGeneration = 2L,
        )

        val timedOut = reduce(
            state = showing,
            requested = MirrorSide.LEFT,
            runtime = runtime(CameraRuntimePhase.READY, MirrorSide.LEFT, generation = 2L),
            nowMs = 300_010L,
        )
        assertEquals(MirrorTransitionPhase.QUARANTINED, timedOut.state.phase)
        assertEquals(MirrorTransitionCommand.Hide, timedOut.command)
    }

    @Test
    fun quarantineNeedsThreeConsecutiveNeutralSamples() {
        var state = MirrorTransitionState(
            phase = MirrorTransitionPhase.QUARANTINED,
            details = "direct side switch",
        )

        repeat(2) { index ->
            val result = reduce(
                state = state,
                requested = null,
                runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
                nowMs = 100L + index * 100L,
            )
            state = result.state
            assertEquals(MirrorTransitionPhase.QUARANTINED, state.phase)
        }

        val recovered = reduce(
            state = state,
            requested = null,
            runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
            nowMs = 300L,
        )
        assertEquals(MirrorTransitionPhase.IDLE, recovered.state.phase)
        assertEquals(MirrorTransitionCommand.None, recovered.command)
        assertNull(recovered.state.side)
    }

    @Test
    fun nonNeutralSampleResetsQuarantineProgress() {
        val state = MirrorTransitionState(
            phase = MirrorTransitionPhase.QUARANTINED,
            neutralSamples = 2,
        )
        val reset = reduce(
            state = state,
            requested = MirrorSide.RIGHT,
            runtime = runtime(CameraRuntimePhase.IDLE, generation = 3L),
            nowMs = 300L,
        )
        assertEquals(0, reset.state.neutralSamples)
        assertEquals(MirrorTransitionCommand.None, reset.command)
    }

    @Test
    fun teardownMustFinishBeforeNeutralCanRecoverQuarantine() {
        val state = MirrorTransitionState(
            phase = MirrorTransitionPhase.QUARANTINED,
            neutralSamples = 2,
            details = "direct side switch",
        )

        val stopping = reduce(
            state = state,
            requested = null,
            runtime = runtime(CameraRuntimePhase.STOPPING, MirrorSide.LEFT, generation = 3L),
            nowMs = 300L,
        )

        assertEquals(MirrorTransitionPhase.QUARANTINED, stopping.state.phase)
        assertEquals(0, stopping.state.neutralSamples)
        assertEquals(MirrorTransitionCommand.None, stopping.command)
    }

    private fun reduce(
        state: MirrorTransitionState,
        requested: MirrorSide?,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
    ): MirrorTransitionResult = MirrorTransitionReducer.reduce(
        state,
        MirrorTransitionObservation(
            requestedSide = requested,
            runtime = runtime,
            nowMs = nowMs,
        ),
    )

    private fun runtime(
        phase: CameraRuntimePhase,
        side: MirrorSide? = null,
        generation: Long = 1L,
    ) = CameraRuntimeSnapshot(
        phase = phase,
        side = side,
        generation = generation,
        details = phase.name.lowercase(),
    )
}
