package ru.adbgw.gateway

import android.content.Context
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

class GatewaySupervisor(
    context: Context,
    private val scope: CoroutineScope,
    private val stateStore: GatewayStateStore,
    private val keyStore: SshKeyStore,
    private val relayClient: RelayClient,
    private val onEvent: (GatewayEvent) -> Unit,
    private val onSupportEvent: (String) -> Unit,
    // The relay refused the vehicle key. Returns true when the registration was dropped (the
    // supervisor stops), false while the rejection is unconfirmed (keep retrying).
    private val onRegistrationRejected: (String) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val detector = AdbEndpointDetector()
    private val provisioner = AdbProvisioner(appContext)
    // The app's own ADB identity, used only to sign for a tunnelled computer in the proxy channel.
    // It never submits the public key, so its authorization policy is immaterial.
    private val adbSigningClient =
        LocalAdbClient(appContext, "car-adb-gateway@local", LocalAdbClient.AuthorizationPolicy.PASSIVE)
    private val adbHandshake = AdbHandshakeProxy { clientIn, clientOut, adbdIn, adbdOut ->
        adbSigningClient.proxyAdbConnect(clientIn, clientOut, adbdIn, adbdOut)
    }
    private val endpoint = AtomicReference<AdbEndpoint?>(null)
    private var adbJob: Job? = null
    private var relayJob: Job? = null
    private var innerServer: InnerGatewayServer? = null
    @Volatile private var tunnel: RelayTunnel? = null
    private val relayPacer = RelayRetryPacer()
    @Volatile private var networkGeneration: Long = 0

    @Synchronized
    fun start() {
        if (adbJob?.isActive == true || relayJob?.isActive == true) {
            // Already running: an explicit start (the app opened, a boot or update broadcast)
            // retries the relay now instead of at the end of its backoff.
            relayPacer.wake()
            return
        }
        val registration = stateStore.registration() ?: return
        if (!stateStore.isEnabled()) return
        ensureInnerServer()
        adbJob = scope.launch { adbLoop(registration) }
        relayJob = scope.launch { relayLoop(registration) }
        onSupportEvent("Самовосстановление запущено")
    }

    @Synchronized
    fun stop() {
        adbJob?.cancel()
        relayJob?.cancel()
        adbJob = null
        relayJob = null
        relayClient.abortTunnelAttempt()
        tunnel?.close()
        tunnel = null
        innerServer?.close()
        innerServer = null
        endpoint.set(null)
        onEvent(GatewayEvent.ClientChanged(ClientState.Waiting, null, System.currentTimeMillis()))
    }

    /**
     * The default network appeared, changed or went away: the tunnel and any attempt in flight are
     * on the old one, so both go, and the relay loop tries again at once with its backoff restarted.
     */
    @Synchronized
    fun networkChanged() {
        networkGeneration += 1
        tunnel?.close()
        tunnel = null
        relayClient.abortTunnelAttempt()
        relayPacer.wake()
        onSupportEvent("Сеть изменилась; соединение будет создано заново")
    }

    /**
     * The default network passed Android's validation, so it now carries traffic. A relay loop
     * waiting out its backoff tries at once; a working tunnel is left alone.
     */
    fun networkValidated() {
        relayPacer.wake()
    }

    private suspend fun adbLoop(registration: RelayRegistration) {
        var lastConfigured: AdbEndpoint? = null
        var lastReported: AdbEndpoint? = null
        while (scope.isActive && stateStore.isEnabled()) {
            onEvent(GatewayEvent.AdbChanged(AdbState.Checking, endpoint.get()))
            val detected = detector.detect()
            val current = detected.getOrNull()
            if (current == null) {
                endpoint.set(null)
                onEvent(GatewayEvent.AdbChanged(AdbState.Unavailable))
                onSupportEvent("ADB временно недоступен; следующая проверка через 30 секунд")
                delay(30_000)
                continue
            }

            val configurationChanged = lastConfigured == null ||
                lastConfigured.kind != current.kind ||
                lastConfigured.host != current.host
            val provisioned = provisioner.authorizeAndConfigure(
                current,
                applyBackgroundSettings = configurationChanged,
            )
            val failure = provisioned.exceptionOrNull()
            if (failure is AdbAuthorizationRequiredException) {
                endpoint.set(null)
                onEvent(GatewayEvent.AdbChanged(AdbState.AuthorizationRequired))
                onSupportEvent("Ожидается системное подтверждение ADB-ключа")
                delay(10_000)
                continue
            }
            if (failure != null) {
                endpoint.set(null)
                onEvent(GatewayEvent.AdbChanged(AdbState.Unavailable))
                onSupportEvent("Проверка ADB не удалась: ${failure.message ?: failure.javaClass.simpleName}")
                delay(30_000)
                continue
            }

            endpoint.set(current)
            lastConfigured = current
            onEvent(GatewayEvent.AdbChanged(AdbState.Available, current))
            provisioned.getOrNull().orEmpty().forEach(onSupportEvent)
            ensureInnerServer()
            val endpointNeedsReporting = lastReported == null ||
                lastReported.kind != current.kind ||
                lastReported.host != current.host
            if (endpointNeedsReporting) {
                try {
                    relayClient.updateEndpoint(registration, current)
                    lastReported = current
                    onSupportEvent("Relay получил актуальный адрес ADB")
                } catch (error: RelayAccessException) {
                    if (error.permanent && handlePermanent(error)) return
                    onSupportEvent("Адрес ADB будет отправлен relay повторно")
                }
            }
            delay(30_000)
        }
    }

    private suspend fun relayLoop(initialRegistration: RelayRegistration) {
        var registration = initialRegistration
        var observedGeneration = networkGeneration
        var nextLeaseRenewalAtMillis = 0L
        var reportedPortBusy = false
        while (scope.isActive && stateStore.isEnabled()) {
            // A wake from here on (a network change, a validated network, start()) ends the pause
            // that follows this attempt.
            val wakeMark = relayPacer.mark()
            var portBusy = false
            onEvent(GatewayEvent.RelayChanged(RelayState.Connecting))
            try {
                recoverPendingPairCommit(registration)
                if (System.currentTimeMillis() >= nextLeaseRenewalAtMillis) {
                    registration = renewLease(registration)
                    nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RENEW_INTERVAL_MILLIS
                }
                if (endpoint.get() == null || innerServer?.isRunning != true) {
                    onEvent(GatewayEvent.RelayChanged(RelayState.WaitingForNetwork))
                    relayPacer.pause(WAITING_POLL_MILLIS, wakeMark)
                    continue
                }
                val opened = relayClient.openTunnel(registration) { error ->
                    if (error != null) {
                        onSupportEvent("Туннель закрыт: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                tunnel = opened
                relayPacer.connected()
                reportedPortBusy = false
                onEvent(GatewayEvent.RelayChanged(RelayState.Connected))
                onSupportEvent("Соединение с relay восстановлено")
                try {
                    // A network change while the tunnel was being opened leaves it on the old network.
                    while (scope.isActive && stateStore.isEnabled() && opened.isOpen &&
                        observedGeneration == networkGeneration
                    ) {
                        if (System.currentTimeMillis() >= nextLeaseRenewalAtMillis) {
                            try {
                                registration = renewLease(registration)
                                nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RENEW_INTERVAL_MILLIS
                            } catch (error: RelayAccessException) {
                                if (error.permanent && handlePermanent(error)) return
                                nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RETRY_INTERVAL_MILLIS
                                onSupportEvent("Продление регистрации будет повторено через пять минут")
                            }
                        }
                        delay(TUNNEL_POLL_MILLIS)
                    }
                } finally {
                    // Also on stop(): a cancelled loop must not leave the tunnel open on the relay.
                    opened.close()
                    if (tunnel === opened) tunnel = null
                }
            } catch (error: RelayAccessException) {
                if (error.permanent && handlePermanent(error)) return
                onEvent(GatewayEvent.RelayChanged(RelayState.WaitingForNetwork))
                portBusy = error.relayPortBusy
                when {
                    portBusy -> if (!reportedPortBusy) {
                        reportedPortBusy = true
                        onSupportEvent("Relay ещё держит прежний туннель; повторяем каждые несколько секунд")
                    }
                    !error.registrationRejected -> onSupportEvent("Relay временно недоступен")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onEvent(GatewayEvent.PermanentFailure("Не удалось сохранить состояние подключения"))
                onSupportEvent("Локальная ошибка состояния: ${error.message ?: error.javaClass.simpleName}")
                return
            }

            if (observedGeneration != networkGeneration) {
                // networkChanged() already restarted the backoff.
                observedGeneration = networkGeneration
                continue
            }
            relayPacer.pause(relayPacer.nextDelayMillis(portBusy), wakeMark)
        }
    }

    private suspend fun renewLease(registration: RelayRegistration): RelayRegistration {
        val updated = relayClient.renewLease(registration)
        check(stateStore.saveRegistration(updated)) { "Не удалось сохранить продлённую регистрацию" }
        onEvent(GatewayEvent.RegistrationChanged(updated))
        onSupportEvent("Регистрация автомобиля продлена")
        return updated
    }

    private suspend fun recoverPendingPairCommit(registration: RelayRegistration) {
        val pending = stateStore.pendingPairCommit() ?: return
        val commit = relayClient.commitPairing(registration, pending.fingerprint)
        check(stateStore.finalizePairCommit(pending.publicKey, commit.clientLabel)) {
            "Не удалось завершить сохранение ключа компьютера"
        }
        onEvent(GatewayEvent.PairingChanged(null))
        onEvent(GatewayEvent.ClientChanged(ClientState.Waiting, commit.clientLabel, System.currentTimeMillis()))
        onSupportEvent("Незавершённое подключение компьютера восстановлено")
    }

    /**
     * Returns true when the loop must stop: a host-key mismatch or corrupt keys (fail closed), or a
     * key rejection the gate confirmed. An unconfirmed rejection returns false and the loop retries.
     */
    private fun handlePermanent(error: RelayAccessException): Boolean {
        val message = error.message ?: "Relay отклонил ключ автомобиля"
        if (error.registrationRejected) return onRegistrationRejected(message)
        onEvent(GatewayEvent.PermanentFailure(message))
        return true
    }

    @Synchronized
    private fun ensureInnerServer() {
        if (innerServer?.isRunning == true) return
        val server = InnerGatewayServer(
            endpointProvider = endpoint::get,
            innerHostKey = keyStore.innerHostKeyPair,
            store = stateStore,
            pairingCommitter = PairingCommitter { fingerprint ->
                val registration = stateStore.registration()
                    ?: throw RelayAccessException("Автомобиль ещё не зарегистрирован")
                runBlocking(Dispatchers.IO) { relayClient.commitPairing(registration, fingerprint) }
            },
            adbHandshake = adbHandshake,
            onClientChanged = { state, label, at ->
                onEvent(GatewayEvent.ClientChanged(state, label, at))
            },
            onPairingCompleted = { onEvent(GatewayEvent.PairingChanged(null)) },
            onPermanentFailure = { onEvent(GatewayEvent.PermanentFailure(it)) },
            onSupportEvent = onSupportEvent,
        )
        runCatching { server.start() }
            .onSuccess { innerServer = server }
            .onFailure {
                server.close()
                onSupportEvent("Внутренний шлюз будет перезапущен: ${it.message ?: it.javaClass.simpleName}")
            }
    }

    companion object {
        private const val LEASE_RENEW_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
        private const val LEASE_RETRY_INTERVAL_MILLIS = 5L * 60L * 1_000L
        private const val WAITING_POLL_MILLIS = 1_000L
        private const val TUNNEL_POLL_MILLIS = 2_000L
    }
}
