package dev.denza.apps.feature.hud

import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The instruments' source reads Yandex only while Yandex itself is projected there. */
class HudMapClusterSourceTest {
    private val yandex = HudGuidanceSettings.NAVIGATOR_PACKAGE

    @Test
    fun yandexProjectedOnTheClusterIsReadFromItsDisplay() {
        val session = NavigationSession(phase = NavigationPhase.PROJECTED, virtualDisplayId = 40, projectedPackage = yandex)

        assertEquals(40, HudMapClusterSource.projectedDisplay(session, yandex))
    }

    @Test
    fun anotherApplicationOnTheClusterIsNotYandex() {
        val session = NavigationSession(phase = NavigationPhase.PROJECTED, virtualDisplayId = 40, projectedPackage = "com.waze")

        assertNull(HudMapClusterSource.projectedDisplay(session, yandex))
    }

    @Test
    fun aProjectionOnItsWayOrBackIsNotThereYet() {
        for (phase in NavigationPhase.entries.filter { it != NavigationPhase.PROJECTED }) {
            val session = NavigationSession(phase = phase, virtualDisplayId = 40, projectedPackage = yandex)

            assertNull(phase.name, HudMapClusterSource.projectedDisplay(session, yandex))
        }
        assertNull(HudMapClusterSource.projectedDisplay(NavigationSession(phase = NavigationPhase.PROJECTED, projectedPackage = yandex), yandex))
    }

    @Test
    fun yandexFillsItsDisplayAtTheDisplaysDensity() {
        // Full on the instruments: 2560×720 at 272 dpi, 1506×424 dp.
        assertEquals(PxRect(0, 0, 2560, 720), HudMapClusterSource.window(2560, 720))
        assertEquals(1.7f, HudMapClusterSource.density(272), 1e-6f)
        assertEquals(2f, HudMapClusterSource.density(320), 1e-6f)
    }

    @Test
    fun theAutoSourceRunsTheInstrumentsOnlyWhileYandexIsThere() {
        assertEquals("instruments", HudMapAutoSource.pick(true, "screen", "instruments"))
        assertEquals("screen", HudMapAutoSource.pick(false, "screen", "instruments"))
    }
}
