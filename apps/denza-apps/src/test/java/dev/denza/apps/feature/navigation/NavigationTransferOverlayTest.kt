package dev.denza.apps.feature.navigation

import dev.denza.apps.ui.VehicleProgressOverlayStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTransferOverlayTest {
    @Test
    fun transferIsVisibleOnlyOutsideTheActiveDenzaAppsWindow() {
        assertFalse(NavigationTransferOverlayState().shouldShow)
        assertFalse(
            NavigationTransferOverlayState(
                transferActive = true,
                mainActivityResumed = true,
            ).shouldShow,
        )
        assertTrue(
            NavigationTransferOverlayState(
                transferActive = true,
                mainActivityResumed = false,
            ).shouldShow,
        )
    }

    @Test
    fun finishingEitherTransferDirectionHidesTheWindow() {
        val projecting = NavigationTransferOverlayState(
            transferActive = true,
            mainActivityResumed = false,
        )

        assertTrue(projecting.shouldShow)
        assertFalse(projecting.copy(transferActive = false).shouldShow)
    }

    @Test
    fun visualTokensMatchTheVehicleSystemToast() {
        assertEquals(0xFF343942.toInt(), VehicleProgressOverlayStyle.backgroundColor)
        assertEquals(0xE6FFFFFF.toInt(), VehicleProgressOverlayStyle.foregroundColor)
        assertEquals(8, VehicleProgressOverlayStyle.cornerRadiusDp)
        assertEquals(16, VehicleProgressOverlayStyle.horizontalPaddingDp)
        assertEquals(10, VehicleProgressOverlayStyle.verticalPaddingDp)
        assertEquals(48, VehicleProgressOverlayStyle.minimumHeightDp)
        assertEquals(24, VehicleProgressOverlayStyle.indicatorSizeDp)
        assertEquals(18f, VehicleProgressOverlayStyle.textSizeSp)
    }
}
