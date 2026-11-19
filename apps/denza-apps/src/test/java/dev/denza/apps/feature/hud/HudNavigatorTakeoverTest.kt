package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/** YandexHUD replacing the ordinary Yandex while Denza Apps stays alive: the capture grant is looked at again. */
class HudNavigatorTakeoverTest {
    private val heard = mutableListOf<String>()
    private val effects = object : HudGuidanceAccessibilityMonitor.NavigatorEffects {
        override fun location(on: Boolean) {
            heard += "location $on"
        }

        override fun reevaluateMapGrant() {
            heard += "grant"
        }
    }

    @Test
    fun theModuleTakingOverLooksAtTheCaptureGrantAtOnce() {
        HudGuidanceAccessibilityMonitor.applyNavigatorBuild(false, true, effects)
        assertEquals(listOf("location false", "grant"), heard)
    }

    @Test
    fun theModuleStayingOrTheOrdinaryYandexLeavesTheGrantAlone() {
        HudGuidanceAccessibilityMonitor.applyNavigatorBuild(true, true, effects)
        HudGuidanceAccessibilityMonitor.applyNavigatorBuild(true, false, effects)
        HudGuidanceAccessibilityMonitor.applyNavigatorBuild(false, false, effects)
        assertEquals(listOf("location false", "location true", "location true"), heard)
    }

    @Test
    fun withTheMapNoLongerOursALiveGrantIsReleased() {
        // What the re-evaluation decides: the module fills the map window, isMapOurs is false.
        assertEquals(
            HudMapGrantAction.RELEASE,
            HudMapGrantPolicy.decide(HudMapGrantTrigger.RESUME, hintsOn = true, mapOn = false, live = true,
                inFlight = false, opAllowed = true, lastAttemptMs = null, nowMs = 0),
        )
    }
}
