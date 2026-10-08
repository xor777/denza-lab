package dev.denza.apps.feature.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AdbRestoreState {
    data object Disabled : AdbRestoreState
    data object Unsupported : AdbRestoreState
    data object NotNeeded : AdbRestoreState
    data object NeedsActivation : AdbRestoreState
    data object WaitingWifi : AdbRestoreState
    data object NeedsDialog : AdbRestoreState
    data object Connecting : AdbRestoreState
    data class Restored(val atMs: Long) : AdbRestoreState
    data class Failed(val reason: String, val atMs: Long) : AdbRestoreState
}

data class AdbRestoreWifi(val id: String, val label: String)
data class AdbTlsEndpoint(val host: String, val port: Int) {
    companion object {
        fun fromProperty(value: String?): AdbTlsEndpoint? = value?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..65535 }?.let { AdbTlsEndpoint("127.0.0.1", it) }
    }
}

data class AdbRestoreSnapshot(
    val enabled: Boolean = true,
    val state: AdbRestoreState = AdbRestoreState.NotNeeded,
    val lastTrigger: String? = null,
    val lastOutcome: String? = null,
    val lastOutcomeAtMs: Long? = null,
    val tlsPort: Int? = null,
    val wifi: AdbRestoreWifi? = null,
    val permissionHeld: Boolean = false,
    val writeWasReverted: Boolean = false,
    val retryBudgetExhausted: Boolean = false,
    val autoAllow: String? = null,
)

/** Persistent wish, prior trust and the settings-write cooldown, separate from the process clock. */
interface AdbRestoreStore {
    var enabled: Boolean
    var trustedBefore: Boolean
    var lastWriteNetwork: String?
    var lastWriteAtMs: Long
    var lastOutcome: String?
    var lastOutcomeAtMs: Long
    var autoAllow: String?
    fun recordWrite(network: String, atMs: Long) { lastWriteNetwork = network; lastWriteAtMs = atMs }
    fun recordOutcome(outcome: String, atMs: Long) { lastOutcome = outcome; lastOutcomeAtMs = atMs }
}

/** Only the Android adapter knows sockets, settings, accessibility and runtime recovery. */
interface AdbRestoreSystem {
    val sdk: Int
    fun nowMs(): Long
    fun elapsedMs(): Long
    suspend fun classicConnect(): Boolean
    fun permissionHeld(): Boolean
    suspend fun selfGrant(abandoned: () -> Boolean)
    fun wifiNetwork(): AdbRestoreWifi?
    fun readAdbWifiEnabled(): Boolean
    fun writeAdbWifiEnabled(enabled: Boolean)
    fun tlsPortProperty(): String?
    suspend fun discoverTlsPort(timeoutMs: Long): AdbTlsEndpoint?
    suspend fun restartTcpip(endpoint: AdbTlsEndpoint, abandoned: () -> Boolean)
    suspend fun ensureAccessibilityForDialog(abandoned: () -> Boolean)
    suspend fun recoverRuntime()
}

internal class AdbRestoreKeyUntrustedException : Exception()

enum class AdbRestoreVerdict { OK, HELPER_DOWN, NO_ACCESS, NOT_ENABLED, OFF_AFTER_REBOOT }

/** null means a restore/check is still in flight; diagnostics must not call that a refusal. */
fun evaluateAdbVerdict(enabled: Boolean, classic: Boolean?, helper: Boolean, trustedBefore: Boolean): AdbRestoreVerdict? = when {
    !enabled -> AdbRestoreVerdict.NOT_ENABLED
    classic == null -> null
    classic && helper -> AdbRestoreVerdict.OK
    classic -> AdbRestoreVerdict.HELPER_DOWN
    trustedBefore -> AdbRestoreVerdict.OFF_AFTER_REBOOT
    else -> AdbRestoreVerdict.NO_ACCESS
}

