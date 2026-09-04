package dev.denza.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryActionPolicyTest {
    @Test
    fun `only unlocked system recovery lifecycle actions are accepted`() {
        assertTrue(RuntimeRecoveryActionPolicy.shouldRecover("android.intent.action.BOOT_COMPLETED"))
        assertFalse(
            RuntimeRecoveryActionPolicy.shouldRecover(
                "android.intent.action.LOCKED_BOOT_COMPLETED",
            ),
        )
        assertTrue(
            RuntimeRecoveryActionPolicy.shouldRecover(
                "android.intent.action.MY_PACKAGE_REPLACED",
            ),
        )
    }

    @Test
    fun `dishare and arbitrary actions cannot trigger recovery`() {
        assertFalse(RuntimeRecoveryActionPolicy.shouldRecover("action.byd.dishare.DIALOG_HOME"))
        assertFalse(RuntimeRecoveryActionPolicy.shouldRecover("action.byd.dishare.DIALOG_LAUNCHER"))
        assertFalse(RuntimeRecoveryActionPolicy.shouldRecover("action.byd.dishare.DIALOG_CLOSE"))
        assertFalse(RuntimeRecoveryActionPolicy.shouldRecover("dev.denza.apps.RECOVER"))
        assertFalse(RuntimeRecoveryActionPolicy.shouldRecover(null))
    }
}
