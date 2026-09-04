package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalMissingReason
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState
import java.util.concurrent.atomic.AtomicReference

internal enum class MirrorTurnAgreement {
    MATCH,
    SIGNAL_WITHOUT_WINDOW,
    WINDOW_WITHOUT_DIRECTIONAL_SIGNAL,
    SIDE_MISMATCH,
    SIGNAL_UNAVAILABLE,
}

internal data class MirrorTurnShadowResult(
    val agreement: MirrorTurnAgreement,
    val mode: TurnIndicatorMode? = null,
    val windowSide: MirrorSide? = null,
)

/** Pure comparison retained for bounded support diagnostics. */
internal object MirrorTurnSignalShadow {
    fun compare(
        state: VehicleSignalState<TurnIndicatorMode>,
        windowSide: MirrorSide?,
    ): MirrorTurnShadowResult {
        val fresh = state as? VehicleSignalState.Fresh
            ?: return MirrorTurnShadowResult(
                MirrorTurnAgreement.SIGNAL_UNAVAILABLE,
                windowSide = windowSide,
            )
        if (fresh.value is TurnIndicatorMode.VendorDefined) {
            return MirrorTurnShadowResult(
                MirrorTurnAgreement.SIGNAL_UNAVAILABLE,
                mode = fresh.value,
                windowSide = windowSide,
            )
        }
        val signalSide = when (fresh.value) {
            TurnIndicatorMode.LEFT -> MirrorSide.LEFT
            TurnIndicatorMode.RIGHT -> MirrorSide.RIGHT
            TurnIndicatorMode.OFF,
            TurnIndicatorMode.HAZARD,
            -> null
        }
        val agreement = when {
            signalSide == windowSide -> MirrorTurnAgreement.MATCH
            signalSide != null && windowSide == null ->
                MirrorTurnAgreement.SIGNAL_WITHOUT_WINDOW
            signalSide == null -> MirrorTurnAgreement.WINDOW_WITHOUT_DIRECTIONAL_SIGNAL
            else -> MirrorTurnAgreement.SIDE_MISMATCH
        }
        return MirrorTurnShadowResult(agreement, fresh.value, windowSide)
    }
}

internal data class MirrorTurnSignalDiagnosticSnapshot(
    val state: String = "not-started",
    val agreement: MirrorTurnAgreement = MirrorTurnAgreement.SIGNAL_UNAVAILABLE,
    val windowSide: MirrorSide? = null,
    val observations: Long = 0L,
    val matches: Long = 0L,
    val signalWithoutWindow: Long = 0L,
    val windowWithoutSignal: Long = 0L,
    val sideMismatches: Long = 0L,
    val unavailable: Long = 0L,
    val lastChangedAtElapsedMs: Long = 0L,
) {
    fun compact(): String =
        "state=$state; window=${windowSide?.name?.lowercase() ?: "none"}; " +
            "agreement=${agreement.name.lowercase().replace('_', '-')}; " +
            "samples=$observations/$matches/$signalWithoutWindow/" +
            "$windowWithoutSignal/$sideMismatches/$unavailable"
}

/** Bounded aggregate only: no raw frames, FIDs, identifiers, or unbounded event transcript. */
internal object MirrorTurnSignalDiagnostics {
    private val latest = AtomicReference(MirrorTurnSignalDiagnosticSnapshot())

    fun reset(nowElapsedMs: Long) {
        latest.set(MirrorTurnSignalDiagnosticSnapshot(lastChangedAtElapsedMs = nowElapsedMs))
    }

    fun record(
        state: VehicleSignalState<TurnIndicatorMode>,
        windowSide: MirrorSide?,
        nowElapsedMs: Long,
    ): MirrorTurnSignalDiagnosticSnapshot {
        val comparison = MirrorTurnSignalShadow.compare(state, windowSide)
        val stateLabel = when (state) {
            is VehicleSignalState.Fresh -> state.value.diagnosticName
            is VehicleSignalState.Missing ->
                "missing-${state.reason.name.lowercase().replace('_', '-')}"
        }
        return latest.updateAndGet { old ->
            val changed = old.state != stateLabel ||
                old.agreement != comparison.agreement ||
                old.windowSide != windowSide
            old.copy(
                state = stateLabel,
                agreement = comparison.agreement,
                windowSide = windowSide,
                observations = old.observations + 1L,
                matches = old.matches + if (comparison.agreement == MirrorTurnAgreement.MATCH) 1 else 0,
                signalWithoutWindow = old.signalWithoutWindow + if (
                    comparison.agreement == MirrorTurnAgreement.SIGNAL_WITHOUT_WINDOW
                ) 1 else 0,
                windowWithoutSignal = old.windowWithoutSignal + if (
                    comparison.agreement == MirrorTurnAgreement.WINDOW_WITHOUT_DIRECTIONAL_SIGNAL
                ) 1 else 0,
                sideMismatches = old.sideMismatches + if (
                    comparison.agreement == MirrorTurnAgreement.SIDE_MISMATCH
                ) 1 else 0,
                unavailable = old.unavailable + if (
                    comparison.agreement == MirrorTurnAgreement.SIGNAL_UNAVAILABLE
                ) 1 else 0,
                lastChangedAtElapsedMs = if (changed) nowElapsedMs else old.lastChangedAtElapsedMs,
            )
        }
    }

    fun snapshot(): MirrorTurnSignalDiagnosticSnapshot = latest.get()

    fun unavailable(details: String): VehicleSignalState<TurnIndicatorMode> =
        VehicleSignalState.Missing(VehicleSignalMissingReason.SOURCE_DOWN, details)
}
