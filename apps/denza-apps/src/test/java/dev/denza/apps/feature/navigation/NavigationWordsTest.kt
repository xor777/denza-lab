package dev.denza.apps.feature.navigation

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.disharebridge.AdbFailures
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationWordsTest {

    /** The press on an unverified screen opens the screens' choice; on a missing one it looks again. */
    @Test
    fun aScreenThatIsNotThereIsAStateWithItsOwnPress() {
        val display = ClusterDisplayDescriptor(2, "ClusterDisplay", 1920, 720, 160, 0, 0)
        val unverified = NavigationWords.display(ClusterDisplaySelection.NeedsVerification(listOf(display)))
        assertEquals("Экран не выбран", unverified.message)
        assertEquals(FeatureResolution.SELECT_CLUSTER_DISPLAY, unverified.resolution)

        val missing = NavigationWords.display(ClusterDisplaySelection.Missing)
        assertEquals("Экран не найден", missing.message)
        assertEquals(FeatureResolution.RETRY, missing.resolution)
    }

    @Test
    fun aStepThatFailedSaysWhichAndIsTriedAgain() {
        assertEquals("Не открылось", NavigationWords.failed(NavigationStep.OPEN, IllegalStateException("x")).message)
        assertEquals("Не перенеслось", NavigationWords.failed(NavigationStep.PROJECT, null).message)
        val back = NavigationWords.failed(NavigationStep.RETURN, IllegalStateException("navigation task return failed"))
        assertEquals("Не вернулось", back.message)
        assertEquals(FeatureResolution.RETRY, back.resolution)
    }

    /** The channel's failures read as on every tile, whichever step met them. */
    @Test
    fun theChannelsFailuresAreNoAccessAtEveryStep() {
        for (step in NavigationStep.entries) {
            assertEquals("Нет доступа", NavigationWords.failed(step, AdbFailures.authorizationRequired()).message)
            assertEquals("Нет доступа", NavigationWords.failed(step, SocketTimeoutException("Read timed out")).message)
        }
    }
}
