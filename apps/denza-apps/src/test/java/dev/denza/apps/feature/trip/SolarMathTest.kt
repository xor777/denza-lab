package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarMathTest {

    @Test
    fun civilFromDaysMatchesKnownDates() {
        assertEquals(SolarMath.CivilDate(1970, 1, 1), SolarMath.civilFromDays(0))
        assertEquals(SolarMath.CivilDate(1971, 1, 1), SolarMath.civilFromDays(365))
        assertEquals(SolarMath.CivilDate(2000, 1, 1), SolarMath.civilFromDays(10957))
        assertEquals(SolarMath.CivilDate(2026, 7, 24), SolarMath.civilFromDays(20658))
    }

    @Test
    fun dayOfYearIsCorrect() {
        assertEquals(1, SolarMath.dayOfYear(SolarMath.CivilDate(2026, 1, 1)))
        assertEquals(172, SolarMath.dayOfYear(SolarMath.CivilDate(2026, 6, 21)))
        assertEquals(59, SolarMath.dayOfYear(SolarMath.CivilDate(2026, 2, 28)))
    }

    @Test
    fun toLocalTimeAppliesOffset() {
        val utcMidnight = 0L // 1970-01-01 00:00 UTC
        val local = SolarMath.toLocalTime(utcMidnight, tzOffsetMinutes = 180)
        assertEquals(SolarMath.CivilDate(1970, 1, 1), local.date)
        assertEquals(180.0, local.minutesOfDay, 1e-6)
    }

    @Test
    fun moscowSummerSolsticeSunriseSunset() {
        // Moscow, 2026-06-21, MSK (UTC+3). Well-known white-nights values:
        // sunrise ~03:45, sunset ~21:18.
        val day = SolarMath.daylight(
            SolarMath.CivilDate(2026, 6, 21),
            latitudeDeg = 55.7558,
            longitudeDeg = 37.6173,
            tzOffsetMinutes = 180,
        )
        assertTrue(day.hasEvents)
        // sunrise between 03:35 and 03:55.
        assertTrue("sunrise=${day.sunriseMinutes}", day.sunriseMinutes in 215.0..235.0)
        // sunset between 21:08 and 21:28.
        assertTrue("sunset=${day.sunsetMinutes}", day.sunsetMinutes in 1268.0..1288.0)
    }

    @Test
    fun equatorEquinoxDayIsAboutTwelveHours() {
        val day = SolarMath.daylight(
            SolarMath.CivilDate(2026, 3, 20),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            tzOffsetMinutes = 0,
        )
        val lengthMinutes = day.sunsetMinutes - day.sunriseMinutes
        assertEquals(720.0, lengthMinutes, 20.0)
    }

    @Test
    fun polarDayHasNoEvents() {
        // Far north in June: sun never sets.
        val day = SolarMath.daylight(
            SolarMath.CivilDate(2026, 6, 21),
            latitudeDeg = 78.0,
            longitudeDeg = 15.0,
            tzOffsetMinutes = 60,
        )
        assertTrue(day.alwaysUp)
        assertTrue(!day.hasEvents)
    }

}
