package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase

internal enum class MirrorSwitchPreemptionDecision {
    NONE,
    PREEMPT,
    QUARANTINE,
}

/**
 * Side-agnostic policy for the live-proven raw lever phases.
 *
 * Values 2 and 4 are the early onset positions. They intentionally do not select a camera side:
 * manual cancellation crosses the opposite onset position. Values 3 and 5 are follow-through,
 * while 1 is neutral/release. An unknown non-neutral value fails closed.
 */
internal object MirrorSwitchPreemption {
    /** True while the retained raw state still describes an active lever transition. */
    fun isTransitionInProgress(phase: TurnSwitchPhase): Boolean = phase.rawValue in 2..5

    fun decide(phase: TurnSwitchPhase): MirrorSwitchPreemptionDecision = when (phase.rawValue) {
        1, 3, 5 -> MirrorSwitchPreemptionDecision.NONE
        2, 4 -> MirrorSwitchPreemptionDecision.PREEMPT
        else -> MirrorSwitchPreemptionDecision.QUARANTINE
    }
}
