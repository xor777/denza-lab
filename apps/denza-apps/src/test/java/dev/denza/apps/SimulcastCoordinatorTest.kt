package dev.denza.apps

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `authorization prompt has an external confirmation resolution`() {
        val problem = SimulcastCoordinator.setupProblem(
            IllegalStateException("authorization pending"),
        )

        assertEquals("Подтвердите запрос на экране автомобиля", problem.message)
        assertEquals(FeatureResolution.CONFIRM_ON_CAR, problem.resolution)
    }

    @Test
    fun `passive authorization failure points to ADB Rescue`() {
        val problem = SimulcastCoordinator.setupProblem(
            IllegalStateException("ADB authorization required; no request was sent"),
        )

        assertEquals("Откройте ADB Rescue в диагностике", problem.message)
        assertEquals(FeatureResolution.CONFIRM_ON_CAR, problem.resolution)
    }

    @Test
    fun `only repairing event keeps setup progress active`() {
        assertTrue(SimulcastReconcileEvent.Repairing.setupRunning)
        assertFalse(SimulcastReconcileEvent.Refresh.setupRunning)
        assertFalse(SimulcastReconcileEvent.Repaired.setupRunning)
        assertFalse(
            SimulcastReconcileEvent.Blocked(
                blocker = SimulcastBlocker.APPS_NOT_SELECTED,
                selectedAppCount = 0,
            ).setupRunning,
        )
        assertFalse(
            SimulcastReconcileEvent.RepairFailed(
                message = "failed",
                details = null,
            ).setupRunning,
        )
    }
}
