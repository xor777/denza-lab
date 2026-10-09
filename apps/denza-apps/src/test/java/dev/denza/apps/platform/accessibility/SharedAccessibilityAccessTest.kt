package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAccessibilityAccessTest {
    @Test
    fun `recognizes canonical and shorthand components`() {
        assertTrue(
            SharedAccessibilityAccess.isEnabled(
                "system/service:${SharedAccessibilityAccess.COMPONENT}:voice/service",
            ),
        )
        assertTrue(
            SharedAccessibilityAccess.isEnabled(
                "system/service:dev.denza.apps/.SimulcastAccessibilityService",
            ),
        )
        assertFalse(SharedAccessibilityAccess.isEnabled("system/service:voice/service"))
    }

    @Test
    fun `rebind removes only simulcast service and restores it once`() {
        val original = "system/service:${SharedAccessibilityAccess.COMPONENT}:voice/service"

        val disabled = SharedAccessibilityAccess.withoutService(original)
        val enabled = SharedAccessibilityAccess.withService(disabled)

        assertEquals("system/service:voice/service", disabled)
        assertEquals(
            "system/service:voice/service:${SharedAccessibilityAccess.COMPONENT}",
            enabled,
        )
    }

    @Test
    fun `retired guard component is stripped and never restored`() {
        val withGuard = "system/service:${SharedAccessibilityAccess.COMPONENT}" +
            ":dev.denza.apps/dev.denza.apps.feature.mirrors.MirrorGuardAccessibilityService"

        val disabled = SharedAccessibilityAccess.withoutService(withGuard)
        val enabled = SharedAccessibilityAccess.withService(withGuard)

        assertEquals("system/service", disabled)
        assertEquals(
            "system/service:${SharedAccessibilityAccess.COMPONENT}",
            enabled,
        )
    }

    @Test
    fun `empty Android setting enables only simulcast service`() {
        assertEquals(
            SharedAccessibilityAccess.COMPONENT,
            SharedAccessibilityAccess.withService("null"),
        )
    }
}
