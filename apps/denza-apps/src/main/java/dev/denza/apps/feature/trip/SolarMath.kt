package dev.denza.apps.feature.trip

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Self-contained NOAA sunrise/sunset math.
 *
 * No network, no dependencies. Implements the equations published in the NOAA
 * "General Solar Position Calculations" note (the same ones behind the NOAA
 * Solar Calculator spreadsheet). Angles are in degrees unless noted.
 *
 * All inputs are explicit so the whole thing is unit-testable on the JVM with no
 * Android clock or timezone involved.
 */
object SolarMath {

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val ZENITH_OFFICIAL_DEG = 90.833 // includes refraction + solar disc radius

    data class CivilDate(val year: Int, val month: Int, val day: Int)

    /** Local wall-clock components derived from a UTC instant and a fixed offset. */
    data class LocalTime(
        val date: CivilDate,
        /** Minutes since local midnight (0..1439.999). */
        val minutesOfDay: Double,
    )

    /** Sunrise/sunset in minutes since local midnight, plus polar flags. */
    data class DayLight(
        val sunriseMinutes: Double,
        val sunsetMinutes: Double,
        val alwaysUp: Boolean,
        val alwaysDown: Boolean,
    ) {
        val hasEvents: Boolean get() = !alwaysUp && !alwaysDown
    }

    /**
     * Convert a UTC instant + timezone offset into a local calendar date and
     * minutes-of-day using Howard Hinnant's days<->civil algorithm (exact, no
     * java.util.Calendar dependency so it stays JVM-pure and deterministic).
     */
    fun toLocalTime(utcMillis: Long, tzOffsetMinutes: Int): LocalTime {
        val localMillis = utcMillis + tzOffsetMinutes.toLong() * 60_000L
        val days = Math.floorDiv(localMillis, MILLIS_PER_DAY)
        val millisOfDay = localMillis - days * MILLIS_PER_DAY
        return LocalTime(civilFromDays(days), millisOfDay / 60_000.0)
    }

    /** Howard Hinnant civil_from_days: days since 1970-01-01 -> (y, m, d). */
    fun civilFromDays(daysSinceEpoch: Long): CivilDate {
        val z = daysSinceEpoch + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
        val year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val outYear = (if (month <= 2) year + 1 else year).toInt()
        return CivilDate(outYear, month, day)
    }

    /** Day of year 1..366 for a civil date. */
    fun dayOfYear(date: CivilDate): Int {
        val k = if (isLeap(date.year)) 1 else 2
        val n = floor(275.0 * date.month / 9.0).toInt() -
            k * floor((date.month + 9.0) / 12.0).toInt() +
            date.day - 30
        return n
    }

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /** Fractional-year gamma (radians) used by the NOAA series. */
    private fun gamma(dayOfYear: Int, hourUtc: Double): Double =
        2.0 * PI / 365.0 * (dayOfYear - 1 + (hourUtc - 12.0) / 24.0)

    /** Equation of time in minutes. */
    private fun equationOfTime(g: Double): Double =
        229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))

    /** Solar declination in radians. */
    private fun declination(g: Double): Double =
        0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) -
            0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)

    /**
     * Sunrise/sunset for the given local date at [latitudeDeg]/[longitudeDeg]
     * (longitude positive east) with [tzOffsetMinutes] east of UTC.
     */
    fun daylight(
        date: CivilDate,
        latitudeDeg: Double,
        longitudeDeg: Double,
        tzOffsetMinutes: Int,
    ): DayLight {
        val doy = dayOfYear(date)
        val g = gamma(doy, 12.0)
        val eqTime = equationOfTime(g)
        val decl = declination(g)
        val latRad = latitudeDeg.toRadians()
        val cosH = cos(ZENITH_OFFICIAL_DEG.toRadians()) / (cos(latRad) * cos(decl)) -
            tan(latRad) * tan(decl)
        if (cosH < -1.0) {
            return DayLight(0.0, 0.0, alwaysUp = true, alwaysDown = false)
        }
        if (cosH > 1.0) {
            return DayLight(0.0, 0.0, alwaysUp = false, alwaysDown = true)
        }
        val haDeg = acos(cosH.coerceIn(-1.0, 1.0)).toDegrees()
        val tzMin = tzOffsetMinutes.toDouble()
        val sunriseUtc = 720.0 - 4.0 * (longitudeDeg + haDeg) - eqTime
        val sunsetUtc = 720.0 - 4.0 * (longitudeDeg - haDeg) - eqTime
        return DayLight(
            sunriseMinutes = wrapMinutes(sunriseUtc + tzMin),
            sunsetMinutes = wrapMinutes(sunsetUtc + tzMin),
            alwaysUp = false,
            alwaysDown = false,
        )
    }

    private fun wrapMinutes(minutes: Double): Double {
        var m = minutes % 1440.0
        if (m < 0) m += 1440.0
        return m
    }

    private fun Double.toRadians(): Double = this / 180.0 * PI
    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
