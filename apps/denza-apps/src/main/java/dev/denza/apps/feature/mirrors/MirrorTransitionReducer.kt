package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot

enum class MirrorTransitionPhase {
    IDLE,
    STARTING,
    SHOWING,
    QUARANTINED,
}

/**
 * How a quarantine may end. Every quarantine also recovers to IDLE after three consecutive polls
 * with no stock window at all.
 */
enum class MirrorQuarantineRecovery {
    /** Something of ours failed. Only neutral recovers, so a broken Show can never loop. */
    NEUTRAL_ONLY,

    /**
     * The stock changed its windows, or the lever announced that it would. An unambiguous stock
     * window reopens the camera once our teardown has finished. A continuously surviving window
     * of the preempted side additionally needs renewed same-side evidence; elapsed polls cannot
     * distinguish a new turn from the stock window's multi-second cancellation tail.
     */
    STOCK_WINDOW_AFTER_TEARDOWN,
}

data class MirrorTransitionState(
    val phase: MirrorTransitionPhase = MirrorTransitionPhase.IDLE,
    val side: MirrorSide? = null,
    val phaseStartedAtMs: Long = 0L,
    val runtimeGeneration: Long = 0L,
    val neutralSamples: Int = 0,
    val quarantineRecovery: MirrorQuarantineRecovery = MirrorQuarantineRecovery.NEUTRAL_ONLY,
    /** The side whose surface was torn down; its stale stock window must not reopen it at once. */
    val preemptedSide: MirrorSide? = null,
    /** The side that [reopenSamples] counts; a different side starts the run over. */
    val reopenSide: MirrorSide? = null,
    val reopenSamples: Int = 0,
    val details: String = "",
)

