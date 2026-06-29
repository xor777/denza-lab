package dev.denza.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingPolicyTest {
    private val endpoint = AdbEndpoint(
        kind = AdbEndpointKind.SmartSocket,
        host = "127.0.0.1",
        port = 5037,
        detail = "test",
    )

    @Test
    fun allowsSelectedAdbEndpoint() {
        assertTrue(ForwardingPolicy.isAllowedDestination(endpoint, "127.0.0.1", 5037))
        assertTrue(ForwardingPolicy.isAllowedDestination(endpoint, "localhost", 5037))
    }

    @Test
    fun rejectsDifferentPortOrHost() {
        assertFalse(ForwardingPolicy.isAllowedDestination(endpoint, "127.0.0.1", 5555))
        assertFalse(ForwardingPolicy.isAllowedDestination(endpoint, "192.168.1.1", 5037))
    }
}
