package dev.denza.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayUiStateTest {
    @Test
    fun runningTracksActiveGatewayInsteadOfStatusText() {
        assertTrue(
            GatewayUiState(
                status = GatewayStatus.BlockedPeer,
                gatewayActive = true,
            ).isRunning,
        )

        assertFalse(
            GatewayUiState(
                status = GatewayStatus.Running,
                gatewayActive = false,
            ).isRunning,
        )
    }
}
