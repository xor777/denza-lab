package dev.denza.apps.feature.warning

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The status line's colour: the summary grey, or the tiles' orange for a car that stopped answering. */
internal enum class WarningTone { QUIET, ATTENTION }

/**
 * Every word the «Предупреждения машины» page and its row on «Сервис» say, decided without Compose
 * so the board can be held to them.
 *
 * A code is shown as the dictionary spells it (`1DD`) beside the car's own name for it; a code the
 * dictionary does not know is the code alone. Nothing here calls a missing warning «норма»: the
 * slot holds one warning at a time, so its silence says nothing about a reservoir.
 */
internal object WarningText {
    val TITLE: String get() = AppText.s(R.string.warning_title)
    val SWITCH: String get() = AppText.s(R.string.warning_switch)
    val SWITCH_SUMMARY: String get() = AppText.s(R.string.warning_switch_summary)
    val CODES: String get() = AppText.s(R.string.warning_codes)
    val EVENTS: String get() = AppText.s(R.string.warning_events)
    val EXPORT: String get() = AppText.s(R.string.warning_export)
    val CLEAR: String get() = AppText.s(R.string.warning_clear)
    val CLEAR_AGAIN: String get() = AppText.s(R.string.warning_clear_again)
    val NONE: String get() = AppText.s(R.string.warning_none)

    /** The row on «Сервис»: the switch's state, or the latest code and when. */
    fun row(state: WarningLogState, nowMs: Long, zone: ZoneId): String {
        if (!state.enabled) return AppText.s(R.string.warning_recording_off)
        val last = state.sightings.firstOrNull() ?: return NONE
        return AppText.s(R.string.warning_row_last, WarningJournal.hex(last.code), day(last.lastMs, nowMs, zone))
    }

    fun status(state: WarningLogState, nowMs: Long, zone: ZoneId): Pair<String, WarningTone> = when {
        !state.enabled -> AppText.s(R.string.warning_recording_off) to WarningTone.QUIET
        state.linked == false && state.lostAtMs != null ->
            AppText.s(R.string.warning_lost_since, since(state.lostAtMs, nowMs, zone)) to WarningTone.ATTENTION
        state.sinceMs != null ->
            AppText.s(R.string.warning_recording_since, since(state.sinceMs, nowMs, zone)) to WarningTone.QUIET
        else -> AppText.s(R.string.warning_recording) to WarningTone.QUIET
    }

    /** A code and the car's name for it, as an event says it: «1DD · Долейте омывающую жидкость». */
    fun named(code: Int, names: WarningNames): String {
        val hex = WarningJournal.hex(code)
        return names.of(code)?.let { "$hex · $it" } ?: hex
    }

    /**
     * A code's row: the code and when it was last seen on the line that stays one line - «13F ·
     * сегодня, 14:01» - and how often with the car's name under it, which may take two. The first
     * sighting is the export's.
     */
    fun sightingTitle(sighting: WarningSighting, nowMs: Long, zone: ZoneId): String =
        "${WarningJournal.hex(sighting.code)} · ${day(sighting.lastMs, nowMs, zone)}"

    fun sightingSummary(sighting: WarningSighting, names: WarningNames): String {
        val times = times(sighting.count)
        return names.of(sighting.code)?.let { "$times · $it" } ?: times
    }

    /** An event's moment to the second: «21.10 17:12:05». */
    fun eventTime(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun eventText(event: WarningEvent, names: WarningNames): String = when (event.kind) {
        WarningEventKind.START -> AppText.s(R.string.warning_event_start)
        WarningEventKind.LINK -> when (val code = event.code ?: 0) {
            0 -> AppText.s(R.string.warning_event_link_clean)
            else -> AppText.s(R.string.warning_event_link_code, named(code, names))
        }
        WarningEventKind.CODE -> when (val code = event.code ?: 0) {
            0 -> AppText.s(R.string.warning_event_cleared_code)
            else -> named(code, names)
        }
        WarningEventKind.LOST -> AppText.s(R.string.warning_event_lost)
        WarningEventKind.ON -> AppText.s(R.string.warning_event_on)
        WarningEventKind.OFF -> AppText.s(R.string.warning_recording_off)
        WarningEventKind.CLEARED -> AppText.s(R.string.warning_event_cleared)
    }

    /** «1 раз», «3 раза», «12 раз», «22 раза». */
    fun times(count: Int): String = AppText.p(R.plurals.warning_times, count, count)

    /** «сегодня, 08:10», «вчера, 23:40», «16.10, 08:10». */
    fun day(ms: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = HOURS.format(at)
        return when (at.toLocalDate()) {
            today -> AppText.s(R.string.warning_day_today, time)
            today.minusDays(1) -> AppText.s(R.string.warning_day_yesterday, time)
            else -> "${DATE.format(at)}, $time"
        }
    }

    /** After «с»: «08:10» today, «16.10, 08:10» before. */
    private fun since(ms: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (at.toLocalDate() == today) HOURS.format(at) else "${DATE.format(at)}, ${HOURS.format(at)}"
    }

    private val HOURS = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM")
    private val STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
}

/**
 * The whole record as one text file for the owner's own reading: the codes with their counts, then
 * every event both ring files still hold, oldest first, each with the code's name as the page shows
 * it and the raw word the journal keeps.
 *
 * The file's headers are Russian in every language, because reports stay Russian. The three words it
 * shares with the page are spelled here as `warning_title`, `warning_codes` and `warning_events` spell
 * them in `res/values/`, and `WarningTextTest` holds them equal. The description column follows the
 * app's language through [WarningText.eventText].
 */
internal object WarningExport {
    const val TITLE = "Предупреждения машины"
    const val CODES = "Коды"
    const val EVENTS = "События"

    fun text(
        header: List<String>,
        state: WarningLogState,
        events: List<WarningEvent>,
        names: WarningNames,
        zone: ZoneId,
    ): String = buildString {
        header.forEach { appendLine(it) }
        appendLine("Запись с: ${state.sinceMs?.let { full(it, zone) } ?: "—"}")
        appendLine("Запись: ${if (state.enabled) "включена" else "выключена"}")
        appendLine()
        appendLine("[$CODES]")
        appendLine("код\tраз\tвпервые\tпоследний раз\tназвание")
        state.sightings.forEach { s ->
            appendLine(
                "${WarningJournal.hex(s.code)}\t${s.count}\t${full(s.firstMs, zone)}\t${full(s.lastMs, zone)}\t" +
                    names.of(s.code).orEmpty(),
            )
        }
        appendLine()
        appendLine("[$EVENTS]")
        appendLine("время\tсобытие\tкод\tописание")
        events.forEach { e ->
            appendLine(
                "${full(e.atMs, zone)}\t${e.kind.tag}\t${e.code?.let(WarningJournal::hex).orEmpty()}\t" +
                    WarningText.eventText(e, names),
            )
        }
    }

    private fun full(ms: Long, zone: ZoneId): String = FULL.format(Instant.ofEpochMilli(ms).atZone(zone))

    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
}
