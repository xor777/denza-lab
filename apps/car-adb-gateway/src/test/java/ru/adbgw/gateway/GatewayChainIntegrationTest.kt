package ru.adbgw.gateway

import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher

/**
 * Drives the real Android chain end to end in a JVM with no device: the real [RelayClient] tunnel,
 * the real [InnerGatewayServer] and its [AdbProxyChannel], a MINA stand-in for the relay, a fake
 * adbd and a MINA "computer".
 *
 * <p>The happy path proves a remote computer reaches ADB with no second prompt: the device opens
 * the relay tunnel, the relay forwards the computer back to the inner server, the computer opens
 * `direct-tcpip` to a stale `5555`, and the car signs adbd's token with the app key so the computer
 * sees an already-authorized adbd and an echo. The others cover pairing, a refused port, and a
 * refused signature.
 */
class GatewayChainIntegrationTest {
    private val closeables = ArrayDeque<AutoCloseable>()

    @After
    fun tearDown() {
        while (closeables.isNotEmpty()) {
            runCatching { closeables.removeLast().close() }
        }
    }

    @Test
    fun `computer reaches adb through the relay tunnel using the app key`() {
        val appKey = rsa()
        val computerKey = rsa()
        val adbd = EchoAdbd(appKey.public).also { start(it) }

        val store = FakeStore().apply { trustedKey = PublicKeyEntry.toString(computerKey.public); trustedLabel = "Mac" }
        val inner = startInner(store, AdbEndpoint(AdbEndpointKind.RawAdbd, "127.0.0.1", adbd.port, "test adbd"), appKey)

        val deviceKey = rsa()
        val relay = startRelay(deviceKey.public)
        val devicePort = freePort()

        val forwarded = AtomicReference<SshdSocketAddress?>(null)
        val relayClient = RelayClient(
            keyStore = SingleKeyStore(deviceKey),
            relayHost = "127.0.0.1",
            relaySshPort = relay.port,
            relayFingerprints = setOf(relay.fingerprint),
            tunnelForwardObserver = { _, address -> forwarded.set(address) },
        )
        val registration = registration(devicePort)
        val tunnel = runBlocking { relayClient.openTunnel(registration) {} }
        closeables.addLast(tunnel)

        val computer = SshClient.setUpDefaultClient().also {
            it.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            it.start(); closeables.addLast(AutoCloseable { it.stop() })
        }
        val session = computer.connect(INNER_SSH_USER, "127.0.0.1", devicePort)
            .verify(Duration.ofSeconds(10)).session
        session.addPublicKeyIdentity(computerKey)
        session.auth().verify(Duration.ofSeconds(10))

        val channel = session.createDirectTcpipChannel(
            SshdSocketAddress("127.0.0.1", 0),
            SshdSocketAddress("stale.invalid", RAW_ADBD_PORT),
        )
        channel.streaming = StreamingChannel.Streaming.Sync
        channel.open().verify(Duration.ofSeconds(10))
        val toCar = channel.invertedIn
        val fromCar = channel.invertedOut

        // The computer connects with no ADB key of its own; the car answers adbd's auth.
        val clientBanner = "host::features=cmd\u0000".toByteArray(StandardCharsets.UTF_8)
        AdbWire.write(toCar, AdbWire.A_CNXN, ADB_VERSION, MAX_PAYLOAD, clientBanner)
        val connectBack = AdbWire.read(fromCar)
        assertEquals("the computer receives adbd's CNXN", AdbWire.A_CNXN, connectBack.command)
        assertArrayEquals("verbatim adbd banner", adbd.banner, connectBack.payload)

        // The post-handshake pipe carries a WRTE both ways.
        val payload = "shell:echo denza\u0000".toByteArray(StandardCharsets.UTF_8)
        AdbWire.write(toCar, AdbWire.A_WRTE, 7, 9, payload)
        val echoed = AdbWire.read(fromCar)
        assertEquals(AdbWire.A_WRTE, echoed.command)
        assertArrayEquals("the payload survives the round trip through adbd", payload, echoed.payload)

        assertArrayEquals("the client's banner reached adbd unchanged", clientBanner, adbd.clientBanner())
        assertTrue("adbd's token was answered with a verifying signature", adbd.signatureVerified())
        assertFalse("the app public key is never sent", adbd.sawPublicKey())

        assertNotNull("MINA asked the tunnel client to accept a forwarded channel", forwarded.get())
        assertEquals("the forwarded channel targets the inner server", INNER_SSH_HOST, forwarded.get()!!.hostName)
        assertEquals(INNER_SSH_PORT, forwarded.get()!!.port)

        runCatching { channel.close(false) }
        runCatching { session.close(false) }
        inner.close()
    }

