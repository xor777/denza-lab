package dev.denza.apps.core

import dev.denza.apps.R

/**
 * A duration as this app prints one: figures with small units after them, never a clock.
 *
 * One rule for every duration on the screens: under a minute «40 с», under an hour «42 мин»,
 * otherwise «1 ч 05 мин». A colon is kept for a time of day («Закат 19:44»), which is a moment and
 * not an amount; «1:42» beside it read as one more clock. The minutes are two figures once hours
 * stand before them, so a reading that ticks over does not change its width every ten minutes.
 *
 * Pure, and in two halves: [of] says which figures and which units, [key] says when they change, so
 * a screen that redraws thirty times a second rebuilds the strings once a second at most - once a
 * minute past the first one.
 */
object AppDuration {

    /** One or two figure-unit pairs; [second] and [secondUnit] are null for a single pair. */
    class Parts(
        val first: String,
        val firstUnit: String,
        val second: String?,
        val secondUnit: String?,
    ) {
        /** As one line of text, the way a test or a log reads it: «1 ч 05 мин». */
        override fun toString(): String =
            if (second == null) "$first $firstUnit" else "$first $firstUnit $second $secondUnit"
    }

    /** [seconds] as its pairs; a negative duration is nought seconds. */
    fun of(seconds: Long): Parts {
        val s = seconds.coerceAtLeast(0L)
        return when {
            s < MINUTE -> Parts(s.toString(), SECONDS, null, null)
            s < HOUR -> Parts((s / MINUTE).toString(), MINUTES, null, null)
            else -> {
                val minutes = (s % HOUR) / MINUTE
                Parts((s / HOUR).toString(), HOURS, if (minutes < 10) "0$minutes" else minutes.toString(), MINUTES)
            }
        }
    }

    /** A number that changes exactly when [of] would print something else. */
    fun key(seconds: Long): Long {
        val s = seconds.coerceAtLeast(0L)
        return if (s < MINUTE) s else MINUTE + s / MINUTE
    }

    private const val MINUTE = 60L
    private const val HOUR = 3600L

    val HOURS: String get() = AppText.s(R.string.duration_hours)
    val MINUTES: String get() = AppText.s(R.string.duration_minutes)
    val SECONDS: String get() = AppText.s(R.string.duration_seconds)
}