/** One passive attempt at a time, with a finite dialog wave and generation-guarded mutations. */
class AdbRestoreManager(
    private val system: AdbRestoreSystem,
    private val store: AdbRestoreStore,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit = {},
) {
    private data class Request(val trigger: String, val retry: Boolean = false, val force: Boolean = false)
    private val control = Any()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    private var pending: Request? = null
    private var active: Job? = null
    private var wave: Job? = null
    private var waveNetwork: String? = null
    private var exhaustedNetwork: String? = null
    @Volatile private var current = AdbRestoreSnapshot(
        enabled = store.enabled,
        state = if (store.enabled) AdbRestoreState.NotNeeded else AdbRestoreState.Disabled,
        lastOutcome = store.lastOutcome,
        lastOutcomeAtMs = store.lastOutcomeAtMs.takeIf { it > 0 },
        autoAllow = store.autoAllow,
    )

    init {
        scope.launch {
            for (signal in wake) {
                val next = synchronized(control) { pending.also { pending = null } } ?: continue
                val gen = synchronized(control) { generation }
                val attempt = launch {
                    mutex.withLock {
                        try {
                            attempt(gen, next)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: AdbRestoreKeyUntrustedException) {
                            publish(gen, AdbRestoreState.NeedsActivation)
                        } catch (error: Exception) {
                            publish(gen, AdbRestoreState.Failed(error.javaClass.simpleName, system.nowMs()))
                        }
                        // Read this attempt's verdict while it still owns the mutex.
                        reconcileWave(gen)
                    }
                }
                synchronized(control) {
                    active = attempt
                    if (abandoned(gen)) attempt.cancel()
                }
                attempt.join()
                synchronized(control) { if (active === attempt) active = null }
            }
        }
    }

    fun snapshot(): AdbRestoreSnapshot = current

    fun attemptIfNeeded(trigger: String) = enqueue(Request(trigger, force = trigger == "settings"))

    private fun enqueue(request: Request) {
        synchronized(control) {
            if (request.force) {
                exhaustedNetwork = null
                wave?.cancel(); wave = null; waveNetwork = null
            }
            val before = pending
            pending = request.copy(force = request.force || before?.force == true,
                retry = request.retry && before?.retry != false)
            wake.trySend(Unit)
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(control) {
            store.enabled = enabled // durable before a mutation is allowed
            if (!enabled) {
                generation++
                pending = null
                active?.cancel()
                wave?.cancel(); wave = null; waveNetwork = null
                if (system.permissionHeld()) runCatching { system.writeAdbWifiEnabled(false) }
                current = current.copy(enabled = false, state = AdbRestoreState.Disabled)
            } else current = current.copy(enabled = true)
        }
        changed()
        if (enabled) attemptIfNeeded("settings")
    }

    fun recordAutoAllow(outcome: String) {
        synchronized(control) {
            store.autoAllow = outcome
            current = current.copy(autoAllow = outcome)
        }
        changed()
    }

    private fun abandoned(gen: Long): Boolean = synchronized(control) { generation != gen || !store.enabled }
    private fun valid(gen: Long, network: AdbRestoreWifi? = null): Boolean =
        !abandoned(gen) && (network == null || system.wifiNetwork()?.id == network.id)

    private suspend fun attempt(gen: Long, request: Request) {
        if (abandoned(gen)) return
        if (system.sdk < MIN_SDK) { publish(gen, AdbRestoreState.Unsupported, request); return }
        synchronized(control) {
            current = current.copy(lastTrigger = request.trigger, writeWasReverted = false,
                retryBudgetExhausted = false)
        }
        if (system.classicConnect()) {
            if (abandoned(gen)) return
            synchronized(control) { store.trustedBefore = true }
            if (!system.permissionHeld()) {
                if (abandoned(gen)) return
                optional { system.selfGrant { abandoned(gen) } }
            }
            publish(gen, AdbRestoreState.NotNeeded, request)
            if (!abandoned(gen)) optional { system.ensureAccessibilityForDialog { abandoned(gen) } }
            return
        }
        if (abandoned(gen)) return
        if (!store.trustedBefore || !system.permissionHeld()) {
            publish(gen, AdbRestoreState.NeedsActivation, request); return
        }
        val network = system.wifiNetwork()
        if (network == null) { publish(gen, AdbRestoreState.WaitingWifi, request); return }
        if (!enableWireless(gen, network, request)) return
        if (!valid(gen, network)) return
        publish(gen, AdbRestoreState.Connecting, request)
        val property = AdbTlsEndpoint.fromProperty(system.tlsPortProperty())
        if (!valid(gen, network)) return
        if (property != null && runTcpip(gen, network, property)) return
        if (!valid(gen, network)) return
        val discovered = system.discoverTlsPort(DISCOVERY_TIMEOUT_MS)
        if (!valid(gen, network)) return
        if (discovered == null) {
            if (system.classicConnect()) restored(gen)
            else publish(gen, AdbRestoreState.Failed("mDNS timeout", system.nowMs()))
            return
        }
        if (!runTcpip(gen, network, discovered) && valid(gen, network)) {
            publish(gen, AdbRestoreState.Failed("classic port silent after tcpip", system.nowMs()))
        }
    }

    private suspend fun enableWireless(gen: Long, network: AdbRestoreWifi, request: Request): Boolean {
        if (!valid(gen, network)) return false
        // Already enabled means the dialog was approved between retries, even on an exhausted wave.
        if (system.readAdbWifiEnabled()) return true
        val cooldown = if (request.retry) WRITE_RETRY_INTERVAL_MS else WRITE_COOLDOWN_MS
        val suppress = synchronized(control) {
            !request.force && (exhaustedNetwork == network.id ||
                (store.lastWriteNetwork == network.id && system.nowMs() - store.lastWriteAtMs in 0 until cooldown))
        }
        if (suppress) {
            publish(gen, AdbRestoreState.NeedsDialog, request)
            synchronized(control) { current = current.copy(retryBudgetExhausted = exhaustedNetwork == network.id) }
            return false
        }
        synchronized(control) {
            if (!valid(gen, network)) return false
            store.recordWrite(network.id, system.nowMs())
            system.writeAdbWifiEnabled(true)
        }
        delay(SETTINGS_SETTLE_MS)
        if (!valid(gen, network)) return false
        if (!system.readAdbWifiEnabled()) {
            synchronized(control) { current = current.copy(writeWasReverted = true) }
            publish(gen, AdbRestoreState.NeedsDialog, request)
            return false
        }
        return true
    }

    private suspend fun runTcpip(gen: Long, network: AdbRestoreWifi, endpoint: AdbTlsEndpoint): Boolean {
        if (!valid(gen, network)) return false
        synchronized(control) { current = current.copy(tlsPort = endpoint.port) }
        changed()
        try {
            system.restartTcpip(endpoint) { !valid(gen, network) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Even EOF after tcpip is followed by classic probes. */ }
        val started = system.elapsedMs()
        do {
            if (!valid(gen, network)) return false
            if (system.classicConnect()) { restored(gen); return true }
            if (system.elapsedMs() - started >= TCPIP_POLL_TIMEOUT_MS) break
            delay(TCPIP_POLL_INTERVAL_MS)
        } while (true)
        return false
    }

    private suspend fun restored(gen: Long) {
        if (abandoned(gen)) return
        publish(gen, AdbRestoreState.Restored(system.nowMs()))
        if (!abandoned(gen)) optional { system.recoverRuntime() }
        if (!abandoned(gen)) optional { system.ensureAccessibilityForDialog { abandoned(gen) } }
    }

    private fun publish(gen: Long, state: AdbRestoreState, request: Request? = null) {
        synchronized(control) {
            if (abandoned(gen)) return
            val outcome = state.label()
            val at = when (state) {
                is AdbRestoreState.Restored -> state.atMs
                is AdbRestoreState.Failed -> state.atMs
                else -> system.nowMs()
            }
            current = current.copy(state = state, lastTrigger = request?.trigger ?: current.lastTrigger,
                lastOutcome = outcome, lastOutcomeAtMs = at,
                permissionHeld = system.permissionHeld(), wifi = system.wifiNetwork())
            if (state == AdbRestoreState.NotNeeded || state is AdbRestoreState.Restored) {
                exhaustedNetwork = null
            }
            if (state !is AdbRestoreState.Connecting) {
                store.recordOutcome(outcome, at)
            }
        }
        changed()
    }

    private fun reconcileWave(gen: Long) = synchronized(control) {
        if (abandoned(gen)) return@synchronized
        val network = current.wifi?.id
        if (current.state != AdbRestoreState.NeedsDialog || network == null) {
            wave?.cancel(); wave = null; waveNetwork = null
            return@synchronized
        }
        if (exhaustedNetwork == network || (wave?.isActive == true && waveNetwork == network)) return@synchronized
        // A suppressed settings write did not open a dialog and cannot create a fresh wave.
        if (!current.writeWasReverted) return@synchronized
        wave?.cancel()
        waveNetwork = network
        val started = system.elapsedMs()
        wave = scope.launch {
            var index = 0
            while (!abandoned(gen) && system.wifiNetwork()?.id == network) {
                val remaining = RETRY_BUDGET_MS - (system.elapsedMs() - started)
                if (remaining <= 0) break
                delay(minOf(RETRY_DELAYS_MS[minOf(index++, RETRY_DELAYS_MS.lastIndex)], remaining))
                if (system.elapsedMs() - started >= RETRY_BUDGET_MS) break
                if (!abandoned(gen) && system.wifiNetwork()?.id == network) enqueue(Request("retry", retry = true))
            }
            synchronized(control) {
                if (!abandoned(gen) && system.wifiNetwork()?.id == network) {
                    exhaustedNetwork = network
                    current = current.copy(retryBudgetExhausted = true)
                    pending = pending?.takeUnless { it.retry }
                }
            }
            changed()
        }
    }

    private fun changed() { runCatching(onChanged) }

    private suspend fun optional(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preparation failure cannot take an already working port away. */ }
    }

    companion object {
        const val MIN_SDK = 30
        const val SETTINGS_SETTLE_MS = 1_500L
        const val DISCOVERY_TIMEOUT_MS = 45_000L
        const val WRITE_COOLDOWN_MS = 600_000L
        const val WRITE_RETRY_INTERVAL_MS = 15_000L
        const val TCPIP_POLL_TIMEOUT_MS = 15_000L
        const val TCPIP_POLL_INTERVAL_MS = 1_000L
        const val RETRY_BUDGET_MS = 300_000L
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 15_000)
    }
}

fun AdbRestoreState.label(): String = when (this) {
    AdbRestoreState.Disabled -> "disabled"
    AdbRestoreState.Unsupported -> "unsupported"
    AdbRestoreState.NotNeeded -> "not-needed"
    AdbRestoreState.NeedsActivation -> "needs-activation"
    AdbRestoreState.WaitingWifi -> "waiting-wifi"
    AdbRestoreState.NeedsDialog -> "needs-dialog"
    AdbRestoreState.Connecting -> "connecting"
    is AdbRestoreState.Restored -> "restored"
    is AdbRestoreState.Failed -> "failed: $reason"
}
