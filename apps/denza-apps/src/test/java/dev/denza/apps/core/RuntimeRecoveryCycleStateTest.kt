package dev.denza.apps.core

import dev.denza.apps.feature.adb.AdbAutostartRetryAction
import dev.denza.apps.feature.adb.AdbAutostartRetryPolicy
import dev.denza.apps.feature.adb.AdbRescuePhase
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

    /**
     * Review finding 11: the look of the cycle's first attempt found the key trusted, the listener
     * ran the startup pass, and the cycle went on - its attempt at 4 s ran the pass again. The pass
     * ends the cycle it began in, whoever started it.
     */
    @Test
    fun `a cycle runs one startup pass, whoever starts it`() {
        val cycles = RuntimeRecoveryCycleState()
        val passes = RuntimePasses(runningCycle = { cycles.activeGeneration() }, cycleDone = { cycles.finish(it) })
        var ran = 0
        var phase = AdbRescuePhase.UNKNOWN
        // The access core's listener: a trusted key starts the runtime.
        val listener = { if (phase == AdbRescuePhase.TRUSTED) passes.run { ran++ } }

        fun cycle(lookFinds: AdbRescuePhase) {
            val entered = cycles.enter()
            RuntimeAutostartRetrySchedule.atMillis.forEach { _ ->
                if (!cycles.isActive(entered.generation)) return@forEach
                when (AdbAutostartRetryPolicy.action(phase)) {
                    AdbAutostartRetryAction.CHECK_ACCESS -> {
                        // The look answers, and the listener hears the change.
                        val before = phase
                        phase = lookFinds
                        if (phase != before) listener()
                    }
                    AdbAutostartRetryAction.START_RUNTIME -> passes.run { ran++ }
                    AdbAutostartRetryAction.NONE -> Unit
                }
            }
            if (cycles.isActive(entered.generation)) cycles.finish(entered.generation)
        }

        cycle(lookFinds = AdbRescuePhase.TRUSTED)
        assertEquals("the boot's cycle", 1, ran)
        // The screen wakes on a trusted key: its cycle's pass, once.
        cycle(lookFinds = AdbRescuePhase.TRUSTED)
        assertEquals(2, ran)
        // A cycle on a refused key runs none.
        phase = AdbRescuePhase.AUTHORIZATION_REQUIRED
        cycle(lookFinds = AdbRescuePhase.AUTHORIZATION_REQUIRED)
        assertEquals(2, ran)
    }

    @Test
    fun `a pass ends only the cycle it began in, and runs alone`() {
        val cycles = RuntimeRecoveryCycleState()
        val passes = RuntimePasses(runningCycle = { cycles.activeGeneration() }, cycleDone = { cycles.finish(it) })
        var nested = true
        // Begun with no cycle running; a cycle entered during it is not its to end.
        passes.run {
            val entered = cycles.enter()
            nested = passes.run { }
            assertTrue(cycles.isActive(entered.generation))
        }
        assertFalse("one pass at a time", nested)
        assertTrue(cycles.activeGeneration() != null)
        val running = cycles.activeGeneration()!!
        assertTrue(passes.run { })
        assertFalse(cycles.isActive(running))
        assertEquals(null, cycles.activeGeneration())
    }
}
