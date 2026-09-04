package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalMissingReason
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorTurnSignalShadowTest {
    @Test
    fun matchingConfirmedSideAndWindowAgree() {
        assertEquals(
            MirrorTurnAgreement.MATCH,
            compare(TurnIndicatorMode.LEFT, MirrorSide.LEFT),
        )
    }

    @Test
    fun confirmedSideWithoutAWindowDoesNotGuessWhyTheWindowIsAbsent() {
        assertEquals(
            MirrorTurnAgreement.SIGNAL_WITHOUT_WINDOW,
            compare(TurnIndicatorMode.RIGHT, null),
        )
    }

    @Test
    fun oppositeConfirmedSideIsANonActuatingMismatch() {
        assertEquals(
            MirrorTurnAgreement.SIDE_MISMATCH,
            compare(TurnIndicatorMode.LEFT, MirrorSide.RIGHT),
        )
    }

    @Test
    fun hazardAndOffNeverRequestASide() {
        assertEquals(MirrorTurnAgreement.MATCH, compare(TurnIndicatorMode.OFF, null))
        assertEquals(MirrorTurnAgreement.MATCH, compare(TurnIndicatorMode.HAZARD, null))
        assertEquals(
            MirrorTurnAgreement.WINDOW_WITHOUT_DIRECTIONAL_SIGNAL,
            compare(TurnIndicatorMode.HAZARD, MirrorSide.LEFT),
        )
    }

    @Test
    fun unavailableSignalStaysUnavailable() {
        val result = MirrorTurnSignalShadow.compare(
            VehicleSignalState.Missing(VehicleSignalMissingReason.SOURCE_DOWN, "sleep"),
            MirrorSide.LEFT,
        )

        assertEquals(MirrorTurnAgreement.SIGNAL_UNAVAILABLE, result.agreement)
    }

    @Test
    fun unqualifiedVendorModeNeverCountsAsAgreement() {
        assertEquals(
            MirrorTurnAgreement.SIGNAL_UNAVAILABLE,
            compare(TurnIndicatorMode.VendorDefined(3), null),
        )
    }

    @Test
    fun diagnosticsStayBoundedToCountersAndLatestState() {
        MirrorTurnSignalDiagnostics.reset(10L)
        MirrorTurnSignalDiagnostics.record(
            VehicleSignalState.Fresh(TurnIndicatorMode.LEFT, 20L, 20L),
            null,
            20L,
        )
        val latest = MirrorTurnSignalDiagnostics.record(
            VehicleSignalState.Fresh(TurnIndicatorMode.LEFT, 20L, 20L),
            MirrorSide.LEFT,
            30L,
        )

        assertEquals(2L, latest.observations)
        assertEquals(1L, latest.signalWithoutWindow)
        assertEquals(1L, latest.matches)
        assertEquals(30L, latest.lastChangedAtElapsedMs)
        assertEquals(
            "state=left; window=left; agreement=match; samples=2/1/1/0/0/0",
            latest.compact(),
        )
    }

    private fun compare(mode: TurnIndicatorMode, window: MirrorSide?): MirrorTurnAgreement =
        MirrorTurnSignalShadow.compare(
            VehicleSignalState.Fresh(mode, 1L, 1L),
            window,
        ).agreement
}
