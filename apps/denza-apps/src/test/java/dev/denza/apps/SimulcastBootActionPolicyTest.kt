package dev.denza.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastBootActionPolicyTest {
    @Test
    fun `only system recovery lifecycle actions are accepted`() {
        assertTrue(SimulcastBootActionPolicy.shouldRecover("android.intent.action.BOOT_COMPLETED"))
        assertTrue(
            SimulcastBootActionPolicy.shouldRecover(
                "android.intent.action.LOCKED_BOOT_COMPLETED",
            ),
        )
        assertTrue(
            SimulcastBootActionPolicy.shouldRecover(
                "android.intent.action.MY_PACKAGE_REPLACED",
            ),
        )
    }

    @Test
    fun `dishare and arbitrary actions cannot trigger recovery`() {
        assertFalse(SimulcastBootActionPolicy.shouldRecover("action.byd.dishare.DIALOG_HOME"))
        assertFalse(SimulcastBootActionPolicy.shouldRecover("action.byd.dishare.DIALOG_LAUNCHER"))
        assertFalse(SimulcastBootActionPolicy.shouldRecover("action.byd.dishare.DIALOG_CLOSE"))
        assertFalse(SimulcastBootActionPolicy.shouldRecover("dev.denza.apps.RECOVER"))
        assertFalse(SimulcastBootActionPolicy.shouldRecover(null))
    }
}
