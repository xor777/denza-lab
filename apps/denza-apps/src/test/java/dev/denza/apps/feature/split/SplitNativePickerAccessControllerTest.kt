package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitNativePickerAccessControllerTest {
    @Test
    fun connectedServiceIsReboundLastAfterAnotherOwnerChangedGlobalSetting() {
        val split =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val shell = FakeAccessibilitySettings(listOf(system, split))
        val lease = FakeAccessLease(owned = true, configurationVersion = 6)

        SplitNativePickerAccessController(
            shell = shell::run,
            leaseStore = lease,
            pauseAfterDisable = {},
            isConnected = { true },
        ).enable(forceRebind = true)

        assertEquals(listOf(listOf(system), listOf(system, split)), shell.writes)
        assertEquals(listOf(false, true), shell.accessibilityEnableWrites)
    }

    @Test
    fun currentSettingIsReboundWhenFirmwareLeftServiceCrashedAfterBoot() {
        val split =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val shell = FakeAccessibilitySettings(listOf(system, split))
        val lease = FakeAccessLease(owned = true, configurationVersion = 6)

        SplitNativePickerAccessController(
            shell = shell::run,
            leaseStore = lease,
            pauseAfterDisable = {},
            isConnected = { false },
        ).enable()

        assertEquals(listOf(listOf(system), listOf(system, split)), shell.writes)
        assertEquals(listOf(false, true), shell.accessibilityEnableWrites)
    }

    @Test
    fun unsettledVersionIsReboundAfterAndroidProcessesDisable() {
        val split =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val timeline = mutableListOf<String>()
        val shell = FakeAccessibilitySettings(listOf(system, split)) {
            timeline += "write:${it.joinToString(":")}"
        }
        val lease = FakeAccessLease(owned = true, configurationVersion = 5)

        SplitNativePickerAccessController(
            shell = shell::run,
            leaseStore = lease,
            pauseAfterDisable = { timeline += "unbind-settled:$it" },
        ).enable()

        assertEquals(listOf(listOf(system), listOf(system, split)), shell.writes)
        assertEquals(
            listOf("write:$system", "unbind-settled:1000", "write:$system:$split"),
            timeline,
        )
        assertEquals(listOf(false, true), shell.accessibilityEnableWrites)
        assertEquals(6, lease.configurationVersion())
    }

    @Test
    fun staleEnabledServiceIsReboundWithoutLosingOtherServices() {
        val split =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        val system = "com.android.systemui/.custom.StatusBarAccessibilityService"
        val voice = "com.byd.autovoice/.SceneSayService"
        val shell = FakeAccessibilitySettings(listOf(system, voice, split))
        val lease = FakeAccessLease(owned = true)

        SplitNativePickerAccessController(
            shell = shell::run,
            leaseStore = lease,
            isConnected = { true },
        ).enable()

        assertEquals(listOf(system, voice, split), shell.services)
        assertEquals(listOf(listOf(system, voice), listOf(system, voice, split)), shell.writes)
        assertTrue(lease.isOwned())

        SplitNativePickerAccessController(
            shell = shell::run,
            leaseStore = lease,
            isConnected = { true },
        ).enable()

        assertEquals(2, shell.writes.size)
    }

    private class FakeAccessibilitySettings(
        initial: List<String>,
        private val onWrite: (List<String>) -> Unit = {},
    ) {
        var services = initial
        val writes = mutableListOf<List<String>>()
        val accessibilityEnableWrites = mutableListOf<Boolean>()

        fun run(command: String): String = when {
            command == "settings get secure enabled_accessibility_services" ->
                services.joinToString(":")
            command.startsWith("settings put secure enabled_accessibility_services '") -> {
                services = command
                    .substringAfter("settings put secure enabled_accessibility_services '")
                    .substringBefore("'")
                    .split(':')
                    .filter(String::isNotBlank)
                writes += services
                accessibilityEnableWrites += command.contains(
                    "settings put secure accessibility_enabled 1",
                )
                onWrite(services)
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }

    private class FakeAccessLease(
        private var owned: Boolean,
        private var configurationVersion: Int = 0,
    ) : SplitNativePickerAccessLeaseStore {
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
