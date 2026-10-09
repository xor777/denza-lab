package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The two commands with which the accessibility repair and the split's picker lease read and write
 * the car's `enabled_accessibility_services`, letter for letter (pinned 2026-10-09).
 */
class AccessibilityServiceSettingsTest {
    private val sent = mutableListOf<String>()

    private fun settings(reply: String = "") = AccessibilityServiceSettings { command ->
        sent += command
        reply
    }

    @Test
    fun `the read asks for the secure list and keeps each entry once`() {
        val entries = settings(" a/.A:b/.B: :a/.A\n").read()

        assertEquals(listOf("settings get secure enabled_accessibility_services"), sent)
        assertEquals(listOf("a/.A", "b/.B"), entries)
    }

    @Test
    fun `an empty or null setting reads as no entries`() {
        assertEquals(emptyList<String>(), settings("null\n").read())
        assertEquals(emptyList<String>(), settings("").read())
    }

    @Test
    fun `a write puts the list in quotes and switches accessibility on only when asked`() {
        settings().write(listOf(SIMULCAST, SPLIT), ensureAccessibilityEnabled = true)
        settings().write(listOf(OTHER, SIMULCAST, OTHER), ensureAccessibilityEnabled = false)
        settings().write(emptyList(), ensureAccessibilityEnabled = false)

        assertEquals(
            listOf(
                "settings put secure enabled_accessibility_services " +
                    "'dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService:" +
                    "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService'" +
                    "; settings put secure accessibility_enabled 1",
                "settings put secure enabled_accessibility_services " +
                    "'com.other/.Observer:dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService'",
                "settings put secure enabled_accessibility_services ''",
            ),
            sent,
        )
    }

    @Test
    fun `a quote inside an entry closes the word, is escaped and reopens it`() {
        settings().write(listOf("a'b/.C"), ensureAccessibilityEnabled = false)

        assertEquals("settings put secure enabled_accessibility_services 'a'\\''b/.C'", sent.single())
    }

    @Test
    fun `a write the shell answers with an error throws`() {
        assertThrows(IllegalStateException::class.java) {
            settings("Error: bad").write(listOf(OTHER), ensureAccessibilityEnabled = false)
        }
    }

    private companion object {
        const val SIMULCAST = "dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService"
        const val SPLIT = "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        const val OTHER = "com.other/.Observer"
    }
}
