package dev.denza.apps.feature.diagnostics.scan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Digits as the boards print them: a space between thousands, a decimal comma, a real minus. */
internal object Figures {
    const val MINUS = '−'

    /** «1 250», «−4,7», and «+3,1» with [signed]; never «−0». */
    fun number(value: Double, decimals: Int, signed: Boolean = false): String {
        val plain = String.format(Locale.ROOT, "%.${decimals}f", abs(value))
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")
        val grouped = if (whole.length > 3) whole.reversed().chunked(3).joinToString(" ").reversed() else whole
        val body = if (fraction.isEmpty()) grouped else "$grouped,$fraction"
        val zero = plain.all { it == '0' || it == '.' }
        return when {
            zero -> body
            value < 0 -> "$MINUS$body"
            signed -> "+$body"
            else -> body
        }
    }

    /** «7E0». */
    fun address(request: Int): String = "%03X".format(request)

    /** «7E0 → 7E8». */
    fun route(request: Int): String = "%03X → %03X".format(request, request + 8)

    /** «41 01 00 07 F5 F4». */
    fun spaced(hex: String): String = hex.chunked(2).joinToString(" ")
}

/** The car's wall clock as the screens print it. */
internal class ScanClock(private val zone: ZoneId) {
    private val minutes = DateTimeFormatter.ofPattern("HH:mm")
    private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dated = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** The day [millis] falls on, here: what the code history dates a change by (5.5). */
    fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** «16:31:07». */
    fun seconds(millis: Long): String = seconds.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** «16:31» the same day, «18.10 16:31» before it. */
    fun minutes(millis: Long, nowMillis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return if (at.toLocalDate() == now.toLocalDate()) minutes.format(at) else dated.format(at)
    }

    /** «3 мин», «2 ч», «4 дн.»: how old a stored scan is. */
    fun age(millis: Long, nowMillis: Long): String {
        val m = ((nowMillis - millis).coerceAtLeast(0) / 60_000L)
        return when {
            m < 1 -> "меньше минуты"
            m < 60 -> "$m мин"
            m < 48 * 60 -> "${m / 60} ч"
            else -> "${m / (24 * 60)} дн."
        }
    }
}
