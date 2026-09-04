package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEvent

internal enum class MirrorSwitchPreemptionDecision {
    NONE,
    KEEP_CURRENT_SIDE,
    PREEMPT,
    QUARANTINE,
}

internal data class MirrorSwitchGestureState(
    val cameraSide: MirrorSide? = null,
    val cameraStartedAtElapsedMs: Long = 0L,
    val boundaryObserved: Boolean = true,
)

internal data class MirrorSwitchPreemptionResult(
    val state: MirrorSwitchGestureState,
    val decision: MirrorSwitchPreemptionDecision,
)

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

    fun cameraStarted(
        side: MirrorSide,
        observedAtElapsedMs: Long,
    ) = MirrorSwitchGestureState(
        cameraSide = side,
        cameraStartedAtElapsedMs = observedAtElapsedMs,
        boundaryObserved = false,
    )

    fun cameraStopped() = MirrorSwitchGestureState()

    fun onModeEvent(
        state: MirrorSwitchGestureState,
        event: VehicleSignalEvent<TurnIndicatorMode>,
    ): MirrorSwitchGestureState = if (
        state.cameraSide != null &&
        event.value == TurnIndicatorMode.OFF &&
        event.observedAtElapsedMs >= state.cameraStartedAtElapsedMs
    ) {
        state.copy(boundaryObserved = true)
    } else {
        state
    }

    fun decide(
        state: MirrorSwitchGestureState,
        event: VehicleSignalEvent<TurnSwitchPhase>,
        activeSide: MirrorSide? = null,
    ): MirrorSwitchPreemptionResult = when (event.value.rawValue) {
        1 -> MirrorSwitchPreemptionResult(
            state = if (
                state.cameraSide != null &&
                event.observedAtElapsedMs >= state.cameraStartedAtElapsedMs
            ) {
                state.copy(boundaryObserved = true)
            } else {
                state
            },
            decision = MirrorSwitchPreemptionDecision.NONE,
        )
        3, 5 -> MirrorSwitchPreemptionResult(
            state,
            MirrorSwitchPreemptionDecision.NONE,
        )
        2 -> onset(state, activeSide, MirrorSide.LEFT)
        4 -> onset(state, activeSide, MirrorSide.RIGHT)
        else -> MirrorSwitchPreemptionResult(
            cameraStopped(),
            MirrorSwitchPreemptionDecision.QUARANTINE,
        )
    }

    private fun onset(
        state: MirrorSwitchGestureState,
        activeSide: MirrorSide?,
        physicalSide: MirrorSide,
    ): MirrorSwitchPreemptionResult {
        val sameGesture = activeSide == physicalSide &&
            state.cameraSide == physicalSide &&
            !state.boundaryObserved
        return MirrorSwitchPreemptionResult(
            state = if (sameGesture) state else cameraStopped(),
            decision = if (sameGesture) {
                MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE
            } else {
                MirrorSwitchPreemptionDecision.PREEMPT
            },
        )
    }
}
