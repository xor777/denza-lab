package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One rule for every duration on the screens: figures and small units, never a clock. */
class AppDurationTest {

    @Test
    fun underAMinuteIsSeconds() {
        assertEquals("0 с", AppDuration.of(0).toString())
        assertEquals("40 с", AppDuration.of(40).toString())
        assertEquals("59 с", AppDuration.of(59).toString())
        assertNull(AppDuration.of(40).second)
    }

    @Test
    fun underAnHourIsWholeMinutes() {
        assertEquals("1 мин", AppDuration.of(60).toString())
        assertEquals("42 мин", AppDuration.of(42L * 60 + 59).toString())
        assertEquals("59 мин", AppDuration.of(3599).toString())
    }

    @Test
    fun anHourAndPastIsHoursAndTwoFigureMinutes() {
        assertEquals("1 ч 00 мин", AppDuration.of(3600).toString())
        assertEquals("1 ч 42 мин", AppDuration.of(3600L + 42 * 60).toString())
        assertEquals("12 ч 05 мин", AppDuration.of(12L * 3600 + 5 * 60 + 30).toString())
        val parts = AppDuration.of(12L * 3600 + 5 * 60)
        assertEquals("12", parts.first)
        assertEquals("ч", parts.firstUnit)
        assertEquals("05", parts.second)
        assertEquals("мин", parts.secondUnit)
    }

    @Test
    fun noDurationHasAColon() {
        for (s in listOf(0L, 9L, 59L, 60L, 599L, 3599L, 3600L, 43_200L, 360_000L)) {
            assertFalse("$s", AppDuration.of(s).toString().contains(':'))
        }
    }

    @Test
    fun aNegativeDurationIsNought() {
        assertEquals("0 с", AppDuration.of(-5).toString())
        assertEquals(AppDuration.key(0), AppDuration.key(-5))
    }

    /** The key changes exactly when the printed duration does - every second, then every minute. */
    @Test
    fun theKeyFollowsWhatIsPrinted() {
        for (s in 0L until 3 * 3600L) {
            val same = AppDuration.of(s).toString() == AppDuration.of(s + 1).toString()
            assertEquals("at $s", same, AppDuration.key(s) == AppDuration.key(s + 1))
        }
        assertNotEquals(AppDuration.key(59), AppDuration.key(60))
    }

    @Test
    fun theUnitsAreTheLanguagesOwn() {
        assertEquals("1 h 42 min", inEnglish { AppDuration.of(3600L + 42 * 60).toString() })
        assertEquals("40 s", inEnglish { AppDuration.of(40).toString() })
    }
}
