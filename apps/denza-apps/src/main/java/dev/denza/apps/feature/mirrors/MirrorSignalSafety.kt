package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEvent
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState

internal data class PendingTurnSwitch(
    val sourceEpoch: Long,
    val sequence: Long,
    val observedAtElapsedMs: Long,
)

internal data class MirrorSignalSafetyState(
    val continuityReady: Boolean = false,
    val pendingSwitch: PendingTurnSwitch? = null,
    val confirmedAfterSwitch: MirrorSide? = null,
    val stableSide: MirrorSide? = null,
    val stableSamples: Int = 0,
    val stableSinceMs: Long = 0L,
    val neutralSamples: Int = 0,
)

internal data class MirrorSignalSafetyResult(
    val state: MirrorSignalSafetyState,
    val eligibleSide: MirrorSide? = null,
)

/**
 * Joins transient switch edges to later confirmed mode and stock-window state.
 *
 * Every onset edge closes the Show gate, even if no Denza camera is active yet. The gate can open
 * for that turn only after a later confirmed mode event from the same source epoch and a stable
 * matching stock window. This prevents a poll using pre-edge retained mode/window from opening an
 * AVC session inside a stock transition.
 */
internal object MirrorSignalSafety {
    const val STABLE_MATCH_SAMPLES = 3
    const val STABLE_MATCH_MS = 200L
    const val NEUTRAL_BASELINE_SAMPLES = 3
    const val PENDING_SWITCH_TIMEOUT_MS = 2_000L

    fun onSwitchEvent(
        state: MirrorSignalSafetyState,
        event: VehicleSignalEvent<*>,
    ): MirrorSignalSafetyState = state.copy(
        continuityReady = false,
        pendingSwitch = PendingTurnSwitch(
            event.sourceEpoch,
            event.sequence,
            event.observedAtElapsedMs,
        ),
        confirmedAfterSwitch = null,
        stableSide = null,
        stableSamples = 0,
        stableSinceMs = 0L,
        neutralSamples = 0,
    )

    fun onModeEvent(
        state: MirrorSignalSafetyState,
        event: VehicleSignalEvent<TurnIndicatorMode>,
    ): MirrorSignalSafetyState {
        val pending = state.pendingSwitch
        if (event.value == TurnIndicatorMode.OFF) {
            if (
                pending != null &&
                (event.sourceEpoch != pending.sourceEpoch || event.sequence <= pending.sequence)
            ) {
                return state
            }
            return MirrorSignalSafetyState(continuityReady = true)
        }
        pending ?: return state
        if (event.sourceEpoch != pending.sourceEpoch || event.sequence <= pending.sequence) {
            return state
        }
        val side = confirmedSide(event.value) ?: return state
        return state.copy(
            confirmedAfterSwitch = side,
            stableSide = null,
            stableSamples = 0,
            stableSinceMs = 0L,
        )
    }

    fun unavailable(): MirrorSignalSafetyState = MirrorSignalSafetyState()

    fun observe(
        state: MirrorSignalSafetyState,
        modeState: VehicleSignalState<TurnIndicatorMode>,
        stockWindowSide: MirrorSide?,
        subscriptionsArmed: Boolean,
        nowMs: Long,
    ): MirrorSignalSafetyResult {
        if (!subscriptionsArmed) return MirrorSignalSafetyResult(unavailable())
        val mode = (modeState as? VehicleSignalState.Fresh)?.value

        val pending = state.pendingSwitch
        if (pending != null) {
            val pendingAgeMs = nowMs - pending.observedAtElapsedMs
            if (pendingAgeMs < 0L || pendingAgeMs > PENDING_SWITCH_TIMEOUT_MS) {
                return MirrorSignalSafetyResult(unavailable())
            }
            val confirmed = state.confirmedAfterSwitch
            if (confirmed == null || confirmedSide(mode) != confirmed || stockWindowSide != confirmed) {
                return MirrorSignalSafetyResult(
                    state.copy(stableSide = null, stableSamples = 0, stableSinceMs = 0L),
                )
            }
            val continuing = state.stableSide == confirmed
            val samples = if (continuing) state.stableSamples + 1 else 1
            val since = if (continuing) state.stableSinceMs else nowMs
            if (samples >= STABLE_MATCH_SAMPLES && nowMs - since >= STABLE_MATCH_MS) {
                return MirrorSignalSafetyResult(
                    MirrorSignalSafetyState(continuityReady = true),
                    confirmed,
                )
            }
            return MirrorSignalSafetyResult(
                state.copy(
                    stableSide = confirmed,
                    stableSamples = samples,
                    stableSinceMs = since,
                ),
            )
        }

        if (state.continuityReady) {
            val confirmed = confirmedSide(mode)
            return MirrorSignalSafetyResult(
                state,
                stockWindowSide.takeIf { confirmed != null && it == confirmed },
            )
        }

        val neutral = mode == TurnIndicatorMode.OFF && stockWindowSide == null
        val neutralSamples = if (neutral) state.neutralSamples + 1 else 0
        return MirrorSignalSafetyResult(
            state.copy(
                continuityReady = neutralSamples >= NEUTRAL_BASELINE_SAMPLES,
                neutralSamples = neutralSamples,
            ),
        )
    }

    private fun confirmedSide(mode: TurnIndicatorMode?): MirrorSide? = when (mode) {
        TurnIndicatorMode.LEFT -> MirrorSide.LEFT
        TurnIndicatorMode.RIGHT -> MirrorSide.RIGHT
        else -> null
    }
}
