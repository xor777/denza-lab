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
    /**
     * The port is closed and Wi-Fi cannot bring it back: the car [refused] this app's key, or the
     * app never had it trusted or lacks the permission the restore writes with.
     */
    data class NeedsActivation(val refused: Boolean) : AdbRestoreState
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

/** What the first look after a boot found on port 5555. */
enum class AdbBootLook { OPEN, CLOSED, REFUSED }

/** Where the restore got to after a boot, once it had something to do. */
enum class AdbBootOutcome {
    CONNECTING,
    WAITING_WIFI,
    NEEDS_DIALOG,
    NEEDS_ACTIVATION,
    FAILED,
    RESTORED,

    /** Access came back while the restore was still waiting or after it failed: not its doing. */
    RETURNED,
    ;

    /** The port is still closed and the restore has not finished with it. */
    val pending: Boolean get() = this != RESTORED && this != RETURNED
}

/**
 * The head unit's latest boot as the restore saw it: what the first look found, and what the
 * restore did about it.
 *
 * The page used to show the last check alone, and every hint is a check - the app starting, the
 * screen coming on, Wi-Fi, the opening of «Сервис» itself. A restore at 08:11 was overwritten by the
 * first of them that found the port open, so the page could never say whether the car had closed
 * the port and whether the app had opened it again. A check that finds the port open leaves this
 * record alone.
 */
