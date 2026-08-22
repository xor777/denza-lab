package dev.denza.apps.feature.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelNavigationAccessTest {
    @Test
    fun disabledToggleNeverRepairsOrReportsReady() {
        val access = SteeringWheelNavigationAccess(
            desired = false,
            serviceEnabled = false,
            serviceConnected = false,
        )

        assertFalse(access.ready)
        assertFalse(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
    }

    @Test
    fun enabledToggleRepairsEveryMissingAccessibilityGate() {
        listOf(
            SteeringWheelNavigationAccess(true, false, false),
            SteeringWheelNavigationAccess(true, false, true),
            SteeringWheelNavigationAccess(true, true, false),
        ).forEach { access ->
            assertFalse(access.ready)
            assertTrue(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
        }
    }

    @Test
    fun enabledToggleIsReadyOnlyWhenServiceIsEnabledAndConnected() {
        val access = SteeringWheelNavigationAccess(
            desired = true,
            serviceEnabled = true,
            serviceConnected = true,
        )

        assertTrue(access.ready)
        assertFalse(SteeringWheelNavigationAccessPolicy.shouldRepair(access))
    }
}
