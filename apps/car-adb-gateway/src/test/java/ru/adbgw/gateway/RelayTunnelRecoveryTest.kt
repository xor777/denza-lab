package ru.adbgw.gateway

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.SshException
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.helpers.AbstractConnectionServiceRequestHandler
import org.apache.sshd.common.channel.RequestHandler
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * How the real tunnel client behaves when the network or the relay goes wrong, against a MINA
 * stand-in for the relay: a link that goes silent, a relay that still holds the previous session's
 * port, a network change mid-attempt, and which algorithms it ends up speaking to OpenSSH 10.2.
 */
class RelayTunnelRecoveryTest {
    private val closeables = ArrayDeque<AutoCloseable>()
    private val deviceKey = rsa()

    @After
    fun tearDown() {
        while (closeables.isNotEmpty()) runCatching { closeables.removeLast().close() }
    }

    @Test
    fun `heartbeats need a reply, and a relay that goes silent closes the tunnel`() {
        val interval = Duration.ofMillis(300)
        val keepalive = OpenSshKeepalive()
        val relay = startRelay { it.globalRequestHandlers = listOf(keepalive) + it.globalRequestHandlers }
        val link = SilenceableLink(relay.port).also(closeables::addLast)
        val closed = CountDownLatch(1)
        val closeError = AtomicReference<Throwable?>(null)
        val client = client(relay, port = link.port, heartbeat = RelayHeartbeat(interval, 3))
        val tunnel = runBlocking {
            client.openTunnel(registration()) { error ->
                if (error != null) closeError.compareAndSet(null, error)
                closed.countDown()
            }
        }
        closeables.addLast(tunnel)

        // OpenSSH answers keepalive@openssh.com with REQUEST_FAILURE; that is a reply.
        Thread.sleep(interval.toMillis() * 6)
        assertTrue("answered heartbeats keep the tunnel up", tunnel.isOpen)
        assertTrue("heartbeats were sent: ${keepalive.seen}", keepalive.seen.size >= 4)
        assertTrue(
            "every heartbeat is keepalive@openssh.com with want-reply: ${keepalive.seen}",
            keepalive.seen.all { it == (RelayHeartbeat.REQUEST to true) },
        )

        val silentAt = System.nanoTime()
        link.silence()
        assertTrue("a silent relay closes the tunnel", closed.await(10, TimeUnit.SECONDS))
        val elapsedMillis = (System.nanoTime() - silentAt) / 1_000_000
        println("[heartbeat] closed ${elapsedMillis}ms after the link went silent: ${closeError.get()}")

        assertFalse(tunnel.isOpen)
        assertTrue(
            "closed within 2-4 intervals of silence, not later: ${elapsedMillis}ms",
            elapsedMillis in (2 * interval.toMillis() - 100)..(4 * interval.toMillis() + 1_500),
        )
        assertTrue(
            "MINA names the unanswered heartbeats: ${closeError.get()}",
            closeError.get()?.message.orEmpty().contains("heartbeat requests without reply"),
        )
    }

    @Test
    fun `the production heartbeat closes a dead tunnel within 30-40 s`() {
        assertEquals(RelayHeartbeat(Duration.ofSeconds(10), 3), RelayHeartbeat.DEFAULT)
        assertEquals("keepalive@openssh.com", RelayHeartbeat.REQUEST)
        assertEquals(Duration.ofSeconds(15), RelayClient.CONNECT_TIMEOUT)
        assertEquals(Duration.ofSeconds(30), RelayClient.AUTH_TIMEOUT)
    }

    @Test
    fun `a relay still holding the previous session's port refuses the forward until it lets go`() {
        val relay = startRelay()
        val registration = registration()
        // The session the relay has not noticed is gone, still listening on the car's port.
        val stale = runBlocking { client(relay).openTunnel(registration) {} }
        closeables.addLast(stale)

        val accepted = AtomicInteger()
        val error = failure { client(relay, accepted = accepted).openTunnel(registration) {} }

        report("port held by the previous session", error)
        assertTrue(error.relayPortBusy)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
        assertEquals("the relay accepted the key first", 1, accepted.get())
        assertTrue(error.cause is SshException)

        // The relay drops the stale session (ClientAlive on the real one) and frees the port.
        stale.close()
        val rebound = retryWhilePortBusy { client(relay).openTunnel(registration) {} }
        closeables.addLast(rebound)
        assertTrue(rebound.isOpen)
    }

