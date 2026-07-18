package ru.adbgw.gateway

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

object GatewayRepository {
    private val stateFlow = MutableStateFlow(GatewayUiState())
    private val operationMutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var stateStore: GatewayStateStore? = null
    private var keyStore: SshKeyStore? = null
    private var relayClient: RelayClient? = null
    private var supervisor: GatewaySupervisor? = null

    val state: StateFlow<GatewayUiState> = stateFlow.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        val store = GatewayStateStore(app)
        val keys = SshKeyStore(app)
        val relay = RelayClient(keys)
        appContext = app
        stateStore = store
        keyStore = keys
        relayClient = relay
        dispatch(GatewayEvent.Initialized(store.registration(), store.isEnabled()))
        val pairing = store.pairingWindow()?.takeIf { it.isActive(Instant.now().epochSecond) }
        if (pairing != null) dispatch(GatewayEvent.PairingChanged(pairing))
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
        ).also { supervisor = it }
        current.start()
    }

    fun stopSupervisor() {
        supervisor?.stop()
    }

    fun networkChanged() {
        supervisor?.networkChanged()
    }

    suspend fun prepareAdb(): Boolean = operationMutex.withLock {
        dispatch(GatewayEvent.BusyChanged(true, "Проверяем доступ к системе"))
        dispatch(GatewayEvent.AdbChanged(AdbState.Checking))
        val endpoint = AdbEndpointDetector().detect().getOrElse { error ->
            dispatch(GatewayEvent.AdbChanged(AdbState.Unavailable))
            dispatch(GatewayEvent.BusyChanged(false, "ADB пока недоступен"))
            support("ADB не найден: ${error.message}")
            return false
        }
        val provisioned = AdbProvisioner(requireContext()).authorizeAndConfigure(endpoint)
        val failure = provisioned.exceptionOrNull()
        if (failure is AdbAuthorizationRequiredException) {
            dispatch(GatewayEvent.AdbChanged(AdbState.AuthorizationRequired))
            dispatch(GatewayEvent.BusyChanged(false, failure.message))
            support("Показан системный запрос ADB-ключа")
            return false
        }
        if (failure != null) {
            dispatch(GatewayEvent.AdbChanged(AdbState.Unavailable))
            dispatch(GatewayEvent.BusyChanged(false, "Не удалось проверить доступ к системе"))
            support("Подготовка ADB не удалась: ${failure.message}")
            return false
        }
        dispatch(GatewayEvent.AdbChanged(AdbState.Available, endpoint))
        dispatch(GatewayEvent.BusyChanged(false, "Доступ к системе подтверждён"))
        provisioned.getOrNull().orEmpty().forEach(::support)
        true
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
            val pairing = requireRelay().openPairing(registration)
            check(requireStore().savePairingWindow(pairing)) { "Не удалось сохранить код" }
            dispatch(GatewayEvent.PairingChanged(pairing))
            dispatch(GatewayEvent.BusyChanged(false))
            support("Код подключения компьютера создан на 10 минут")
            pairing
        }.onFailure { error ->
            dispatch(GatewayEvent.BusyChanged(false, readable(error)))
            support("Не удалось создать код: ${error.message ?: error.javaClass.simpleName}")
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
                .onFailure { support("Relay недоступен; локальный tunnel всё равно остановлен") }
        }
        GatewayService.stop(requireContext())
    }

    suspend fun enableRemoteAccess(): Result<Unit> = operationMutex.withLock {
        runCatching {
            val store = requireStore()
            val registration = store.registration() ?: error("Автомобиль ещё не подключён")
            dispatch(GatewayEvent.BusyChanged(true, "Включаем удалённый доступ"))
            requireRelay().setEnabled(registration, true)
            check(store.setEnabled(true)) { "Не удалось сохранить состояние" }
            dispatch(GatewayEvent.EnabledChanged(true))
            dispatch(GatewayEvent.BusyChanged(false))
            support("Удалённый доступ включён пользователем")
            GatewayService.start(requireContext())
        }.onFailure { error ->
            dispatch(GatewayEvent.BusyChanged(false, readable(error)))
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

    private fun readable(error: Throwable): String = when (error) {
        is RelayAccessException -> if (error.permanent) {
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
}
