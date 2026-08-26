package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbSystemSwitchTest {
    @Test
    fun `zero is the only reading that proves the prompt cannot appear`() {
        assertEquals(AdbSystemSwitch.DISABLED, AdbSystemSwitchReader.classify(0))
    }

    @Test
    fun `any non-zero reading means the system can still show a prompt`() {
        assertEquals(AdbSystemSwitch.ENABLED, AdbSystemSwitchReader.classify(1))
        assertEquals(AdbSystemSwitch.ENABLED, AdbSystemSwitchReader.classify(2))
    }

    @Test
    fun `an unreadable or absent flag is not an off switch`() {
        assertEquals(AdbSystemSwitch.UNKNOWN, AdbSystemSwitchReader.classify(null))
    }
}
