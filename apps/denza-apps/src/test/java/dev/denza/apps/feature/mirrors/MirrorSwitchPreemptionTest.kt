package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSwitchPreemptionTest {
    @Test
    fun onlyTheTwoLiveProvenOnsetPhasesNameASide() {
        assertEquals(MirrorSide.LEFT, MirrorSwitchPreemption.onsetSide(TurnSwitchPhase(2)))
        assertEquals(MirrorSide.RIGHT, MirrorSwitchPreemption.onsetSide(TurnSwitchPhase(4)))
        listOf(0, 1, 3, 5, 6, 7).forEach { raw ->
            assertNull(
                "raw $raw is not an onset",
                MirrorSwitchPreemption.onsetSide(TurnSwitchPhase(raw)),
            )
        }
    }

    @Test
    fun neutralFollowThroughAndUnknownPhasesDecideNothing() {
        listOf(1, 3, 5, 6).forEach { raw ->
            assertEquals(
                "raw $raw",
                MirrorSwitchPreemptionDecision.NONE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(raw), MirrorSide.LEFT),
            )
            assertEquals(
                "raw $raw",
                MirrorSwitchPreemptionDecision.NONE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(raw), MirrorSide.RIGHT),
            )
        }
    }

    @Test
    fun anOnsetWithNoActiveCameraDecidesNothing() {
        listOf(2, 4).forEach { raw ->
            assertEquals(
                "raw $raw",
                MirrorSwitchPreemptionDecision.NONE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(raw), activeSide = null),
            )
        }
    }

    @Test
    fun aSameSideOnsetKeepsTheCameraHoweverManyPulsesArrive() {
        repeat(4) {
            assertEquals(
                MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(2), MirrorSide.LEFT),
            )
            assertEquals(
                MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE,
                MirrorSwitchPreemption.decide(TurnSwitchPhase(4), MirrorSide.RIGHT),
            )
        }
    }

    @Test
    fun aSameSideOnsetAfterNeutralStillKeepsTheCamera() {
        // A lever movement emits several pulses (4 -> 5 -> 1, then 4 -> 1 again). No neutral in
        // between makes the next same-side pulse a side switch, so none of them may tear down.
        listOf(4, 5, 1, 4, 1, 4).forEach { raw ->
            val decision = MirrorSwitchPreemption.decide(TurnSwitchPhase(raw), MirrorSide.RIGHT)
            assertTrue(
                "raw $raw must never preempt the side it already shows",
                decision != MirrorSwitchPreemptionDecision.PREEMPT,
            )
        }
    }

    @Test
    fun anOppositeOnsetPreemptsTheActiveCamera() {
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            MirrorSwitchPreemption.decide(TurnSwitchPhase(4), MirrorSide.LEFT),
        )
        assertEquals(
            MirrorSwitchPreemptionDecision.PREEMPT,
            MirrorSwitchPreemption.decide(TurnSwitchPhase(2), MirrorSide.RIGHT),
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

    @Test
    fun activeCameraSidePrefersOurOwnTransition() {
        listOf(MirrorTransitionPhase.STARTING, MirrorTransitionPhase.SHOWING).forEach { phase ->
            assertEquals(
                MirrorSide.LEFT,
                MirrorSwitchPreemption.activeCameraSide(
                    MirrorTransitionState(phase = phase, side = MirrorSide.LEFT),
                    runtime(CameraRuntimePhase.IDLE),
                ),
            )
        }
    }

    @Test
    fun activeCameraSideFallsBackToTheCameraRuntime() {
        listOf(CameraRuntimePhase.STARTING, CameraRuntimePhase.READY).forEach { phase ->
            assertEquals(
                "runtime $phase",
                MirrorSide.RIGHT,
                MirrorSwitchPreemption.activeCameraSide(
                    MirrorTransitionState(phase = MirrorTransitionPhase.QUARANTINED),
                    runtime(phase, MirrorSide.RIGHT),
                ),
            )
        }
    }

    @Test
    fun activeCameraSideIsNoneWhenNothingIsUp() {
        listOf(MirrorTransitionPhase.IDLE, MirrorTransitionPhase.QUARANTINED).forEach { phase ->
            listOf(
                CameraRuntimePhase.IDLE,
                CameraRuntimePhase.STOPPING,
                CameraRuntimePhase.FAILED,
            ).forEach { runtimePhase ->
                assertNull(
                    "$phase / $runtimePhase",
                    MirrorSwitchPreemption.activeCameraSide(
                        MirrorTransitionState(phase = phase),
                        runtime(runtimePhase, MirrorSide.LEFT),
                    ),
                )
            }
        }
    }

    private fun runtime(
        phase: CameraRuntimePhase,
        side: MirrorSide? = null,
    ) = CameraRuntimeSnapshot(
        phase = phase,
        side = side,
        generation = 1L,
        details = phase.name.lowercase(),
    )
}