data class AdbBootRecord(
    /** `Settings.Global.BOOT_COUNT`, or null where it cannot be read. */
    val bootCount: Int?,
    /** When the head unit booted, on the wall clock as last read. */
    val bootAtMs: Long,
    /** The elapsed-realtime clock at the last write: a later reading below it is a later boot. */
    val seenAtElapsedMs: Long,
    val look: AdbBootLook? = null,
    /** When the port was first found closed in the current closure; what a restore's time counts from. */
    val closedAtMs: Long = 0L,
    val outcome: AdbBootOutcome? = null,
    val outcomeAtMs: Long = 0L,
) {
    /** Whether [count] and [elapsedMs], read now, are still this boot. */
    fun isBoot(count: Int?, elapsedMs: Long): Boolean =
        if (count != null && bootCount != null) count == bootCount else elapsedMs >= seenAtElapsedMs

    /** One preference value, so the record is written whole or not at all. */
    fun encode(): String = listOf(bootCount ?: "", bootAtMs, seenAtElapsedMs, look ?: "", closedAtMs,
        outcome ?: "", outcomeAtMs).joinToString("|")

    companion object {
        fun decode(text: String?): AdbBootRecord? = runCatching {
            val f = text!!.split('|')
            AdbBootRecord(
                bootCount = f[0].toIntOrNull(),
                bootAtMs = f[1].toLong(),
                seenAtElapsedMs = f[2].toLong(),
                look = f[3].takeIf { it.isNotEmpty() }?.let(AdbBootLook::valueOf),
                closedAtMs = f[4].toLong(),
                outcome = f[5].takeIf { it.isNotEmpty() }?.let(AdbBootOutcome::valueOf),
                outcomeAtMs = f[6].toLong(),
            )
        }.getOrNull()
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
    /** What the shared accessibility service was, and what was done for it, before the last dialog. */
    val dialogService: String? = null,
    /** This boot's record; null until the restore has looked at the port since the boot. */
    val boot: AdbBootRecord? = null,
)

/** Persistent wish, prior trust and the settings-write cooldown, separate from the process clock. */
interface AdbRestoreStore {
    /** The driver's switch; changed only through [saveEnabled]. */
    val enabled: Boolean

    /** The switch made durable: false, with [enabled] as it was, when it could not be. */
    fun saveEnabled(enabled: Boolean): Boolean
    var trustedBefore: Boolean
    var lastWriteNetwork: String?
    var lastWriteAtMs: Long
    var lastOutcome: String?
    var lastOutcomeAtMs: Long
    var autoAllow: String?
    var dialogService: String?
    var boot: AdbBootRecord?
    fun recordWrite(network: String, atMs: Long) { lastWriteNetwork = network; lastWriteAtMs = atMs }
    fun recordOutcome(outcome: String, atMs: Long) { lastOutcome = outcome; lastOutcomeAtMs = atMs }
}

/** Only the Android adapter knows sockets, settings, accessibility and runtime recovery. */
interface AdbRestoreSystem {
    val sdk: Int
    fun nowMs(): Long
    fun elapsedMs(): Long
    /** `Settings.Global.BOOT_COUNT`: which boot this is, or null where it cannot be read. */
    fun bootCount(): Int?
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
    /** The shared accessibility service, which presses the network dialog, as the shared health reads it. */
    fun dialogService(): AdbDialogService
    /**
     * Lists the shared service in this process, with `WRITE_SECURE_SETTINGS` and no ADB: the port is
     * closed when the restore needs it. Throws when the setting could not be written.
     */
    suspend fun enableDialogService(abandoned: () -> Boolean)
    suspend fun recoverRuntime()
}

/** The service that presses the network dialog, at the moment before the write that raises it. */
enum class AdbDialogService {
    /** Switched on and bound: it will see the dialog. */
    BOUND,
    /** Listed and within its bind deadline (`AccessibilityHealth.available`): on its way, not to be rewritten. */
    BINDING,
    /** Switched off, or listed and never bound: only a rewrite of the setting brings it. */
    MISSING,
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
    /**
     * What is waiting to run. Hints that arrive during an attempt merge into one, and every flag is
     * merged with OR: the trigger kept is only the newest, so a decision keyed on it lost the screen
     * coming on to a passive hint that came after it. (A dialog wave's retry was a flag too until
     * 2026-11-19, for its shorter write interval; a retry now only reads, like any passive hint.)
     */
    private data class Request(
        val trigger: String,
        val force: Boolean = false,
        /** The screen came on: passes the failed-network hold, as the `Failed` gate promises. */
        val wake: Boolean = false,
    ) {
        fun merge(before: Request?): Request = if (before == null) this else copy(
            force = force || before.force,
            wake = wake || before.wake,
        )
    }
    private val control = Any()
    private val mutex = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    /**
     * Counts the Wi-Fi connections: one more at every `wifi-lost`. An attempt notes it as it starts
     * and sets the failed-network hold only if it is still the same, because a hint that came during
     * it may belong to a new connection to the same router - forgetting the hold at that hint was
     * undone by the failure the attempt published afterwards.
     */
    private var connection = 0L
    private var attemptConnection = 0L
    /**
     * Attempts restarted in a row by [leaveConnecting] because the network changed, with none run to
     * its own end since. A network id that never settles (two Wi-Fi networks listed in an unstable
     * order, a flapping mesh) would otherwise chain attempts at probe speed with the ring up for good.
     */
    private var networkRestarts = 0
    private var pending: Request? = null
    private var active: Job? = null
    private var wave: Job? = null
    private var waveNetwork: String? = null
    private var exhaustedNetwork: String? = null
    /**
     * The network an attempt last failed on, and when, by the elapsed clock. In memory only: a new
     * process gets one fresh attempt. While it holds, a passive hint on the same network does not
     * start another attempt - one is up to about 100 s of `Connecting`, and hints come back to back.
     */
    private var failedNetwork: String? = null
    private var failedAtElapsedMs = 0L
    @Volatile private var current = AdbRestoreSnapshot(
        enabled = store.enabled,
        state = if (store.enabled) AdbRestoreState.NotNeeded else AdbRestoreState.Disabled,
        lastOutcome = store.lastOutcome,
        lastOutcomeAtMs = store.lastOutcomeAtMs.takeIf { it > 0 },
        autoAllow = store.autoAllow,
        dialogService = store.dialogService,
        // An earlier boot's record is history the page has no row for: this boot starts its own.
        boot = store.boot?.takeIf { it.isBoot(system.bootCount(), system.elapsedMs()) },
    )

    init {
        scope.launch {
            for (ready in signal) {
                val next = synchronized(control) { pending.also { pending = null } } ?: continue
                val gen = synchronized(control) { attemptConnection = connection; generation }
                val attempt = launch {
                    mutex.withLock {
                        try {
                            attempt(gen, next)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: AdbRestoreKeyUntrustedException) {
                            look(gen, AdbBootLook.REFUSED)
                            publish(gen, AdbRestoreState.NeedsActivation(refused = true))
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

    fun attemptIfNeeded(trigger: String) =
        enqueue(Request(trigger, force = trigger in FORCING, wake = trigger == SCREEN_ON))

    private fun enqueue(request: Request) {
        synchronized(control) {
            if (request.force) {
                exhaustedNetwork = null
                wave?.cancel(); wave = null; waveNetwork = null
            }
            // Wi-Fi gone: whatever comes up next is a new connection, even to the same router.
            if (request.trigger == WIFI_LOST) connection++
            if (request.force || request.trigger == WIFI_LOST) { forgetFailure(); networkRestarts = 0 }
            pending = request.merge(pending)
            signal.trySend(Unit)
        }
    }

    /**
     * The driver's switch. Durable before anything acts on it: a write the disk refused changes
     * nothing, returns false, and the switch, which shows [snapshot], stays where it was.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val saved = synchronized(control) {
            if (!store.saveEnabled(enabled)) return@synchronized false
            forgetFailure()
            networkRestarts = 0
            if (!enabled) {
                generation++
                pending = null
                active?.cancel()
                wave?.cancel(); wave = null; waveNetwork = null
                if (system.permissionHeld()) runCatching { system.writeAdbWifiEnabled(false) }
                current = current.copy(enabled = false, state = AdbRestoreState.Disabled)
            } else current = current.copy(enabled = true)
            true
        }
        // Either way the switch reads the snapshot again: after a refused write, the setting kept.
        changed()
        if (saved && enabled) attemptIfNeeded(SWITCH)
        return saved
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
        val before = current
        synchronized(control) {
            current = current.copy(lastTrigger = request.trigger, writeWasReverted = false,
                retryBudgetExhausted = false)
        }
        if (system.classicConnect()) {
            if (abandoned(gen)) return
            look(gen, AdbBootLook.OPEN)
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
        look(gen, AdbBootLook.CLOSED)
        if (!store.trustedBefore || !system.permissionHeld()) {
            publish(gen, AdbRestoreState.NeedsActivation(refused = false), request); return
        }
        val network = system.wifiNetwork()
        if (network == null) { publish(gen, AdbRestoreState.WaitingWifi, request); return }
        if (failedHere(network, request)) {
            // Nothing happened, so nothing is said: the page keeps the failure and what made it.
            synchronized(control) {
                current = current.copy(lastTrigger = before.lastTrigger, writeWasReverted = before.writeWasReverted,
                    retryBudgetExhausted = before.retryBudgetExhausted)
            }
            return
        }
        if (!enableWireless(gen, network, request) || !valid(gen, network)) {
            // `Connecting` may be up already - published while the dialog's service was made ready,
            // or left by the attempt before - and only a network change ends here with no final
            // state of its own: the same exit as after the TLS request.
            leaveConnecting(gen)
            return
        }
        publish(gen, AdbRestoreState.Connecting, request)
        connect(gen, network)
        leaveConnecting(gen)
    }

    /**
     * The TLS request and the probes after it, once `Connecting` is published. Every early return
     * here is the network changing or the attempt being abandoned; [leaveConnecting] after it is the
     * one exit, so none of them can leave the ring turning.
     */
    private suspend fun connect(gen: Long, network: AdbRestoreWifi) {
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

    /**
     * An attempt that published `Connecting` and stopped because the network changed or went away.
     * The network id is the BSSID, so roaming between access points changes it, and with no hint
     * coinciding the state stayed `Connecting` and the gate's ring turned for good. Without Wi-Fi it
     * is `WaitingWifi`; on another network a fresh attempt starts there at once, up to
     * [MAX_NETWORK_RESTARTS] in a row. After that it is `Failed`, held on the network seen last like
     * any failure, so the gate has its button instead of a ring that never stops.
     */
    private fun leaveConnecting(gen: Long) {
        val next = synchronized(control) {
            if (abandoned(gen) || current.state != AdbRestoreState.Connecting) return
            when {
                system.wifiNetwork() == null -> AdbRestoreState.WaitingWifi
                networkRestarts >= MAX_NETWORK_RESTARTS ->
                    AdbRestoreState.Failed(NETWORK_UNSETTLED, system.nowMs())
                else -> { networkRestarts++; null }
            }
        }
        if (next != null) publish(gen, next) else enqueue(Request(WIFI))
    }

    /**
     * Whether this attempt would repeat the one that just failed on [network]. Only a passive hint
     * is held back, and only while the failure is what the gate shows: a press, «Сервис», the
     * switch and the screen coming on each get an attempt, and so does a dialog wave's retry.
     */
    private fun failedHere(network: AdbRestoreWifi, request: Request): Boolean = synchronized(control) {
        !request.force && !request.wake && current.state is AdbRestoreState.Failed &&
            failedNetwork == network.id && system.elapsedMs() - failedAtElapsedMs in 0 until FAILED_COOLDOWN_MS
    }

    /** Under [control]. */
    private fun forgetFailure() { failedNetwork = null; failedAtElapsedMs = 0L }

    /**
     * Wireless debugging on, by one settings write per network and [WRITE_COOLDOWN_MS]; true when it
     * is on. Every write raises one more «Allow wireless debugging on this network?» on top of the
     * one already waiting, and an approval sets `adb_wifi_enabled=1` itself, so a dialog wave's retry
     * only reads: within one wave there is one write. Until 2026-11-19 a retry wrote again every
     * 15 s, stacking up to twenty dialogs in the wave's five minutes. A dialog the driver dismissed
     * is raised again by a forcing request ([FORCING]); a passive hint writes again only after the
     * cooldown and only where no wave has run to its end - an exhausted network waits for another
     * network, a success or a forcing request.
     */
    private suspend fun enableWireless(gen: Long, network: AdbRestoreWifi, request: Request): Boolean {
        if (!valid(gen, network)) return false
        // Already enabled means the dialog was approved between retries, even on an exhausted wave.
        if (system.readAdbWifiEnabled()) return true
        val suppress = synchronized(control) {
            // An exhausted wave's dialog is still standing unanswered: one more, however late, is
            // only a slower stack. Its network waits for another network, a success or a forcing
            // request.
            !request.force && (exhaustedNetwork == network.id || (store.lastWriteNetwork == network.id &&
                system.nowMs() - store.lastWriteAtMs in 0 until WRITE_COOLDOWN_MS))
        }
        if (suppress) {
            publish(gen, AdbRestoreState.NeedsDialog, request)
            synchronized(control) { current = current.copy(retryBudgetExhausted = exhaustedNetwork == network.id) }
            return false
        }
        readyDialogService(gen, request)
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

    /**
     * The write that comes next raises the network dialog, and only the shared accessibility service
     * presses it: on a window-state event, so a service bound after the dialog came up never sees it.
     * Until 2026-11-19 the service was prepared only on the healthy classic path and after a restore,
     * over the shell - which a cold start with port 5555 closed, the case the restore exists for, does
     * not have. A bound service is left alone; one on its way is waited for and never rewritten (a
     * rewrite takes both Denza services down for about three seconds); any other is listed in this
     * process and then waited for. Never fatal: the write follows whatever came of it, and the
     * reading is kept for «Технические сведения».
     *
     * When something is to be done, it takes up to about seven seconds (the transaction's pauses
     * and the wait), so `Connecting` is published first: the gate shows the restore at work rather
     * than the state before it. `Connecting` is what this is - the restore under way towards the
     * port - and every exit after it publishes a final state: the write reverted is `NeedsDialog`,
     * the write held is `Connecting` proper, a changed network goes through [leaveConnecting], an
     * abandoned attempt leaves what the switch put there. A bound service publishes nothing early.
     */
    private suspend fun readyDialogService(gen: Long, request: Request) {
        val found = system.dialogService()
        if (found == AdbDialogService.BOUND) return recordDialogService(gen, "bound")
        if (found == AdbDialogService.MISSING && !system.permissionHeld()) {
            return recordDialogService(gen, "missing, no permission")
        }
        publish(gen, AdbRestoreState.Connecting, request)
        val step = when (found) {
            AdbDialogService.BOUND, AdbDialogService.BINDING -> "binding"
            AdbDialogService.MISSING -> {
                try {
                    system.enableDialogService { abandoned(gen) }
                    "enabled"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    return recordDialogService(gen, "enable failed: ${error.javaClass.simpleName}")
                }
            }
        }
        if (abandoned(gen)) return
        val bound = awaitDialogService(gen)
        recordDialogService(gen, if (bound) "$step, bound" else "$step, not bound in ${DIALOG_SERVICE_BIND_WAIT_MS / 1000} s")
    }

    /** Whether the service bound within [DIALOG_SERVICE_BIND_WAIT_MS], read every [DIALOG_SERVICE_POLL_MS]. */
    private suspend fun awaitDialogService(gen: Long): Boolean {
        repeat((DIALOG_SERVICE_BIND_WAIT_MS / DIALOG_SERVICE_POLL_MS).toInt()) {
            if (abandoned(gen)) return false
            if (system.dialogService() == AdbDialogService.BOUND) return true
            delay(DIALOG_SERVICE_POLL_MS)
        }
        return system.dialogService() == AdbDialogService.BOUND
    }

    private fun recordDialogService(gen: Long, reading: String) {
        synchronized(control) {
            if (abandoned(gen)) return
            store.dialogService = reading
            current = current.copy(dialogService = reading)
        }
        changed()
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
            // An attempt that ran to its own end: the network held still long enough.
            if (state is AdbRestoreState.Restored || state is AdbRestoreState.Failed) networkRestarts = 0
            when (state) {
                // Only on the connection the attempt started on: after a `wifi-lost` it is a new one.
                is AdbRestoreState.Failed -> current.wifi?.id?.takeIf { attemptConnection == connection }
                    ?.let { failedNetwork = it; failedAtElapsedMs = system.elapsedMs() }
                AdbRestoreState.NotNeeded, is AdbRestoreState.Restored, AdbRestoreState.WaitingWifi -> forgetFailure()
                else -> Unit
            }
            if (state !is AdbRestoreState.Connecting) {
                store.recordOutcome(outcome, at)
            }
            val held = current.boot
            val reached = when (state) {
                AdbRestoreState.Connecting -> AdbBootOutcome.CONNECTING
                AdbRestoreState.WaitingWifi -> AdbBootOutcome.WAITING_WIFI
                AdbRestoreState.NeedsDialog -> AdbBootOutcome.NEEDS_DIALOG
                is AdbRestoreState.NeedsActivation -> AdbBootOutcome.NEEDS_ACTIVATION
                is AdbRestoreState.Failed -> AdbBootOutcome.FAILED
                is AdbRestoreState.Restored -> AdbBootOutcome.RESTORED
                // The port open again, with the restore not the one that opened it.
                AdbRestoreState.NotNeeded -> AdbBootOutcome.RETURNED.takeIf { held?.outcome?.pending == true }
                AdbRestoreState.Disabled, AdbRestoreState.Unsupported -> null
            }
            if (reached != null) saveBoot(bootRecord().copy(outcome = reached, outcomeAtMs = at))
        }
        changed()
    }

    /**
     * What a look at the port found, kept as this boot's first look if it is the first. A closed
     * port where the last look found it working starts a closure, the moment a restore counts from.
     */
    private fun look(gen: Long, found: AdbBootLook) = synchronized(control) {
        if (abandoned(gen)) return@synchronized
        val record = bootRecord()
        val opens = found != AdbBootLook.OPEN && record.outcome?.pending != true
        saveBoot(record.copy(look = record.look ?: found,
            closedAtMs = if (opens) system.nowMs() else record.closedAtMs))
    }

    /** This boot's record, begun afresh if the one held belongs to an earlier boot. Under [control]. */
    private fun bootRecord(): AdbBootRecord {
        val count = system.bootCount()
        val elapsed = system.elapsedMs()
        val held = current.boot?.takeIf { it.isBoot(count, elapsed) }
            ?: AdbBootRecord(bootCount = count, bootAtMs = 0L, seenAtElapsedMs = 0L)
        // Read again on every write: the wall clock may have been set since the boot.
        return held.copy(bootCount = count ?: held.bootCount, bootAtMs = system.nowMs() - elapsed,
            seenAtElapsedMs = elapsed)
    }

    private fun saveBoot(record: AdbBootRecord) {
        store.boot = record
        current = current.copy(boot = record)
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
                if (!abandoned(gen) && system.wifiNetwork()?.id == network) enqueue(Request(RETRY))
            }
            synchronized(control) {
                if (!abandoned(gen) && system.wifiNetwork()?.id == network) {
                    exhaustedNetwork = network
                    current = current.copy(retryBudgetExhausted = true)
                    // The wave's own retry goes; a hint merged into it stays, as the hint it was.
                    pending = pending?.takeUnless { it.trigger == RETRY }
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
        /** «Сервис» opened, and the switch turned on: somebody is looking, so the cooldown waits. */
        const val SETTINGS = "settings"
        const val SWITCH = "switch"
        /** The gate's button, or the recovery dialog's: a person asked, so nothing holds it back. */
        const val GATE = "gate"
        private val FORCING = setOf(SETTINGS, SWITCH, GATE)
        /** The screen coming on: passes the failed-network hold, the one the gate promises, but forces nothing. */
        const val SCREEN_ON = "screen-on"
        const val WIFI_LOST = "wifi-lost"
        /** Wi-Fi came up or changed; also what an attempt cut short by a network change leaves queued. */
        const val WIFI = "wifi"
        /** A dialog wave's next attempt: a read of the setting, never a write inside [WRITE_COOLDOWN_MS]. */
        const val RETRY = "retry"
        const val MIN_SDK = 30
        /** Restarts on a changed network in a row before the attempt is called `Failed`. */
        const val MAX_NETWORK_RESTARTS = 3
        const val NETWORK_UNSETTLED = "network kept changing"
        const val SETTINGS_SETTLE_MS = 1_500L
        /**
         * How long the write waits for the dialog's service to bind. A bind takes milliseconds once
         * the setting lists the service, and the repair itself allows the system
         * [dev.denza.apps.platform.accessibility.AccessibilityHealth.BIND_GRACE_MS] (2 s) for it; a
         * third second is margin for a head unit still busy after its boot. Longer only holds back a
         * dialog the driver can still answer by hand.
         */
        const val DIALOG_SERVICE_BIND_WAIT_MS = 3_000L
        const val DIALOG_SERVICE_POLL_MS = 100L
        const val DISCOVERY_TIMEOUT_MS = 45_000L
        /**
         * Between two writes of `adb_wifi_enabled=1` on one network, for every request that does not
         * force: a dialog wave's retries included, so one wave ([RETRY_BUDGET_MS]) raises one dialog.
         */
        const val WRITE_COOLDOWN_MS = 600_000L
        const val FAILED_COOLDOWN_MS = 600_000L
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
    is AdbRestoreState.NeedsActivation -> if (refused) "needs-activation: refused" else "needs-activation"
    AdbRestoreState.WaitingWifi -> "waiting-wifi"
    AdbRestoreState.NeedsDialog -> "needs-dialog"
    AdbRestoreState.Connecting -> "connecting"
    is AdbRestoreState.Restored -> "restored"
    is AdbRestoreState.Failed -> "failed: $reason"
}
