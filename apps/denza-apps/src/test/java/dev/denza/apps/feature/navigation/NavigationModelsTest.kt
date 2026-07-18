package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationModelsTest {
    @Test
    fun onlyYandexNavigatorIsAllowed() {
        assertTrue(YandexPackagePolicy.isAllowed("ru.yandex.yandexnavi"))
        assertFalse(YandexPackagePolicy.isAllowed("com.android.settings"))
        assertFalse(YandexPackagePolicy.isAllowed("ru.yandex.maps"))
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
