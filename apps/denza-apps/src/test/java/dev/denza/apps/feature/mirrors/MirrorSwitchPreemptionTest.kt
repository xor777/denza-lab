package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSwitchPreemptionTest {
    @Test
    fun onlyLiveProvenOnsetPhasesPreemptWithoutSelectingASide() {
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            MirrorSwitchPreemption.decide(TurnSwitchPhase(2)),
        )
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            MirrorSwitchPreemption.decide(TurnSwitchPhase(4)),
        )
    }

    @Test
    fun neutralAndFollowThroughPhasesDoNothing() {
        listOf(1, 3, 5).forEach { raw ->
            assertEquals(
                MirrorSwitchPreemptionDecision.NONE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(raw)),
            )
        }
    }

    @Test
    fun unknownPhaseFailsClosed() {
        assertEquals(
            MirrorSwitchPreemptionDecision.QUARANTINE,
            MirrorSwitchPreemption.decide(TurnSwitchPhase(6)),
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
}
