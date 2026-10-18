package dev.denza.apps.feature.adb

import dev.denza.apps.feature.cloud.CloudLinkReport
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The automatic restore in words: its page in «Сервис», its row there, and its section of
 * «Технические сведения».
 *
 * The page answers what it is opened for - is there access, did the car close the port after the
 * last boot, and did the app open it again - in a sentence and three rows. It used to be eight
 * readings in machine words, two of them one reading, all of them about the last check, which the
 * opening of «Сервис» had itself just made. What support reads is the technical page's, once.
 */
object AdbRestoreReport {
    const val TITLE = "Восстановление ADB"
    const val SWITCH = "Восстанавливать автоматически"
    const val SWITCH_SUMMARY = "Если после перезагрузки машина закроет доступ, приложение вернёт его через Wi-Fi"
    const val BOOT_SECTION = "Последняя перезагрузка"

    enum class Tone { QUIET, ATTENTION, BROKEN }

    data class Line(val text: String, val tone: Tone = Tone.QUIET)

    /** The page's sentence; null when the switch is off, which says it all. */
    fun status(s: AdbRestoreSnapshot): Line? = when {
        !s.enabled -> null
        s.state == AdbRestoreState.Unsupported -> Line("Восстановление работает с Android 11")
        // Not looked at in this process: the snapshot's NotNeeded is its default, not an answer.
        s.lastTrigger == null -> Line("Проверяю доступ…")
        else -> when (s.state) {
            AdbRestoreState.NotNeeded, is AdbRestoreState.Restored ->
                if (s.boot?.outcome == AdbBootOutcome.RESTORED) Line("Доступ есть: после перезагрузки его вернуло приложение")
                else Line("Доступ есть, восстанавливать не нужно")
            AdbRestoreState.Connecting -> Line("Доступа нет, возвращаю его через Wi-Fi")
            AdbRestoreState.WaitingWifi ->
                Line("Доступа нет. Вернётся, как только машина подключится к Wi-Fi", Tone.ATTENTION)
            AdbRestoreState.NeedsDialog ->
                if (s.retryBudgetExhausted) Line("Доступа нет: система не включает отладку по Wi-Fi", Tone.BROKEN)
                else Line("Доступа нет. Жду, пока система включит отладку по Wi-Fi", Tone.ATTENTION)
            AdbRestoreState.NeedsActivation ->
                Line("Доступа нет. Через Wi-Fi его не вернуть: машина не доверяет приложению", Tone.BROKEN)
            is AdbRestoreState.Failed -> Line("Доступ вернуть не удалось. Приложение попробует ещё раз", Tone.BROKEN)
            AdbRestoreState.Disabled, AdbRestoreState.Unsupported -> null
        }
    }

