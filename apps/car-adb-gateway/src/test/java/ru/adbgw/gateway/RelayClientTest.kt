package ru.adbgw.gateway

import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayClientTest {
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