data class MirrorTransitionObservation(
    val requestedSide: MirrorSide?,
    val runtime: CameraRuntimeSnapshot,
    val nowMs: Long,
    val runtimeWindowAmbiguous: Boolean = false,
    val preemptionInFlight: Boolean = false,
    /** The retained raw lever phase still describes a movement (raw 2..5). */
    val leverEngaged: Boolean = false,
    /** Fresh matching confirmed mode observation; used ONLY for the surviving preempted side. */
    val sameSideRearmObservedAtMs: Long? = null,
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

    /** Consecutive clean polls of one stock window before a quarantined camera reopens on it. */
    const val REOPEN_SAMPLES = 2

    /**
     * Existing settling interval for an explicitly rearmed same side, NOT evidence of rearming.
     */
    const val SAME_SIDE_REOPEN_SAMPLES = 5

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
        recovery: MirrorQuarantineRecovery = MirrorQuarantineRecovery.NEUTRAL_ONLY,
        preemptedSide: MirrorSide? = null,
    ) = state.copy(
        phase = MirrorTransitionPhase.QUARANTINED,
        side = null,
        phaseStartedAtMs = nowMs,
        runtimeGeneration = runtime.generation,
        neutralSamples = 0,
        quarantineRecovery = recovery,
        preemptedSide = preemptedSide,
        reopenSide = null,
        reopenSamples = 0,
        details = details,
    )

    private class QuarantineCause(
        val details: String,
        val recovery: MirrorQuarantineRecovery,
        val preemptedSide: MirrorSide?,
    )

    /** The stock moved its windows; [heldSide] is the surface of ours that was attached, if any. */
    private fun stockCause(details: String, heldSide: MirrorSide?) =
        QuarantineCause(details, MirrorQuarantineRecovery.STOCK_WINDOW_AFTER_TEARDOWN, heldSide)

    /** Something of ours failed; only neutral recovers. */
    private fun ownCause(details: String) =
        QuarantineCause(details, MirrorQuarantineRecovery.NEUTRAL_ONLY, null)

    private fun quarantine(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
        cause: QuarantineCause,
    ) = MirrorTransitionResult(
        quarantine(
            state,
            observation.runtime,
            observation.nowMs,
            cause.details,
            cause.recovery,
            cause.preemptedSide,
        ),
        MirrorTransitionCommand.Hide,
    )

    private fun reduceIdle(observation: MirrorTransitionObservation): MirrorTransitionResult {
        if (
            observation.runtimeWindowAmbiguous &&
            observation.leverEngaged &&
            observation.requestedSide == null &&
            observation.runtime.phase == CameraRuntimePhase.IDLE
        ) {
            // Stock AVC briefly exposes overlapping/partial window state while it changes side.
            // An engaged lever makes that a bounded wait instead of a quarantine, never permission
            // to Show: a stock window must still resolve to one unambiguous side before we open.
            return MirrorTransitionResult(
                MirrorTransitionState(
                    runtimeGeneration = observation.runtime.generation,
                    details = "waiting for confirmed stock switch",
                ),
            )
        }
        if (observation.runtimeWindowAmbiguous) {
            // Nothing of ours is attached, so the Hide is a no-op; the side the stock settles on
            // may reopen the camera after two clean polls.
            return quarantine(
                MirrorTransitionState(),
                observation,
                stockCause("ambiguous AVC windows", heldSide = null),
            )
        }
        val requested = observation.requestedSide ?: return MirrorTransitionResult(
            MirrorTransitionState(
                runtimeGeneration = observation.runtime.generation,
                details = "ready",
            ),
        )
        if (observation.preemptionInFlight) {
            // A lever onset is still tearing a surface down. Nothing attaches until the vendor
            // has freed the display, whatever the stock window says meanwhile.
            return MirrorTransitionResult(
                MirrorTransitionState(
                    runtimeGeneration = observation.runtime.generation,
                    details = "waiting for teardown",
                ),
            )
        }
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
            else -> quarantine(
                MirrorTransitionState(),
                observation,
                stockCause("camera runtime is not idle", heldSide = null),
            )
        }
    }

    private fun reduceStarting(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val side = state.side
        val cause = when {
            observation.runtimeWindowAmbiguous -> stockCause("ambiguous AVC windows", side)
            observation.requestedSide == null ->
                stockCause("window hidden while camera was starting", heldSide = null)
            observation.requestedSide != side -> stockCause("direct side switch", side)
            observation.runtime.phase == CameraRuntimePhase.FAILED -> ownCause("AVC failure")
            observation.runtime.phase == CameraRuntimePhase.READY &&
                observation.runtime.side != side -> ownCause("AVC ready for unexpected side")
            observation.nowMs - state.phaseStartedAtMs >= START_ACK_TIMEOUT_MS ->
                ownCause("camera start acknowledgement timed out")
            else -> null
        }
        if (cause != null) return quarantine(state, observation, cause)
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
        val cause = when {
            observation.runtimeWindowAmbiguous -> stockCause("ambiguous AVC windows", state.side)
            observation.requestedSide == null -> stockCause("stock window closed", heldSide = null)
            observation.requestedSide != state.side -> stockCause("direct side switch", state.side)
            observation.runtime.phase != CameraRuntimePhase.READY -> ownCause("camera runtime was lost")
            observation.runtime.side != state.side -> ownCause("camera runtime changed side")
            observation.nowMs - state.phaseStartedAtMs >= SESSION_TIMEOUT_MS ->
                ownCause("camera session timed out")
            else -> null
        }
        if (cause != null) return quarantine(state, observation, cause)
        return MirrorTransitionResult(
            state.copy(runtimeGeneration = observation.runtime.generation),
        )
    }

    private fun reduceQuarantined(
        previous: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val state = if (previous.preemptedSide != null && observation.requestedSide == null &&
            !observation.runtimeWindowAmbiguous
        ) previous.copy(preemptedSide = null) else previous
        val runtimeInactive = observation.runtime.phase == CameraRuntimePhase.IDLE ||
            observation.runtime.phase == CameraRuntimePhase.FAILED
        // A stock-driven quarantine ends on a clean stock window once the vendor has finished its
        // own teardown: runtime idle (not merely inactive), one unambiguous side, no preempt in
        // flight. The side we tore down needs the longer run, because the stock window we saw at
        // the onset is the one the vendor is still dismantling.
        val stockWindowClean = state.quarantineRecovery ==
            MirrorQuarantineRecovery.STOCK_WINDOW_AFTER_TEARDOWN &&
            observation.requestedSide != null &&
            observation.runtime.phase == CameraRuntimePhase.IDLE &&
            !observation.runtimeWindowAmbiguous &&
            !observation.preemptionInFlight
        if (stockWindowClean) {
            val requested = checkNotNull(observation.requestedSide)
            val renewedAt = observation.sameSideRearmObservedAtMs
            if (requested == state.preemptedSide &&
                (renewedAt == null || renewedAt <= state.phaseStartedAtMs || renewedAt > observation.nowMs)
            ) {
                return MirrorTransitionResult(
                    state.copy(
                        runtimeGeneration = observation.runtime.generation,
                        neutralSamples = 0,
                        reopenSide = null,
                        reopenSamples = 0,
                        details = "waiting for a new stock window or renewed same-side turn",
                    ),
                )
            }
            val reopenSamples = if (requested == state.reopenSide) state.reopenSamples + 1 else 1
            val required = if (requested == state.preemptedSide) {
                SAME_SIDE_REOPEN_SAMPLES
            } else {
                REOPEN_SAMPLES
            }
            if (reopenSamples < required) {
                return MirrorTransitionResult(
                    state.copy(
                        runtimeGeneration = observation.runtime.generation,
                        neutralSamples = 0,
                        reopenSide = requested,
                        reopenSamples = reopenSamples,
                    ),
                )
            }
            val how = when {
                requested == state.preemptedSide -> "after the lever came back"
                state.preemptedSide != null -> "after stock switch"
                else -> "after stock reopen"
            }
            return MirrorTransitionResult(
                MirrorTransitionState(
                    phase = MirrorTransitionPhase.STARTING,
                    side = requested,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "starting ${requested.name.lowercase()} $how",
                ),
                MirrorTransitionCommand.Show(requested),
            )
        }
        if (
            observation.requestedSide != null ||
            observation.runtimeWindowAmbiguous ||
            !runtimeInactive ||
            observation.preemptionInFlight
        ) {
            return MirrorTransitionResult(
                state.copy(
                    runtimeGeneration = observation.runtime.generation,
                    neutralSamples = 0,
                    reopenSide = null,
                    reopenSamples = 0,
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
                    reopenSide = null,
                    reopenSamples = 0,
                ),
            )
        }
    }
}
