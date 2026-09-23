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
 * AVC itself opens its card on the lamps (flash FID `0x38A0002C`), not on the lever: the raw
 * phase only feeds its full-screen mode and lamp icons (read from the OTA image, 2026-09-23). A
 * Denza camera follows AVC's card and the lamps ([MirrorTransitionReducer]); the raw lever onset
 * (2 = left, 4 = right) exists for one reason only. With our surface in AVC's renderer, a fast
 * left-to-right made AVC re-bind its new card's not-yet-created surface and crash; the onset
 * arrives ~63 ms before the lamps AVC reacts to, and detaching our surface within a few ms of it
 * hands the renderer back in time. So an onset can only tear a camera down. It never selects a
 * side and it never opens one.
 *
 * This is deliberately stateless. The lever emits several pulses per movement (`4 -> 5 -> 1`, then
 * a second `4 -> 1` about 300 ms later) and cancelling a turn crosses the opposite onset, so no
 * pulse can be read as an edge inside a remembered gesture. A same-side onset can never be a side
 * switch, whether or not the lever passed neutral in between, so it never tears down.
 *
 * The side an onset tore down stays closed while its card survives, until the lamps say what the
 * onset was: off (a cancellation), the other side (a switch) or still on after the bump window.
 */
internal object MirrorSwitchPreemption {
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
