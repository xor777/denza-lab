package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEvent
import dev.denza.apps.feature.vehicle.signal.VehicleSignalKeys
import dev.denza.apps.feature.vehicle.signal.VehicleSignalSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSwitchPreemptionTest {
    @Test
    fun onlyLiveProvenOnsetPhasesPreemptWithoutSelectingASide() {
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            decide(2).decision,
        )
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            decide(4).decision,
        )
    }

    @Test
    fun repeatedOnsetForTheAlreadyActiveSideDoesNotRevokeItsCamera() {
        val left = MirrorSwitchPreemption.cameraStarted(MirrorSide.LEFT, 100L)
        val right = MirrorSwitchPreemption.cameraStarted(MirrorSide.RIGHT, 100L)
        assertEquals(
            MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE,
            decide(2, left, MirrorSide.LEFT, 120L).decision,
        )
        assertEquals(
            MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE,
            decide(4, right, MirrorSide.RIGHT, 120L).decision,
        )
    }

    @Test
    fun oppositeOnsetStillPreemptsAnActiveCamera() {
        val left = MirrorSwitchPreemption.cameraStarted(MirrorSide.LEFT, 100L)
        val right = MirrorSwitchPreemption.cameraStarted(MirrorSide.RIGHT, 100L)
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            decide(4, left, MirrorSide.LEFT, 120L).decision,
        )
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            decide(2, right, MirrorSide.RIGHT, 120L).decision,
        )
    }

    @Test
    fun neutralPhaseEndsGestureSoNewSameSideOnsetPreempts() {
        val started = MirrorSwitchPreemption.cameraStarted(MirrorSide.RIGHT, 100L)
        val neutral = decide(1, started, MirrorSide.RIGHT, 120L)
        val restarted = decide(4, neutral.state, MirrorSide.RIGHT, 140L)

        assertEquals(MirrorSwitchPreemptionDecision.NONE, neutral.decision)
        assertTrue(neutral.state.boundaryObserved)
        assertEquals(MirrorSwitchPreemptionDecision.PREEMPT, restarted.decision)
    }

    @Test
    fun confirmedOffEndsGestureSoNewSameSideOnsetPreempts() {
        val started = MirrorSwitchPreemption.cameraStarted(MirrorSide.LEFT, 100L)
        val stopped = MirrorSwitchPreemption.onModeEvent(
            started,
            modeEvent(TurnIndicatorMode.OFF, 120L),
        )
        val restarted = decide(2, stopped, MirrorSide.LEFT, 140L)

        assertTrue(stopped.boundaryObserved)
        assertEquals(MirrorSwitchPreemptionDecision.PREEMPT, restarted.decision)
    }

    @Test
    fun delayedNeutralFromBeforeCameraStartDoesNotCloseCurrentGesture() {
        val started = MirrorSwitchPreemption.cameraStarted(MirrorSide.RIGHT, 100L)
        val staleNeutral = decide(1, started, MirrorSide.RIGHT, 90L)
        val trailing = decide(4, staleNeutral.state, MirrorSide.RIGHT, 120L)

        assertFalse(staleNeutral.state.boundaryObserved)
        assertEquals(MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE, trailing.decision)
    }

    @Test
    fun neutralAndFollowThroughPhasesDoNothing() {
        listOf(1, 3, 5).forEach { raw ->
            assertEquals(
                MirrorSwitchPreemptionDecision.NONE,
                decide(raw).decision,
            )
        }
    }

    @Test
    fun unknownPhaseFailsClosed() {
        assertEquals(
            MirrorSwitchPreemptionDecision.QUARANTINE,
            decide(6).decision,
        )
    }

    @Test
    fun retainedOnsetAndFollowThroughIdentifyAnActiveTransition() {
        listOf(2, 3, 4, 5).forEach { raw ->
            assertTrue(MirrorSwitchPreemption.isTransitionInProgress(TurnSwitchPhase(raw)))
        }
        listOf(1, 6).forEach { raw ->
            assertFalse(MirrorSwitchPreemption.isTransitionInProgress(TurnSwitchPhase(raw)))
        }
    }

    private fun decide(
        raw: Int,
        state: MirrorSwitchGestureState = MirrorSwitchGestureState(),
        activeSide: MirrorSide? = null,
        observedAtMs: Long = 1L,
    ) = MirrorSwitchPreemption.decide(
        state,
        switchEvent(raw, observedAtMs),
        activeSide,
    )

    private fun switchEvent(
        raw: Int,
        observedAtMs: Long,
    ) = VehicleSignalEvent(
        key = VehicleSignalKeys.TurnSwitchPhase,
        value = TurnSwitchPhase(raw),
        source = VehicleSignalSourceId("test"),
        sourceEpoch = 1L,
        sequence = observedAtMs,
        observedAtElapsedMs = observedAtMs,
        publishedAtElapsedMs = observedAtMs,
    )

    private fun modeEvent(
        mode: TurnIndicatorMode,
        observedAtMs: Long,
    ) = VehicleSignalEvent(
        key = VehicleSignalKeys.TurnIndicatorMode,
        value = mode,
        source = VehicleSignalSourceId("test"),
        sourceEpoch = 1L,
        sequence = observedAtMs,
        observedAtElapsedMs = observedAtMs,
        publishedAtElapsedMs = observedAtMs,
    )
}
