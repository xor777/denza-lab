package ru.adbgw.gateway

const val RELAY_HOST = "adbgw.ru"
const val RELAY_SSH_PORT = 443
const val RELAY_HOST_FINGERPRINT = "SHA256:w02E2cvN65HmjeC6h9aLY/6zovde3nvqorQPYtNRp6c"
const val INNER_SSH_HOST = "127.0.0.1"
const val INNER_SSH_PORT = 2222
const val INNER_SSH_USER = "cag"

enum class AdbEndpointKind(val relayValue: String) {
    SmartSocket("smart"),
    RawAdbd("raw"),
}

data class AdbEndpoint(
    val kind: AdbEndpointKind,
    val host: String,
    val port: Int,
    val detail: String,
)

data class RelayRegistration(
    val deviceId: String,
    val deviceLabel: String,
    val relayDevicePort: Int,
    val innerHostKey: String,
    val endpointKind: AdbEndpointKind?,
    val endpointHost: String?,
    val enabled: Boolean,
)

data class PairingWindow(
    val requestId: String,
    val code: String,
    val expiresAtEpochSeconds: Long,
    val attemptsRemaining: Int = 5,
) {
    fun isActive(nowEpochSeconds: Long): Boolean = nowEpochSeconds < expiresAtEpochSeconds
}

data class PairCommitResult(
    val clientLabel: String,
    val replacedFingerprint: String?,
)

enum class AdbState {
    Checking,
    AuthorizationRequired,
    Available,
    Unavailable,
}

enum class RelayState {
    Disabled,
    WaitingForNetwork,
    Connecting,
    Connected,
    PermanentFailure,
}

enum class ClientState {
    Waiting,
    Connected,
    Active,
}

data class SupportEvent(
    val timestampMillis: Long,
    val message: String,
)

data class GatewayUiState(
    val initialized: Boolean = false,
    val registration: RelayRegistration? = null,
    val enabled: Boolean = true,
    val adbState: AdbState = AdbState.Checking,
    val endpoint: AdbEndpoint? = null,
    val relayState: RelayState = RelayState.WaitingForNetwork,
    val clientState: ClientState = ClientState.Waiting,
    val clientLabel: String? = null,
    val connectedSinceMillis: Long? = null,
    val lastActivityMillis: Long? = null,
    val pairingWindow: PairingWindow? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val permanentFailure: String? = null,
    val supportEvents: List<SupportEvent> = emptyList(),
) {
    val isEnrolled: Boolean get() = registration != null

    val headline: String
        get() = when {
            permanentFailure != null -> "Нужна помощь"
            !isEnrolled -> "Подготовка подключения"
            !enabled -> "Удалённый доступ выключен"
            adbState == AdbState.AuthorizationRequired -> "Подтвердите доступ на экране"
            adbState != AdbState.Available -> "Проверяем доступ к системе"
            relayState == RelayState.Connected && clientState == ClientState.Active -> "Удалённый компьютер работает"
            relayState == RelayState.Connected && clientState == ClientState.Connected -> "Компьютер подключён, ожидает"
            relayState == RelayState.Connected -> "Ожидание компьютера"
            else -> "Восстанавливаем соединение"
        }
}

sealed interface GatewayEvent {
    data class Initialized(val registration: RelayRegistration?, val enabled: Boolean) : GatewayEvent
    data class AdbChanged(val state: AdbState, val endpoint: AdbEndpoint? = null) : GatewayEvent
    data class RelayChanged(val state: RelayState) : GatewayEvent
    data class ClientChanged(val state: ClientState, val label: String?, val atMillis: Long) : GatewayEvent
    data class PairingChanged(val window: PairingWindow?) : GatewayEvent
    data class EnabledChanged(val enabled: Boolean) : GatewayEvent
    data class RegistrationChanged(val registration: RelayRegistration) : GatewayEvent
    data class BusyChanged(val busy: Boolean, val message: String? = null) : GatewayEvent
    data class PermanentFailure(val message: String) : GatewayEvent
}

object GatewayStateMachine {
    fun reduce(current: GatewayUiState, event: GatewayEvent): GatewayUiState = when (event) {
        is GatewayEvent.Initialized -> current.copy(
            initialized = true,
            registration = event.registration,
            enabled = event.enabled,
            relayState = if (event.enabled) RelayState.WaitingForNetwork else RelayState.Disabled,
        )
        is GatewayEvent.AdbChanged -> current.copy(adbState = event.state, endpoint = event.endpoint)
        is GatewayEvent.RelayChanged -> current.copy(relayState = event.state)
        is GatewayEvent.ClientChanged -> current.copy(
            clientState = event.state,
            clientLabel = event.label ?: current.clientLabel,
            connectedSinceMillis = when (event.state) {
                ClientState.Waiting -> null
                else -> current.connectedSinceMillis ?: event.atMillis
            },
            lastActivityMillis = if (event.state == ClientState.Active) event.atMillis else current.lastActivityMillis,
        )
        is GatewayEvent.PairingChanged -> current.copy(pairingWindow = event.window)
        is GatewayEvent.EnabledChanged -> current.copy(
            enabled = event.enabled,
            relayState = if (event.enabled) RelayState.WaitingForNetwork else RelayState.Disabled,
            clientState = ClientState.Waiting,
            connectedSinceMillis = null,
        )
        is GatewayEvent.RegistrationChanged -> current.copy(registration = event.registration)
        is GatewayEvent.BusyChanged -> current.copy(busy = event.busy, message = event.message)
        is GatewayEvent.PermanentFailure -> current.copy(
            relayState = RelayState.PermanentFailure,
            permanentFailure = event.message,
            message = event.message,
            busy = false,
        )
    }
}
