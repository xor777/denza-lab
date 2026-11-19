package dev.denza.apps.feature.hud

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.platform.accessibility.AccessibilityBindWatch
import dev.denza.apps.platform.accessibility.AccessibilityHealth
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

    /**
     * After an update the setting listed the service and the system never bound it: the tile read
     * «Подключаю подсказки» for good, its press switched the hints off and the panel's switch was
     * held disabled. Past the shared deadline it is the no-access state whose press repairs; a late
     * bind brings it back without a press.
     */
    @Test
    fun aServiceListedButNeverBoundIsNoAccessAfterTheDeadline() {
        val watch = AccessibilityBindWatch {}
        fun at(nowMs: Long, connected: Boolean = false) = HudGuidanceStatus.snapshot(
            enabled = true,
            navigatorInstalled = true,
            accessibility = watch.read(enabled = true, connected = connected, nowMs = nowMs),
            active = false,
        ) { "детали" }

        assertEquals(FeatureStatus.RECOVERING, at(2_000L).status)
        assertEquals(FeatureStatus.RECOVERING, at(2_000L + AccessibilityHealth.BIND_DEADLINE_MS - 1).status)

        val stalled = at(2_000L + AccessibilityHealth.BIND_DEADLINE_MS)
        assertEquals(FeatureStatus.NEEDS_ACTION, stalled.status)
        assertEquals("Нет доступа", stalled.message)
        assertEquals(FeatureResolution.RETRY, stalled.resolution)

        assertEquals(FeatureStatus.READY, at(40_000L, connected = true).status)
    }
}
