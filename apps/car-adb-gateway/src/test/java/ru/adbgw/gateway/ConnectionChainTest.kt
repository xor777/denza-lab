package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionChainTest {
    private val registration = RelayRegistration(
        deviceId = "0123456789abcdef",
        deviceLabel = "Машина",
        relayDevicePort = 20_000,
        innerHostKey = "ssh-rsa key",
        endpointKind = AdbEndpointKind.SmartSocket,
        endpointHost = "127.0.0.1",
        enabled = true,
        leaseExpiresAtEpochSeconds = 2_000_000_000L,
    )
    private val enrolled = GatewayUiState(
        initialized = true,
        registration = registration,
        adbState = AdbState.Available,
        relayState = RelayState.Connected,
    )

    @Test
    fun `onboarding leaves the server and the computer dim and both hops dark`() {
        val chain = chainModel(GatewayUiState(adbState = AdbState.AuthorizationRequired), 0)
        assertEquals(ChainNode(NodeTone.Attention, "Разрешите ADB"), chain.car)
        assertEquals(ChainNode(NodeTone.Dim, "Нужен код"), chain.server)
        assertEquals(ChainNode(NodeTone.Dim, "Не назначен"), chain.computer)
        assertFalse(chain.carServerLit)
        assertFalse(chain.serverComputerLit)
        assertFalse(chain.pulses)
    }

    @Test
    fun `enrolled car can request approval or check ADB while keeping its existing computer`() {
        val paired = enrolled.copy(clientLabel = "Mac")
        fun actions(state: GatewayUiState) = sceneOf(state, GatewayViewState(), 0).actions
        val needsKey = paired.copy(adbState = AdbState.AuthorizationRequired)
        assertEquals(listOf(SceneAction.RetryAdb to true, SceneAction.Disable to true), actions(needsKey))
        assertEquals(listOf(SceneAction.CheckAdb to true, SceneAction.Disable to true), actions(paired.copy(adbState = AdbState.Unavailable)))
        assertEquals(listOf(SceneAction.Enable to true), actions(needsKey.copy(enabled = false)))
        assertEquals(listOf(SceneAction.Replace to true, SceneAction.Disable to true), actions(paired))
        assertFalse(actions(needsKey.copy(permanentFailure = "identity")).any { it.first == SceneAction.RetryAdb })
    }

    @Test
    fun `an open ADB channel lights both hops and runs the pulses`() {
        val chain = chainModel(enrolled.copy(clientState = ClientState.Active, clientLabel = "Mac"), 0)
        assertEquals(ChainNode(NodeTone.Live, "ADB готов"), chain.car)
        assertEquals(ChainNode(NodeTone.Live, "На связи"), chain.server)
        assertEquals(ChainNode(NodeTone.Live, "Работает"), chain.computer)
        assertTrue(chain.carServerLit)
        assertTrue(chain.serverComputerLit)
        assertTrue(chain.pulses)
    }

    @Test
    fun `a connected computer shows how long it has been on`() {
        val chain = chainModel(
            enrolled.copy(clientState = ClientState.Connected, clientLabel = "Mac", connectedSinceMillis = 1_000),
            1_000 + 754_000,
        )
        assertEquals(ChainNode(NodeTone.Live, "На связи · 12:34"), chain.computer)
        assertTrue(chain.serverComputerLit)
        assertFalse(chain.pulses)
    }

    @Test
    fun `disabled access dims the server and keeps the trusted computer named`() {
        val chain = chainModel(enrolled.copy(enabled = false, relayState = RelayState.Disabled, clientLabel = "Mac"), 0)
        assertEquals(ChainNode(NodeTone.Dim, "Выключено"), chain.server)
        assertEquals(ChainNode(NodeTone.Neutral, "Mac"), chain.computer)
        assertFalse(chain.carServerLit)
    }

    @Test
    fun `a permanent failure breaks the server node`() {
        val chain = chainModel(enrolled.copy(relayState = RelayState.PermanentFailure, permanentFailure = "ключ"), 0)
        assertEquals(ChainNode(NodeTone.Broken, "Остановлено"), chain.server)
        assertFalse(chain.carServerLit)
    }

    @Test
    fun `reconnecting turns the working ring on the server`() {
        val chain = chainModel(enrolled.copy(relayState = RelayState.WaitingForNetwork), 0)
        assertEquals(ChainNode(NodeTone.Working, "Подключение"), chain.server)
        assertEquals(ChainNode(NodeTone.Working, "Проверка ADB"), chainModel(GatewayUiState(), 0).car)
    }

    @Test
    fun `headline follows the chain`() {
        assertEquals("Подключение машины", GatewayUiState().headline)
        assertEquals("Ждём компьютер", enrolled.headline)
        assertEquals("Компьютер на связи", enrolled.copy(clientState = ClientState.Connected).headline)
        assertEquals("Подключаемся к серверу", enrolled.copy(relayState = RelayState.Connecting).headline)
        assertEquals("Доступ выключен", enrolled.copy(enabled = false).headline)
        assertEquals("Доступ остановлен", enrolled.copy(permanentFailure = "x").headline)
        assertEquals("Разрешите ADB на экране", enrolled.copy(adbState = AdbState.AuthorizationRequired).headline)
        assertEquals("ADB недоступен", enrolled.copy(adbState = AdbState.Unavailable).headline)
    }

    @Test
    fun `waiting for local ADB is shown on both screens without network activity`() {
        val state = enrolled.copy(adbState = AdbState.AuthorizationRequired, relayState = RelayState.WaitingForAdb)
        val chain = chainModel(state, 0)
        assertEquals(ChainNode(NodeTone.Dim, "Ждёт ADB"), chain.server)
        assertFalse(chain.carServerLit)
        assertEquals("Ждёт ADB", supportRows(state).toMap()["Соединение"])
    }

    @Test
    fun `request feedback survives passive checks and clears after approval`() {
        val waiting = enrolled.copy(adbState = AdbState.AuthorizationRequired)
        val failed = GatewayStateMachine.reduce(waiting, GatewayEvent.AdbRequestFeedback("Попробуйте ещё раз"))
        val checked = GatewayStateMachine.reduce(failed, GatewayEvent.AdbChanged(AdbState.AuthorizationRequired))
        assertEquals("Попробуйте ещё раз", sceneOf(checked, GatewayViewState(), 0).detail)
        val ready = GatewayStateMachine.reduce(checked, GatewayEvent.AdbChanged(AdbState.Available))
        assertEquals(null, ready.adbRequestHint)
    }

    @Test
    fun `support shows states in words, never enum names`() {
        val rows = supportRows(enrolled.copy(relayState = RelayState.WaitingForNetwork)).toMap()
        assertEquals("adbgw.ru:443", rows["Сервер"])
        assertEquals("Готов", rows["ADB"])
        assertEquals("Ждёт сеть", rows["Соединение"])
        assertEquals("Не назначен", rows["Компьютер"])
        assertEquals("20000", rows["Порт на сервере"])
    }

    @Test
    fun `code input keeps eight upper-case letters and digits`() {
        assertEquals("AB12CD34", normalizeCodeInput("ab12-cd34-ef"))
    }
}
