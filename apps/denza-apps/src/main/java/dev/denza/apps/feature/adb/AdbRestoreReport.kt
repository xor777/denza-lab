package dev.denza.apps.feature.adb

import dev.denza.apps.R
import dev.denza.apps.core.AppText
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
    val TITLE: String get() = AppText.s(R.string.adb_restore_title)
    val SWITCH: String get() = AppText.s(R.string.adb_restore_switch)
    val SWITCH_SUMMARY: String get() = AppText.s(R.string.adb_restore_switch_summary)
    val BOOT_SECTION: String get() = AppText.s(R.string.adb_restore_boot_section)

    enum class Tone { QUIET, ATTENTION, BROKEN }

    data class Line(val text: String, val tone: Tone = Tone.QUIET)

    /** The page's sentence; null when the switch is off, which says it all. */
    fun status(s: AdbRestoreSnapshot): Line? = when {
        !s.enabled -> null
        s.state == AdbRestoreState.Unsupported -> Line(AppText.s(R.string.adb_restore_status_unsupported))
        // Not looked at in this process: the snapshot's NotNeeded is its default, not an answer.
        s.lastTrigger == null -> Line(AppText.s(R.string.adb_checking))
        else -> when (s.state) {
            AdbRestoreState.NotNeeded, is AdbRestoreState.Restored ->
                if (s.boot?.outcome == AdbBootOutcome.RESTORED) Line(AppText.s(R.string.adb_restore_status_restored))
                else Line(AppText.s(R.string.adb_restore_status_not_needed))
            AdbRestoreState.Connecting -> Line(AppText.s(R.string.adb_restore_status_connecting))
            AdbRestoreState.WaitingWifi ->
                Line(AppText.s(R.string.adb_restore_status_waiting_wifi), Tone.ATTENTION)
            AdbRestoreState.NeedsDialog ->
                if (s.retryBudgetExhausted) Line(AppText.s(R.string.adb_restore_status_system_refuses), Tone.BROKEN)
                else Line(AppText.s(R.string.adb_restore_status_waiting_system), Tone.ATTENTION)
            AdbRestoreState.NeedsActivation ->
                Line(AppText.s(R.string.adb_restore_status_untrusted), Tone.BROKEN)
            is AdbRestoreState.Failed -> Line(AppText.s(R.string.adb_restore_status_failed), Tone.BROKEN)
            AdbRestoreState.Disabled, AdbRestoreState.Unsupported -> null
        }
    }

    /** «Последняя перезагрузка»: when, what the first look found, what the restore did. */
    fun bootRows(s: AdbRestoreSnapshot, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<String, String>> {
        val boot = s.boot ?: return emptyList()
        val clock = Clock(nowMs, zone)
        val restore = when (boot.outcome) {
            null -> when (boot.look) {
                AdbBootLook.OPEN -> AppText.s(R.string.adb_boot_restore_not_needed)
                null -> AppText.s(R.string.adb_boot_restore_none)
                else -> AppText.s(R.string.adb_boot_restore_running)
            }
            AdbBootOutcome.CONNECTING -> AppText.s(R.string.adb_boot_restore_running)
            AdbBootOutcome.WAITING_WIFI -> AppText.s(R.string.adb_boot_restore_waiting_wifi)
            AdbBootOutcome.NEEDS_DIALOG -> AppText.s(R.string.adb_boot_restore_waiting_system)
            AdbBootOutcome.NEEDS_ACTIVATION -> AppText.s(R.string.adb_boot_restore_impossible)
            AdbBootOutcome.FAILED -> AppText.s(R.string.adb_boot_restore_failed_at, clock.at(boot.outcomeAtMs))
            AdbBootOutcome.RESTORED -> tookMs(boot)?.let { AppText.s(R.string.adb_boot_restore_restored_took, clock.at(boot.outcomeAtMs), span(it)) }
                ?: AppText.s(R.string.adb_boot_restore_restored_at, clock.at(boot.outcomeAtMs))
            AdbBootOutcome.RETURNED -> AppText.s(R.string.adb_boot_restore_returned_at, clock.at(boot.outcomeAtMs))
        }
        return listOf(
            AppText.s(R.string.adb_boot_when) to clock.day(boot.bootAtMs),
            AppText.s(R.string.adb_boot_access) to when (boot.look) {
                AdbBootLook.OPEN -> AppText.s(R.string.adb_boot_look_open)
                AdbBootLook.CLOSED -> AppText.s(R.string.adb_boot_look_closed)
                AdbBootLook.REFUSED -> AppText.s(R.string.adb_boot_look_refused)
                null -> AppText.s(R.string.adb_boot_look_unknown)
            },
            AppText.s(R.string.adb_boot_restore) to restore,
        )
    }

    /** The row's value on «Сервис»: what happened, not what the function is. */
    fun summary(s: AdbRestoreSnapshot, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Line {
        val boot = s.boot
        return when {
            !s.enabled -> Line(AppText.s(R.string.adb_restore_summary_off))
            else -> when (s.state) {
                AdbRestoreState.Unsupported -> Line(AppText.s(R.string.adb_restore_summary_unsupported))
                AdbRestoreState.WaitingWifi -> Line(AppText.s(R.string.adb_restore_summary_waiting_wifi), Tone.ATTENTION)
                AdbRestoreState.NeedsDialog ->
                    if (s.retryBudgetExhausted) Line(AppText.s(R.string.adb_restore_summary_system_refuses), Tone.BROKEN)
                    else Line(AppText.s(R.string.adb_restore_summary_waiting_system), Tone.ATTENTION)
                AdbRestoreState.NeedsActivation -> Line(AppText.s(R.string.adb_restore_summary_cannot), Tone.BROKEN)
                is AdbRestoreState.Failed -> Line(AppText.s(R.string.adb_restore_summary_failed), Tone.BROKEN)
                AdbRestoreState.Connecting -> Line(AppText.s(R.string.adb_restore_summary_connecting))
                else -> when {
                    boot?.outcome == AdbBootOutcome.RESTORED ->
                        Line(AppText.s(R.string.adb_restore_summary_restored_at, Clock(nowMs, zone).moment(boot.outcomeAtMs)))
                    boot?.outcome == AdbBootOutcome.RETURNED ->
                        Line(AppText.s(R.string.adb_restore_summary_returned_at, Clock(nowMs, zone).moment(boot.outcomeAtMs)))
                    boot?.look == AdbBootLook.OPEN -> Line(AppText.s(R.string.adb_restore_summary_not_needed))
                    else -> Line(AppText.s(R.string.adb_restore_summary_on))
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
        tookMs(boot)?.let { ", за ${CloudLinkReport.span(it)}" }.orEmpty()

    private fun tookMs(boot: AdbBootRecord): Long? =
        if (boot.closedAtMs > 0 && boot.outcomeAtMs >= boot.closedAtMs) boot.outcomeAtMs - boot.closedAtMs else null

    /** The page's «12 с», «4 мин», «2 ч 5 мин». [CloudLinkReport.span] is the technical page's. */
    private fun span(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return when {
            seconds < 60 -> AppText.s(R.string.adb_span_seconds, seconds)
            seconds < 3600 -> AppText.s(R.string.adb_span_minutes, seconds / 60)
            else -> AppText.s(R.string.adb_span_hours, seconds / 3600, seconds % 3600 / 60)
        }
    }

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
            0 -> AppText.s(R.string.adb_time_today, format(ms, "HH:mm"))
            1 -> AppText.s(R.string.adb_time_yesterday, format(ms, "HH:mm"))
            else -> format(ms, "dd.MM, HH:mm")
        }

        /** «в 08:11», «вчера в 08:11», «16.10 в 08:11» - after a word that says what happened. */
        fun at(ms: Long): String = when (daysAgo(ms)) {
            0 -> AppText.s(R.string.adb_at_time, format(ms, "HH:mm"))
            1 -> AppText.s(R.string.adb_at_yesterday, format(ms, "HH:mm"))
            else -> AppText.s(R.string.adb_at_date, format(ms, "dd.MM"), format(ms, "HH:mm"))
        }

        /** «сегодня в 08:11»: the same, standing alone after a verb. */
        fun moment(ms: Long): String =
            if (daysAgo(ms) == 0) AppText.s(R.string.adb_at_today, format(ms, "HH:mm")) else at(ms)
    }
}
