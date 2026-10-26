package ru.adbgw.gateway

import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClientTest {
    @Test
    fun `only a forwarded channel to the inner server is accepted`() {
        assertTrue(
            tunnelForwardedAllowed(TcpForwardingFilter.Type.Forwarded, INNER_SSH_HOST, INNER_SSH_PORT),
        )
        // A direct channel the relay should never open, even to the inner server.
        assertFalse(
            tunnelForwardedAllowed(TcpForwardingFilter.Type.Direct, INNER_SSH_HOST, INNER_SSH_PORT),
        )
        // The right type but not our inner listener.
        assertFalse(tunnelForwardedAllowed(TcpForwardingFilter.Type.Forwarded, INNER_SSH_HOST, 5555))
        assertFalse(tunnelForwardedAllowed(TcpForwardingFilter.Type.Forwarded, "10.0.0.5", INNER_SSH_PORT))
    }

    @Test
    fun `pinned Ed25519 host key is negotiated before other server keys`() {
        val defaults: List<NamedFactory<Signature>> = listOf(
            BuiltinSignatures.nistp256,
            BuiltinSignatures.ed25519,
            BuiltinSignatures.rsaSHA512,
        )

        val preferred = preferPinnedHostKeyAlgorithm(defaults)

        assertEquals("ssh-ed25519", preferred.first().name)
        assertEquals(defaults.toSet(), preferred.toSet())
    }
}