    @Test
    fun `a relay that refuses tcpip-forward outright reads as a busy port`() {
        val relay = startRelay { it.forwardingFilter = RejectAllForwardingFilter.INSTANCE }

        val error = failure { client(relay).openTunnel(registration()) {} }

        report("tcpip-forward refused", error)
        assertTrue(error.relayPortBusy)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
    }

    @Test
    fun `a network change abandons the attempt in flight at once`() {
        val release = CountDownLatch(1)
        closeables.addLast(AutoCloseable { release.countDown() })
        val relay = startRelayWith(PublickeyAuthenticator { _, _, _ -> release.await(20, TimeUnit.SECONDS); true })
        val client = client(relay, authTimeout = Duration.ofSeconds(15))
        val abort = Thread { Thread.sleep(500); client.abortTunnelAttempt() }.apply { isDaemon = true; start() }

        val started = System.nanoTime()
        val error = failure { client.openTunnel(registration()) {} }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        abort.join()

        report("abandoned attempt", error)
        assertTrue("the abort cut the 15 s auth wait short: ${elapsedMillis}ms", elapsedMillis < 5_000)
        assertFalse(error.permanent)
        assertFalse(error.registrationRejected)
        assertFalse(error.relayPortBusy)
    }

    @Test
    fun `the tunnel client speaks P-256, AES-GCM and the pinned Ed25519 to OpenSSH 10_2's defaults`() {
        // OpenSSH 10.2's default server set, minus the post-quantum hybrids MINA cannot host
        // without Bouncy Castle; the client never offers those (SshAlgorithmsTest).
        val relay = startRelay(configureAlgorithms(OPENSSH_10_KEX, OPENSSH_10_CIPHERS, OPENSSH_10_MACS))

        val negotiated = negotiate(relay)

        println("[relay defaults] $negotiated")
        assertEquals("ecdh-sha2-nistp256", negotiated[KexProposalOption.ALGORITHMS])
        assertEquals("ssh-ed25519", negotiated[KexProposalOption.SERVERKEYS])
        assertEquals("aes128-gcm@openssh.com", negotiated[KexProposalOption.C2SENC])
        assertEquals("aes128-gcm@openssh.com", negotiated[KexProposalOption.S2CENC])
        // GCM authenticates itself; MINA reports the MAC as "aead", OpenSSH as "<implicit>".
        assertEquals("aead", negotiated[KexProposalOption.C2SMAC])
    }

    @Test
    fun `the tunnel client falls back to curve25519, group 14 and AES-CTR`() {
        val cases = listOf(
            Triple(listOf(BuiltinDHFactories.curve25519), OPENSSH_10_CIPHERS, OPENSSH_10_MACS) to
                listOf("curve25519-sha256", "aes128-gcm@openssh.com", "aead"),
            Triple(listOf(BuiltinDHFactories.dhg14_256), listOf(BuiltinCiphers.aes128ctr), listOf(BuiltinMacs.hmacsha256)) to
                listOf("diffie-hellman-group14-sha256", "aes128-ctr", "hmac-sha2-256"),
        )
        for ((offer, expected) in cases) {
            val relay = startRelay(configureAlgorithms(offer.first, offer.second, offer.third))
            val negotiated = negotiate(relay)
            println("[relay ${offer.first.map { it.getName() }}] $negotiated")
            assertEquals(expected[0], negotiated[KexProposalOption.ALGORITHMS])
            assertEquals(expected[1], negotiated[KexProposalOption.C2SENC])
            assertEquals(expected[2], negotiated[KexProposalOption.C2SMAC])
        }
    }

    // --- harness ---------------------------------------------------------------------------------

    private fun negotiate(relay: Relay): Map<KexProposalOption, String> {
        val tunnel = runBlocking { client(relay).openTunnel(registration()) {} }
        try {
            return relay.server.activeSessions.single().kexNegotiationResult
        } finally {
            tunnel.close()
        }
    }

    private fun configureAlgorithms(
        kex: List<BuiltinDHFactories>,
        ciphers: List<BuiltinCiphers>,
        macs: List<BuiltinMacs>,
    ): (SshServer) -> Unit = { server ->
        server.keyExchangeFactories = NamedFactory.setUpTransformedFactories(true, kex, ServerBuilder.DH2KEX)
        server.cipherFactories = NamedFactory.setUpBuiltinFactories(true, ciphers)
        server.macFactories = NamedFactory.setUpBuiltinFactories(true, macs)
    }

