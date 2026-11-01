package ru.adbgw.gateway

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

object GatewayRepository {
    private val stateFlow = MutableStateFlow(GatewayUiState())
    private val operationMutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var stateStore: GatewayStateStore? = null
    private var keyStore: SshKeyStore? = null
    private var relayClient: RelayClient? = null
    private var supervisor: GatewaySupervisor? = null
    private var rejectionGate: RegistrationRejectionGate? = null
    @Volatile private var approvalWatch: Job? = null

    val state: StateFlow<GatewayUiState> = stateFlow.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        SshdRuntime.initialize(app.filesDir.toPath())
        val store = GatewayStateStore(app)
        val keys = SshKeyStore(app)
        keys.preserveLegacyControlIdentity(store.registration() != null)
        val rotationFailure = if (store.isKeyRotationRequired()) {
            runCatching {
                keys.rotateForReenrollment()
                check(store.finishKeyRotation()) { "Не удалось подтвердить смену ключей" }
            }.exceptionOrNull()
        } else {
            null
        }
        val gate = RegistrationRejectionGate(store)
        val relay = RelayClient(keys, onRegisteredKeyAccepted = gate::recordSuccess)
        appContext = app
        stateStore = store
        rejectionGate = gate
        keyStore = keys
        relayClient = relay
        dispatch(GatewayEvent.Initialized(store.registration(), store.isEnabled()))
        if (rotationFailure != null) {
            dispatch(GatewayEvent.PermanentFailure("Не удалось подготовить новые ключи автомобиля"))
        }
        val pairing = store.pairingWindow()?.takeIf { it.isActive(Instant.now().epochSecond) }
        if (pairing != null) dispatch(GatewayEvent.PairingChanged(pairing))
        else if (store.pairingWindow() != null && store.pendingPairCommit() == null) store.clearPairing()
        store.trustedClientLabel()?.let { label ->
            dispatch(GatewayEvent.ClientChanged(ClientState.Waiting, label, System.currentTimeMillis()))
        }
        support("Приложение запущено")
    }

    fun startSupervisor() {
        val context = requireContext()
        val store = requireStore()
        if (store.registration() == null || !store.isEnabled()) return
        val current = supervisor ?: GatewaySupervisor(
            context = context,
            scope = appScope,
            stateStore = store,
            keyStore = requireKeys(),
            relayClient = requireRelay(),
            onEvent = ::dispatch,
            onSupportEvent = ::support,
            onRegistrationRejected = ::handleRegistrationRejected,
        ).also { supervisor = it }
        current.start()
    }

    fun stopSupervisor() {
        supervisor?.stop()
    }

    fun networkChanged() {
        supervisor?.networkChanged()
    }

    fun networkValidated() {
        supervisor?.networkValidated()
    }

    suspend fun prepareAdb(): Boolean {
        // A new explicit request replaces the previous wait, so the retry button always works.
        approvalWatch?.cancel()
        return operationMutex.withLock {
            dispatch(GatewayEvent.BusyChanged(true, "Проверяем доступ к системе"))
            dispatch(GatewayEvent.AdbChanged(AdbState.Checking))
            val endpoint = AdbEndpointDetector().detect().getOrElse { error ->
                dispatch(GatewayEvent.AdbChanged(AdbState.Unavailable))
                dispatch(GatewayEvent.BusyChanged(false, "ADB пока недоступен"))
                support("ADB не найден: ${error.message}")
                return@withLock false
            }
            val provisioner = AdbProvisioner(requireContext())
            val provisioned = provisioner.authorizeAndConfigure(endpoint)
            if (provisioned.exceptionOrNull() is AdbAuthorizationRequiredException) {
                // The explicit foreground request is the only place the key goes out, and only once.
                dispatch(GatewayEvent.AdbChanged(AdbState.AuthorizationRequired))
                dispatch(GatewayEvent.BusyChanged(false))
                provisioner.requestAuthorization()
                support("Отправлен запрос подтверждения ADB-ключа на экране автомобиля")
                approvalWatch = appScope.launch { awaitAuthorization(provisioner, endpoint) }
                return@withLock false
            }
            applyAdbResult(provisioned, endpoint)
        }
    }

    // After the one key submission, keep checking passively until the person taps Allow or the
    // window closes; no further key is ever sent here, and the screen stays usable meanwhile.
    private suspend fun awaitAuthorization(provisioner: AdbProvisioner, endpoint: AdbEndpoint) {
        val deadline = System.currentTimeMillis() + AUTH_WAIT_TOTAL_MILLIS
        while (System.currentTimeMillis() < deadline) {
            delay(AUTH_WAIT_POLL_MILLIS)
            val latest = provisioner.authorizeAndConfigure(endpoint)
            if (latest.exceptionOrNull() is AdbAuthorizationRequiredException) continue
            operationMutex.withLock { applyAdbResult(latest, endpoint) }
            return
        }
        support("Подтверждение ADB не получено; запрос можно отправить снова")
    }

    private fun applyAdbResult(provisioned: Result<List<String>>, endpoint: AdbEndpoint): Boolean {
        val failure = provisioned.exceptionOrNull()
        if (failure is AdbAuthorizationRequiredException) {
            dispatch(GatewayEvent.AdbChanged(AdbState.AuthorizationRequired))
            dispatch(GatewayEvent.BusyChanged(false))
            support("Ожидается подтверждение ADB на экране автомобиля")
            return false
        }
        if (failure != null) {
            dispatch(GatewayEvent.AdbChanged(AdbState.Unavailable))
            dispatch(GatewayEvent.BusyChanged(false, "Не удалось проверить доступ к системе"))
            support("Подготовка ADB не удалась: ${failure.message}")
            return false
        }
        dispatch(GatewayEvent.AdbChanged(AdbState.Available, endpoint))
        dispatch(GatewayEvent.BusyChanged(false))
        provisioned.getOrNull().orEmpty().forEach(::support)
        return true
    }

    suspend fun enroll(inviteCode: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            val endpoint = state.value.endpoint ?: error("Сначала подтвердите доступ к системе")
            dispatch(GatewayEvent.BusyChanged(true, "Подключаем автомобиль"))
            val label = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android автомобиль" }
            val registration = requireRelay().enroll(inviteCode, label, endpoint)
            check(requireStore().saveRegistration(registration)) { "Не удалось сохранить регистрацию" }
            dispatch(GatewayEvent.RegistrationChanged(registration))
            dispatch(GatewayEvent.BusyChanged(false, "Автомобиль подключён"))
            support("Регистрация автомобиля завершена")
            GatewayService.start(requireContext())
        }.onFailure { error ->
            Log.e("CarAdbGateway", "Registration failed", error)
            dispatch(GatewayEvent.BusyChanged(false, readable(error)))
            support("Регистрация не удалась: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    suspend fun requestPairing(): Result<PairingWindow> = operationMutex.withLock {
        runCatching {
            requireStore().pairingWindow()
                ?.takeIf { it.isActive(Instant.now().epochSecond) }
                ?.let { existing ->
                    dispatch(GatewayEvent.PairingChanged(existing))
                    return@runCatching existing
                }
            val registration = requireStore().registration() ?: error("Автомобиль ещё не подключён")
            dispatch(GatewayEvent.BusyChanged(true, "Создаём код"))
            val requestId = requireStore().getOrCreatePairingRequestId { UUID.randomUUID().toString() }
            val pairing = requireRelay().openPairing(registration, requestId)
            check(requireStore().savePairingWindow(pairing)) { "Не удалось сохранить код" }
            dispatch(GatewayEvent.PairingChanged(pairing))
            dispatch(GatewayEvent.BusyChanged(false))
            support("Код подключения компьютера создан на 10 минут")
            pairing
        }.onFailure { error ->
            if (!handleRegistrationRejection(error)) {
                dispatch(GatewayEvent.BusyChanged(false, readable(error)))
                support("Не удалось создать код: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    suspend fun disableRemoteAccess() = operationMutex.withLock {
        val store = requireStore()
        val registration = store.registration()
        supervisor?.stop()
        store.setEnabled(false)
        dispatch(GatewayEvent.EnabledChanged(false))
        support("Удалённый доступ выключен пользователем")
        if (registration != null) {
            runCatching { requireRelay().setEnabled(registration, false) }
                .onSuccess { updated ->
                    requireStore().saveRegistration(updated)
                    dispatch(GatewayEvent.RegistrationChanged(updated))
                }
                .onFailure { error ->
                    if (!handleRegistrationRejection(error)) {
                        support("Relay недоступен; локальный tunnel всё равно остановлен")
                    }
                }
        }
        GatewayService.stop(requireContext())
    }

    suspend fun enableRemoteAccess(): Result<Unit> = operationMutex.withLock {
        runCatching {
            val store = requireStore()
            val registration = store.registration() ?: error("Автомобиль ещё не подключён")
            dispatch(GatewayEvent.BusyChanged(true, "Включаем удалённый доступ"))
            val updated = requireRelay().setEnabled(registration, true)
            check(store.saveRegistration(updated)) { "Не удалось сохранить регистрацию" }
            check(store.setEnabled(true)) { "Не удалось сохранить состояние" }
            dispatch(GatewayEvent.RegistrationChanged(updated))
            dispatch(GatewayEvent.EnabledChanged(true))
            dispatch(GatewayEvent.BusyChanged(false))
            support("Удалённый доступ включён пользователем")
            GatewayService.start(requireContext())
        }.onFailure { error ->
            if (!handleRegistrationRejection(error)) {
                dispatch(GatewayEvent.BusyChanged(false, readable(error)))
            }
        }
    }

    private fun dispatch(event: GatewayEvent) {
        stateFlow.update { GatewayStateMachine.reduce(it, event) }
    }

    private fun support(message: String) {
        val entry = SupportEvent(System.currentTimeMillis(), message)
        stateFlow.update { current ->
            current.copy(supportEvents = (listOf(entry) + current.supportEvents).take(40))
        }
    }

    @Synchronized
    private fun handleRegistrationExpired(message: String) {
        val store = requireStore()
        if (store.registration() == null) return
        supervisor?.stop()
        if (!store.clearForReenrollment()) {
            dispatch(GatewayEvent.PermanentFailure("Не удалось очистить истёкшую регистрацию"))
            return
        }
        dispatch(GatewayEvent.RegistrationExpired(message))
        GatewayService.stop(requireContext())
        runCatching { requireKeys().rotateForReenrollment() }
            .onFailure {
                dispatch(GatewayEvent.PermanentFailure("Не удалось подготовить новые ключи автомобиля"))
                return
            }
        if (!store.finishKeyRotation()) {
            dispatch(GatewayEvent.PermanentFailure("Не удалось подтвердить смену ключей автомобиля"))
            return
        }
        support("Истёкшая регистрация удалена; для нового подключения созданы новые ключи")
    }

    /**
     * The relay definitively refused the registered vehicle key. Drops the registration only once
     * [RegistrationRejectionGate] confirms it; until then the registration stays and the caller
     * retries. Returns true when the registration is gone (or could not be kept), so the caller
     * stops.
     */
    @Synchronized
    private fun handleRegistrationRejected(message: String): Boolean {
        if (requireStore().registration() == null) return true
        when (val verdict = requireGate().recordRejection()) {
            is RejectionVerdict.Unconfirmed -> {
                support("Сервер не принял ключ машины; повторим (${verdict.count} из ${verdict.required})")
                return false
            }
            RejectionVerdict.Confirmed -> {
                handleRegistrationExpired(message)
                return true
            }
        }
    }

    private fun handleRegistrationRejection(error: Throwable): Boolean {
        if (error !is RelayAccessException || !error.registrationRejected) return false
        return handleRegistrationRejected(error.message ?: "Регистрация автомобиля истекла")
    }

    private fun readable(error: Throwable): String = when (error) {
        is RelayAccessException -> if (error.registrationRejected) {
            "Сервер не принял ключ машины; повторим позже"
        } else if (error.permanent) {
            error.message ?: "Подключение остановлено для безопасности"
        } else {
            error.message ?: "Relay временно недоступен"
        }
        else -> error.message ?: "Не удалось выполнить действие"
    }

    private fun requireContext(): Context = checkNotNull(appContext) { "Repository is not initialized" }
    private fun requireStore(): GatewayStateStore = checkNotNull(stateStore)
    private fun requireKeys(): SshKeyStore = checkNotNull(keyStore)
    private fun requireRelay(): RelayClient = checkNotNull(relayClient)
    private fun requireGate(): RegistrationRejectionGate = checkNotNull(rejectionGate)

    private const val AUTH_WAIT_POLL_MILLIS = 3_000L
    private const val AUTH_WAIT_TOTAL_MILLIS = 2L * 60L * 1_000L
}
