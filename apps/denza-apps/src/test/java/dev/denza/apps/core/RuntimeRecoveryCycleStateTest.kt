package dev.denza.apps.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryCycleStateTest {
    @Test
    fun `only boot completed grants acc registration authority`() {
        listOf(
            RuntimeStartCause.PROCESS_START,
            RuntimeStartCause.PACKAGE_REPLACED,
            RuntimeStartCause.SCREEN_ON,
        ).forEach { cause ->
            assertFalse(cause.name, cause.mayRegisterAccWhitelist)
        }
        assertTrue(RuntimeStartCause.BOOT_COMPLETED.mayRegisterAccWhitelist)
    }

    @Test
    fun `application receiver and screen signals coalesce and boot upgrades the cycle`() {
        val state = RuntimeRecoveryCycleState()

        val application = state.enter(RuntimeStartCause.PROCESS_START)
        val receiver = state.enter(RuntimeStartCause.BOOT_COMPLETED)
        val screen = state.enter(RuntimeStartCause.SCREEN_ON)

        assertTrue(application.started)
        assertFalse(receiver.started)
        assertFalse(screen.started)
        assertTrue(receiver.mayRegisterAccWhitelist)
        assertTrue(screen.mayRegisterAccWhitelist)
        assertFalse(state.isRuntimeReconciled(application.generation))
        assertTrue(state.markRuntimeReconciled(application.generation))
        assertTrue(state.isRuntimeReconciled(application.generation))
        assertTrue(state.finish(application.generation))

        val nextScreen = state.enter(RuntimeStartCause.SCREEN_ON)
        assertTrue(nextScreen.started)
        assertNotEquals(application.generation, nextScreen.generation)
        assertFalse(nextScreen.mayRegisterAccWhitelist)
    }

    @Test
    fun `bootstrap service stops on recovery or timeout only`() {
        assertTrue(RuntimeRecoveryServicePolicy.shouldStop(recovered = true, elapsedMillis = 1L))
        assertTrue(
            RuntimeRecoveryServicePolicy.shouldStop(
                recovered = false,
                elapsedMillis = 60_000L,
            ),
        )
        assertFalse(
            RuntimeRecoveryServicePolicy.shouldStop(
                recovered = false,
                elapsedMillis = 59_999L,
            ),
        )
    }
}