    @Test
    fun `a stock OpenSSH client reaches adb with ssh -W through the tunnel`() {
        val ssh = openSshTool("ssh")
        val keygen = openSshTool("ssh-keygen")
        assumeTrue("no OpenSSH client on PATH", ssh != null && keygen != null)
        val dir = Files.createTempDirectory("cag-openssh")
        val keyFile = dir.resolve("id_ed25519")
        val generated = ProcessBuilder(keygen!!, "-q", "-t", "ed25519", "-N", "", "-C", "", "-f", keyFile.toString())
            .redirectErrorStream(true).start()
        assertTrue(generated.waitFor(20, TimeUnit.SECONDS) && generated.exitValue() == 0)
        val computerKey = PublicKeyEntry.parsePublicKeyEntry(Files.readString(dir.resolve("id_ed25519.pub")).trim())
            .resolvePublicKey(null, emptyMap(), null)

        val appKey = rsa()
        val adbd = EchoAdbd(appKey.public).also { start(it) }
        val store = FakeStore().apply { trustedKey = PublicKeyEntry.toString(computerKey); trustedLabel = "Mac" }
        startInner(store, AdbEndpoint(AdbEndpointKind.RawAdbd, "127.0.0.1", adbd.port, "test adbd"), appKey)
        val deviceKey = rsa()
        val relay = startRelay(deviceKey.public)
        val devicePort = freePort()
        val relayClient = RelayClient(
            keyStore = SingleKeyStore(deviceKey),
            relayHost = "127.0.0.1",
            relaySshPort = relay.port,
            relayFingerprints = setOf(relay.fingerprint),
        )
        val tunnel = runBlocking { relayClient.openTunnel(registration(devicePort)) {} }
        closeables.addLast(tunnel)

        // -F /dev/null: the client's built-in defaults, no user or system configuration.
        val log = dir.resolve("ssh.log")
        val process = ProcessBuilder(
            ssh!!, "-F", "/dev/null", "-v",
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none",
            "-o", "ConnectTimeout=10",
            "-i", keyFile.toString(),
            "-p", devicePort.toString(),
            "-W", "127.0.0.1:$RAW_ADBD_PORT",
            "$INNER_SSH_USER@127.0.0.1",
        ).redirectError(log.toFile()).start()
        closeables.addLast(AutoCloseable { process.destroyForcibly() })
        Thread {
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        }.apply { isDaemon = true; start() }
        val toCar = process.outputStream
        val fromCar = process.inputStream

        val clientBanner = "host::features=cmd\u0000".toByteArray(StandardCharsets.UTF_8)
        AdbWire.write(toCar, AdbWire.A_CNXN, ADB_VERSION, MAX_PAYLOAD, clientBanner)
        val connectBack = try {
            AdbWire.read(fromCar)
        } catch (error: EOFException) {
            throw AssertionError("ssh -W closed early:\n${Files.readString(log)}", error)
        }
        assertEquals(AdbWire.A_CNXN, connectBack.command)
        assertArrayEquals(adbd.banner, connectBack.payload)
        val payload = "shell:echo denza\u0000".toByteArray(StandardCharsets.UTF_8)
        AdbWire.write(toCar, AdbWire.A_WRTE, 7, 9, payload)
        assertArrayEquals(payload, AdbWire.read(fromCar).payload)
        // The ADB pipe stays open on EOF; the handshake lines are long written by now.
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)

