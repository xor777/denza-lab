package ru.adbgw.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory
import org.apache.sshd.common.forward.DefaultForwarderFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.DirectTcpipFactory
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.forward.TcpipServerChannel
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InnerGatewayServer(
    private val endpointProvider: () -> AdbEndpoint?,
    private val keyStore: SshKeyStore,
    private val stateStore: GatewayStateStore,
    private val registrationProvider: () -> RelayRegistration?,
    private val relayClient: RelayClient,
    private val onClientChanged: (ClientState, String?, Long) -> Unit,
    private val onPairingCompleted: () -> Unit,
    private val onPermanentFailure: (String) -> Unit,
    private val onSupportEvent: (String) -> Unit,
) : AutoCloseable {
    private val candidateKey = AttributeRepository.AttributeKey<PublicKey>()
    private val clientFingerprint = AttributeRepository.AttributeKey<String>()
    private val clientLabel = AttributeRepository.AttributeKey<String>()
    private val authenticatedSessions = ConcurrentHashMap<Session, String>()
    private val openAdbChannels = AtomicInteger(0)
    private var server: SshServer? = null

    @Synchronized
    fun start() {
        if (server?.isOpen == true) return
        val ssh = SshServer().apply {
            host = INNER_SSH_HOST
            port = INNER_SSH_PORT
            keyPairProvider = KeyPairProvider.wrap(keyStore.innerHostKeyPair)
            fileSystemFactory = NoneFileSystemFactory.INSTANCE
            forwarderFactory = DefaultForwarderFactory.INSTANCE
            userAuthFactories = listOf(
                UserAuthPublicKeyFactory.INSTANCE,
                UserAuthPasswordFactory.INSTANCE,
            )
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { username, key, session ->
                authenticatePublicKey(username, key, session)
            }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, session ->
                authenticatePairingCode(username, password, session)
            }
            keyboardInteractiveAuthenticator = null
            channelFactories = listOf(
                DirectTcpipFactory.INSTANCE,
                org.apache.sshd.server.channel.ChannelSessionFactory.INSTANCE,
            )
            forwardingFilter = forwardingFilter()
            commandFactory = CommandFactory { _, command ->
                if (command != "pair-complete") throw IOException("Command is not allowed")
                PairCompleteCommand()
            }
            shellFactory = null
            subsystemFactories = emptyList()
            globalRequestHandlers = emptyList()
            addSessionListener(sessionListener())
            addChannelListener(channelListener())
        }
        ssh.start()
        server = ssh
        onSupportEvent("Внутренний шлюз слушает только 127.0.0.1:$INNER_SSH_PORT")
    }

    val isRunning: Boolean get() = server?.isOpen == true

    private fun authenticatePublicKey(
        username: String,
        key: PublicKey,
        session: org.apache.sshd.server.session.ServerSession,
    ): Boolean {
        if (username != INNER_SSH_USER) return false
        val canonical = PublicKeyEntry.toString(key)
        val fingerprint = KeyUtils.getFingerPrint(key)
        session.setAttribute(clientFingerprint, fingerprint)
        val trusted = stateStore.trustedClientKey()
        if (trusted == canonical) {
            session.setAttribute(clientLabel, stateStore.trustedClientLabel() ?: "Компьютер")
            return true
        }
        val pairing = activePairingWindow() ?: return false
        if (pairing.attemptsRemaining <= 0) return false
        session.setAttribute(candidateKey, key)
        return false
    }

    private fun authenticatePairingCode(
        username: String,
        password: String,
        session: org.apache.sshd.server.session.ServerSession,
    ): Boolean {
        if (username != INNER_SSH_USER) return false
        val pairing = activePairingWindow() ?: return false
        if (password != pairing.code) {
            val remaining = (pairing.attemptsRemaining - 1).coerceAtLeast(0)
            stateStore.savePairingWindow(pairing.copy(attemptsRemaining = remaining))
            if (remaining == 0) onSupportEvent("Лимит попыток кода исчерпан")
            return false
        }
        val key = session.getAttribute(candidateKey) ?: return false
        val registration = registrationProvider() ?: return false
        val fingerprint = KeyUtils.getFingerPrint(key)
        return try {
            val commit = runBlocking(Dispatchers.IO) {
                relayClient.commitPairing(registration, fingerprint)
            }
            val canonical = PublicKeyEntry.toString(key)
            if (!stateStore.saveTrustedClient(canonical, commit.clientLabel)) {
                onPermanentFailure("Не удалось сохранить ключ доверенного компьютера")
                return false
            }
            stateStore.savePairingWindow(null)
            onPairingCompleted()
            session.setAttribute(clientFingerprint, fingerprint)
            session.setAttribute(clientLabel, commit.clientLabel)
            closePreviousSessions(session)
            onSupportEvent("Компьютер ${commit.clientLabel} подтверждён; прежний доступ отозван")
            true
        } catch (error: RelayAccessException) {
            if (error.permanent) onPermanentFailure(error.message ?: "Relay отклонил ключ автомобиля")
            onSupportEvent("Не удалось завершить подключение компьютера: ${error.message}")
            false
        }
    }

    private fun activePairingWindow(): PairingWindow? {
        val pairing = stateStore.pairingWindow() ?: return null
        if (!pairing.isActive(Instant.now().epochSecond)) {
            stateStore.savePairingWindow(null)
            return null
        }
        return pairing
    }

    private fun closePreviousSessions(current: Session) {
        authenticatedSessions.keys
            .filter { it !== current }
            .forEach { old -> runCatching { old.close(true) } }
    }

    private fun sessionListener() = object : SessionListener {
        override fun sessionEvent(session: Session, event: SessionListener.Event) {
            if (event != SessionListener.Event.Authenticated) return
            val label = session.getAttribute(clientLabel) ?: "Компьютер"
            closePreviousSessions(session)
            authenticatedSessions[session] = label
            onClientChanged(ClientState.Connected, label, System.currentTimeMillis())
        }

        override fun sessionClosed(session: Session) {
            authenticatedSessions.remove(session)
            if (authenticatedSessions.isEmpty()) {
                openAdbChannels.set(0)
                onClientChanged(ClientState.Waiting, null, System.currentTimeMillis())
            }
        }

        override fun sessionException(session: Session, t: Throwable) {
            onSupportEvent("Ошибка сессии компьютера: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun channelListener() = object : ChannelListener {
        override fun channelOpenSuccess(channel: Channel) {
            if (channel !is TcpipServerChannel) return
            openAdbChannels.incrementAndGet()
            val label = authenticatedSessions[channel.session]
            onClientChanged(ClientState.Active, label, System.currentTimeMillis())
        }

        override fun channelClosed(channel: Channel, reason: Throwable?) {
            if (channel !is TcpipServerChannel) return
            val remaining = openAdbChannels.decrementAndGet().coerceAtLeast(0)
            if (remaining == 0 && authenticatedSessions.isNotEmpty()) {
                val label = authenticatedSessions.values.firstOrNull()
                onClientChanged(ClientState.Connected, label, System.currentTimeMillis())
            }
        }
    }

    private fun forwardingFilter(): ForwardingFilter = object : ForwardingFilter {
        override fun canForwardAgent(session: Session, requestType: String): Boolean = false

        override fun canForwardX11(session: Session, requestType: String): Boolean = false

        override fun canListen(address: SshdSocketAddress, session: Session): Boolean = false

        override fun canConnect(
            type: TcpForwardingFilter.Type,
            address: SshdSocketAddress,
            session: Session,
        ): Boolean {
            if (type != TcpForwardingFilter.Type.Direct) return false
            val endpoint = endpointProvider() ?: return false
            val allowed = address.hostName == endpoint.host && address.port == endpoint.port
            if (!allowed) {
                onSupportEvent("Отклонён канал к ${address.hostName}:${address.port}")
            }
            return allowed
        }
    }

    @Synchronized
    override fun close() {
        server?.stop(true)
        server = null
        authenticatedSessions.clear()
        openAdbChannels.set(0)
    }
}

private class PairCompleteCommand : Command {
    private var output: OutputStream? = null
    private var exitCallback: ExitCallback? = null

    override fun setInputStream(input: InputStream?) = Unit

    override fun setOutputStream(output: OutputStream?) {
        this.output = output
    }

    override fun setErrorStream(error: OutputStream?) = Unit

    override fun setExitCallback(callback: ExitCallback?) {
        exitCallback = callback
    }

    override fun start(channel: ChannelSession?, env: Environment?) {
        output?.write("OK paired\n".toByteArray())
        output?.flush()
        exitCallback?.onExit(0)
    }

    override fun destroy(channel: ChannelSession?) = Unit
}
