package dev.denza.apps.feature.navigation

import dev.denza.apps.platform.accessibility.AccessibilityHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelNavigationAccessTest {
    @Test
    fun disabledToggleNeverRepairsOrReportsReady() {
        val access = SteeringWheelNavigationAccess(
            desired = false,
            service = AccessibilityHealth(enabled = false, connected = false),
        )

        assertFalse(access.ready)
        assertFalse(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
    }

    @Test
    fun enabledToggleRepairsEveryMissingAccessibilityGate() {
        listOf(
            SteeringWheelNavigationAccess(true, AccessibilityHealth(enabled = false, connected = false)),
            SteeringWheelNavigationAccess(true, AccessibilityHealth(enabled = false, connected = true)),
            SteeringWheelNavigationAccess(true, AccessibilityHealth(enabled = true, connected = false)),
        ).forEach { access ->
            assertFalse(access.ready)
            assertTrue(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
        }
    }

    @Test
    fun enabledToggleIsReadyOnlyWhenServiceIsEnabledAndConnected() {
        val access = SteeringWheelNavigationAccess(
            desired = true,
            service = AccessibilityHealth(enabled = true, connected = true),
        )

        assertTrue(access.ready)
        assertFalse(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
    }
}
