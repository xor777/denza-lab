package dev.denza.apps.feature.warning

import dev.denza.apps.feature.warning.WarningEventKind.CLEARED
import dev.denza.apps.feature.warning.WarningEventKind.CODE
import dev.denza.apps.feature.warning.WarningEventKind.LINK
import dev.denza.apps.feature.warning.WarningEventKind.LOST
import dev.denza.apps.feature.warning.WarningEventKind.OFF
import dev.denza.apps.feature.warning.WarningEventKind.ON
import dev.denza.apps.feature.warning.WarningEventKind.START
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WarningJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A car answering every second from wall time [WALL] and elapsed time zero. */
    private class Car(val journal: WarningJournal) {
        var elapsed = 0L
        fun wall() = WALL + elapsed
        fun answer(code: Int?, afterMs: Long = 1_000L) {
            elapsed += afterMs
            journal.sample(code, wall(), elapsed)
        }
    }

    private fun journal(dir: File = folder.root, half: Long = WarningJournal.HALF_BYTES) =
        WarningJournal(dir, half).also { it.open(WALL) }

    private fun kinds(journal: WarningJournal) = journal.state().events.reversed().map { it.kind to it.code }

    @Test
    fun theFirstAnswerIsTheSlotAsFoundAndRepeatsWriteNothing() {
        val car = Car(journal())
        repeat(5) { car.answer(0) }
        assertEquals(listOf(START to null, LINK to 0), kinds(car.journal))
        assertEquals(2, File(folder.root, "events.log").readLines().size)
        assertTrue(car.journal.state().sightings.isEmpty())
    }

    @Test
    fun everyChangeIsKeptAndABackAndForthCountsTwice() {
        val car = Car(journal())
        listOf(0, 0x13F, 0x13F, 0x7D, 0x13F, 0).forEach { car.answer(it) }
        assertEquals(
            listOf(START to null, LINK to 0, CODE to 0x13F, CODE to 0x7D, CODE to 0x13F, CODE to 0),
            kinds(car.journal),
        )
        val seen = car.journal.state().sightings.associateBy { it.code }
        assertEquals(2, seen.getValue(0x13F).count)
        assertEquals(WALL + 2_000, seen.getValue(0x13F).firstMs)
        assertEquals(WALL + 5_000, seen.getValue(0x13F).lastMs)
        assertEquals(1, seen.getValue(0x7D).count)
        // the most recent sighting leads: it is the page's last warning
        assertEquals(0x13F, car.journal.state().sightings.first().code)
    }

    @Test
    fun aFewMissedSweepsAreNotALoss() {
        val car = Car(journal())
        car.answer(0x13F)
        car.answer(null)
        car.answer(null, afterMs = 4_000)
        car.answer(0x13F, afterMs = 4_000)
        assertEquals(listOf(START to null, LINK to 0x13F), kinds(car.journal))
        assertEquals(true, car.journal.state().linked)
    }

    @Test
    fun silenceThatLastsIsALossFromItsFirstMomentAndTheNextAnswerALink() {
        val car = Car(journal())
        car.answer(0x13F)
        car.answer(null)
        val firstSilence = car.wall()
        car.answer(null, afterMs = 4_000)
        car.answer(null, afterMs = 8_000)
        val state = car.journal.state()
        assertEquals(LOST, state.events.first().kind)
        assertEquals(firstSilence, state.events.first().atMs)
        assertEquals(false, state.linked)
        assertEquals(firstSilence, state.lostAtMs)
        // silence after the loss writes nothing more
        car.answer(null, afterMs = 60_000)
        assertEquals(3, kinds(car.journal).size)
        // the same warning found again is a link, and it was seen again
        car.answer(0x13F, afterMs = 4_000)
        assertEquals(listOf(START to null, LINK to 0x13F, LOST to null, LINK to 0x13F), kinds(car.journal))
        assertEquals(2, car.journal.state().sightings.single().count)
        assertNull(car.journal.state().lostAtMs)
    }

    @Test
    fun aCarThatNeverAnsweredIsLostToo() {
        val car = Car(journal())
        car.answer(null)
        car.answer(null, afterMs = 10_000)
        assertEquals(listOf(START to null, LOST to null), kinds(car.journal))
    }

    @Test
    fun aGapBetweenCallsIsALossFromTheLastAnswer() {
        val car = Car(journal())
        car.answer(0)
        val lastAnswer = car.wall()
        // the head unit slept: nobody asked for a minute
        car.answer(0, afterMs = 60_000)
        val events = car.journal.state().events.reversed()
        assertEquals(listOf(START to null, LINK to 0, LOST to null, LINK to 0), events.map { it.kind to it.code })
        assertEquals(lastAnswer, events[2].atMs)
    }

    @Test
    fun theRecordOutlivesTheProcess() {
        val first = Car(journal())
        listOf(0, 0x1DD, 0).forEach { first.answer(it) }
        val since = first.journal.state().sinceMs

        val reopened = WarningJournal(folder.root)
        val state = reopened.open(WALL + 100_000)
        assertEquals(since, state.sinceMs)
        assertEquals(1, state.sightings.single { it.code == 0x1DD }.count)
        assertEquals(
            listOf(START to null, LINK to 0, CODE to 0x1DD, CODE to 0, START to null),
            state.events.reversed().map { it.kind to it.code },
        )
        // a new process does not know the slot: its first answer is a link, not a repeat
        Car(reopened).apply { elapsed = 5_000 }.answer(0)
        assertEquals(LINK to 0, reopened.state().events.first().let { it.kind to it.code })
    }

    @Test
    fun theRingDropsItsOldestHalfAndTheCodesStay() {
        val car = Car(journal(half = 400))
        car.answer(0)
        car.answer(0x1DD)
        repeat(60) { car.answer(if (it % 2 == 0) 0x13F else 0) }
        val current = File(folder.root, "events.log")
        val previous = File(folder.root, "events.old.log")
        assertTrue(current.length() < 400 + 40)
        assertTrue(previous.exists())
        val kept = car.journal.allEvents()
        assertFalse("the early warning's line is gone from the ring", kept.any { it.code == 0x1DD })
        assertEquals(1, car.journal.state().sightings.single { it.code == 0x1DD }.count)
        assertEquals(WALL, car.journal.state().sinceMs)
    }

    @Test
    fun thePageKeepsTheNewestEvents() {
        val car = Car(journal())
        repeat(WarningJournal.VIEW + 50) { car.answer(if (it % 2 == 0) 0x13F else 0) }
        val events = car.journal.state().events
        assertEquals(WarningJournal.VIEW, events.size)
        assertTrue(events.first().atMs > events.last().atMs)
        assertEquals(WarningJournal.VIEW, WarningJournal(folder.root).open(WALL).events.size)
    }

    @Test
    fun offRecordsNothingAndSurvivesARestart() {
        val car = Car(journal())
        car.answer(0)
        car.journal.setEnabled(false, car.wall())
        car.answer(0x13F)
        assertEquals(listOf(START to null, LINK to 0, OFF to null), kinds(car.journal))
        assertFalse(car.journal.state().enabled)

        val reopened = WarningJournal(folder.root)
        assertFalse(reopened.open(WALL).enabled)
        assertFalse(reopened.isEnabled)
        reopened.setEnabled(true, WALL + 10)
        val again = Car(reopened)
        again.answer(0x13F)
        assertEquals(listOf(ON to null, LINK to 0x13F), kinds(reopened).takeLast(2))
    }

    @Test
    fun clearingStartsTheRecordAgain() {
        val car = Car(journal())
        listOf(0, 0x1DD, 0).forEach { car.answer(it) }
        car.journal.clear(WALL + 50_000)
        val state = car.journal.state()
        assertEquals(listOf(CLEARED to null), state.events.map { it.kind to it.code })
        assertTrue(state.sightings.isEmpty())
        assertEquals(WALL + 50_000, state.sinceMs)
        assertFalse(File(folder.root, "events.old.log").exists())
        // the slot is looked at afresh
        car.answer(0)
        assertEquals(LINK to 0, car.journal.state().events.first().let { it.kind to it.code })
        assertEquals(WALL + 50_000, WarningJournal(folder.root).open(WALL + 60_000).sinceMs)
    }

    @Test
    fun aLineCutByAPowerLossIsSkipped() {
        val car = Car(journal())
        car.answer(0x13F)
        File(folder.root, "events.log").appendText("1729520000000\tco")
        File(folder.root, "summary.tsv").appendText("1DD\t17")
        val state = WarningJournal(folder.root).open(WALL + 10_000)
        assertEquals(listOf(START to null, LINK to 0x13F, START to null), state.events.reversed().map { it.kind to it.code })
        assertEquals(listOf(0x13F), state.sightings.map { it.code })
    }

    @Test
    fun aLineIsTheEventAndNothingElse() {
        val event = WarningEvent(1_729_520_000_123, CODE, 0x1DD)
        assertEquals("1729520000123\tcode\t1DD\n", WarningJournal.line(event))
        assertEquals(event, WarningJournal.parse(WarningJournal.line(event).trimEnd()))
        assertNull(WarningJournal.parse("1729520000123\tcode\t"))
        assertNull(WarningJournal.parse("1729520000123\tlost\t13F"))
    }

    private companion object {
        /** 2026-10-21 17:00:00 Moscow. */
        const val WALL = 1_792_591_200_000L
    }
}
