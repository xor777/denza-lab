package dev.denza.gateway

import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory
import org.apache.sshd.common.forward.DefaultForwarderFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.random.JceRandomFactory
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator
import org.apache.sshd.server.forward.DirectTcpipFactory
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.kex.DHGServer
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.common.config.keys.KeyUtils
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class SshGatewayServer(
    private val bindAddress: Inet4Address,
    private val port: Int,
    private val allowedSubnet: Ipv4Subnet,
    private val endpoint: AdbEndpoint,
    private val hostKeyFile: File,
    private val codeProvider: () -> String,
    private val onLog: (LogLevel, String) -> Unit,
    private val onClientCountChanged: (Int) -> Unit,
    private val onBlockedPeer: (String) -> Unit,
) {
    private var sshServer: SshServer? = null
    private val authenticatedSessions = ConcurrentHashMap.newKeySet<Session>()

    fun start(): String {
        val keyProvider = createKeyProvider()
        val fingerprint = keyProvider.loadKeys(null).firstOrNull()?.public?.let {
            KeyUtils.getFingerPrint(BuiltinDigests.sha256, it)
        }.orEmpty()

        val server = SshServer().apply {
            host = bindAddress.hostAddress
            this.port = this@SshGatewayServer.port
            keyPairProvider = keyProvider
            keyExchangeFactories = listOf(
                DHGServer.newFactory(BuiltinDHFactories.dhg14_256),
                DHGServer.newFactory(BuiltinDHFactories.dhg14),
            )
            cipherFactories = listOf(
                BuiltinCiphers.aes128ctr,
                BuiltinCiphers.aes256ctr,
            )
            macFactories = listOf(
                BuiltinMacs.hmacsha256,
                BuiltinMacs.hmacsha1,
            )
            compressionFactories = listOf(BuiltinCompressions.none)
            signatureFactories = listOf(
                BuiltinSignatures.rsaSHA256,
                BuiltinSignatures.rsaSHA512,
                BuiltinSignatures.rsa,
            )
            randomFactory = JceRandomFactory.INSTANCE
            fileSystemFactory = NoneFileSystemFactory.INSTANCE
            forwarderFactory = DefaultForwarderFactory.INSTANCE
            publickeyAuthenticator = RejectAllPublickeyAuthenticator.INSTANCE
            keyboardInteractiveAuthenticator = null
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            channelFactories = listOf(DirectTcpipFactory.INSTANCE)
            globalRequestHandlers = emptyList()
            forwardingFilter = buildForwardingFilter()
            passwordAuthenticator = buildPasswordAuthenticator()
            shellFactory = null
            commandFactory = null
            subsystemFactories = emptyList()
            addSessionListener(buildSessionListener())
        }

        server.start()
        sshServer = server
        onLog(LogLevel.Info, "SSH gateway listening on ${bindAddress.hostAddress}:$port")
        return fingerprint
    }

    fun stop() {
        sshServer?.stop(true)
        sshServer = null
        onLog(LogLevel.Info, "SSH gateway stopped")
    }

    private fun createKeyProvider(): SimpleGeneratorHostKeyProvider =
        SimpleGeneratorHostKeyProvider(hostKeyFile.toPath()).apply {
            algorithm = "RSA"
            keySize = 2048
            setStrictFilePermissions(false)
        }

    private fun buildPasswordAuthenticator() =
        org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, session ->
            val peer = session.clientInetAddress()
            val subnetOk = allowedSubnet.contains(peer)
            val accepted = username == SSH_USER && password == codeProvider() && subnetOk
            when {
                !subnetOk -> blockPeer(peer, "auth denied from outside ${allowedSubnet}")
                accepted -> {
                    markAuthenticated(session)
                }
                else -> onLog(LogLevel.Warn, "SSH auth rejected for user '$username' from ${peer?.hostAddress ?: "unknown peer"}")
            }
            accepted
        }

    private fun buildSessionListener() =
        object : SessionListener {
            override fun sessionClosed(session: Session) {
                if (authenticatedSessions.remove(session)) {
                    val count = authenticatedSessions.size
                    onClientCountChanged(count)
                    onLog(LogLevel.Info, "SSH client disconnected: ${session.clientLabel()} ($count active)")
                }
            }

            override fun sessionException(session: Session, t: Throwable) {
                onLog(LogLevel.Warn, "SSH session error from ${session.clientLabel()}: ${t.gatewayMessage()}")
            }
        }

    private fun markAuthenticated(session: Session) {
        if (authenticatedSessions.add(session)) {
            val count = authenticatedSessions.size
            onClientCountChanged(count)
            onLog(LogLevel.Info, "SSH client connected: ${session.clientLabel()} ($count active)")
        } else {
            onLog(LogLevel.Info, "SSH auth accepted from ${session.clientLabel()}")
        }
    }

    private fun buildForwardingFilter(): ForwardingFilter =
        object : ForwardingFilter {
            override fun canForwardAgent(session: Session, requestType: String): Boolean = false

            override fun canForwardX11(session: Session, requestType: String): Boolean = false

            override fun canListen(address: SshdSocketAddress, session: Session): Boolean {
                onLog(LogLevel.Warn, "Remote forwarding denied: ${address.hostName}:${address.port}")
                return false
            }

            override fun canConnect(
                type: TcpForwardingFilter.Type,
                address: SshdSocketAddress,
                session: Session,
            ): Boolean {
                val peer = session.clientInetAddress()
                onLog(LogLevel.Info, "Client requested forward: ${peer?.hostAddress ?: "unknown peer"} -> ${address.hostName}:${address.port}")
                if (!allowedSubnet.contains(peer)) {
                    blockPeer(peer, "forward denied from outside ${allowedSubnet}")
                    return false
                }
                if (type != TcpForwardingFilter.Type.Direct) {
                    onLog(LogLevel.Warn, "Forwarding denied: unsupported channel ${type.name}")
                    return false
                }

                val destinationOk = ForwardingPolicy.isAllowedDestination(endpoint, address.hostName, address.port)
                if (!destinationOk) {
                    onLog(
                        LogLevel.Warn,
                        "Forwarding denied to ${address.hostName}:${address.port}; only ${endpoint.host}:${endpoint.port} is allowed",
                    )
                    return false
                }

                onLog(LogLevel.Info, "Forward allowed: ${peer?.hostAddress ?: "unknown peer"} -> ${endpoint.host}:${endpoint.port}")
                return true
            }
        }

    private fun blockPeer(peer: InetAddress?, reason: String) {
        val peerLabel = peer?.hostAddress ?: "unknown peer"
        onBlockedPeer("$reason ($peerLabel)")
        onLog(LogLevel.Warn, "$reason ($peerLabel)")
    }

    private fun Session.clientInetAddress(): InetAddress? {
        val address = (this as? ServerSession)?.clientAddress as? InetSocketAddress
        return address?.address
    }

    private fun Session.clientLabel(): String = clientInetAddress()?.hostAddress ?: "unknown peer"
}
