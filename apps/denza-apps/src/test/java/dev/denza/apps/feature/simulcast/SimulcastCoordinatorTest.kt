package dev.denza.apps.feature.simulcast

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.platform.accessibility.AccessibilityBindWatch
import dev.denza.apps.platform.accessibility.AccessibilityHealth
import dev.denza.disharebridge.AdbFailures
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulcastCoordinatorTest {
    @Test
    fun `ready status requires permissions and connected accessibility runtime`() {
        val ready = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                overlayAllowed = true,
                accessibility = AccessibilityHealth(enabled = true, connected = true),
                active = true,
            ),
        )

        assertEquals(FeatureStatus.ACTIVE, ready.status)
    }

    /**
     * Listed in the setting, never bound, after a repair that returned clean: the tile used to say
     * it was recovering for good, and the panel held its switch disabled under that. The clock is
     * the shared one every feature reads ([AccessibilityBindWatch]); a late bind clears it unpressed.
     */
    @Test
    fun `a service that never binds is a retryable no access after the deadline`() {
        val watch = AccessibilityBindWatch {}
        fun at(nowMs: Long, connected: Boolean = false) = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                overlayAllowed = true,
                accessibility = watch.read(enabled = true, connected = connected, nowMs = nowMs),
                active = false,
            ),
        )

        assertEquals(FeatureStatus.RECOVERING, at(1_000L).status)
        assertEquals(FeatureStatus.RECOVERING, at(1_000L + AccessibilityHealth.BIND_DEADLINE_MS - 1).status)

        val stalled = at(1_000L + AccessibilityHealth.BIND_DEADLINE_MS)
        assertEquals(FeatureStatus.NEEDS_ACTION, stalled.status)
        assertEquals("Нет доступа", stalled.message)
        assertEquals(FeatureResolution.RETRY, stalled.resolution)

        assertEquals(FeatureStatus.READY, at(30_000L, connected = true).status)
    }

    @Test
    fun `missing app selection names the exact user resolution`() {
        val blocked = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                blocker = SimulcastBlocker.APPS_NOT_SELECTED,
                overlayAllowed = false,
                accessibility = AccessibilityHealth(enabled = false, connected = false),
                active = false,
            ),
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, blocked.status)
        assertEquals("Не выбрано", blocked.message)
        assertEquals(FeatureResolution.SELECT_APPS, blocked.resolution)
    }

    @Test
    fun `missing system simulcast is unavailable rather than repairable`() {
        val unavailable = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                blocker = SimulcastBlocker.DISHARE_UNAVAILABLE,
                overlayAllowed = false,
                accessibility = AccessibilityHealth(enabled = false, connected = false),
                active = false,
            ),
        )

        assertEquals(FeatureStatus.UNAVAILABLE, unavailable.status)
        // No words of its own: the tile prints its «Недоступно».
        assertEquals("", unavailable.message)
        assertNull(unavailable.resolution)
    }

    @Test
    fun `missing access offers a typed retry after automatic setup`() {
        val blocked = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                overlayAllowed = false,
                accessibility = AccessibilityHealth(enabled = false, connected = false),
                active = false,
            ),
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, blocked.status)
        assertEquals("Нет доступа", blocked.message)
        assertEquals(FeatureResolution.RETRY, blocked.resolution)
    }

    /**
     * The channel's failures, the real ones the library throws, read as one word on the tile.
     * They used to be found by words in the message and sent the driver to «ADB Rescue».
     */
    @Test
    fun `a key the car does not trust reads as no access`() {
        for (failure in listOf(AdbFailures.authorizationPending(), AdbFailures.authorizationRequired())) {
            val problem = SimulcastCoordinator.setupProblem(failure)
            assertEquals("Нет доступа", problem.message)
            assertEquals(FeatureResolution.CHECK_ACCESS, problem.resolution)
        }
    }

    @Test
    fun `an adbd that does not answer reads the same on the tile`() {
        val problem = SimulcastCoordinator.setupProblem(SocketTimeoutException("Read timed out"))

        assertEquals("Нет доступа", problem.message)
        assertEquals(FeatureResolution.CHECK_ACCESS, problem.resolution)
    }

    /**
     * Whatever stopped the repair, the app has no access and the tile says so; only the press
     * differs. Words that only look like the channel's are not read as it any more.
     */
    @Test
    fun `a failure of the repair itself is still no access, and the press repairs again`() {
        for (failure in listOf(null, IllegalStateException("authorization pending"))) {
            val problem = SimulcastCoordinator.setupProblem(failure)

            assertEquals("Нет доступа", problem.message)
            assertEquals(FeatureResolution.RETRY, problem.resolution)
        }
    }
}
