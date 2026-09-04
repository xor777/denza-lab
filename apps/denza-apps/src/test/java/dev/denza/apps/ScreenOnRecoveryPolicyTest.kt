package dev.denza.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnRecoveryPolicyTest {
    @Test
    fun `only screen on reconciles a surviving live process`() {
        assertTrue(ScreenOnRecoveryPolicy.shouldRecover("android.intent.action.SCREEN_ON"))
        assertFalse(ScreenOnRecoveryPolicy.shouldRecover("android.intent.action.SCREEN_OFF"))
        assertFalse(ScreenOnRecoveryPolicy.shouldRecover(null))
    }
}
