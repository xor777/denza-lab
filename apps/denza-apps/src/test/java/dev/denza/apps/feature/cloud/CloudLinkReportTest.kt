package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudLinkReportTest {

    private val wifi = CloudNetworkReading(validated = true, wifi = true, cellular = false, simOperator = null)

    private fun rows(
        enabled: Boolean = true,
        tile: String = "На связи",
        failure: String? = null,
        network: CloudNetworkReading = wifi,
        car: CloudCarState? = CloudCarState(
            profile = CloudLinkProtocol.WIFI_PROFILE,
            buildProfile = CloudLinkProtocol.STOCK_PROFILE,
            apn1Disabled = true,
            cellular = false,
            cloudPid = "113",
            connected = true,
            wifiRetained = true,
        ),
        readAtMs: Long? = NOW - 12_000L,
        adapter: CloudLinkReport.Adapter? = CloudLinkReport.Adapter(
            gate = "OPENED",
            attempts = 1,
            lastReadyAtMs = NOW - 4 * 60_000L,
            disconnectedSinceMs = null,
            nextReadyAtMs = null,
        ),
        busy: Boolean = false,
        automaticFailure: String? = null,
        remembered: String? = null,
    ): Map<String, String> =
        CloudLinkReport.rows(
            enabled, tile, failure, network, car, readAtMs, adapter, busy, NOW,
            automaticFailure = automaticFailure,
            remembered = remembered,
        ).toMap()

    /** The press's refusal and the adapter's own failed pass clear differently, so both are told. */
    @Test
    fun `a failed pass of the adapter's own is on the page beside a refused press`() {
        assertEquals(
            "автоматика: Нет ответа: IOException",
            rows(automaticFailure = "Нет ответа: IOException")["Отказ"],
        )
        assertEquals(
            "Не включилось; автоматика: Нет ответа: IOException",
            rows(failure = "Не включилось", automaticFailure = "Нет ответа: IOException")["Отказ"],
        )
    }

    @Test
    fun `a connected link reads every fact the app has, on one screen`() {
        assertEquals(
            listOf(
                "Связь" to "включена, плитка «На связи»",
                "Отказ" to "нет",
                "Сеть" to "Wi-Fi, интернет проверен",
                "Wi-Fi / сотовая" to "да / нет",
                "SIM" to "нет",
                "Профиль" to "double_apn, сборки triple_apn, APN1 выключен",
                "Сотовая BYD" to "нет",
                "APN2" to "не прочитано",
                "Сокет облака" to "не прочитано",
                "cloudmanager" to "PID 113, TCP 1",
                "Wi-Fi во сне" to "да",
                "Адаптер" to "OPENED (оценка приложения), попыток 1",
                "Последний ready" to "4 мин назад",
                "Без связи" to "—",
                "Прочитано" to "12 с назад",
            ),
            rows().toList(),
        )
    }

    @Test
    fun `a car never read says so instead of inventing an answer`() {
        val r = rows(enabled = false, tile = "Выключено", car = null, readAtMs = null, adapter = null)
        assertEquals("выключена, плитка «Выключено»", r["Связь"])
        assertEquals("?, сборки ?, APN1 ?", r["Профиль"])
        assertEquals("—", r["Последний ready"])
        assertEquals("PID —, TCP ?", r["cloudmanager"])
        assertEquals("—", r["Адаптер"])
        assertEquals("ещё не было", r["Прочитано"])
    }

    @Test
    fun `operator metadata does not turn validated mobile internet into no network`() {
        val roaming = CloudNetworkReading(validated = true, wifi = false, cellular = true, simOperator = "46001")
        val r = rows(network = roaming)
        assertEquals("46001, MCC 460", r["SIM"])
        assertEquals("мобильный, интернет проверен", r["Сеть"])
        assertEquals("нет / да", r["Wi-Fi / сотовая"])
        val local = CloudNetworkReading(validated = true, wifi = false, cellular = true, simOperator = "25001")
        assertEquals("мобильный, интернет проверен", rows(network = local)["Сеть"])
        assertEquals("25001", rows(network = local)["SIM"])
    }

    @Test
    fun `a refused press and a held-back ready are both on the page`() {
        val r = rows(
            tile = "Не включилось",
            failure = "Не включилось",
            adapter = CloudLinkReport.Adapter(
                gate = "CLOSED",
                attempts = 2,
                lastReadyAtMs = NOW - 60_000L,
                disconnectedSinceMs = NOW - 2 * 3_600_000L - 5 * 60_000L,
                nextReadyAtMs = NOW + 9 * 60_000L,
            ),
            busy = true,
        )
        assertEquals("Не включилось", r["Отказ"])
        assertEquals("CLOSED (оценка приложения), попыток 2", r["Адаптер"])
        assertEquals("1 мин назад, повтор не раньше чем через 9 мин", r["Последний ready"])
        assertEquals("2 ч 5 мин", r["Без связи"])
        assertEquals("12 с назад, идёт запись", r["Прочитано"])
    }

    /**
     * Which path the client is on, and what the adapter owes: what tells the next failure on a car
     * with a SIM adapter from one on Wi-Fi.
     */
    @Test
    fun `the page says the car's data APN, the socket's interface and the profile owed`() {
        val read = CloudCarState(
            profile = CloudLinkProtocol.WIFI_PROFILE, apn1Disabled = true, connected = true,
            apn2State = "connect", apn2Interface = "ccmni1", cloudInterface = "wlan0",
        )
        val r = rows(car = read, remembered = CloudLinkReport.remembered(CloudProfileMemory.Owed("triple_apn")))
        assertEquals("connect, ccmni1", r["APN2"])
        assertEquals("wlan0", r["Сокет облака"])
        assertEquals("triple_apn", r["Вернуть профиль"])
        assertEquals("disconnected", rows(car = read.copy(apn2State = "disconnected", apn2Interface = null))["APN2"])

        val unread = rows(car = null)
        assertEquals("не прочитано", unread["APN2"])
        assertEquals("не прочитано", unread["Сокет облака"])
        assertEquals("—", rows(remembered = CloudLinkReport.remembered(CloudProfileMemory.Clear))["Вернуть профиль"])
        assertEquals(
            "не запомнен, вернётся профиль сборки",
            rows(remembered = CloudLinkReport.remembered(CloudProfileMemory.Legacy))["Вернуть профиль"],
        )
    }

    private companion object {
        const val NOW = 10_000_000L
    }
}