    /** «Последняя перезагрузка»: when, what the first look found, what the restore did. */
    fun bootRows(s: AdbRestoreSnapshot, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<String, String>> {
        val boot = s.boot ?: return emptyList()
        val clock = Clock(nowMs, zone)
        val restore = when (boot.outcome) {
            null -> when (boot.look) {
                AdbBootLook.OPEN -> "не понадобилось"
                null -> "ещё не было"
                else -> "идёт"
            }
            AdbBootOutcome.CONNECTING -> "идёт"
            AdbBootOutcome.WAITING_WIFI -> "ждёт Wi-Fi"
            AdbBootOutcome.NEEDS_DIALOG -> "ждёт разрешения системы"
            AdbBootOutcome.NEEDS_ACTIVATION -> "невозможно"
            AdbBootOutcome.FAILED -> "не вышло ${clock.at(boot.outcomeAtMs)}"
            AdbBootOutcome.RESTORED -> "удалось ${clock.at(boot.outcomeAtMs)}${took(boot)}"
            AdbBootOutcome.RETURNED -> "доступ появился ${clock.at(boot.outcomeAtMs)}"
        }
        return listOf(
            "Когда" to clock.day(boot.bootAtMs),
            "Доступ после неё" to when (boot.look) {
                AdbBootLook.OPEN -> "был сразу"
                AdbBootLook.CLOSED -> "был закрыт"
                AdbBootLook.REFUSED -> "ключ не принят"
                null -> "ещё не проверен"
            },
            "Восстановление" to restore,
        )
    }

    /** The row's value on «Сервис»: what happened, not what the function is. */
    fun summary(s: AdbRestoreSnapshot, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Line {
        val boot = s.boot
        return when {
            !s.enabled -> Line("Выключено")
            else -> when (s.state) {
                AdbRestoreState.Unsupported -> Line("Недоступно на этой версии Android")
                AdbRestoreState.WaitingWifi -> Line("Ждёт Wi-Fi", Tone.ATTENTION)
                AdbRestoreState.NeedsDialog ->
                    if (s.retryBudgetExhausted) Line("Система не включает отладку по Wi-Fi", Tone.BROKEN)
                    else Line("Ждёт разрешения системы", Tone.ATTENTION)
                AdbRestoreState.NeedsActivation -> Line("Не может вернуть доступ", Tone.BROKEN)
                is AdbRestoreState.Failed -> Line("Не вернуло доступ", Tone.BROKEN)
                AdbRestoreState.Connecting -> Line("Возвращает доступ…")
                else -> when {
                    boot?.outcome == AdbBootOutcome.RESTORED ->
                        Line("Вернуло доступ ${Clock(nowMs, zone).moment(boot.outcomeAtMs)}")
                    boot?.outcome == AdbBootOutcome.RETURNED ->
                        Line("Доступ появился ${Clock(nowMs, zone).moment(boot.outcomeAtMs)}")
                    boot?.look == AdbBootLook.OPEN -> Line("После перезагрузки не понадобилось")
                    else -> Line("Включено")
                }
            }
        }
    }

    /**
     * The section of «Технические сведения», ahead of the port's readings. One row a fact: the
     * state and the last outcome were the same words twice, and the Wi-Fi row named a network the
     * system does not name to an app.
     */
    fun technicalRows(s: AdbRestoreSnapshot, zone: TimeZone = TimeZone.getDefault()): List<Pair<String, String>> {
        fun stamp(ms: Long, pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }.format(Date(ms))
        val boot = s.boot
        val check = when {
            s.lastTrigger != null && s.lastOutcomeAtMs != null ->
                "${stateWords(s.state)} · ${stamp(s.lastOutcomeAtMs, "HH:mm:ss")} · ${triggerWords(s.lastTrigger)}"
            // From before this process: the stored outcome, as it was stored.
            s.lastOutcome != null -> listOfNotNull(s.lastOutcome, s.lastOutcomeAtMs?.let { stamp(it, "dd.MM HH:mm:ss") })
                .joinToString(" · ")
            else -> "ещё не было"
        }
        return listOf(
            "Включено" to if (s.enabled) "да" else "нет",
            "Перезагрузка" to (boot?.let { stamp(it.bootAtMs, "dd.MM HH:mm") + (it.bootCount?.let { n -> " · №$n" } ?: "") } ?: "—"),
            "После неё" to (boot?.let { afterBoot(it) { ms -> stamp(ms, "HH:mm:ss") } } ?: "—"),
            "Последняя проверка" to check,
            "TLS-порт" to (s.tlsPort?.toString() ?: "—"),
            "Сетевой диалог" to (s.autoAllow ?: "ещё не было"),
            "Повторы" to if (s.retryBudgetExhausted) "бюджет на этой сети исчерпан" else "в пределах бюджета",
        )
    }

    private fun afterBoot(boot: AdbBootRecord, time: (Long) -> String): String {
        val look = when (boot.look) {
            AdbBootLook.OPEN -> "порт открыт"
            AdbBootLook.CLOSED -> "порт закрыт"
            AdbBootLook.REFUSED -> "ключ не принят"
            null -> "не проверено"
        }
        val outcome = when (boot.outcome) {
            null -> return look
            AdbBootOutcome.CONNECTING -> "восстанавливается"
            AdbBootOutcome.WAITING_WIFI -> "ждёт Wi-Fi"
            AdbBootOutcome.NEEDS_DIALOG -> "ждёт разрешения системы"
            AdbBootOutcome.NEEDS_ACTIVATION -> "восстановить нельзя"
            AdbBootOutcome.FAILED -> "не вышло"
            AdbBootOutcome.RESTORED -> "восстановлен"
            AdbBootOutcome.RETURNED -> "доступ появился"
        }
        val took = if (boot.outcome == AdbBootOutcome.RESTORED) took(boot) else ""
        return "$look → $outcome ${time(boot.outcomeAtMs)}$took"
    }

    private fun took(boot: AdbBootRecord): String =
        if (boot.closedAtMs > 0 && boot.outcomeAtMs >= boot.closedAtMs) ", за ${CloudLinkReport.span(boot.outcomeAtMs - boot.closedAtMs)}"
        else ""

    private fun stateWords(state: AdbRestoreState): String = when (state) {
        AdbRestoreState.NotNeeded -> "доступ есть"
        is AdbRestoreState.Restored -> "доступ возвращён"
        AdbRestoreState.Connecting -> "возвращает доступ"
        AdbRestoreState.WaitingWifi -> "ждёт Wi-Fi"
        AdbRestoreState.NeedsDialog -> "ждёт разрешения системы"
        AdbRestoreState.NeedsActivation -> "машина не доверяет приложению"
        is AdbRestoreState.Failed -> "не вышло: ${state.reason}"
        AdbRestoreState.Disabled -> "выключено"
        AdbRestoreState.Unsupported -> "Android ниже 11"
    }

    /** What made the check, in the words of what happened: the hints are the manager's names. */
    private fun triggerWords(trigger: String): String = when (trigger) {
        "process-start" -> "запуск приложения"
        "screen-on" -> "включение экрана"
        "wifi" -> "Wi-Fi"
        "wifi-lost" -> "потеря Wi-Fi"
        "watchdog" -> "проверка функций"
        "recovery" -> "восстановление функций"
        AdbRestoreManager.SETTINGS -> "открыт «Сервис»"
        AdbRestoreManager.SWITCH -> "включён переключатель"
        "dialog-allowed" -> "разрешение отладки"
        "retry" -> "повтор"
        else -> trigger
    }

    /** Times the way the page says them: from today, yesterday or a date. */
    private class Clock(nowMs: Long, private val zone: TimeZone) {
        private val today = dayOf(nowMs)

        private fun dayOf(ms: Long): Int = Calendar.getInstance(zone).run {
            timeInMillis = ms
            get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
        }

        private fun format(ms: Long, pattern: String) =
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }.format(Date(ms))

        private fun daysAgo(ms: Long): Int = Calendar.getInstance(zone).run {
            timeInMillis = ms
            var days = 0
            while (days < 2 && dayOf(timeInMillis) != today) { add(Calendar.DAY_OF_YEAR, 1); days++ }
            if (dayOf(timeInMillis) == today) days else -1
        }

        /** «сегодня, 08:10», «вчера, 23:40», «16.10, 08:10». */
        fun day(ms: Long): String = when (daysAgo(ms)) {
            0 -> "сегодня, ${format(ms, "HH:mm")}"
            1 -> "вчера, ${format(ms, "HH:mm")}"
            else -> format(ms, "dd.MM, HH:mm")
        }

        /** «в 08:11», «вчера в 08:11», «16.10 в 08:11» - after a word that says what happened. */
        fun at(ms: Long): String = when (daysAgo(ms)) {
            0 -> "в ${format(ms, "HH:mm")}"
            1 -> "вчера в ${format(ms, "HH:mm")}"
            else -> "${format(ms, "dd.MM")} в ${format(ms, "HH:mm")}"
        }

        /** «сегодня в 08:11»: the same, standing alone after a verb. */
        fun moment(ms: Long): String = if (daysAgo(ms) == 0) "сегодня ${at(ms)}" else at(ms)
    }
}
