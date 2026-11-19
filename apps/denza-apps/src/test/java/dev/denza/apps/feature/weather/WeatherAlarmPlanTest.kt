package dev.denza.apps.feature.weather

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an app start registers, across a reboot that discarded every alarm and kept the record. */
class WeatherAlarmPlanTest {
    private val minute = 60_000L
    private val interval = WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS

    /**
     * A success at three minutes of uptime stores the next run at thirteen. The car reboots two
     * minutes later; at thirty seconds of the new uptime the stored thirteen minutes lies inside
     * the window, and the success is fresh. Until 2026-11-19 the start registered nothing then.
     */
    @Test
    fun aQuickRebootStillLeavesAnAlarmStanding() {
        val successWall = 1_000_000_000_000L
        val storedNext = 3 * minute + interval
        val alarm = WeatherAlarmPlan.onStart(
            storedNextElapsed = storedNext,
            nowElapsed = 30_000L,
            lastSuccessMillis = successWall,
            nowMillis = successWall + 3 * minute,
        )

        assertFalse(alarm.soon)
        assertTrue("it fires after now", alarm.triggerAtElapsed > 30_000L)
        assertTrue("and no later than the stored time", alarm.triggerAtElapsed <= storedNext)
    }

    @Test
    fun aStoredTimeOutsideTheWindowIsReplacedByAFullInterval() {
        val now = 50 * minute
        val success = 1_000_000_000_000L
        for (stored in listOf(0L, now - 1, now + 2 * interval + 1)) {
            val alarm = WeatherAlarmPlan.onStart(stored, now, success, success + minute)
            assertEquals("stored $stored", now + interval, alarm.triggerAtElapsed)
        }
    }

    @Test
    fun aStaleOrFutureSuccessRunsSoon() {
        val now = 50 * minute
        val wall = 1_000_000_000_000L
        val stale = WeatherAlarmPlan.onStart(now + minute, now, wall - interval, wall)
        val future = WeatherAlarmPlan.onStart(now + minute, now, wall + minute, wall)

        assertTrue(stale.soon)
        assertTrue("a success after now means the clock went back", future.soon)
        assertEquals(now + WeatherAdapterConfig.INITIAL_REFRESH_DELAY_MILLIS, future.triggerAtElapsed)
    }

    /** The start registers the plan's alarm whatever it says; there is no path that skips it. */
    @Test
    fun theStartAlwaysArmsThePlan() {
        val scheduler = File("src/main/java/dev/denza/apps/feature/weather/WeatherAdapterScheduler.kt").readText()
        val ensure = scheduler.substringAfter("fun ensureScheduled(").substringBefore("\n    }\n")
        val afterPlan = ensure.substringAfter("WeatherAlarmPlan.onStart(")
        assertTrue("ensureScheduled must arm the plan's alarm", "arm(app, alarm)" in afterPlan)
        assertFalse("nothing may skip arming once the plan is made", "return" in afterPlan)
    }
}
