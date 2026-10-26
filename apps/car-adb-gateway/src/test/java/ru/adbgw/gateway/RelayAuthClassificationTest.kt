package ru.adbgw.gateway

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What MINA throws when the relay refuses the vehicle key, stalls during auth, or drops the TCP
 * connection, and how [RelayClient] classifies each one. Only the first may count as a rejected
 * registration; the others are transient and must never wipe the car's enrollment.
 */
class RelayAuthClassificationTest {
    private val closeables = ArrayDeque<AutoCloseable>()

    @After
    fun tearDown() {
        while (closeables.isNotEmpty()) runCatching { closeables.removeLast().close() }
    }

    @Test
    fun `a relay that refuses the device key rejects the registration`() {
        val deviceKey = rsa()
        val relay = startRelay(PublickeyAuthenticator { _, _, _ -> false })
        val accepted = AtomicInteger()

        val error = failure { client(deviceKey, relay, accepted).openTunnel(registration()) {} }

        report("rejected key", error)
        assertTrue(error.permanent)
        assertTrue(error.registrationRejected)
        assertTrue(isDefinitiveAuthRejection(error.cause!!))
        assertEquals(0, accepted.get())
    }

    @Test
    fun `a control call to a relay that refuses the key rejects the registration`() {
        val relay = startRelay(PublickeyAuthenticator { _, _, _ -> false })

        val error = failure { client(rsa(), relay).renewLease(registration()) }

        assertTrue(error.permanent)
        assertTrue(error.registrationRejected)
    }

    @Test
    fun `an auth that outlasts the client timeout is transient`() {
        val release = CountDownLatch(1)
        closeables.addLast(AutoCloseable { release.countDown() })
        val relay = startRelay(PublickeyAuthenticator { _, _, _ -> release.await(10, TimeUnit.SECONDS); true })

        val started = System.nanoTime()
        val error = failure { client(rsa(), relay).openTunnel(registration()) {} }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        report("auth timeout", error)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
        assertFalse(isDefinitiveAuthRejection(error.cause!!))
        assertTrue("the injected timeout bounds the wait: ${elapsedMillis}ms", elapsedMillis < 5_000)
    }

    @Test
    fun `a control call whose auth outlasts the timeout is transient`() {
        val release = CountDownLatch(1)
        closeables.addLast(AutoCloseable { release.countDown() })
        val relay = startRelay(PublickeyAuthenticator { _, _, _ -> release.await(10, TimeUnit.SECONDS); true })

        val error = failure { client(rsa(), relay).renewLease(registration()) }

        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
    }

    @Test
    fun `a relay that drops the connection right after accept is transient`() {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closeables.addLast(socket)
        Thread({
            while (!socket.isClosed) runCatching { socket.accept().close() }
        }, "drop-after-accept").apply { isDaemon = true; start() }
        val relay = Relay(socket.localPort, "SHA256:unused")

        val error = failure { client(rsa(), relay).openTunnel(registration()) {} }

        report("dropped connection", error)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
    }

    @Test
    fun `a relay that closes the session during auth is transient`() {
        val relay = startRelay(PublickeyAuthenticator { _, _, session -> session.close(true); false })

        val error = failure { client(rsa(), relay).openTunnel(registration()) {} }

        report("closed during auth", error)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
    }

    @Test
    fun `an accepted key reports success`() {
        val deviceKey = rsa()
        val canonical = PublicKeyEntry.toString(deviceKey.public)
        val relay = startRelay(PublickeyAuthenticator { _, key, _ -> PublicKeyEntry.toString(key) == canonical })
        val accepted = AtomicInteger()

        val tunnel = runBlocking { client(deviceKey, relay, accepted).openTunnel(registration()) {} }
        closeables.addLast(tunnel)

        assertEquals(1, accepted.get())
    }

    @Test
    fun `a pinned host key mismatch stays permanent and is not a rejection`() {
        val relay = startRelay(PublickeyAuthenticator { _, _, _ -> false })
        val client = RelayClient(
            keyStore = Keys(rsa()),
            relayHost = "127.0.0.1",
            relaySshPort = relay.port,
            relayFingerprints = setOf("SHA256:not-the-relay"),
            connectTimeout = Duration.ofSeconds(2),
            authTimeout = Duration.ofSeconds(1),
        )

        val error = failure { client.openTunnel(registration()) {} }

        assertTrue(error.permanent)
        assertFalse(error.registrationRejected)
    }

    private fun failure(block: suspend () -> Unit): RelayAccessException {
        try {
            runBlocking { block() }
        } catch (error: RelayAccessException) {
            return error
        }
        fail("expected a RelayAccessException")
        throw AssertionError()
    }

    private fun report(case: String, error: RelayAccessException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ") { cause ->
            val code = (cause as? org.apache.sshd.common.SshException)?.disconnectCode
            "${cause.javaClass.simpleName}(${cause.message}${code?.let { ", code=$it" } ?: ""})"
        }
        println("[$case] $chain")
    }

    private fun client(key: KeyPair, relay: Relay, accepted: AtomicInteger = AtomicInteger()) = RelayClient(
        keyStore = Keys(key),
        relayHost = "127.0.0.1",
        relaySshPort = relay.port,
        relayFingerprints = setOf(relay.fingerprint),
        connectTimeout = Duration.ofSeconds(2),
        authTimeout = Duration.ofSeconds(1),
        onRegisteredKeyAccepted = { accepted.incrementAndGet() },
    )

    private fun startRelay(authenticator: PublickeyAuthenticator): Relay {
        val hostKey = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this.port = port
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            publickeyAuthenticator = authenticator
        }
        server.start()
        closeables.addLast(AutoCloseable { server.stop(true) })
        return Relay(port, KeyUtils.getFingerPrint(hostKey.public))
    }

    private class Relay(val port: Int, val fingerprint: String)

    private class Keys(private val key: KeyPair) : RelayKeyStore {
        override val tunnelKeyPair: KeyPair get() = key
        override val controlKeyPair: KeyPair get() = key
        override val tunnelPublicKey: String get() = PublicKeyEntry.toString(key.public)
        override val controlPublicKey: String get() = PublicKeyEntry.toString(key.public)
        override val innerHostPublicKey: String get() = PublicKeyEntry.toString(key.public)
    }

    private fun registration() = RelayRegistration(
        deviceId = "0123456789abcdef",
        deviceLabel = "Автомобиль",
        relayDevicePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort },
        innerHostKey = "ssh-rsa inner",
        endpointKind = AdbEndpointKind.RawAdbd,
        endpointHost = "127.0.0.1",
        enabled = true,
        leaseExpiresAtEpochSeconds = 4_000_000_000L,
    )

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
