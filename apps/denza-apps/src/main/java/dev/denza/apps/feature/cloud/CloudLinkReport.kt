package dev.denza.apps.feature.cloud

/**
 * The cloud link's line in the service report: everything this app knows about the link, as the
 * technical page's «Облако» section.
 *
 * The link is tested on cars nobody here can reach - mobile data on a local SIM most of all - so the
 * section is the whole state, and one screenshot of it has to be enough: what the driver asked for
 * and what the tile says, why a press did not take, the network the rule reads and the facts it
 * reads it from, the SIM's operator code (never its identity), what the car last said and how long
 * ago, and where the adapter is in its own clock. Related facts share a row so the section fits
 * one screen.
 *
 * The rows of the report's «Облако» section, in the order the page shows them: what the driver
 * would look for first on top.
 */
object CloudLinkReport {

    /** What the adapter believes about the gate, and its clocks (elapsedRealtime). */
    data class Adapter(
        val gate: String,
        val attempts: Int,
        val lastReadyAtMs: Long?,
        val disconnectedSinceMs: Long?,
        val nextReadyAtMs: Long?,
    )

    fun rows(
        enabled: Boolean,
        tile: String,
        failure: String?,
        network: CloudNetworkReading,
        car: CloudCarState?,
        readAtMs: Long?,
        adapter: Adapter?,
        busy: Boolean,
        nowMs: Long,
    ): List<Pair<String, String>> = listOf(
        "Связь" to "${if (enabled) "включена" else "выключена"}, плитка «$tile»",
        "Отказ" to (failure ?: "нет"),
        "Сеть" to "${network.kind.label}, интернет ${if (network.validated) "проверен" else "не проверен"}",
        "Wi-Fi / сотовая" to "${yesNo(network.wifi)} / ${yesNo(network.cellular)}",
        "SIM" to sim(network.simOperator),
        "Профиль" to "${car?.profile ?: UNKNOWN}, сборки ${car?.buildProfile ?: UNKNOWN}, " +
            "APN1 ${apn1(car?.apn1Disabled)}",
        "Сотовая BYD" to yesNo(car?.cellular),
        "cloudmanager" to "PID ${car?.cloudPid ?: NONE}, TCP ${car?.connected?.let { if (it) 1 else 0 } ?: UNKNOWN}",
        "Wi-Fi во сне" to yesNo(car?.wifiRetained),
        "Адаптер" to (adapter?.let { "${it.gate} (оценка приложения), попыток ${it.attempts}" } ?: NONE),
        "Последний ready" to ready(adapter, nowMs),
        "Без связи" to (adapter?.disconnectedSinceMs?.let { span(nowMs - it) } ?: NONE),
        "Прочитано" to (readAtMs?.let { "${span(nowMs - it)} назад" } ?: "ещё не было") +
            if (busy) ", идёт запись" else "",
    )

    /** When the last «ready» went, and how long the backoff holds the next one back. */
    private fun ready(adapter: Adapter?, nowMs: Long): String {
        val last = adapter?.lastReadyAtMs ?: return NONE
        val next = adapter.nextReadyAtMs?.takeIf { it > nowMs }
            ?.let { ", повтор не раньше чем через ${span(it - nowMs)}" }
            .orEmpty()
        return "${span(nowMs - last)} назад$next"
    }

    private fun apn1(disabled: Boolean?): String = when (disabled) {
        true -> "выключен"
        false -> "включён"
        null -> UNKNOWN
    }

    private fun sim(operator: String?): String {
        val code = operator?.ifBlank { null } ?: return "нет"
        return if (CloudNetwork.chineseSim(code)) "$code, MCC 460" else code
    }

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "да"
        false -> "нет"
        null -> UNKNOWN
    }

    /** «12 с», «4 мин», «2 ч 5 мин»: to the second while that matters, then to the minute. */
    internal fun span(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return when {
            seconds < 60 -> "$seconds с"
            seconds < 3600 -> "${seconds / 60} мин"
            else -> "${seconds / 3600} ч ${seconds % 3600 / 60} мин"
        }
    }

    private const val UNKNOWN = "?"
    private const val NONE = "—"
}
