package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitNativePickerAccessControllerTest {
    @Test
    fun staleEnabledServiceIsReboundWithoutLosingOtherServices() {
        val split =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val voice = "com.byd.autovoice/.SceneSayService"
        val shell = FakeAccessibilitySettings(listOf(system, voice, split))
        val lease = FakeAccessLease(owned = true)

        SplitNativePickerAccessController(shell::run, lease).enable()

        assertEquals(listOf(system, voice, split), shell.services)
        assertEquals(listOf(listOf(system, voice), listOf(system, voice, split)), shell.writes)
        assertTrue(lease.isOwned())

        SplitNativePickerAccessController(shell::run, lease).enable()

        assertEquals(2, shell.writes.size)
    }

    private class FakeAccessibilitySettings(initial: List<String>) {
        var services = initial
        val writes = mutableListOf<List<String>>()

        fun run(command: String): String = when {
            command == "settings get secure enabled_accessibility_services" ->
                services.joinToString(":")
            command.startsWith("settings put secure enabled_accessibility_services '") -> {
                services = command
                    .substringAfter("settings put secure enabled_accessibility_services '")
                    .substringBefore("';")
                    .split(':')
                    .filter(String::isNotBlank)
                writes += services
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }

    private class FakeAccessLease(
        private var owned: Boolean,
    ) : SplitNativePickerAccessLeaseStore {
        private var configurationVersion = 0

        override fun isOwned(): Boolean = owned

        override fun setOwned(owned: Boolean): Boolean {
            this.owned = owned
            return true
        }

        override fun configurationVersion(): Int = configurationVersion

        override fun setConfigurationVersion(version: Int): Boolean {
            configurationVersion = version
            return true
        }
    }
}
