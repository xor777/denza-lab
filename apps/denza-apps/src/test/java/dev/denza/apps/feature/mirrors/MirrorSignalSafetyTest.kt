package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEvent
import dev.denza.apps.feature.vehicle.signal.VehicleSignalKeys
import dev.denza.apps.feature.vehicle.signal.VehicleSignalSourceId
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSignalSafetyTest {
    @Test
    fun startupNeedsAConfirmedNeutralBaseline() {
        var state = MirrorSignalSafetyState()

        repeat(2) { index ->
            val result = observe(state, TurnIndicatorMode.OFF, null, index * 100L)
            state = result.state
            assertFalse(state.continuityReady)
            assertNull(result.eligibleSide)
        }
        val ready = observe(state, TurnIndicatorMode.OFF, null, 200L)

        assertTrue(ready.state.continuityReady)
        assertNull(ready.eligibleSide)
    }

    @Test
    fun everyOnsetBlocksShowUntilLaterModeAndStableWindow() {
        var state = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 4L, sequence = 10L),
        )

        val retainedOldState = observe(state, TurnIndicatorMode.LEFT, MirrorSide.LEFT, 100L)
        state = retainedOldState.state
        assertNull(retainedOldState.eligibleSide)

        state = MirrorSignalSafety.onModeEvent(
            state,
            modeEvent(TurnIndicatorMode.RIGHT, epoch = 4L, sequence = 11L),
        )
        val first = observe(state, TurnIndicatorMode.RIGHT, MirrorSide.RIGHT, 200L)
        val second = observe(first.state, TurnIndicatorMode.RIGHT, MirrorSide.RIGHT, 300L)
        val third = observe(second.state, TurnIndicatorMode.RIGHT, MirrorSide.RIGHT, 400L)

        assertNull(first.eligibleSide)
        assertNull(second.eligibleSide)
        assertEquals(MirrorSide.RIGHT, third.eligibleSide)
        assertTrue(third.state.continuityReady)
        assertNull(third.state.pendingSwitch)
    }

    @Test
    fun oldOrOtherEpochModeCannotConfirmPendingSwitch() {
        val pending = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 7L, sequence = 20L),
        )

        val old = MirrorSignalSafety.onModeEvent(
            pending,
            modeEvent(TurnIndicatorMode.RIGHT, epoch = 7L, sequence = 20L),
        )
        val otherEpoch = MirrorSignalSafety.onModeEvent(
            pending,
            modeEvent(TurnIndicatorMode.RIGHT, epoch = 8L, sequence = 21L),
        )

        assertNull(old.confirmedAfterSwitch)
        assertNull(otherEpoch.confirmedAfterSwitch)
    }

    @Test
    fun delayedOnsetClosesAnAlreadyOpenEligibilityGate() {
        val ready = MirrorSignalSafetyState(continuityReady = true)
        assertEquals(
            MirrorSide.LEFT,
            observe(ready, TurnIndicatorMode.LEFT, MirrorSide.LEFT, 100L).eligibleSide,
        )

        val pending = MirrorSignalSafety.onSwitchEvent(
            ready,
            switchEvent(epoch = 3L, sequence = 9L),
        )

        assertNull(
            observe(pending, TurnIndicatorMode.LEFT, MirrorSide.LEFT, 101L).eligibleSide,
        )
    }

    @Test
    fun laterConfirmedOffSafelyCompletesPendingTransition() {
        val pending = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 2L, sequence = 5L),
        )

        val off = MirrorSignalSafety.onModeEvent(
            pending,
            modeEvent(TurnIndicatorMode.OFF, epoch = 2L, sequence = 6L),
        )

        assertTrue(off.continuityReady)
        assertNull(off.pendingSwitch)
    }

    @Test
    fun oldOffCannotClearANewerPendingSwitch() {
        val pending = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 2L, sequence = 5L),
        )

        val oldOff = MirrorSignalSafety.onModeEvent(
            pending,
            modeEvent(TurnIndicatorMode.OFF, epoch = 2L, sequence = 4L),
        )

        assertEquals(pending.pendingSwitch, oldOff.pendingSwitch)
        assertFalse(oldOff.continuityReady)
    }

    @Test
    fun pendingSwitchExpiresFailClosedAfterBoundedWait() {
        val pending = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 2L, sequence = 5L, observedAt = 100L),
        )

        val atBoundary = observe(
            pending,
            TurnIndicatorMode.RIGHT,
            MirrorSide.RIGHT,
            100L + MirrorSignalSafety.PENDING_SWITCH_TIMEOUT_MS,
        )
        val expired = observe(
            atBoundary.state,
            TurnIndicatorMode.RIGHT,
            MirrorSide.RIGHT,
            101L + MirrorSignalSafety.PENDING_SWITCH_TIMEOUT_MS,
        )

        assertEquals(pending.pendingSwitch, atBoundary.state.pendingSwitch)
        assertFalse(expired.state.continuityReady)
        assertNull(expired.state.pendingSwitch)
        assertNull(expired.eligibleSide)
    }

    @Test
    fun elapsedRealtimeRollbackExpiresPendingSwitchFailClosed() {
        val pending = MirrorSignalSafety.onSwitchEvent(
            MirrorSignalSafetyState(continuityReady = true),
            switchEvent(epoch = 2L, sequence = 5L, observedAt = 500L),
        )

        val result = observe(pending, TurnIndicatorMode.RIGHT, MirrorSide.RIGHT, 499L)

        assertFalse(result.state.continuityReady)
        assertNull(result.state.pendingSwitch)
        assertNull(result.eligibleSide)
    }

    private fun observe(
        state: MirrorSignalSafetyState,
        mode: TurnIndicatorMode,
        window: MirrorSide?,
        now: Long,
    ) = MirrorSignalSafety.observe(
        state,
        VehicleSignalState.Fresh(mode, now, now),
        window,
        subscriptionsArmed = true,
        nowMs = now,
    )

    private fun switchEvent(
        epoch: Long,
        sequence: Long,
        observedAt: Long = 1L,
    ) = VehicleSignalEvent(
        VehicleSignalKeys.TurnSwitchPhase,
        TurnSwitchPhase(4),
        SOURCE,
        epoch,
        sequence,
        observedAt,
        1L,
    )

    private fun modeEvent(
        mode: TurnIndicatorMode,
        epoch: Long,
        sequence: Long,
    ) = VehicleSignalEvent(
        VehicleSignalKeys.TurnIndicatorMode,
        mode,
        SOURCE,
        epoch,
        sequence,
        1L,
        1L,
    )

    private companion object {
        val SOURCE = VehicleSignalSourceId("test")
    }
}
