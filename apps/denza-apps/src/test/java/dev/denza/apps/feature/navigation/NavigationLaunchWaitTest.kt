package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cold start's wait, on a clock the test turns by hand. */
class NavigationLaunchWaitTest {

    /** Walks the coordinator's schedule: a first look, then the pauses, each look [lookMs] long. */
    private fun looks(lookMs: Long): List<Long> {
        var clock = NavigationLaunchWait.FIRST_MS
        val at = mutableListOf<Long>()
        var pause: Long? = null
        while (true) {
            at += clock
            clock += lookMs
            pause = NavigationLaunchWait.next(clock, pause) ?: return at
            clock += pause
        }
    }

    @Test
    fun itWaitsFifteenSecondsFromTheLaunchAndNoLonger() {
        val quick = looks(lookMs = 0)
        assertTrue("last look at ${quick.last()}", quick.last() <= NavigationLaunchWait.DEADLINE_MS)
        assertTrue("last look at ${quick.last()}", quick.last() >= NavigationLaunchWait.DEADLINE_MS - 1_500)
        // A slow look does not stretch the wait: the deadline is the clock's, not a count of looks.
        val slow = looks(lookMs = 2_000)
        assertTrue("last look at ${slow.last()}", slow.last() <= NavigationLaunchWait.DEADLINE_MS + 2_000)
        assertTrue(slow.size < quick.size)
    }

    @Test
    fun thePausesBackOffFromSevenTenthsToASecondAndAHalf() {
        assertEquals(700L, NavigationLaunchWait.next(1_000, null))
        assertEquals(1_050L, NavigationLaunchWait.next(2_000, 700))
        assertEquals(1_500L, NavigationLaunchWait.next(3_000, 1_400))
        assertEquals(1_500L, NavigationLaunchWait.next(4_000, 1_500))
        // Never past the deadline, and nothing once it has passed.
        assertEquals(200L, NavigationLaunchWait.next(14_800, 1_500))
        assertNull(NavigationLaunchWait.next(15_000, 1_500))
    }

    /** It waits far longer than the four seconds that ended in «Дождитесь запуска…». */
    @Test
    fun itWaitsLongerThanItUsedTo() {
        assertTrue(looks(lookMs = 600).last() > 10_000)
    }
}
