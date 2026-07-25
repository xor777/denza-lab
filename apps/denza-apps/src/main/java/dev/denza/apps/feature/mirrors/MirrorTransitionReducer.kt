package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot

enum class MirrorTransitionPhase {
    IDLE,
    STARTING,
    SHOWING,
    QUARANTINED,
}

data class MirrorTransitionState(
    val phase: MirrorTransitionPhase = MirrorTransitionPhase.IDLE,
    val side: MirrorSide? = null,
    val phaseStartedAtMs: Long = 0L,
    val runtimeGeneration: Long = 0L,
    val neutralSamples: Int = 0,
    val details: String = "",
)

data class MirrorTransitionObservation(
    val requestedSide: MirrorSide?,
    val runtime: CameraRuntimeSnapshot,
    val nowMs: Long,
    val runtimeWindowAmbiguous: Boolean = false,
)

sealed interface MirrorTransitionCommand {
    data class Show(val side: MirrorSide) : MirrorTransitionCommand
    data object Hide : MirrorTransitionCommand
    data object None : MirrorTransitionCommand
}

data class MirrorTransitionResult(
    val state: MirrorTransitionState,
    val command: MirrorTransitionCommand = MirrorTransitionCommand.None,
)

object MirrorTransitionReducer {
    const val START_ACK_TIMEOUT_MS = 1_500L
    const val SESSION_TIMEOUT_MS = 300_000L
    const val NEUTRAL_SAMPLES_TO_RECOVER = 3

    fun reduce(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult = when (state.phase) {
        MirrorTransitionPhase.IDLE -> reduceIdle(observation)
        MirrorTransitionPhase.STARTING -> reduceStarting(state, observation)
        MirrorTransitionPhase.SHOWING -> reduceShowing(state, observation)
        MirrorTransitionPhase.QUARANTINED -> reduceQuarantined(state, observation)
    }

    fun quarantine(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        details: String,
    ) = state.copy(
        phase = MirrorTransitionPhase.QUARANTINED,
        side = null,
        phaseStartedAtMs = nowMs,
        runtimeGeneration = runtime.generation,
        neutralSamples = 0,
        details = details,
    )

    private fun reduceIdle(observation: MirrorTransitionObservation): MirrorTransitionResult {
        if (observation.runtimeWindowAmbiguous) {
            return MirrorTransitionResult(
                quarantine(
                    MirrorTransitionState(),
                    observation.runtime,
                    observation.nowMs,
                    "ambiguous AVC windows",
                ),
                MirrorTransitionCommand.Hide,
            )
        }
        val requested = observation.requestedSide ?: return MirrorTransitionResult(
            MirrorTransitionState(
                runtimeGeneration = observation.runtime.generation,
                details = "ready",
            ),
        )
        return when {
            observation.runtime.phase == CameraRuntimePhase.READY &&
                observation.runtime.side == requested -> MirrorTransitionResult(
                MirrorTransitionState(
                    phase = MirrorTransitionPhase.SHOWING,
                    side = requested,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "showing ${requested.name.lowercase()}",
                ),
            )
            observation.runtime.phase == CameraRuntimePhase.STARTING &&
                observation.runtime.side == requested -> MirrorTransitionResult(
                MirrorTransitionState(
                    phase = MirrorTransitionPhase.STARTING,
                    side = requested,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "starting ${requested.name.lowercase()}",
                ),
            )
            observation.runtime.phase == CameraRuntimePhase.IDLE -> MirrorTransitionResult(
                MirrorTransitionState(
                    phase = MirrorTransitionPhase.STARTING,
                    side = requested,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "starting ${requested.name.lowercase()}",
                ),
                MirrorTransitionCommand.Show(requested),
            )
            else -> MirrorTransitionResult(
                quarantine(
                    MirrorTransitionState(),
                    observation.runtime,
                    observation.nowMs,
                    "camera runtime is not idle",
                ),
                MirrorTransitionCommand.Hide,
            )
        }
    }

    private fun reduceStarting(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val side = state.side
        val quarantineReason = when {
            observation.runtimeWindowAmbiguous -> "ambiguous AVC windows"
            observation.requestedSide == null -> "window hidden while camera was starting"
            observation.requestedSide != side -> "direct side switch"
            observation.runtime.phase == CameraRuntimePhase.FAILED -> "AVC failure"
            observation.runtime.phase == CameraRuntimePhase.READY &&
                observation.runtime.side != side -> "AVC ready for unexpected side"
            observation.nowMs - state.phaseStartedAtMs >= START_ACK_TIMEOUT_MS ->
                "camera start acknowledgement timed out"
            else -> null
        }
        if (quarantineReason != null) {
            return MirrorTransitionResult(
                quarantine(state, observation.runtime, observation.nowMs, quarantineReason),
                MirrorTransitionCommand.Hide,
            )
        }
        if (
            observation.runtime.phase == CameraRuntimePhase.READY &&
            observation.runtime.side == side
        ) {
            return MirrorTransitionResult(
                state.copy(
                    phase = MirrorTransitionPhase.SHOWING,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "showing ${side?.name?.lowercase().orEmpty()}",
                ),
            )
        }
        return MirrorTransitionResult(
            state.copy(runtimeGeneration = observation.runtime.generation),
        )
    }

    private fun reduceShowing(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        if (observation.runtimeWindowAmbiguous) {
            return MirrorTransitionResult(
                quarantine(
                    state,
                    observation.runtime,
                    observation.nowMs,
                    "ambiguous AVC windows",
                ),
                MirrorTransitionCommand.Hide,
            )
        }
        if (observation.requestedSide == null) {
            return MirrorTransitionResult(
                quarantine(
                    state,
                    observation.runtime,
                    observation.nowMs,
                    "waiting for confirmed neutral",
                ),
                MirrorTransitionCommand.Hide,
            )
        }
        val quarantineReason = when {
            observation.requestedSide != state.side -> "direct side switch"
            observation.runtime.phase != CameraRuntimePhase.READY -> "camera runtime was lost"
            observation.runtime.side != state.side -> "camera runtime changed side"
            observation.nowMs - state.phaseStartedAtMs >= SESSION_TIMEOUT_MS ->
                "camera session timed out"
            else -> null
        }
        return if (quarantineReason == null) {
            MirrorTransitionResult(
                state.copy(runtimeGeneration = observation.runtime.generation),
            )
        } else {
            MirrorTransitionResult(
                quarantine(state, observation.runtime, observation.nowMs, quarantineReason),
                MirrorTransitionCommand.Hide,
            )
        }
    }

    private fun reduceQuarantined(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val runtimeInactive = observation.runtime.phase == CameraRuntimePhase.IDLE ||
            observation.runtime.phase == CameraRuntimePhase.FAILED
        if (
            observation.requestedSide != null ||
            observation.runtimeWindowAmbiguous ||
            !runtimeInactive
        ) {
            return MirrorTransitionResult(
                state.copy(
                    runtimeGeneration = observation.runtime.generation,
                    neutralSamples = 0,
                ),
            )
        }
        val neutralSamples = state.neutralSamples + 1
        return if (neutralSamples >= NEUTRAL_SAMPLES_TO_RECOVER) {
            MirrorTransitionResult(
                MirrorTransitionState(
                    runtimeGeneration = observation.runtime.generation,
                    details = "ready after neutral",
                ),
            )
        } else {
            MirrorTransitionResult(
                state.copy(
                    runtimeGeneration = observation.runtime.generation,
                    neutralSamples = neutralSamples,
                ),
            )
        }
    }
}
