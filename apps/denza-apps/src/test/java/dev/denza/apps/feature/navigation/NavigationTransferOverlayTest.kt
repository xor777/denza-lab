package dev.denza.apps.feature.navigation

import org.junit.Assert.assertFalse
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
}
