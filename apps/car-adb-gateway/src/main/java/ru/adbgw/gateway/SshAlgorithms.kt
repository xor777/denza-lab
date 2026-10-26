package ru.adbgw.gateway

import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.server.ServerBuilder

/**
 * What both SSH ends on the car offer, in preference order: the tunnel client to the relay and the
 * inner server the computer's OpenSSH reaches through it.
 *
 * <p>MINA's own defaults lead with the post-quantum hybrids and ChaCha20-Poly1305, which it computes
 * in Java (and the hybrids need a Bouncy Castle the car does not have); these run in the platform
 * provider, Conscrypt, on the head unit. Each list is filtered by what the running platform
 * supports, so a platform without X25519 drops curve25519 and keeps P-256 and group 14. The relay's
 * OpenSSH 10.2 and every OpenSSH 9/10 client offer all three key exchanges, both ciphers and both
 * MACs by default.
 */
internal object SshAlgorithms {
    val keyExchanges: List<BuiltinDHFactories> = listOf(
        BuiltinDHFactories.ecdhp256, // ecdh-sha2-nistp256: native in Conscrypt
        BuiltinDHFactories.curve25519, // curve25519-sha256: where the platform has X25519
        BuiltinDHFactories.dhg14_256, // diffie-hellman-group14-sha256: the floor
    )
    val ciphers: List<BuiltinCiphers> = listOf(BuiltinCiphers.aes128gcm, BuiltinCiphers.aes128ctr)
    val macs: List<BuiltinMacs> = listOf(BuiltinMacs.hmacsha256etm, BuiltinMacs.hmacsha256)

    fun clientKeyExchanges(): List<KeyExchangeFactory> =
        NamedFactory.setUpTransformedFactories(true, keyExchanges, ClientBuilder.DH2KEX)

    fun serverKeyExchanges(): List<KeyExchangeFactory> =
        NamedFactory.setUpTransformedFactories(true, keyExchanges, ServerBuilder.DH2KEX)

    fun cipherFactories(): List<NamedFactory<Cipher>> = NamedFactory.setUpBuiltinFactories(true, ciphers)

    fun macFactories(): List<NamedFactory<Mac>> = NamedFactory.setUpBuiltinFactories(true, macs)
}
