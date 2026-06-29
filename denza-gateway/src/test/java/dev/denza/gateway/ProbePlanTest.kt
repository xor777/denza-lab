package dev.denza.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbePlanTest {
    @Test
    fun autoModeTriesSmartSocketThenRawAdbd() {
        val candidates = GatewayConfig().probeCandidates()

        assertEquals(
            listOf(
                ProbeCandidate(AdbEndpointKind.SmartSocket, "127.0.0.1", 5037),
                ProbeCandidate(AdbEndpointKind.RawAdbd, "127.0.0.1", 5555),
            ),
            candidates,
        )
    }

    @Test
    fun explicitRawModeOnlyTriesRawAdbd() {
        val candidates = GatewayConfig(endpointMode = EndpointMode.RawAdbd, rawAdbdPort = 15555)
            .probeCandidates()

        assertEquals(
            listOf(ProbeCandidate(AdbEndpointKind.RawAdbd, "127.0.0.1", 15555)),
            candidates,
        )
    }
}
