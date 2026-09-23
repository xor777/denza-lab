package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState

enum class MirrorTransitionPhase {
    IDLE,
    STARTING,
    SHOWING,
}

/**
 * The flash state of the lamps, reduced to what the stock camera reads from it.
 *
 * `com.byd.avc` opens its PIP only on the flash FID `0x38A0002C` (2/3 left, 4/5 right) and treats
 * every other value, hazard included, as off (`AVCBYDAutoLightDevice.java:195-223`). [UNKNOWN]
 * means our feed is down, not that the lamps are off.
 */
enum class MirrorLamp {
    LEFT,
    RIGHT,
    OFF,
    UNKNOWN,
    ;

    val side: MirrorSide?
        get() = when (this) {
            LEFT -> MirrorSide.LEFT
            RIGHT -> MirrorSide.RIGHT
            OFF, UNKNOWN -> null
        }

    companion object {
        internal fun of(state: VehicleSignalState<TurnIndicatorMode>?): Pair<MirrorLamp, Long> {
            val fresh = state as? VehicleSignalState.Fresh ?: return UNKNOWN to -1L
            val lamp = when (fresh.value) {
                TurnIndicatorMode.LEFT -> LEFT
                TurnIndicatorMode.RIGHT -> RIGHT
                else -> OFF
            }
            return lamp to fresh.observedAtElapsedMs
        }
    }
}

data class MirrorTransitionState(
    val phase: MirrorTransitionPhase = MirrorTransitionPhase.IDLE,
    val side: MirrorSide? = null,
    val phaseStartedAtMs: Long = 0L,
    val runtimeGeneration: Long = 0L,
    /**
     * The side a lever onset tore down. Its stock card outlives a cancellation by two seconds
     * (AVC arms `sendEmptyMessageDelayed(…, 2000)` on lamps off), so that card alone is not a
     * request. The block ends when the lamps change after the onset, when the lamps stay on that
     * side for [MirrorTransitionReducer.LEVER_BUMP_SETTLE_MS] (a bumped lever, not a
     * cancellation), or when the stock card of that side is gone.
     */
    val blockedSide: MirrorSide? = null,
    val blockedAtMs: Long = 0L,
    /** Something of ours failed on this side; no retry until its stock card or its lamps end. */
    val failedSide: MirrorSide? = null,
    /**
     * A camera of ours was torn down while a stock card may still be settling. The next start
     * waits for [MirrorTransitionReducer.REOPEN_SAMPLES] clean polls of one side, so the stock has
     * built its surface before we take the renderer. A poll without any stock card clears it.
     */
    val settleRequired: Boolean = false,
    val settleSide: MirrorSide? = null,
    val settleSamples: Int = 0,
    val details: String = "",
)

