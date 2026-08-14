package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitResizeabilityControllerTest {
    @Test
    fun enableCapturesMissingValueAndEnablesOverride() {
        val fake = FakeSetting(SplitGlobalSettingValue.MISSING)

        fake.controller().enable()

        assertEquals(SplitGlobalSettingValue.ENABLED, fake.current)
        assertEquals(SplitGlobalSettingValue.MISSING, fake.original)
        assertEquals(
            listOf(
                "settings get global force_resizable_activities",
                "settings put global force_resizable_activities 1",
                "settings get global force_resizable_activities",
            ),
            fake.commands,
        )
    }

    @Test
    fun restartKeepsTheFirstLease() {
        val fake = FakeSetting(
            current = SplitGlobalSettingValue.ENABLED,
            original = SplitGlobalSettingValue.MISSING,
        )

        fake.controller().enable()

        assertEquals(SplitGlobalSettingValue.MISSING, fake.original)
        assertEquals(
            listOf(
                "settings get global force_resizable_activities",
                "settings get global force_resizable_activities",
            ),
            fake.commands,
        )
    }

    @Test
    fun restoreDeletesSettingThatWasOriginallyMissing() {
        val fake = FakeSetting(
            current = SplitGlobalSettingValue.ENABLED,
            original = SplitGlobalSettingValue.MISSING,
        )

        fake.controller().restore()

        assertEquals(SplitGlobalSettingValue.MISSING, fake.current)
        assertNull(fake.original)
        assertEquals(
            listOf(
                "settings get global force_resizable_activities",
                "settings delete global force_resizable_activities",
                "settings get global force_resizable_activities",
            ),
            fake.commands,
        )
    }

    @Test
    fun preexistingEnabledSettingIsPreservedOnRestore() {
        val fake = FakeSetting(
            current = SplitGlobalSettingValue.ENABLED,
            original = SplitGlobalSettingValue.ENABLED,
        )

        fake.controller().restore()

        assertEquals(SplitGlobalSettingValue.ENABLED, fake.current)
        assertNull(fake.original)
        assertEquals(
            listOf("settings get global force_resizable_activities"),
            fake.commands,
        )
    }

    @Test
    fun externalChangeWinsOverLeaseRestore() {
        val fake = FakeSetting(
            current = SplitGlobalSettingValue.DISABLED,
            original = SplitGlobalSettingValue.MISSING,
        )

        fake.controller().restore()

        assertEquals(SplitGlobalSettingValue.DISABLED, fake.current)
        assertNull(fake.original)
        assertEquals(
            listOf("settings get global force_resizable_activities"),
            fake.commands,
        )
    }

    private class FakeSetting(
        var current: SplitGlobalSettingValue,
        var original: SplitGlobalSettingValue? = null,
    ) : SplitResizeabilityLeaseStore {
        val commands = mutableListOf<String>()

        fun controller() = SplitResizeabilityController(::shell, this)

        override fun loadOriginal(): SplitGlobalSettingValue? = original

        override fun saveOriginal(value: SplitGlobalSettingValue): Boolean {
            original = value
            return true
        }

        override fun clearOriginal(): Boolean {
            original = null
            return true
        }

        private fun shell(command: String): String {
            commands += command
            return when (command) {
                "settings get global force_resizable_activities" -> when (current) {
                    SplitGlobalSettingValue.MISSING -> "null"
                    SplitGlobalSettingValue.DISABLED -> "0"
                    SplitGlobalSettingValue.ENABLED -> "1"
                }
                "settings delete global force_resizable_activities" -> {
                    current = SplitGlobalSettingValue.MISSING
                    ""
                }
                "settings put global force_resizable_activities 0" -> {
                    current = SplitGlobalSettingValue.DISABLED
                    ""
                }
                "settings put global force_resizable_activities 1" -> {
                    current = SplitGlobalSettingValue.ENABLED
                    ""
                }
                else -> error("Unexpected command: $command")
            }
        }
    }
}
