package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalMissingReason
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorSameSideRearmTest {
    @Test fun matchingModeUsesObservationNotVerificationTime() {
        assertEquals(100L, evidence(TurnIndicatorMode.LEFT, MirrorSide.LEFT))
        assertEquals(100L, evidence(TurnIndicatorMode.RIGHT, MirrorSide.RIGHT))
    }

    @Test fun offHazardUnknownMismatchAndNoWindowNeverRearm() {
        listOf(TurnIndicatorMode.OFF, TurnIndicatorMode.HAZARD, TurnIndicatorMode.VendorDefined(123))
            .forEach { mode -> MirrorSide.entries.forEach { assertNull(evidence(mode, it)) } }
        assertNull(evidence(TurnIndicatorMode.RIGHT, MirrorSide.LEFT))
        assertNull(evidence(TurnIndicatorMode.LEFT, MirrorSide.RIGHT))
        assertNull(evidence(TurnIndicatorMode.LEFT, null))
    }

    @Test fun missingDataNeverRearms() {
        VehicleSignalMissingReason.entries.forEach { reason ->
            assertNull(MirrorSameSideRearm.observedAtMs(VehicleSignalState.Missing(reason), MirrorSide.LEFT, 200L))
        }
    }

    @Test fun invalidClockMetadataNeverRearms() {
        listOf(-1L to 150L, 151L to 150L, 100L to 201L, 201L to 201L).forEach { (observed, verified) ->
            assertNull(MirrorSameSideRearm.observedAtMs(
                VehicleSignalState.Fresh(TurnIndicatorMode.LEFT, observed, verified), MirrorSide.LEFT, 200L,
            ))
        }
    }

    private fun evidence(mode: TurnIndicatorMode, side: MirrorSide?) = MirrorSameSideRearm.observedAtMs(
        VehicleSignalState.Fresh(mode, observedAtElapsedMs = 100L, verifiedAtElapsedMs = 150L), side, 200L,
    )
}
