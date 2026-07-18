package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbCandidatePlanTest {
    @Test
    fun `loopback is tried before own interface fallback`() {
        assertEquals(
            listOf(
                AdbCandidate(AdbEndpointKind.SmartSocket, "127.0.0.1", 5037),
                AdbCandidate(AdbEndpointKind.RawAdbd, "127.0.0.1", 5555),
                AdbCandidate(AdbEndpointKind.SmartSocket, "192.168.1.10", 5037),
                AdbCandidate(AdbEndpointKind.RawAdbd, "192.168.1.10", 5555),
            ),
            AdbCandidatePlan.build(listOf("192.168.1.10", "192.168.1.10")),
        )
    }
}
