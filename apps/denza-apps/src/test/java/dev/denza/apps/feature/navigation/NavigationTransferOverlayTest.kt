package dev.denza.apps.feature.navigation

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
        assertEquals(0xFF343942.toInt(), NavigationTransferOverlayStyle.backgroundColor)
        assertEquals(0xE6FFFFFF.toInt(), NavigationTransferOverlayStyle.foregroundColor)
        assertEquals(8, NavigationTransferOverlayStyle.cornerRadiusDp)
        assertEquals(16, NavigationTransferOverlayStyle.horizontalPaddingDp)
        assertEquals(10, NavigationTransferOverlayStyle.verticalPaddingDp)
        assertEquals(48, NavigationTransferOverlayStyle.minimumHeightDp)
        assertEquals(24, NavigationTransferOverlayStyle.indicatorSizeDp)
        assertEquals(18f, NavigationTransferOverlayStyle.textSizeSp)
    }
}
