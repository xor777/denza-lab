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
    ): Map<String, String> =
        CloudLinkReport.rows(enabled, tile, failure, network, car, readAtMs, adapter, busy, NOW).toMap()

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

    private companion object {
        const val NOW = 10_000_000L
    }
}
