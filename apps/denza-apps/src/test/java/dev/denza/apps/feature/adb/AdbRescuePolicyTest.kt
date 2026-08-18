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
        )

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, checked.phase)
        assertTrue(checked.canRequest)
        assertFalse(checked.requestPending)
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
        )

        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, checked.phase)
        assertFalse(checked.canRequest)
    }

    @Test
    fun `one shot submission stays latched after send`() {
        val required = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
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
}
