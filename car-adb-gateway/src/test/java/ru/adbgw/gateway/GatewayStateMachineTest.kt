package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayStateMachineTest {
    @Test
    fun `manual disconnect remains disabled and clears session state`() {
        val connected = GatewayUiState(
            initialized = true,
            enabled = true,
            relayState = RelayState.Connected,
            clientState = ClientState.Active,
            connectedSinceMillis = 100,
        )
        val disabled = GatewayStateMachine.reduce(connected, GatewayEvent.EnabledChanged(false))
        assertEquals(RelayState.Disabled, disabled.relayState)
        assertEquals(ClientState.Waiting, disabled.clientState)
        assertNull(disabled.connectedSinceMillis)
    }

    @Test
    fun `activity records the session start only once`() {
        val first = GatewayStateMachine.reduce(
            GatewayUiState(),
            GatewayEvent.ClientChanged(ClientState.Connected, "Mac", 100),
        )
        val active = GatewayStateMachine.reduce(
            first,
            GatewayEvent.ClientChanged(ClientState.Active, "Mac", 200),
        )
        assertEquals(100L, active.connectedSinceMillis)
        assertEquals(200L, active.lastActivityMillis)
        val enrolled = active.copy(
            relayState = RelayState.Connected,
            registration = RelayRegistration(
                deviceId = "0123456789abcdef",
                deviceLabel = "Автомобиль",
                relayDevicePort = 20_000,
                innerHostKey = "ssh-rsa key",
                endpointKind = AdbEndpointKind.SmartSocket,
                endpointHost = "127.0.0.1",
                enabled = true,
            ),
            adbState = AdbState.Available,
        )
        assertEquals("Удалённый компьютер работает", enrolled.headline)
    }
}
