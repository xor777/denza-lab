package ru.adbgw.gateway

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import org.json.JSONObject
import java.security.KeyPair
import java.security.GeneralSecurityException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class RelayAccessException(
    message: String,
    cause: Throwable? = null,
    val permanent: Boolean = false,
    val registrationRejected: Boolean = false,
) : Exception(message, cause)

class RelayClient(
    private val keyStore: RelayKeyStore,
    private val relayHost: String = RELAY_HOST,
    private val relaySshPort: Int = RELAY_SSH_PORT,
    private val relayFingerprints: Set<String> = RELAY_HOST_FINGERPRINTS,
    // Test-only hook: records the address MINA asks the tunnel client to accept a forwarded
    // channel to. Null in production.
    private val tunnelForwardObserver: ((org.apache.sshd.server.forward.TcpForwardingFilter.Type, org.apache.sshd.common.util.net.SshdSocketAddress) -> Unit)? = null,
    // A test shortens these so a stalled server is noticed in about a second.
    private val connectTimeout: Duration = Duration.ofSeconds(12),
    private val authTimeout: Duration = Duration.ofSeconds(12),
    // Called after the relay accepted a registered vehicle key (tunnel or control), never for the
    // invite code. Clears the evidence of earlier rejections.
    private val onRegisteredKeyAccepted: () -> Unit = {},
) {
    suspend fun enroll(code: String, label: String, endpoint: AdbEndpoint): RelayRegistration =
        withContext(Dispatchers.IO) {
            val command = RelayProtocol.enrollmentCommand(
                code = code,
                label = label,
                tunnelPublicKey = keyStore.tunnelPublicKey,
                controlPublicKey = keyStore.controlPublicKey,
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

    suspend fun openPairing(registration: RelayRegistration, requestId: String): PairingWindow =
        withContext(Dispatchers.IO) {
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

    suspend fun setEnabled(registration: RelayRegistration, enabled: Boolean): RelayRegistration =
        withContext(Dispatchers.IO) {
            RelayProtocol.parseRegistration(executeControl(registration, "set-enabled $enabled"))
        }

    suspend fun renewLease(registration: RelayRegistration): RelayRegistration =
        withContext(Dispatchers.IO) {
            val json = RelayProtocol.parseOk(executeControl(registration, "renew-lease"))
            registration.copy(leaseExpiresAtEpochSeconds = json.getLong("lease_expires_at"))
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
            throw classify(error, mismatch.get())
        }
    }

    private fun executeControl(registration: RelayRegistration, command: String): String {
        require(registration.deviceId.matches(Regex("[a-f0-9]{16}")))
        return execute(
            username = "cag-control",
            keyPair = keyStore.controlKeyPair,
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
            throw classify(error, mismatch.get())
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
            client.connect(username, relayHost, relaySshPort).verify(connectTimeout).session
        } catch (error: Throwable) {
            throw classify(error, mismatch.get())
        }
        keyPair?.let(session::addPublicKeyIdentity)
        password?.let(session::addPasswordIdentity)
        try {
            session.auth().verify(authTimeout)
        } catch (error: Throwable) {
            runCatching { session.close(true) }
            if (mismatch.get()) {
                throw RelayAccessException(
                    "Relay identity changed; connection stopped for safety",
                    error,
                    permanent = true,
                )
            }
            if (!authenticationFailurePermanent) {
                // The invite path is unchanged: any failure reads as a refused invite and is retried
                // by the person, never by wiping anything.
                throw RelayAccessException("Relay rejected the invite code", error)
            }
            if (isDefinitiveAuthRejection(error) && error.causeSequence().none { it is GeneralSecurityException }) {
                // The server answered and refused every key we offered. Even this is only evidence:
                // the registration is dropped after RegistrationRejectionGate confirms it.
                throw RelayAccessException(
                    "Relay rejected the registered vehicle key",
                    error,
                    permanent = true,
                    registrationRejected = true,
                )
            }
            // A timeout, a reset during key exchange, a session closed before the answer: the
            // network, not the relay's verdict. Retried with backoff.
            throw classify(error, hostMismatch = false)
        }
        if (authenticationFailurePermanent) onRegisteredKeyAccepted()
        return session
    }

    private fun configuredClient(mismatch: AtomicBoolean): SshClient =
        SshClient.setUpDefaultClient().apply {
            signatureFactories = preferPinnedHostKeyAlgorithm(signatureFactories)
            serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, serverKey ->
                val fingerprint = KeyUtils.getFingerPrint(serverKey)
                val accepted = fingerprint in relayFingerprints
                if (!accepted) {
                    mismatch.set(true)
                    Log.e(
                        "CarAdbGateway",
                        "Rejected relay host key algorithm=${serverKey.algorithm} fingerprint=$fingerprint",
                    )
                }
                accepted
            }
            // setUpDefaultClient keeps RejectAllForwardingFilter, which refuses the forwarded-tcpip
            // channel the relay opens back for every -R connection. Accept only a Forwarded channel
            // to our own inner server, and nothing else.
            forwardingFilter = tunnelForwardingFilter()
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(this, Duration.ofSeconds(30))
            CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(this, Duration.ofSeconds(30))
            CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(this, 3)
        }

    private fun tunnelForwardingFilter(): org.apache.sshd.server.forward.ForwardingFilter =
        object : org.apache.sshd.server.forward.ForwardingFilter {
            override fun canForwardAgent(session: org.apache.sshd.common.session.Session, requestType: String) = false

            override fun canForwardX11(session: org.apache.sshd.common.session.Session, requestType: String) = false

            override fun canListen(
                address: org.apache.sshd.common.util.net.SshdSocketAddress,
                session: org.apache.sshd.common.session.Session,
            ): Boolean = address.hostName == INNER_SSH_HOST && address.port == INNER_SSH_PORT

            override fun canConnect(
                type: org.apache.sshd.server.forward.TcpForwardingFilter.Type,
                address: org.apache.sshd.common.util.net.SshdSocketAddress,
                session: org.apache.sshd.common.session.Session,
            ): Boolean {
                tunnelForwardObserver?.invoke(type, address)
                return tunnelForwardedAllowed(type, address.hostName, address.port)
            }
        }

    private fun classify(
        error: Throwable,
        hostMismatch: Boolean,
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
        else -> RelayAccessException("Relay connection failed", error, permanent = false)
        }
}

/**
 * The tunnel client accepts exactly one forwarded channel: a `Forwarded`-type `direct-tcpip` back
 * to the inner server (`127.0.0.1:INNER_SSH_PORT`). Everything else — any other port, any direct
 * channel the relay should never ask for — is refused.
 */
internal fun tunnelForwardedAllowed(
    type: org.apache.sshd.server.forward.TcpForwardingFilter.Type,
    host: String?,
    port: Int,
): Boolean = type == org.apache.sshd.server.forward.TcpForwardingFilter.Type.Forwarded &&
    host == INNER_SSH_HOST &&
    port == INNER_SSH_PORT

internal fun preferPinnedHostKeyAlgorithm(
    factories: List<NamedFactory<Signature>>,
): List<NamedFactory<Signature>> {
    val pinned = factories.filter { it.name == "ssh-ed25519" }
    require(pinned.isNotEmpty()) { "Pinned relay host-key algorithm is unavailable" }
    return pinned + factories.filterNot { it.name == "ssh-ed25519" }
}

/**
 * True only when the relay definitively refused authentication: MINA ran out of methods after the
 * server answered every offered key with a failure (`SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE`).
 * Timeouts, IO errors and a session closed during key exchange or auth are not a rejection.
 */
internal fun isDefinitiveAuthRejection(error: Throwable): Boolean = error.causeSequence().any {
    (it is org.apache.sshd.common.SshException &&
        it.disconnectCode == org.apache.sshd.common.SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE) ||
        it.message?.contains(NO_MORE_AUTH_METHODS_MESSAGE) == true
}

private const val NO_MORE_AUTH_METHODS_MESSAGE = "No more authentication methods available"

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
