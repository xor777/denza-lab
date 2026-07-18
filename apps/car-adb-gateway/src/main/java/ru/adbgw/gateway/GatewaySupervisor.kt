package ru.adbgw.gateway

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class GatewaySupervisor(
    context: Context,
    private val scope: CoroutineScope,
    private val stateStore: GatewayStateStore,
    private val keyStore: SshKeyStore,
    private val relayClient: RelayClient,
    private val onEvent: (GatewayEvent) -> Unit,
    private val onSupportEvent: (String) -> Unit,
    private val onRegistrationExpired: (String) -> Unit,
) {
    private val detector = AdbEndpointDetector()
    private val provisioner = AdbProvisioner(context.applicationContext)
    private val endpoint = AtomicReference<AdbEndpoint?>(null)
    private var adbJob: Job? = null
    private var relayJob: Job? = null
    private var innerServer: InnerGatewayServer? = null
    private var tunnel: RelayTunnel? = null
    private val relayBackoff = RetryBackoff()
    @Volatile private var networkGeneration: Long = 0

    @Synchronized
    fun start() {
        if (adbJob?.isActive == true || relayJob?.isActive == true) return
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
        tunnel?.close()
        tunnel = null
        innerServer?.close()
        innerServer = null
        endpoint.set(null)
        onEvent(GatewayEvent.ClientChanged(ClientState.Waiting, null, System.currentTimeMillis()))
    }

    @Synchronized
    fun networkChanged() {
        networkGeneration += 1
        relayBackoff.reset()
        tunnel?.close()
        tunnel = null
        onSupportEvent("Сеть изменилась; соединение будет создано заново")
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
                    if (error.permanent) {
                        handlePermanent(error)
                        return
                    }
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
        while (scope.isActive && stateStore.isEnabled()) {
            onEvent(GatewayEvent.RelayChanged(RelayState.Connecting))
            try {
                recoverPendingPairCommit(registration)
                if (System.currentTimeMillis() >= nextLeaseRenewalAtMillis) {
                    registration = renewLease(registration)
                    nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RENEW_INTERVAL_MILLIS
                }
                if (endpoint.get() == null || innerServer?.isRunning != true) {
                    onEvent(GatewayEvent.RelayChanged(RelayState.WaitingForNetwork))
                    delay(1_000)
                    continue
                }
                val opened = relayClient.openTunnel(registration) { error ->
                    if (error != null) {
                        onSupportEvent("Туннель закрыт: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                tunnel = opened
                relayBackoff.reset()
                onEvent(GatewayEvent.RelayChanged(RelayState.Connected))
                onSupportEvent("Соединение с relay восстановлено")
                while (scope.isActive && stateStore.isEnabled() && opened.isOpen) {
                    if (System.currentTimeMillis() >= nextLeaseRenewalAtMillis) {
                        try {
                            registration = renewLease(registration)
                            nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RENEW_INTERVAL_MILLIS
                        } catch (error: RelayAccessException) {
                            if (error.permanent) {
                                opened.close()
                                handlePermanent(error)
                                return
                            }
                            nextLeaseRenewalAtMillis = System.currentTimeMillis() + LEASE_RETRY_INTERVAL_MILLIS
                            onSupportEvent("Продление регистрации будет повторено через пять минут")
                        }
                    }
                    delay(2_000)
                }
                opened.close()
                if (tunnel === opened) tunnel = null
            } catch (error: RelayAccessException) {
                if (error.permanent) {
                    handlePermanent(error)
                    return
                }
                onEvent(GatewayEvent.RelayChanged(RelayState.WaitingForNetwork))
                onSupportEvent("Relay временно недоступен")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onEvent(GatewayEvent.PermanentFailure("Не удалось сохранить состояние подключения"))
                onSupportEvent("Локальная ошибка состояния: ${error.message ?: error.javaClass.simpleName}")
                return
            }

            if (observedGeneration != networkGeneration) {
                observedGeneration = networkGeneration
                relayBackoff.reset()
                continue
            }
            delay(relayBackoff.nextDelayMillis())
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

    private fun handlePermanent(error: RelayAccessException) {
        val message = error.message ?: "Relay отклонил ключ автомобиля"
        if (error.registrationRejected) onRegistrationExpired(message)
        else onEvent(GatewayEvent.PermanentFailure(message))
    }

    @Synchronized
    private fun ensureInnerServer() {
        if (innerServer?.isRunning == true) return
        val server = InnerGatewayServer(
            endpointProvider = endpoint::get,
            keyStore = keyStore,
            stateStore = stateStore,
            registrationProvider = stateStore::registration,
            relayClient = relayClient,
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
    }
}
