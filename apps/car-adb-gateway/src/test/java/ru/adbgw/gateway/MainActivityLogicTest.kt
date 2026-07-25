package ru.adbgw.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLogicTest {
    @Test
    fun `ADB onboarding is not interrupted by notification permission`() {
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                isEnrolled = false,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun `notification permission is requested only after enrollment`() {
        assertTrue(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                isEnrolled = true,
                permissionGranted = false,
            ),
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 32,
                isEnrolled = true,
                permissionGranted = false,
            ),
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                isEnrolled = true,
                permissionGranted = true,
            ),
        )
    }
}
