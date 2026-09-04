package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase

internal enum class MirrorSwitchPreemptionDecision {
    NONE,
    KEEP_CURRENT_SIDE,
    PREEMPT,
}

/**
 * The turn lever is a teardown trigger, never a Show authority.
 *
 * The stock AVC window set is the only authority for showing a side: the reducer's requested side
 * is the detected stock window side. Ordinary starts and new/opposite stock windows do not need
 * CAN confirmation. The one exception is reusing the continuously surviving preempted-side
 * window: the reducer requires a renewed matching mode observation, not just elapsed polls.
 * An unavailable feed never closes a camera; a blocked old window waits for a new stock cycle.
 *
 * The raw lever onset (2 = left, 4 = right) exists for one reason only: with a Denza camera
 * surface attached, a fast left-to-right switch crashed stock `com.byd.avc`, and detaching our
 * surface within a few ms of the onset pulse prevented it. So an onset can only tear a camera
 * down. It never selects a side and it never opens one.
 *
 * This is deliberately stateless. The lever emits several pulses per movement (`4 -> 5 -> 1`, then
 * a second `4 -> 1` about 300 ms later) and cancelling a turn crosses the opposite onset, so no
 * pulse can be read as an edge inside a remembered gesture. A same-side onset can never be a side
 * switch, whether or not the lever passed neutral in between, so it never tears down.
 *
 * After a preempt the stale window alone cannot reopen the same side. A new stock window cycle
 * or renewed confirmed same-side observation must distinguish it from a cancellation tail.
 * Every reopen still waits for the vendor teardown to finish.
 */
internal object MirrorSwitchPreemption {
    /** True while the retained raw state still describes an active lever transition. */
    fun isTransitionInProgress(phase: TurnSwitchPhase): Boolean = phase.rawValue in 2..5

    /** The physical side of an onset pulse. Follow-through, neutral and unknown values are not onsets. */
    fun onsetSide(phase: TurnSwitchPhase): MirrorSide? = when (phase.rawValue) {
        2 -> MirrorSide.LEFT
        4 -> MirrorSide.RIGHT
        else -> null
    }

    fun decide(
        phase: TurnSwitchPhase,
        activeSide: MirrorSide?,
    ): MirrorSwitchPreemptionDecision {
        val onset = onsetSide(phase) ?: return MirrorSwitchPreemptionDecision.NONE
        // Nothing of ours is on screen, so there is nothing to protect and no state to create.
        if (activeSide == null) return MirrorSwitchPreemptionDecision.NONE
        return if (onset == activeSide) {
            MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE
        } else {
            MirrorSwitchPreemptionDecision.PREEMPT
        }
    }

    /**
     * The side an onset would be tearing down. The surface the runtime has attached is what arms
     * the crash, so it outranks the side our own transition meant to show; that side counts only
     * while the runtime has nothing attached yet.
     */
    fun activeCameraSide(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
    ): MirrorSide? = when {
        runtime.phase == CameraRuntimePhase.STARTING ||
            runtime.phase == CameraRuntimePhase.READY -> runtime.side ?: state.side
        state.phase == MirrorTransitionPhase.STARTING ||
            state.phase == MirrorTransitionPhase.SHOWING -> state.side
        else -> null
    }
}
