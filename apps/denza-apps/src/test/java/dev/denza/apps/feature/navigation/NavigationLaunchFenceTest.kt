package dev.denza.apps.feature.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLaunchFenceTest {
    @Test
    fun staleDiscoveryCannotFollowAChangedSelection() {
        val fence = NavigationLaunchFence()
        val yandex = fence.begin("ru.yandex.yandexnavi")

        fence.invalidate()
        val maps = fence.begin("ru.yandex.yandexmaps")

        assertFalse(fence.accepts(yandex, "ru.yandex.yandexmaps"))
        assertTrue(fence.accepts(maps, "ru.yandex.yandexmaps"))
    }

    @Test
    fun olderDiscoveryCannotConsumeANewerLaunchOfTheSamePackage() {
        val fence = NavigationLaunchFence()
        val first = fence.begin("ru.yandex.yandexnavi")
        val second = fence.begin("ru.yandex.yandexnavi")

        assertFalse(fence.accepts(first, "ru.yandex.yandexnavi"))
        assertTrue(fence.accepts(second, "ru.yandex.yandexnavi"))
    }
}
