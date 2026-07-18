package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationModelsTest {
    @Test
    fun onlyKnownNavigationAppsAreAllowed() {
        assertTrue(NavigationAppPolicy.isAllowed("ru.yandex.yandexnavi"))
        assertTrue(NavigationAppPolicy.isAllowed("ru.yandex.yandexmaps"))
        assertTrue(NavigationAppPolicy.isAllowed("com.google.android.apps.maps"))
        assertTrue(NavigationAppPolicy.isAllowed("com.waze"))
        assertTrue(NavigationAppPolicy.isAllowed("ru.dublgis.dgismobile"))
        assertFalse(NavigationAppPolicy.isAllowed("com.android.settings"))
    }

    @Test
    fun proxyDeathNeverCreatesAnAutostartSession() {
        val recovered = NavigationRecovery.proxyLost(NavigationSession())
        assertEquals(NavigationPhase.READY, recovered.phase)
        assertNull(recovered.virtualDisplayId)
    }

    @Test
    fun projectedTaskMovesToRecoveringWhenProxyDies() {
        val recovered = NavigationRecovery.proxyLost(
            NavigationSession(
                phase = NavigationPhase.PROJECTED,
                taskId = 12,
                virtualDisplayId = 8,
            ),
        )
        assertEquals(NavigationPhase.RECOVERING, recovered.phase)
        assertEquals(12, recovered.taskId)
    }
}