    private fun retryWhilePortBusy(open: suspend () -> RelayTunnel): RelayTunnel {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            try {
                return runBlocking { open() }
            } catch (error: RelayAccessException) {
                if (!error.relayPortBusy || System.nanoTime() > deadline) throw error
                Thread.sleep(100)
            }
        }
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
            "${cause.javaClass.simpleName}(${cause.message})"
        }
        println("[$case] $chain")
    }

    private fun client(
        relay: Relay,
        port: Int = relay.port,
        accepted: AtomicInteger = AtomicInteger(),
        authTimeout: Duration = Duration.ofSeconds(5),
        heartbeat: RelayHeartbeat = RelayHeartbeat.DEFAULT,
    ) = RelayClient(
        keyStore = Keys(deviceKey),
        relayHost = "127.0.0.1",
        relaySshPort = port,
        relayFingerprints = setOf(relay.fingerprint),
        connectTimeout = Duration.ofSeconds(5),
        authTimeout = authTimeout,
        heartbeat = heartbeat,
        onRegisteredKeyAccepted = { accepted.incrementAndGet() },
    )

    private fun startRelay(configure: (SshServer) -> Unit = {}): Relay {
        val canonical = PublicKeyEntry.toString(deviceKey.public)
        return startRelayWith(PublickeyAuthenticator { _, key, _ -> PublicKeyEntry.toString(key) == canonical }, configure)
    }

    private fun startRelayWith(authenticator: PublickeyAuthenticator, configure: (SshServer) -> Unit = {}): Relay {
        val hostKey = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this.port = port
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            publickeyAuthenticator = authenticator
        }
        configure(server)
        server.start()
        closeables.addLast(AutoCloseable { server.stop(true) })
        return Relay(server, port, KeyUtils.getFingerPrint(hostKey.public))
    }

    private class Relay(val server: SshServer, val port: Int, val fingerprint: String)

    /** OpenSSH's sshd knows no keepalive request: it answers REQUEST_FAILURE. Records each one. */
    private class OpenSshKeepalive : AbstractConnectionServiceRequestHandler() {
        val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()

        override fun process(
            connectionService: ConnectionService,
            request: String,
            wantReply: Boolean,
            buffer: Buffer,
        ): RequestHandler.Result {
            if (!request.startsWith("keepalive@")) return super.process(connectionService, request, wantReply, buffer)
            seen += request to wantReply
            return RequestHandler.Result.ReplyFailure
        }
    }

    /**
     * A TCP hop between the car and the relay that can go silent like Wi-Fi switched off: the
     * sockets stay open and every byte after [silence] is dropped, in both directions.
     */
    private class SilenceableLink(private val upstreamPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int = listener.localPort
        private val silent = AtomicBoolean(false)
        private val sockets = ConcurrentLinkedQueue<Socket>()

        init {
            Thread({
                while (!listener.isClosed) {
                    val down = runCatching { listener.accept() }.getOrNull() ?: break
                    val up = Socket("127.0.0.1", upstreamPort)
                    sockets += down
                    sockets += up
                    pump(down, up)
                    pump(up, down)
                }
            }, "silenceable-link").apply { isDaemon = true; start() }
        }

        fun silence() = silent.set(true)

        private fun pump(from: Socket, to: Socket) {
            Thread({
                val buffer = ByteArray(16 * 1024)
                runCatching {
                    val input = from.getInputStream()
                    val output = to.getOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (silent.get()) continue
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
                runCatching { to.close() }
            }, "silenceable-link-pump").apply { isDaemon = true; start() }
        }

        override fun close() {
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

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

    companion object {
        private val OPENSSH_10_KEX = listOf(
            BuiltinDHFactories.curve25519,
            BuiltinDHFactories.curve25519_libssh,
            BuiltinDHFactories.ecdhp256,
            BuiltinDHFactories.ecdhp384,
            BuiltinDHFactories.ecdhp521,
            BuiltinDHFactories.dhgex256,
            BuiltinDHFactories.dhg16_512,
            BuiltinDHFactories.dhg18_512,
            BuiltinDHFactories.dhg14_256,
        )
        private val OPENSSH_10_CIPHERS = listOf(
            BuiltinCiphers.cc20p1305_openssh,
            BuiltinCiphers.aes128gcm,
            BuiltinCiphers.aes256gcm,
            BuiltinCiphers.aes128ctr,
            BuiltinCiphers.aes192ctr,
            BuiltinCiphers.aes256ctr,
        )
        private val OPENSSH_10_MACS = listOf(
            BuiltinMacs.hmacsha256etm,
            BuiltinMacs.hmacsha512etm,
            BuiltinMacs.hmacsha1etm,
            BuiltinMacs.hmacsha256,
            BuiltinMacs.hmacsha512,
            BuiltinMacs.hmacsha1,
        )
    }
}
