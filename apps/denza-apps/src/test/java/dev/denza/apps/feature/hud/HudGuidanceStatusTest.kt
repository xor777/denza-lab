package dev.denza.apps.feature.hud

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudGuidanceStatusTest {

    private fun status(
        enabled: Boolean = true,
        navigator: Boolean = true,
        accessibility: Boolean = true,
        connected: Boolean = true,
    ) = HudGuidanceStatus.snapshot(enabled, navigator, accessibility, connected, active = false) { "детали" }

    @Test
    fun offIsOffWhateverElseIsMissing() {
        assertEquals(FeatureStatus.OFF, status(enabled = false, navigator = false, accessibility = false).status)
    }

    /** A state the car has, not a refusal: the press opens the panel, which names the navigator. */
    @Test
    fun withoutTheNavigatorItIsUnavailable() {
        val snapshot = status(navigator = false)
        assertEquals(FeatureStatus.UNAVAILABLE, snapshot.status)
        assertEquals("Нет навигатора", snapshot.message)
        assertNull(snapshot.resolution)
    }

    /** The press switches the feature on again, which repairs the service it reads hints from. */
    @Test
    fun withoutTheServiceItWaitsOnThePress() {
        val snapshot = status(accessibility = false)
        assertEquals(FeatureStatus.NEEDS_ACTION, snapshot.status)
        assertEquals("Нет доступа", snapshot.message)
        assertEquals(FeatureResolution.RETRY, snapshot.resolution)
    }

    @Test
    fun aServiceStillConnectingIsWorkingAndReadyWhenConnected() {
        assertEquals(FeatureStatus.RECOVERING, status(connected = false).status)
        val ready = status()
        assertEquals(FeatureStatus.READY, ready.status)
        assertEquals("детали", ready.details)
    }
}
