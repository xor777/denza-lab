package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastAccessibilityAccessTest {
    @Test
    fun `recognizes canonical and shorthand components`() {
        assertTrue(
            SimulcastAccessibilityAccess.isEnabled(
                "system/service:${SimulcastAccessibilityAccess.COMPONENT}:voice/service",
            ),
        )
        assertTrue(
            SimulcastAccessibilityAccess.isEnabled(
                "system/service:dev.denza.apps/.SimulcastAccessibilityService",
            ),
        )
        assertFalse(SimulcastAccessibilityAccess.isEnabled("system/service:voice/service"))
    }

    @Test
    fun `rebind removes only simulcast service and restores it once`() {
        val original = "system/service:${SimulcastAccessibilityAccess.COMPONENT}:voice/service"

        val disabled = SimulcastAccessibilityAccess.withoutService(original)
        val enabled = SimulcastAccessibilityAccess.withService(disabled)

        assertEquals("system/service:voice/service", disabled)
        assertEquals(
            "system/service:voice/service:${SimulcastAccessibilityAccess.COMPONENT}",
            enabled,
        )
    }

    @Test
    fun `retired guard component is stripped and never restored`() {
        val withGuard = "system/service:${SimulcastAccessibilityAccess.COMPONENT}" +
            ":dev.denza.apps/dev.denza.apps.feature.mirrors.MirrorGuardAccessibilityService"

        val disabled = SimulcastAccessibilityAccess.withoutService(withGuard)
        val enabled = SimulcastAccessibilityAccess.withService(withGuard)

        assertEquals("system/service", disabled)
        assertEquals(
            "system/service:${SimulcastAccessibilityAccess.COMPONENT}",
            enabled,
        )
    }

    @Test
    fun `empty Android setting enables only simulcast service`() {
        assertEquals(
            SimulcastAccessibilityAccess.COMPONENT,
            SimulcastAccessibilityAccess.withService("null"),
        )
    }
}
