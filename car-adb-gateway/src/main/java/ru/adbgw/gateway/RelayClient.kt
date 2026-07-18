package ru.adbgw.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import org.json.JSONObject
import java.security.KeyPair
import java.security.GeneralSecurityException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RelayAccessException(
    message: String,
    cause: Throwable? = null,
    val permanent: Boolean = false,
) : Exception(message, cause)

class RelayClient(private val keyStore: SshKeyStore) {
    suspend fun enroll(code: String, label: String, endpoint: AdbEndpoint): RelayRegistration =
        withContext(Dispatchers.IO) {
            val command = RelayProtocol.enrollmentCommand(
                code = code,
                label = label,
                tunnelPublicKey = keyStore.tunnelPublicKey,
                innerHostKey = keyStore.innerHostPublicKey,
                endpoint = endpoint,
            )
            val response = execute(
                username = "cag-enroll",
                password = RelayProtocol.normalizeCode(code),
                command = command,
                authenticationFailurePermanent = false,
            )
            RelayProtocol.parseRegistration(response).also {
                if (it.innerHostKey != keyStore.innerHostPublicKey) {
                    throw RelayAccessException("Relay returned a different vehicle host key", permanent = true)
                }
            }
        }

    suspend fun openPairing(registration: RelayRegistration): PairingWindow =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            RelayProtocol.parsePairingWindow(
                executeControl(registration, "pair-open $requestId"),
            )
        }

    suspend fun commitPairing(
        registration: RelayRegistration,
        clientFingerprint: String,
    ): PairCommitResult = withContext(Dispatchers.IO) {
        val json = RelayProtocol.parseOk(
            executeControl(registration, "pair-commit $clientFingerprint"),
        )
        PairCommitResult(
            clientLabel = json.optString("client_label", "Компьютер"),
            replacedFingerprint = json.optString("replaced_fingerprint")
                .takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun abortPairing(registration: RelayRegistration, requestId: String) =
        withContext(Dispatchers.IO) {
            executeControl(registration, "pair-abort $requestId")
        }

    suspend fun updateEndpoint(registration: RelayRegistration, endpoint: AdbEndpoint) =
        withContext(Dispatchers.IO) {
            executeControl(
                registration,
                "set-endpoint ${endpoint.kind.relayValue} ${endpoint.host}",
            )
        }

    suspend fun setEnabled(registration: RelayRegistration, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            executeControl(registration, "set-enabled $enabled")
        }

    suspend fun status(registration: RelayRegistration): JSONObject = withContext(Dispatchers.IO) {
        RelayProtocol.parseOk(executeControl(registration, "status"))
    }

    suspend fun openTunnel(
        registration: RelayRegistration,
        onClosed: (Throwable?) -> Unit,
    ): RelayTunnel = withContext(Dispatchers.IO) {
        val mismatch = AtomicBoolean(false)
        val client = configuredClient(mismatch)
        try {
            client.start()
            val session = connect(client, "cag-device", keyStore.tunnelKeyPair, null, mismatch, true)
            session.startRemotePortForwarding(
                SshdSocketAddress("127.0.0.1", registration.relayDevicePort),
                SshdSocketAddress(INNER_SSH_HOST, INNER_SSH_PORT),
            )
            RelayTunnel(client, session, onClosed)
        } catch (error: RelayAccessException) {
            runCatching { client.stop() }
            throw error
        } catch (error: Throwable) {
            runCatching { client.stop() }
            throw classify(error, mismatch.get(), authenticationFailure = false)
        }
    }

    private fun executeControl(registration: RelayRegistration, command: String): String {
        require(registration.deviceId.matches(Regex("[a-f0-9]{16}")))
        return execute(
            username = "cag-control",
            keyPair = keyStore.tunnelKeyPair,
            command = command,
            authenticationFailurePermanent = true,
        )
    }

    private fun execute(
        username: String,
        password: String? = null,
        keyPair: KeyPair? = null,
        command: String,
        authenticationFailurePermanent: Boolean,
    ): String {
        val mismatch = AtomicBoolean(false)
        val client = configuredClient(mismatch)
        try {
            client.start()
            val session = connect(
                client,
                username,
                keyPair,
                password,
                mismatch,
                authenticationFailurePermanent,
            )
            return try {
                session.executeRemoteCommand(command, Duration.ofSeconds(15))
            } finally {
                session.close(false)
            }
        } catch (error: RelayAccessException) {
            throw error
        } catch (error: Throwable) {
            throw classify(error, mismatch.get(), authenticationFailure = false)
        } finally {
            runCatching { client.stop() }
        }
    }

    private fun connect(
        client: SshClient,
        username: String,
        keyPair: KeyPair?,
        password: String?,
        mismatch: AtomicBoolean,
        authenticationFailurePermanent: Boolean,
    ): ClientSession {
        val session = try {
            client.connect(username, RELAY_HOST, RELAY_SSH_PORT).verify(Duration.ofSeconds(12)).session
        } catch (error: Throwable) {
            throw classify(error, mismatch.get(), authenticationFailure = false)
        }
        keyPair?.let(session::addPublicKeyIdentity)
        password?.let(session::addPasswordIdentity)
        try {
            session.auth().verify(Duration.ofSeconds(12))
        } catch (error: Throwable) {
            runCatching { session.close(true) }
            throw RelayAccessException(
                if (authenticationFailurePermanent) {
                    "Relay rejected the registered vehicle key"
                } else {
                    "Relay rejected the invite code"
                },
                error,
                permanent = authenticationFailurePermanent,
            )
        }
        return session
    }

    private fun configuredClient(mismatch: AtomicBoolean): SshClient =
        SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, serverKey ->
                val accepted = KeyUtils.getFingerPrint(serverKey) == RELAY_HOST_FINGERPRINT
                if (!accepted) mismatch.set(true)
                accepted
            }
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(this, Duration.ofSeconds(30))
            CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(this, Duration.ofSeconds(30))
            CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(this, 3)
        }

    private fun classify(
        error: Throwable,
        hostMismatch: Boolean,
        authenticationFailure: Boolean,
    ): RelayAccessException = when {
        hostMismatch -> RelayAccessException(
            "Relay identity changed; connection stopped for safety",
            error,
            permanent = true,
        )
        error.causeSequence().any { it is GeneralSecurityException } -> RelayAccessException(
            "Vehicle keys are corrupt; connection stopped for safety",
            error,
            permanent = true,
        )
        authenticationFailure -> RelayAccessException(
            "Relay rejected the registered vehicle key",
            error,
            permanent = true,
        )
        else -> RelayAccessException("Relay connection failed", error, permanent = false)
    }
}

private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

class RelayTunnel internal constructor(
    private val client: SshClient,
    private val session: ClientSession,
    onClosed: (Throwable?) -> Unit,
) : AutoCloseable {
    private val closing = AtomicBoolean(false)

    init {
        session.addSessionListener(object : org.apache.sshd.common.session.SessionListener {
            override fun sessionException(
                session: org.apache.sshd.common.session.Session,
                t: Throwable,
            ) {
                if (!closing.get()) onClosed(t)
            }

            override fun sessionClosed(session: org.apache.sshd.common.session.Session) {
                if (!closing.get()) onClosed(null)
            }
        })
    }

    val isOpen: Boolean get() = session.isOpen && !closing.get()

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching { session.close(true) }
        runCatching { client.stop() }
    }
}
