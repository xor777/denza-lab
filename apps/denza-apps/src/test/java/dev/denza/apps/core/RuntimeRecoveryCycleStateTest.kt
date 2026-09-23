package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryCycleStateTest {
    @Test
    fun `application receiver and screen signals coalesce into one cycle`() {
        val state = RuntimeRecoveryCycleState()

        val application = state.enter()
        val receiver = state.enter()
        val screen = state.enter()

        assertTrue(application.started)
        assertFalse(receiver.started)
        assertFalse(screen.started)
        assertEquals(application.generation, receiver.generation)
        assertEquals(application.generation, screen.generation)
        assertTrue(state.finish(application.generation))
        assertFalse(state.isActive(application.generation))
        assertFalse(state.finish(application.generation))

        val next = state.enter()
        assertTrue(next.started)
        assertNotEquals(application.generation, next.generation)
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
