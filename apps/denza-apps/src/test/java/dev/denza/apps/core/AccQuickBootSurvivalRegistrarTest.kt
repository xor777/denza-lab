package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccQuickBootSurvivalRegistrarTest {
    @Test
    fun `whitelist command is fixed`() {
        assertEquals(
            "service call accmodemanager 1 s16 dev.denza.apps",
            AccWhitelistRegistrationPolicy.COMMAND,
        )
    }

    @Test
    fun `registration command can begin at most once in a process`() {
        val gate = AccWhitelistRegistrationGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertEquals(
            AccWhitelistRegistrationState.REGISTERED,
            gate.complete(success = true),
        )
        assertFalse(gate.begin())
    }

    @Test
    fun `registration failure is non throwing and does not rearm command spam`() {
        val gate = AccWhitelistRegistrationGate()

        assertTrue(gate.begin())
        assertEquals(
            AccWhitelistRegistrationState.NOT_REGISTERED,
            gate.complete(success = false),
        )
        assertFalse(gate.begin())
    }

    @Test
    fun `binder errors are not accepted as registration`() {
        assertTrue(
            AccWhitelistRegistrationPolicy.accepted(
                "Result: Parcel(00000000    '....')",
            ),
        )
        assertFalse(AccWhitelistRegistrationPolicy.accepted("Permission Denial: DEVICE_ACC"))
        assertFalse(AccWhitelistRegistrationPolicy.accepted("Exception occurred while executing"))
        assertFalse(AccWhitelistRegistrationPolicy.accepted(""))
    }
}
