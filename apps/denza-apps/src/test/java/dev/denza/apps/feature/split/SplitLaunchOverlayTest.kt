package dev.denza.apps.feature.split

import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLaunchOverlayTest {
    @Test
    fun callbackCannotHideBeforeMinimumVisibleTime() {
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        lease.close()
        assertEquals(listOf(true), fixture.rendered)

        fixture.advanceTo(SplitLaunchOverlay.MIN_VISIBLE_MS - 1)
        assertEquals(listOf(true), fixture.rendered)

        fixture.advanceTo(SplitLaunchOverlay.MIN_VISIBLE_MS)
        assertEquals(listOf(true, false), fixture.rendered)
    }

    @Test
    fun hardDeadlineReleasesWindowWithoutCoordinatorCallback() {
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        fixture.advanceTo(SplitLaunchOverlay.MAX_VISIBLE_MS)
        assertEquals(listOf(true, false), fixture.rendered)

        lease.close()
        fixture.runAll()
        assertEquals(listOf(true, false), fixture.rendered)
    }

    @Test
    fun errorCloseReleasesImmediately() {
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        lease.closeImmediately()

        assertEquals(listOf(true, false), fixture.rendered)
    }

    @Test
    fun errorCloseOverridesAlreadyScheduledMinimumTimeClose() {
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        lease.close()
        lease.closeImmediately()

        assertEquals(listOf(true, false), fixture.rendered)
        fixture.runAll()
        assertEquals(listOf(true, false), fixture.rendered)
    }

    @Test
    fun overlappingRequestsStayVisibleUntilEveryLeaseEnds() {
        val fixture = Fixture()
        val first = fixture.controller.begin()
        fixture.advanceTo(100)
        val second = fixture.controller.begin()

        first.close()
        fixture.advanceTo(SplitLaunchOverlay.MIN_VISIBLE_MS)
        assertTrue(fixture.rendered.last())

        second.close()
        fixture.advanceTo(100 + SplitLaunchOverlay.MIN_VISIBLE_MS)
        assertFalse(fixture.rendered.last())
    }

    @Test
    fun repeatedCloseAndStaleTimersAreIdempotent() {
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        lease.close()
        lease.close()
        fixture.runAll()

        assertEquals(listOf(true, false), fixture.rendered)
    }

    @Test
    fun staleDeadlineCannotHideANewerLease() {
        val fixture = Fixture()
        val first = fixture.controller.begin()
        fixture.advanceTo(SplitLaunchOverlay.MAX_VISIBLE_MS)
        val second = fixture.controller.begin()

        first.closeImmediately()
        assertTrue(fixture.rendered.last())

        second.close()
        fixture.advanceTo(
            SplitLaunchOverlay.MAX_VISIBLE_MS + SplitLaunchOverlay.MIN_VISIBLE_MS,
        )
        assertFalse(fixture.rendered.last())
    }

    private class Fixture {
        private var nowMs = 0L
        private var sequence = 0L
        private val scheduled = PriorityQueue<Scheduled>(
            compareBy<Scheduled> { it.atMs }.thenBy { it.sequence },
        )
        val rendered = mutableListOf<Boolean>()
        val controller = SplitLaunchOverlayController(
            nowMs = { nowMs },
            schedule = { delayMs, action ->
                sequence += 1
                scheduled += Scheduled(nowMs + delayMs, sequence, action)
            },
            render = rendered::add,
            minimumVisibleMs = SplitLaunchOverlay.MIN_VISIBLE_MS,
            maximumVisibleMs = SplitLaunchOverlay.MAX_VISIBLE_MS,
        )

        fun advanceTo(targetMs: Long) {
            require(targetMs >= nowMs)
            while (scheduled.peek()?.atMs?.let { it <= targetMs } == true) {
                val next = scheduled.remove()
                nowMs = next.atMs
                next.action()
            }
            nowMs = targetMs
        }

        fun runAll() {
            while (scheduled.isNotEmpty()) {
                advanceTo(checkNotNull(scheduled.peek()).atMs)
            }
        }
    }

    private data class Scheduled(
        val atMs: Long,
        val sequence: Long,
        val action: () -> Unit,
    )
}
