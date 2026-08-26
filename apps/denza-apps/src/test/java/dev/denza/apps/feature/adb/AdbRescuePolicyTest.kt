package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRescuePolicyTest {
    @Test
    fun deniedPassiveCheckAllowsOneManualRequest() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, checked.phase)
        assertTrue(checked.canRequest)
        assertFalse(checked.requestPending)
        assertEquals(AdbSystemSwitch.ENABLED, checked.systemSwitch)
    }

    @Test
    fun pendingRequestSurvivesRestartAndSuppressesAnotherRequest() {
        val restored = AdbRescuePolicy.initial(true, 1, 42L)

        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, restored.phase)
        assertFalse(restored.canRequest)
        assertTrue(restored.canResetAttempt)
    }

    @Test
    fun deniedCheckDoesNotRearmPendingRequest() {
        val pending = AdbRescuePolicy.initial(true, 1, 42L)
        val checked = AdbRescuePolicy.afterCheck(
            pending,
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )

        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, checked.phase)
        assertFalse(checked.canRequest)
    }

    @Test
    fun `one shot submission stays latched after send`() {
        val required = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )
        val requesting = AdbRescuePolicy.requesting(required, 42L)
        val sent = AdbRescuePolicy.afterRequest(requesting, AdbRequestOutcome.REQUEST_SENT)

        assertEquals(1, sent.attemptCount)
        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, sent.phase)
        assertTrue(sent.requestPending)
        assertFalse(sent.canRequest)
    }

    @Test
    fun `uncertain send failure remains fail closed`() {
        val required = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )
        val requesting = AdbRescuePolicy.requesting(required, 42L)
        val failed = AdbRescuePolicy.afterRequest(
            requesting,
            AdbRequestOutcome.UNAVAILABLE,
            "ConnectException",
        )

        assertTrue(failed.requestPending)
        assertFalse(failed.canRequest)
        assertTrue(failed.canResetAttempt)
    }

    @Test
    fun trustedCheckClearsOneShotLatch() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(true, 1, 42L),
            AdbCheckOutcome.TRUSTED,
            AdbSystemSwitch.ENABLED,
        )

        assertEquals(AdbRescuePhase.TRUSTED, checked.phase)
        assertFalse(checked.requestPending)
        assertFalse(checked.canRequest)
    }

    @Test
    fun resetOnlyRearmsPassiveCheckNotTheRequestItself() {
        val reset = AdbRescuePolicy.resetAttempt(
            AdbRescuePolicy.initial(true, 1, 42L),
        )

        assertEquals(AdbRescuePhase.UNKNOWN, reset.phase)
        assertFalse(reset.requestPending)
        assertFalse(reset.canRequest)
    }

    @Test
    fun `an off system switch is the service state, not a confirmation request`() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.DISABLED,
        )

        assertEquals(AdbRescuePhase.UNAVAILABLE, checked.phase)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, checked.details)
        assertFalse(checked.canRequest)
    }

    @Test
    fun `an unreadable system switch keeps the handshake classification`() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.UNKNOWN,
        )

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, checked.phase)
        assertTrue(checked.canRequest)
        assertEquals(AdbSystemSwitch.UNKNOWN, checked.systemSwitch)
    }

    @Test
    fun `a trusted handshake outranks an off switch`() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.TRUSTED,
            AdbSystemSwitch.DISABLED,
        )

        assertEquals(AdbRescuePhase.TRUSTED, checked.phase)
    }

    @Test
    fun `an off switch found on a pending request does not clear the latch`() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(true, 1, 42L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.DISABLED,
        )

        assertEquals(AdbRescuePhase.UNAVAILABLE, checked.phase)
        assertTrue(checked.requestPending)
        assertEquals(1, checked.attemptCount)
        assertTrue(checked.canResetAttempt)
    }
}