data class MirrorTransitionObservation(
    /** The stock turn-signal card ready to be taken over ([MirrorStockPip.side]), or null. */
    val stockSide: MirrorSide?,
    val lamp: MirrorLamp,
    /** When the current lamp value was observed (not re-verified); -1 when [lamp] is UNKNOWN. */
    val lampObservedAtMs: Long,
    val runtime: CameraRuntimeSnapshot,
    val nowMs: Long,
    val preemptionInFlight: Boolean = false,
    /** Age of the last camera frame after READY; null before the first frame. */
    val frameAgeMs: Long? = null,
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

/**
 * A Denza camera follows the stock turn-signal camera, read from the firmware (2026-09-23):
 * it shows side X while AVC's own card of side X is up and the lamps flash X, and it goes away
 * the moment either stops. The stock inputs are the ones AVC itself acts on, so the rules below
 * are the firmware's rules, not guesses about its windows:
 *
 * - AVC opens the card on the flash FID only, not on the lever, so a raw onset never opens ours;
 *   an opposite onset still tears ours down early ([preempted]) because AVC crashes when it
 *   re-binds its card while our surface holds its only renderer.
 * - AVC keeps its card two seconds after the lamps stop. Ours does not wait: lamps off closes it
 *   at once, and a lever re-engaged inside those two seconds opens it again on the same card.
 * - Holding AVC's renderer while AVC itself is idle is its most exposed state, which is another
 *   reason ours never outlives the lamps.
 */
object MirrorTransitionReducer {
    const val START_ACK_TIMEOUT_MS = 1_500L
    const val SESSION_TIMEOUT_MS = 300_000L

    /** Clean polls of one side before a camera reopens after a teardown. */
    const val REOPEN_SAMPLES = 2

    /**
     * A cancellation turns the lamps off at most ~1.06 s after the lever's opposite onset (the
     * 2026-09-04 tails of 2.18–3.06 s minus AVC's 2.00 s timer). Lamps still on the torn-down side
     * after this long mean the lever was bumped, not cancelled.
     */
    const val LEVER_BUMP_SETTLE_MS = 1_500L

    /** No frame for this long after the first one: AVC took its renderer back. */
    const val FRAME_STALL_MS = MirrorFrameWatch.STALL_MS

    /** READY without a single frame for this long is a camera that will not come. */
    const val FIRST_FRAME_TIMEOUT_MS = 2_000L

    fun reduce(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val current = clearEndedLatches(state, observation)
        return when (current.phase) {
            MirrorTransitionPhase.IDLE -> reduceIdle(current, observation)
            MirrorTransitionPhase.STARTING -> reduceStarting(current, observation)
            MirrorTransitionPhase.SHOWING -> reduceShowing(current, observation)
        }
    }

    /** A lever onset toward the other side tore the camera down; [side] is the one torn down. */
    fun preempted(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        side: MirrorSide?,
        details: String,
    ) = idleAfterTeardown(state, runtime, nowMs, details).copy(
        blockedSide = side,
        blockedAtMs = nowMs,
    )

    /** The lamps left the side on screen; the camera closes and may reopen with them. */
    fun lampsLeft(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        details: String,
    ) = idleAfterTeardown(state, runtime, nowMs, details)

    /** Something of ours failed while opening [side]. */
    fun failed(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        side: MirrorSide?,
        details: String,
    ) = idleAfterTeardown(state, runtime, nowMs, details).copy(failedSide = side)

    /** True when the camera on screen (or starting) should close for the lamp value [lamp]. */
    fun lampsLeftSide(lamp: MirrorLamp, activeSide: MirrorSide?): Boolean =
        activeSide != null && lamp != MirrorLamp.UNKNOWN && lamp.side != activeSide

    private fun idleAfterTeardown(
        state: MirrorTransitionState,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        details: String,
    ) = MirrorTransitionState(
        phaseStartedAtMs = nowMs,
        runtimeGeneration = runtime.generation,
        blockedSide = state.blockedSide,
        blockedAtMs = state.blockedAtMs,
        failedSide = state.failedSide,
        settleRequired = true,
        details = details,
    )

    private fun clearEndedLatches(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionState {
        var next = state
        val blocked = state.blockedSide
        if (blocked != null) {
            val lampKnown = observation.lamp != MirrorLamp.UNKNOWN
            val lampsChangedSince = lampKnown && observation.lampObservedAtMs > state.blockedAtMs
            val bumpSettled = observation.lamp.side == blocked &&
                observation.nowMs - state.blockedAtMs >= LEVER_BUMP_SETTLE_MS
            if (observation.stockSide != blocked || lampsChangedSince || bumpSettled) {
                next = next.copy(blockedSide = null, blockedAtMs = 0L)
            }
        }
        val failed = state.failedSide
        if (failed != null) {
            val lampsLeft = observation.lamp != MirrorLamp.UNKNOWN && observation.lamp.side != failed
            if (observation.stockSide != failed || lampsLeft) next = next.copy(failedSide = null)
        }
        if (observation.stockSide == null && next.settleRequired) {
            next = next.copy(settleRequired = false, settleSide = null, settleSamples = 0)
        }
        return next
    }

    private fun eligibleSide(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorSide? {
        val side = observation.stockSide ?: return null
        if (observation.lamp != MirrorLamp.UNKNOWN && observation.lamp.side != side) return null
        if (side == state.blockedSide || side == state.failedSide) return null
        return side
    }

    private fun reduceIdle(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val runtimeFree = observation.runtime.phase == CameraRuntimePhase.IDLE ||
            observation.runtime.phase == CameraRuntimePhase.FAILED
        val side = eligibleSide(state, observation)
        if (side == null || !runtimeFree || observation.preemptionInFlight) {
            // Nothing to open, or our previous surface is not gone yet: the settle run restarts.
            return MirrorTransitionResult(
                state.copy(
                    runtimeGeneration = observation.runtime.generation,
                    settleSide = null,
                    settleSamples = 0,
                    details = when {
                        side == null -> state.details.ifBlank { "ready" }
                        else -> "waiting for teardown"
                    },
                ),
            )
        }
        if (state.settleRequired) {
            val samples = if (side == state.settleSide) state.settleSamples + 1 else 1
            if (samples < REOPEN_SAMPLES) {
                return MirrorTransitionResult(
                    state.copy(
                        runtimeGeneration = observation.runtime.generation,
                        settleSide = side,
                        settleSamples = samples,
                    ),
                )
            }
        }
        return MirrorTransitionResult(
            state.copy(
                phase = MirrorTransitionPhase.STARTING,
                side = side,
                phaseStartedAtMs = observation.nowMs,
                runtimeGeneration = observation.runtime.generation,
                settleRequired = false,
                settleSide = null,
                settleSamples = 0,
                details = "starting ${side.name.lowercase()}",
            ),
            MirrorTransitionCommand.Show(side),
        )
    }

    private fun reduceStarting(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val side = checkNotNull(state.side)
        stockEnded(state, observation)?.let { return hide(state, observation, it, failed = false) }
        val failure = when {
            observation.runtime.phase == CameraRuntimePhase.FAILED -> "AVC failure"
            observation.runtime.phase == CameraRuntimePhase.READY &&
                observation.runtime.side != side -> "AVC ready for unexpected side"
            observation.nowMs - state.phaseStartedAtMs >= START_ACK_TIMEOUT_MS ->
                "camera start acknowledgement timed out"
            else -> null
        }
        if (failure != null) return hide(state, observation, failure, failed = true)
        if (observation.runtime.phase == CameraRuntimePhase.READY) {
            return MirrorTransitionResult(
                state.copy(
                    phase = MirrorTransitionPhase.SHOWING,
                    phaseStartedAtMs = observation.nowMs,
                    runtimeGeneration = observation.runtime.generation,
                    details = "showing ${side.name.lowercase()}",
                ),
            )
        }
        return MirrorTransitionResult(state.copy(runtimeGeneration = observation.runtime.generation))
    }

    private fun reduceShowing(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): MirrorTransitionResult {
        val side = checkNotNull(state.side)
        stockEnded(state, observation)?.let { return hide(state, observation, it, failed = false) }
        val frameAge = observation.frameAgeMs
        val failure = when {
            observation.runtime.phase != CameraRuntimePhase.READY -> "camera runtime was lost"
            observation.runtime.side != side -> "camera runtime changed side"
            frameAge != null && frameAge >= FRAME_STALL_MS -> "stock took its renderer back"
            frameAge == null && observation.nowMs - state.phaseStartedAtMs >= FIRST_FRAME_TIMEOUT_MS ->
                "no camera picture"
            observation.nowMs - state.phaseStartedAtMs >= SESSION_TIMEOUT_MS -> "camera session timed out"
            else -> null
        }
        if (failure != null) return hide(state, observation, failure, failed = true)
        return MirrorTransitionResult(state.copy(runtimeGeneration = observation.runtime.generation))
    }

    /** The ordinary endings: the stock card of our side is gone, or the lamps left our side. */
    private fun stockEnded(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
    ): String? = when {
        observation.stockSide != state.side -> when (observation.stockSide) {
            null -> "stock camera closed"
            else -> "stock camera switched side"
        }
        lampsLeftSide(observation.lamp, state.side) -> "lamps left ${state.side?.name?.lowercase()}"
        else -> null
    }

    private fun hide(
        state: MirrorTransitionState,
        observation: MirrorTransitionObservation,
        details: String,
        failed: Boolean,
    ): MirrorTransitionResult {
        val idle = idleAfterTeardown(state, observation.runtime, observation.nowMs, details)
        return MirrorTransitionResult(
            if (failed) idle.copy(failedSide = state.side) else idle,
            MirrorTransitionCommand.Hide,
        )
    }
}
