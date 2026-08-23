package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract 1.12: the pair SmartMulti remembers is borrowed for a session and given back.
 *
 * The live consequence of not giving it back was rated РАЗДРАЖАЕТ in the vertical slice: after a
 * Denza session the firmware still had `dev.denza.apps` as its primary, and opening Denza Apps from
 * the dock on a clean Home brought ADAS up beside it (1.9.2, 1.9.3).
 */
class SplitSmartMultiControllerTest {

    private val firmware = mutableMapOf(
        "byd_smart_multi_primary_activity" to "com.android.launcher3",
        "byd_smart_multi_primary_position" to "2",
        "byd_smart_multi_second_activity" to "com.byd.sr",
        "byd_smart_multi_split_window_mode" to "102",
    )
    private val commands = mutableListOf<String>()
    private val store = FakeSmartMultiLeaseStore()

    private val controller = SplitSmartMultiController(::shell, store)

    @Test
    fun takingTheLeaseReadsEveryKeyOnceAndWritesNothing() {
        controller.enable()

        assertEquals("one round trip, not one per key", 1, commands.size)
        assertTrue(commands.single().startsWith("settings get system "))
        assertEquals(
            mapOf(
                "byd_smart_multi_primary_activity" to "com.android.launcher3",
                "byd_smart_multi_second_activity" to "com.byd.sr",
                "byd_smart_multi_primary_position" to "2",
                "byd_smart_multi_split_window_mode" to "102",
            ),
            store.original,
        )
    }

    @Test
    fun aSessionAlreadyHoldingTheLeaseDoesNotRejournalWhatItAlreadyChanged() {
        controller.enable()
        firmware["byd_smart_multi_primary_activity"] = SPLIT_HOST_PACKAGE

        controller.enable()

        assertEquals("com.android.launcher3", store.original?.get("byd_smart_multi_primary_activity"))
    }

    @Test
    fun restoreGivesBackOnlyTheKeysTheSessionChanged() {
        controller.enable()
        firmware["byd_smart_multi_primary_activity"] = SPLIT_HOST_PACKAGE
        firmware["byd_smart_multi_second_activity"] = MUSIC
        commands.clear()

        controller.restore()

        assertEquals("com.android.launcher3", firmware["byd_smart_multi_primary_activity"])
        assertEquals("com.byd.sr", firmware["byd_smart_multi_second_activity"])
        assertEquals(
            "the two untouched keys are not written",
            2,
            commands.count { it.startsWith("settings put system ") },
        )
        assertNull("and the lease is over", store.original)
    }

    /** A key that was not set at all is a real state, and putting `null` back is deleting it. */
    @Test
    fun aKeyThatWasNeverSetIsDeletedRatherThanFilledIn() {
        firmware.remove("byd_smart_multi_second_activity")
        controller.enable()
        firmware["byd_smart_multi_second_activity"] = MUSIC

        controller.restore()

        assertNull(firmware["byd_smart_multi_second_activity"])
        assertTrue(commands.any { it == "settings delete system byd_smart_multi_second_activity" })
    }

    @Test
    fun restoringWithoutTheLeaseTouchesNothingAtAll() {
        controller.restore()

        assertEquals(emptyList<String>(), commands)
    }

    @Test
    fun anUnchangedPairIsNotWrittenBackOverItself() {
        controller.enable()
        commands.clear()

        controller.restore()

        assertEquals("only the read", 1, commands.size)
        assertNull(store.original)
    }

    private fun shell(command: String): String {
        commands += command
        return when {
            command.startsWith("settings get system ") -> command.split(';').joinToString("\n") {
                firmware[it.trim().removePrefix("settings get system ")] ?: "null"
            }
            command.startsWith("settings put system ") -> {
                val parts = command.removePrefix("settings put system ").split(' ', limit = 2)
                firmware[parts[0]] = parts[1].trim('\'')
                ""
            }
            command.startsWith("settings delete system ") -> {
                firmware.remove(command.removePrefix("settings delete system "))
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }
}
