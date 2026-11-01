package ru.adbgw.gateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat

/**
 * Draws one scene of [GatewayScreen] from a fixed state, with everything that moves held still -
 * for screenshots, debug builds only. No repository, no service, no network:
 *
 * `adb shell am start -S -n ru.adbgw.gateway/.ScreenFixtureActivity --es scene main-active`
 *
 * The scenes are [ScreenFixtures.SCENES]; an unknown name draws `main-connected`.
 */
class ScreenFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val scene = ScreenFixtures.scene(intent.getStringExtra("scene"))
        setContent {
            GatewayTheme {
                CompositionLocalProvider(LocalStillFrame provides true) {
                    GatewayScreen(scene.state, scene.view, ScreenFixtures.NOW, GatewayActions())
                }
            }
        }
    }
}

internal object ScreenFixtures {
    class Scene(val state: GatewayUiState, val view: GatewayViewState = GatewayViewState())

    /** 26 October 2026, 14:32:00 Moscow time. */
    const val NOW = 1_793_014_320_000L
    private const val NOW_SECONDS = NOW / 1_000
    private const val MINUTE = 60_000L

    private val registration = RelayRegistration(
        deviceId = "a3f9c2e17b4d5068",
        deviceLabel = "Denza Z9 GT",
        relayDevicePort = 20_417,
        innerHostKey = "ssh-ed25519 fixture",
        endpointKind = AdbEndpointKind.SmartSocket,
        endpointHost = "127.0.0.1",
        enabled = true,
        leaseExpiresAtEpochSeconds = NOW_SECONDS + 182L * 24 * 3_600,
    )
    private val endpoint = AdbEndpoint(AdbEndpointKind.SmartSocket, "127.0.0.1", 5037, "fixture")

    private val journal = listOf(
        "Компьютер MacBook Pro подключился",
        "Соединение с relay восстановлено",
        "Relay получил актуальный адрес ADB",
        "Сеть изменилась; соединение будет создано заново",
        "Туннель закрыт: Connection reset",
        "Регистрация автомобиля продлена",
        "Компьютер MacBook Pro подтверждён; прежний доступ отозван",
        "Код подключения компьютера создан на 10 минут",
        "Внутренний шлюз слушает только 127.0.0.1:2222",
        "Самовосстановление запущено",
        "Приложение запущено",
        "Регистрация автомобиля завершена",
        "Показан системный запрос ADB-ключа",
        "ADB не найден: connection refused",
    ).mapIndexed { index, message -> SupportEvent(NOW - (index * 7L + 1) * MINUTE - index * 13_000L, message) }

    private val onboarding = GatewayUiState(initialized = true, adbState = AdbState.Checking, relayState = RelayState.WaitingForNetwork)

    private val enrolled = GatewayUiState(
        initialized = true,
        registration = registration,
        enabled = true,
        adbState = AdbState.Available,
        endpoint = endpoint,
        relayState = RelayState.Connected,
        clientState = ClientState.Waiting,
        supportEvents = journal,
    )

    private val waiting = enrolled
    private val connected = enrolled.copy(
        clientState = ClientState.Connected,
        clientLabel = "MacBook Pro",
        connectedSinceMillis = NOW - 12 * MINUTE - 34_000,
        lastActivityMillis = NOW - 3 * MINUTE,
    )
    private val active = connected.copy(clientState = ClientState.Active, lastActivityMillis = NOW - 2_000)

    private fun pairing(expiresIn: Long) = PairingWindow(
        requestId = "fixture-request",
        code = "7KQ4-M2XR",
        expiresAtEpochSeconds = NOW_SECONDS + expiresIn,
    )

    val SCENES: Map<String, Scene> = linkedMapOf(
        "onboarding-checking" to Scene(onboarding),
        "onboarding-adb" to Scene(onboarding.copy(adbState = AdbState.AuthorizationRequired)),
        "onboarding-adb-off" to Scene(onboarding.copy(adbState = AdbState.Unavailable)),
        "onboarding-code" to Scene(
            onboarding.copy(adbState = AdbState.Available, endpoint = endpoint),
            GatewayViewState(code = "K7Q2X"),
        ),
        "onboarding-code-error" to Scene(
            onboarding.copy(adbState = AdbState.Available, endpoint = endpoint, message = "Код не найден или уже использован"),
            GatewayViewState(code = "K7Q2X9MB", enrollmentError = "Код не найден или уже использован"),
        ),
        "main-connecting" to Scene(
            enrolled.copy(
                relayState = RelayState.Connecting,
                clientLabel = "MacBook Pro",
                lastActivityMillis = NOW - 2 * 60 * MINUTE - 5 * MINUTE,
            ),
        ),
        "main-waiting" to Scene(waiting),
        "main-connected" to Scene(connected),
        "main-active" to Scene(active),
        "main-disabled" to Scene(
            connected.copy(
                enabled = false,
                relayState = RelayState.Disabled,
                clientState = ClientState.Waiting,
                connectedSinceMillis = null,
            ),
        ),
        "main-failure" to Scene(
            connected.copy(
                relayState = RelayState.PermanentFailure,
                clientState = ClientState.Waiting,
                connectedSinceMillis = null,
                permanentFailure = "Ключ сервера не совпадает с сохранённым",
                message = "Ключ сервера не совпадает с сохранённым",
            ),
        ),
        "dialog-enroll-warning" to Scene(
            onboarding.copy(adbState = AdbState.Available, endpoint = endpoint),
            GatewayViewState(code = "K7Q2X9MB", overlay = GatewayOverlay.EnrollWarning),
        ),
        "dialog-pair-warning" to Scene(waiting, GatewayViewState(overlay = GatewayOverlay.PairWarning)),
        "dialog-pair-code" to Scene(
            waiting.copy(pairingWindow = pairing(581)),
            GatewayViewState(overlay = GatewayOverlay.PairCode(pairing(581))),
        ),
        "dialog-pair-expired" to Scene(
            waiting.copy(pairingWindow = pairing(-30)),
            GatewayViewState(overlay = GatewayOverlay.PairCode(pairing(-30))),
        ),
        "dialog-disable" to Scene(connected, GatewayViewState(overlay = GatewayOverlay.DisableConfirm)),
        "support" to Scene(connected, GatewayViewState(overlay = GatewayOverlay.Support)),
    )

    fun scene(name: String?): Scene = SCENES[name] ?: SCENES.getValue("main-connected")
}
