package ru.adbgw.gateway

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

/**
 * The key material [RelayClient] needs, behind an interface so a JVM test can supply plain key
 * pairs. Production passes [SshKeyStore], which wraps app-private key files and needs a `Context`.
 */
interface RelayKeyStore {
    val tunnelKeyPair: KeyPair
    val controlKeyPair: KeyPair
    val tunnelPublicKey: String
    val controlPublicKey: String
    val innerHostPublicKey: String
}

/**
 * The subset of [GatewayStateStore] the inner gateway reads and writes, isolated so the inner
 * server can be exercised in a JVM test. [GatewayStateStore] wraps SharedPreferences and cannot be
 * built off a device.
 */
interface InnerGatewayStore {
    fun trustedClientKey(): String?
    fun trustedClientLabel(): String?
    fun pairingWindow(): PairingWindow?
    fun savePairingWindow(value: PairingWindow?): Boolean
    fun clearPairing(): Boolean
    fun pendingPairCommit(): PendingPairCommit?
    fun savePendingPairCommit(value: PendingPairCommit): Boolean
    fun finalizePairCommit(publicKey: String, label: String): Boolean
}

/**
 * Commits a verified pairing with the relay. Production routes it through [RelayClient]; a test
 * stubs it, so the inner server's pairing path can be checked without a relay.
 */
fun interface PairingCommitter {
    @Throws(RelayAccessException::class)
    fun commit(fingerprint: String): PairCommitResult
}

/**
 * Terminates the ADB connect handshake for a tunnelled computer, authenticating with the app's own
 * ADB key and never submitting the public key. Production is backed by
 * `LocalAdbClient.proxyAdbConnect`; a test backs it with the same code and a generated key.
 */
fun interface AdbHandshakeProxy {
    @Throws(Exception::class)
    fun terminate(
        clientIn: InputStream,
        clientOut: OutputStream,
        adbdIn: InputStream,
        adbdOut: OutputStream,
    )
}
