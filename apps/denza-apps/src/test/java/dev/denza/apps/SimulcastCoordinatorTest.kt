package dev.denza.apps

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
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
                accessibilityEnabled = true,
                accessibilityConnected = true,
                active = true,
            ),
        )

        assertEquals(FeatureStatus.ACTIVE, ready.status)
    }

    @Test
    fun `missing app selection names the exact user resolution`() {
        val blocked = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                blocker = SimulcastBlocker.APPS_NOT_SELECTED,
                overlayAllowed = false,
                accessibilityEnabled = false,
                accessibilityConnected = false,
                active = false,
            ),
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, blocked.status)
        assertEquals("Выберите приложения для трансляции", blocked.message)
        assertEquals(FeatureResolution.SELECT_APPS, blocked.resolution)
    }

    @Test
    fun `missing system simulcast is unavailable rather than repairable`() {
        val unavailable = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                blocker = SimulcastBlocker.DISHARE_UNAVAILABLE,
                overlayAllowed = false,
                accessibilityEnabled = false,
                accessibilityConnected = false,
                active = false,
            ),
        )

        assertEquals(FeatureStatus.UNAVAILABLE, unavailable.status)
        assertEquals("Трансляция недоступна на этой системе", unavailable.message)
        assertNull(unavailable.resolution)
    }

    @Test
    fun `missing access offers a typed retry after automatic setup`() {
        val blocked = SimulcastCoordinator.evaluate(
            SimulcastEnvironment(
                desired = true,
                overlayAllowed = false,
                accessibilityEnabled = false,
                accessibilityConnected = false,
                active = false,
            ),
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, blocked.status)
        assertEquals("Повторите настройку доступа", blocked.message)
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
            assertEquals(FeatureResolution.CONFIRM_ON_CAR, problem.resolution)
        }
    }

    @Test
    fun `an adbd that does not answer reads the same on the tile`() {
        val problem = SimulcastCoordinator.setupProblem(SocketTimeoutException("Read timed out"))

        assertEquals("Нет доступа", problem.message)
        assertEquals(FeatureResolution.RETRY, problem.resolution)
    }

    /** Words that only look like the channel's are not read as it any more. */
    @Test
    fun `a failure of the repair itself keeps its own words`() {
        val problem = SimulcastCoordinator.setupProblem(IllegalStateException("authorization pending"))

        assertEquals("Не удалось восстановить доступ", problem.message)
        assertEquals(FeatureResolution.RETRY, problem.resolution)
    }
}
