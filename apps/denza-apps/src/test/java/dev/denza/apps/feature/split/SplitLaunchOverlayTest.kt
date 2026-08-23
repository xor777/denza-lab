package dev.denza.apps.feature.split

import dev.denza.apps.ui.VehicleProgressOverlayStyle
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLaunchOverlayTest {
    /**
     * 1.13.2, live defect of the vertical slice: the shield took the input and showed the rebuild.
     *
     * It was a fully transparent touch blocker, so the user watched the firmware's remembered
     * companion flash up, a settings card appear in the neighbouring pane and an empty picker sit
     * where the music was about to come back - during a wait that claimed to be a wait. An opaque
     * ground is the whole of that fix, which is why it is asserted rather than left to a colour.
     */
    @Test
    fun theBlockingShieldHidesWhatIsBehindIt() {
        VehicleProgressOverlayStyle.scrimGradient.forEach { color ->
            val alpha = (color ushr 24) and 0xFF

            assertEquals("the wait is not a window onto the rebuild", 0xFF, alpha)
        }
    }

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
    fun theHardDeadlineIsAnEmergencyShieldRelease() {
        // §14.6: этот таймер не отменяет операцию - отмену держит дедлайн операции в акторе
        // (K3). Здесь он гарантирует единственное: пользователь не остаётся с заблокированным
        // экраном, что бы ни случилось с координатором.
        val fixture = Fixture()
        val lease = fixture.controller.begin()

        fixture.advanceTo(SplitLaunchOverlay.MAX_VISIBLE_MS)
        assertEquals("the screen is given back to the user", listOf(true, false), fixture.rendered)

        // And a coordinator callback that arrives after that release cannot raise the shield again.
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
    fun aSecondOperationNestsInsteadOfDroppingTheShieldEarly() {
        // 1.3.7 и K4: два тапа - это одна операция и один lease, что доказывает
        // twoLauncherTapsShareOneOperationOneLeaseAndOneResult. Сюда lease попадает только от
        // ВТОРОЙ операции, и тогда щит держится, пока его держит хоть одна из них.
        val fixture = Fixture()
        val first = fixture.controller.begin()
        fixture.advanceTo(100)
        val second = fixture.controller.begin()

        first.close()
        fixture.advanceTo(SplitLaunchOverlay.MIN_VISIBLE_MS)
        assertTrue("the second operation still owns the screen", fixture.rendered.last())

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
