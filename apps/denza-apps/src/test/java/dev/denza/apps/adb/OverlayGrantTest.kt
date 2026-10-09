package dev.denza.apps.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGrantTest {
    @Test
    fun theGrantIsThisAppopsCommand() {
        assertEquals(
            "cmd appops set dev.denza.apps SYSTEM_ALERT_WINDOW allow",
            OverlayGrant.command("dev.denza.apps"),
        )
    }
}
