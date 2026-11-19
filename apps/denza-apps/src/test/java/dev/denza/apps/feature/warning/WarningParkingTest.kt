package dev.denza.apps.feature.warning

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The recorder outside a `-Pwarnings` build: the sweep never asks for the slot, and no file is touched. */
class WarningParkingTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun withTheFlagOffTheSlotIsNeverAskedForAndTheJournalNeverOpened() {
        val dir = File(folder.root, "warnings")
        val journal = WarningJournal(dir)
        var asked = 0
        val wants = WarningRecorder.asksSlot(built = false) {
            asked++
            journal.open(0L)
            journal.isEnabled
        }
        assertFalse(wants)
        assertEquals(0, asked)
        assertFalse(dir.exists())
    }

    @Test
    fun withTheFlagOnTheJournalsSwitchDecides() {
        assertTrue(WarningRecorder.asksSlot(built = true) { true })
        assertFalse(WarningRecorder.asksSlot(built = true) { false })
    }

    /** An earlier build's record stays where it was: the parked recorder reads and deletes nothing. */
    @Test
    fun anEarlierJournalIsLeftInPlace() {
        val dir = File(folder.root, "warnings").apply { mkdirs() }
        val log = File(dir, "events.log").apply { writeText("kept") }
        WarningRecorder.asksSlot(built = false) { error("asked") }
        assertEquals("kept", log.readText())
    }
}