        val debug = Files.readString(log)
        val kex = Regex("kex: algorithm: (\\S+)").find(debug)?.groupValues?.get(1)
        val cipher = Regex("kex: server->client cipher: (\\S+) MAC: (\\S+)").find(debug)?.groupValues
        val version = debug.lineSequence().firstOrNull { it.contains("OpenSSH_") }.orEmpty()
        println("[stock OpenSSH ssh -W -> inner server] kex=$kex cipher=${cipher?.get(1)} mac=${cipher?.get(2)} ($version)")
        assertTrue("negotiated $kex", kex in SshAlgorithms.keyExchanges.map { it.getName() })
        assertTrue("negotiated ${cipher?.get(1)}", cipher?.get(1) in SshAlgorithms.ciphers.map { it.getName() })
    }

    @Test
    fun `an unknown key with the code pairs and becomes trusted`() {
        val appKey = rsa()
        val computerKey = rsa()
        val store = FakeStore().apply {
            pairing = PairingWindow("req-1", "WXYZ-2345", Long.MAX_VALUE, attemptsRemaining = 5)
        }
        val committed = AtomicBoolean(false)
        val inner = startInner(
            store,
            endpoint = AdbEndpoint(AdbEndpointKind.RawAdbd, "127.0.0.1", 1, "test adbd"),
            committer = PairingCommitter { committed.set(true); PairCommitResult("Mac", null) },
        )

        val computer = SshClient.setUpDefaultClient().also {
            it.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            it.start(); closeables.addLast(AutoCloseable { it.stop() })
        }
        val session = computer.connect(INNER_SSH_USER, "127.0.0.1", INNER_SSH_PORT)
            .verify(Duration.ofSeconds(10)).session
        session.addPublicKeyIdentity(computerKey) // unknown: pubkey auth fails, stashes the candidate
        session.addPasswordIdentity("WXYZ-2345")
        session.auth().verify(Duration.ofSeconds(10))

        assertTrue("the commit ran through the stubbed relay", committed.get())
        assertEquals(
            "the computer's key is now trusted",
            PublicKeyEntry.toString(computerKey.public),
            store.trustedClientKey(),
        )
        runCatching { session.close(false) }
        inner.close()
    }

    @Test
    fun `a direct-tcpip to any other port is refused`() {
        val computerKey = rsa()
        val store = FakeStore().apply { trustedKey = PublicKeyEntry.toString(computerKey.public); trustedLabel = "Mac" }
        val inner = startInner(store, endpoint = AdbEndpoint(AdbEndpointKind.RawAdbd, "127.0.0.1", 1, "test adbd"))

        val computer = SshClient.setUpDefaultClient().also {
            it.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            it.start(); closeables.addLast(AutoCloseable { it.stop() })
        }
        val session = computer.connect(INNER_SSH_USER, "127.0.0.1", INNER_SSH_PORT)
            .verify(Duration.ofSeconds(10)).session
        session.addPublicKeyIdentity(computerKey)
        session.auth().verify(Duration.ofSeconds(10))

        val channel = session.createDirectTcpipChannel(
            SshdSocketAddress("127.0.0.1", 0),
            SshdSocketAddress("stale.invalid", 9999),
        )
        channel.streaming = StreamingChannel.Streaming.Sync
        try {
            channel.open().verify(Duration.ofSeconds(10))
            fail("the channel to port 9999 should have been refused")
        } catch (expected: Exception) {
            // OPEN_FAILURE: a port that is neither 5037 nor 5555 is denied.
        }
        runCatching { channel.close(true) }
        runCatching { session.close(false) }
        inner.close()
    }

    @Test
    fun `a refused signature closes the channel without sending the public key`() {
        val appKey = rsa()
        val computerKey = rsa()
        val adbd = RejectingAdbd().also { start(it) }

        val store = FakeStore().apply { trustedKey = PublicKeyEntry.toString(computerKey.public); trustedLabel = "Mac" }
        val inner = startInner(store, endpoint = AdbEndpoint(AdbEndpointKind.RawAdbd, "127.0.0.1", adbd.port, "test adbd"))

        val computer = SshClient.setUpDefaultClient().also {
            it.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            it.start(); closeables.addLast(AutoCloseable { it.stop() })
        }
        val session = computer.connect(INNER_SSH_USER, "127.0.0.1", INNER_SSH_PORT)
            .verify(Duration.ofSeconds(10)).session
        session.addPublicKeyIdentity(computerKey)
        session.auth().verify(Duration.ofSeconds(10))

        val channel = session.createDirectTcpipChannel(
            SshdSocketAddress("127.0.0.1", 0),
            SshdSocketAddress("stale.invalid", RAW_ADBD_PORT),
        )
        channel.streaming = StreamingChannel.Streaming.Sync
        channel.open().verify(Duration.ofSeconds(10))
        val toCar = channel.invertedIn
        val fromCar = channel.invertedOut

        AdbWire.write(toCar, AdbWire.A_CNXN, ADB_VERSION, MAX_PAYLOAD,
            "host::\u0000".toByteArray(StandardCharsets.UTF_8))

        // The car never relays a CNXN: the channel closes with the stream at EOF.
        try {
            val leftover = AdbWire.read(fromCar)
            fail("expected EOF, not ${AdbWire.name(leftover.command)}")
        } catch (expected: EOFException) {
            // closed without a CNXN
        } catch (expected: java.io.IOException) {
            // the channel went away under the read
        }
        assertFalse("the app public key is never offered on a refusal", adbd.sawPublicKey())

        runCatching { channel.close(true) }
        runCatching { session.close(false) }
        inner.close()
    }

    // --- harness ---------------------------------------------------------------------------------

    private fun startInner(
        store: InnerGatewayStore,
        endpoint: AdbEndpoint,
        appKey: KeyPair = rsa(),
        committer: PairingCommitter = PairingCommitter { PairCommitResult("Mac", null) },
    ): InnerGatewayServer {
        val handshake = AdbHandshakeProxy { clientIn, clientOut, adbdIn, adbdOut ->
            LocalAdbClient.proxyAdbConnect(appKey, clientIn, clientOut, adbdIn, adbdOut)
        }
        val server = InnerGatewayServer(
            endpointProvider = { endpoint },
            innerHostKey = rsa(),
            store = store,
            pairingCommitter = committer,
            adbHandshake = handshake,
            onClientChanged = { _, _, _ -> },
            onPairingCompleted = {},
            onPermanentFailure = {},
            onSupportEvent = {},
        )
        server.start()
        closeables.addLast(server)
        return server
    }

    private fun start(adbd: AutoCloseable) {
        closeables.addLast(adbd)
    }

    private fun startRelay(deviceKey: PublicKey): Relay {
        val hostKey = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair()
        val port = freePort()
        val deviceCanonical = PublicKeyEntry.toString(deviceKey)
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this.port = port
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            publickeyAuthenticator = PublickeyAuthenticator { _, key, _ ->
                PublicKeyEntry.toString(key) == deviceCanonical
            }
        }
        server.start()
        closeables.addLast(AutoCloseable { server.stop(true) })
        return Relay(port, KeyUtils.getFingerPrint(hostKey.public))
    }

    private class Relay(val port: Int, val fingerprint: String)

    private fun registration(devicePort: Int) = RelayRegistration(
        deviceId = "0123456789abcdef",
        deviceLabel = "Автомобиль",
        relayDevicePort = devicePort,
        innerHostKey = "ssh-rsa inner",
        endpointKind = AdbEndpointKind.RawAdbd,
        endpointHost = "127.0.0.1",
        enabled = true,
        leaseExpiresAtEpochSeconds = 4_000_000_000L,
    )

    private class SingleKeyStore(private val key: KeyPair) : RelayKeyStore {
        override val tunnelKeyPair: KeyPair get() = key
        override val controlKeyPair: KeyPair get() = key
        override val tunnelPublicKey: String get() = PublicKeyEntry.toString(key.public)
        override val controlPublicKey: String get() = PublicKeyEntry.toString(key.public)
        override val innerHostPublicKey: String get() = PublicKeyEntry.toString(key.public)
    }

    private class FakeStore : InnerGatewayStore {
        @Volatile var trustedKey: String? = null
        @Volatile var trustedLabel: String? = null
        @Volatile var pairing: PairingWindow? = null
        @Volatile var pending: PendingPairCommit? = null

        override fun trustedClientKey(): String? = trustedKey
        override fun trustedClientLabel(): String? = trustedLabel
        override fun pairingWindow(): PairingWindow? = pairing
        override fun savePairingWindow(value: PairingWindow?): Boolean { pairing = value; return true }
        override fun clearPairing(): Boolean { pairing = null; pending = null; return true }
        override fun pendingPairCommit(): PendingPairCommit? = pending
        override fun savePendingPairCommit(value: PendingPairCommit): Boolean { pending = value; return true }
        override fun finalizePairCommit(publicKey: String, label: String): Boolean {
            trustedKey = publicKey
            trustedLabel = label
            pending = null
            pairing = null
            return true
        }
    }

    /** A fake adbd that trusts the app key: challenge, verify the signature, accept, then echo. */
    private class EchoAdbd(private val appPublicKey: PublicKey) : AutoCloseable {
        val banner = "device::ro.product=denza\u0000".toByteArray(StandardCharsets.UTF_8)
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val clientBannerRef = AtomicReference<ByteArray?>(null)
        private val verified = AtomicBoolean(false)
        private val publicKeySeen = AtomicBoolean(false)

        private val thread = Thread({ serve() }, "fake-adbd-echo").apply { isDaemon = true; start() }

        private fun serve() {
            try {
                val connection = socket.accept()
                connection.tcpNoDelay = true
                val input = connection.getInputStream()
                val output = connection.getOutputStream()
                val connect = AdbWire.read(input)
                clientBannerRef.set(connect.payload)
                val token = ByteArray(20) { it.toByte() }
                AdbWire.write(output, AdbWire.A_AUTH, 1, 0, token)
                val signature = AdbWire.read(input)
                if (signature.command == AdbWire.A_AUTH && signature.arg0 == 3) publicKeySeen.set(true)
                verified.set(signature.command == AdbWire.A_AUTH && signature.arg0 == 2 &&
                    signatureMatches(appPublicKey, token, signature.payload))
                AdbWire.write(output, AdbWire.A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner)
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (ignored: Exception) {
                // the proxy closed the socket; the test asserts on the recorded flags
            }
        }

        fun clientBanner(): ByteArray? = clientBannerRef.get()
        fun signatureVerified(): Boolean = verified.get()
        fun sawPublicKey(): Boolean = publicKeySeen.get()

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** A fake adbd that refuses the signature by challenging again; it must never see a public key. */
    private class RejectingAdbd : AutoCloseable {
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val publicKeySeen = AtomicBoolean(false)
        private val thread = Thread({ serve() }, "fake-adbd-reject").apply { isDaemon = true; start() }

        private fun serve() {
            try {
                val connection = socket.accept()
                val input = connection.getInputStream()
                val output = connection.getOutputStream()
                AdbWire.read(input) // client CNXN
                AdbWire.write(output, AdbWire.A_AUTH, 1, 0, ByteArray(20) { 1 })
                AdbWire.read(input) // the signature
                AdbWire.write(output, AdbWire.A_AUTH, 1, 0, ByteArray(20) { 2 }) // refuse: challenge again
                try {
                    val next = AdbWire.read(input)
                    if (next.command == AdbWire.A_AUTH && next.arg0 == 3) publicKeySeen.set(true)
                } catch (ignored: Exception) {
                    // the proxy closed instead of sending the key
                }
            } catch (ignored: Exception) {
                // socket closed under us
            }
        }

        fun sawPublicKey(): Boolean = publicKeySeen.get()

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private object AdbWire {
        val A_CNXN = cmd("CNXN")
        val A_AUTH = cmd("AUTH")
        val A_OPEN = cmd("OPEN")
        val A_WRTE = cmd("WRTE")

        fun name(command: Int): String {
            val bytes = byteArrayOf(
                (command and 0xff).toByte(),
                ((command shr 8) and 0xff).toByte(),
                ((command shr 16) and 0xff).toByte(),
                ((command shr 24) and 0xff).toByte(),
            )
            return String(bytes, StandardCharsets.US_ASCII)
        }

        fun write(out: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            var checksum = 0
            for (b in payload) checksum += b.toInt() and 0xff
            val message = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            message.putInt(command)
            message.putInt(arg0)
            message.putInt(arg1)
            message.putInt(payload.size)
            message.putInt(checksum)
            message.putInt(command.inv())
            message.put(payload)
            out.write(message.array())
            out.flush()
        }

        fun read(input: InputStream): Msg {
            val header = readExactly(input, 24)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            buffer.int // checksum
            buffer.int // magic
            val payload = readExactly(input, length)
            return Msg(command, arg0, arg1, payload)
        }

        private fun cmd(command: String): Int {
            val bytes = command.toByteArray(StandardCharsets.US_ASCII)
            return (bytes[0].toInt() and 0xff) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or
                ((bytes[3].toInt() and 0xff) shl 24)
        }
    }

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    companion object {
        private const val ADB_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        private fun rsa(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }

        /** An executable on PATH, or null: the test is skipped on a machine without OpenSSH. */
        private fun openSshTool(name: String): String? = System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparator)
            .map { java.io.File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.path

        private fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val buffer = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buffer, offset, count - offset)
                if (read == -1) throw EOFException("expected $count bytes, got $offset")
                offset += read
            }
            return buffer
        }

        private fun signatureMatches(publicKey: PublicKey, token: ByteArray, signature: ByteArray): Boolean {
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, publicKey)
            val block = cipher.doFinal(signature)
            val tail = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token.size)
            System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, tail, 0, SHA1_DIGEST_INFO_PREFIX.size)
            System.arraycopy(token, 0, tail, SHA1_DIGEST_INFO_PREFIX.size, token.size)
            val actual = Arrays.copyOfRange(block, block.size - tail.size, block.size)
            return Arrays.equals(tail, actual) && block[0].toInt() == 0 && block[1].toInt() == 1
        }
    }
}
