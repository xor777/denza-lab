package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val COMPOUND_ENABLE =
    "settings get global force_resizable_activities; " +
        "settings put global force_resizable_activities 1; " +
        "settings get global force_resizable_activities"

class SplitResizeabilityControllerTest {
    @Test
    fun enableCapturesMissingValueAndEnablesOverride() {
        val fake = FakeSetting(SplitGlobalSettingValue.MISSING)

        fake.controller().enable()

        assertEquals(SplitGlobalSettingValue.ENABLED, fake.current)
        assertEquals(SplitGlobalSettingValue.MISSING, fake.original)
        assertEquals(
            "1.13.3: the open path pays one round trip for this lease, not three",
            listOf(COMPOUND_ENABLE),
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
        assertEquals(listOf(COMPOUND_ENABLE), fake.commands)
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

        /** A shell, so a chain of statements answers one line per `get` and nothing else. */
        private fun shell(command: String): String {
            commands += command
            return command.split(';').mapNotNull { statement -> run(statement.trim()) }
                .joinToString("\n")
        }

        private fun run(statement: String): String? = when (statement) {
            "settings get global force_resizable_activities" -> when (current) {
                SplitGlobalSettingValue.MISSING -> "null"
                SplitGlobalSettingValue.DISABLED -> "0"
                SplitGlobalSettingValue.ENABLED -> "1"
            }
            "settings delete global force_resizable_activities" -> {
                current = SplitGlobalSettingValue.MISSING
                null
            }
            "settings put global force_resizable_activities 0" -> {
                current = SplitGlobalSettingValue.DISABLED
                null
            }
            "settings put global force_resizable_activities 1" -> {
                current = SplitGlobalSettingValue.ENABLED
                null
            }
            else -> error("Unexpected statement: $statement")
        }
    }
}
