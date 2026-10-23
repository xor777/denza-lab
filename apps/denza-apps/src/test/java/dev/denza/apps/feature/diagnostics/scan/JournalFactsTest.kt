package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.scan.JournalFixtures.at
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screens ask of the journal, on the car of 20.10: LA_BCM read alone on 18.10, then four full
 * scans. Illustrative after 21.10: LA_BCM's B2148-02 goes, Media gains B1AC9-01, INS gets a new software
 * code, the selector does not answer once.
 */
class JournalFactsTest {
    private val zone = ZoneOffset.UTC
    private val ins2 = "62F1944B450011E0250037B2"
    private val lbcm7 = JournalFixtures.LBCM_LIST.replace("A1480208", "")
    private fun media2() = JournalFixtures.result(
        0x741, Triple("22F194", "62F1944B450011E0930034E2", 5), Triple("190109", "590109000002", 14),
        Triple("190209", "5902099AC800089AC90108", 15),
    )

    private val s0 = JournalFixtures.scan(at("2026-10-18T15:42:00Z"), listOf(JournalFixtures.lbcm()), JournalScanKind.MODULE, odometerKm = 13_150.0)
    private val s1 = JournalFixtures.scan(at("2026-10-20T19:31:29Z"), JournalFixtures.car2010())
    private val s2 = JournalFixtures.scan(at("2026-10-21T08:05:00Z"), JournalFixtures.car2010())
    private fun later() = listOf(
        JournalFixtures.ecm(), JournalFixtures.lbcm(lbcm7, 7), JournalFixtures.rzcu(), JournalFixtures.ins(ins2),
        media2(), JournalFixtures.silent(0x7A7), JournalFixtures.idc(),
    )
    private val s3 = JournalFixtures.scan(at("2026-10-22T07:55:00Z"), later(), vitals = JournalFixtures.VITALS.copy(lvVolts = 13.6, cellMaxMv = 3285.0))
    private val s4 = JournalFixtures.scan(at("2026-10-23T08:50:31Z"), later())
    private val data = listOf(s3, s0, s4, s1, s2).fold(JournalData()) { d, s -> d.with(s) }

    private fun key(address: Int, code: String) = JournalCodeKey(address, code)

    @Test
    fun aCodeIsThereSinceItsFirstReading() {
        assertEquals(listOf(s0, s1, s2, s3, s4), data.scans)
        val life = JournalFacts.life(data, key(0x720, "U0121-87"))!!
        assertEquals("LA_BCM", life.abbreviation)
        assertEquals(s0.atMillis, life.firstAtMillis)
        assertEquals(13_150.0, life.firstOdometerKm!!, 0.0)
        assertEquals(s4.atMillis, life.lastAtMillis)
        assertEquals(5, life.seenIn)
        assertEquals(5, life.readIn)
        assertEquals(listOf(s0, s1, s2, s3, s4).map { it.atMillis }, life.seenAt)
        assertFalse(life.isNew)
        assertEquals(0x08, life.record.status)

        // R_ZCU's are there since the first full scan: «в 4 сканах из 4».
        val rzcu = JournalFacts.life(data, key(0x747, "C1170-09"))!!
        assertEquals(s1.atMillis, rzcu.firstAtMillis)
        assertEquals(4 to 4, rzcu.seenIn to rzcu.readIn)

        // A code that went: its last reading, and the readings since its first that did not hold it.
        val went = JournalFacts.life(data, key(0x720, "B2148-02"))!!
        assertEquals(s2.atMillis, went.lastAtMillis)
        assertEquals(3 to 5, went.seenIn to went.readIn)

        // The selector did not answer on 22.10: that scan says nothing of its code.
        val p230e = JournalFacts.life(data, key(0x7A7, "P230E-07"))!!
        assertEquals(2 to 2, p230e.seenIn to p230e.readIn)
        assertNull(JournalFacts.life(data, key(0x720, "U9999-00")))
        assertNull(JournalFacts.life(JournalData(), key(0x720, "U0121-87")))
    }

    @Test
    fun aCodeIsNewOnlyAgainstTheModulesReadingBefore() {
        val at3 = JournalFacts.lives(data, s3).associateBy { it.key }
        assertTrue(at3.getValue(key(0x741, "B1AC9-01")).isNew)
        assertFalse(at3.getValue(key(0x741, "B1AC8-00")).isNew)
        assertEquals(17, at3.size)
        // The next scan held it too: no longer new.
        assertFalse(JournalFacts.life(data, key(0x741, "B1AC9-01"))!!.isNew)
        // The journal's first reading of a module has nothing new: there is nothing to compare.
        assertTrue(JournalFacts.lives(data, s0).none { it.isNew })
        assertTrue(JournalFacts.lives(data, s1).filter { it.key.address != 0x720 }.none { it.isNew })

        val gone = JournalFacts.gone(data, s3)
        assertEquals(listOf(key(0x720, "B2148-02")), gone.map { it.key })
        assertEquals(s2.atMillis, gone.single().lastAtMillis)
        assertEquals(emptyList<JournalGoneCode>(), JournalFacts.gone(data))
    }

    @Test
    fun twoScansSideBySide() {
        assertEquals(s2, JournalFacts.previous(data, s3))
        // The default «from» is the full scan before: a module read alone is not one.
        assertNull(JournalFacts.previous(data, s1))
        val c = JournalFacts.compare(s2, s3)
        assertEquals(listOf(key(0x741, "B1AC9-01")), c.added.map { it.key })
        assertEquals(listOf(key(0x720, "B2148-02")), c.gone.map { it.key })
        assertEquals(listOf(key(0x7A7, "P230E-07")), c.unread.map { it.key })
        assertEquals(16, c.same.size)
        assertTrue(c.same.none { it.statusChanged })
        assertEquals(
            listOf(JournalSoftwareDiff(0x723, "INS", "4B450011E0250037B1", "4B450011E0250037B2", null, null)),
            c.software,
        )
        val lv = c.quantities.first { it.quantity == JournalQuantity.LV_VOLTS }
        assertEquals(-0.1, lv.delta!!, 1e-9)
        assertEquals(1.0, c.quantities.first { it.quantity == JournalQuantity.CELL_SPREAD_MV }.delta!!, 1e-9)
        assertEquals(JournalQuantity.SCAN, c.quantities.map { it.quantity })
        // Nothing between two scans of the same answers.
        val none = JournalFacts.compare(s3, s4)
        assertTrue(none.added.isEmpty() && none.gone.isEmpty() && none.software.isEmpty())
        assertEquals(17, none.same.size)
    }

    @Test
    fun softwareUpdatesFromTheHistoryAndTheScans() {
        // No history: the scans show INS's new code on 22.10.
        val fromScans = JournalFacts.softwareEvents(data, SoftwareHistory(), zone)
        assertEquals(1, fromScans.size)
        with(fromScans.single()) {
            assertEquals(SoftwareKey(0x723), key)
            assertEquals("INS", abbreviation)
            assertEquals("4B450011E0250037B1" to "4B450011E0250037B2", oldCode to newCode)
            assertEquals(LocalDate.parse("2026-10-22"), day)
            assertEquals(s3.atMillis, atMillis)
        }
        // The history saw the same update the same day, with the version an opened block read, and a
        // module behind DUCU that no scan reads: each once, the scan's time where a scan held it.
        val history = SoftwareHistory(mapOf(
            SoftwareKey(0x723) to SoftwareRecord(
                SoftwareSeen("4B450011E0250037B2", "0348", LocalDate.parse("2026-10-22")),
                listOf(SoftwareChange("4B450011E0250037B1", "0347", "4B450011E0250037B2", "0348", LocalDate.parse("2026-10-22"))),
            ),
            SoftwareKey(0x750, 0xF8C4) to SoftwareRecord(
                SoftwareSeen("4B310011E0019A0003", null, LocalDate.parse("2026-10-19")),
                listOf(SoftwareChange("4B310011E0019A0002", null, "4B310011E0019A0003", null, LocalDate.parse("2026-10-19"))),
            ),
        ))
        val events = JournalFacts.softwareEvents(data, history, zone)
        assertEquals(listOf(SoftwareKey(0x750, 0xF8C4), SoftwareKey(0x723)), events.map { it.key })
        assertNull(events[0].atMillis)
        assertEquals(s3.atMillis, events[1].atMillis)
        assertEquals("0348", events[1].newVersion)
    }

    @Test
    fun usualIsTheMiddleEightyOfThirtyDays() {
        val now = at("2026-10-23T09:00:00Z")
        fun lv(iso: String, v: Double) = s1.copy(atMillis = at(iso), vitals = JournalFixtures.VITALS.copy(lvVolts = v))
        var d = JournalData()
        listOf(
            lv("2026-09-01T08:00:00Z", 11.0), // older than 30 days
            lv("2026-10-10T08:00:00Z", 13.5), lv("2026-10-12T08:00:00Z", 13.7),
            lv("2026-10-20T08:00:00Z", 13.6), lv("2026-10-22T08:00:00Z", 13.8),
        ).forEach { d = d.with(it) }
        assertNull(JournalFacts.usual(d, JournalQuantity.LV_VOLTS, now))
        d = d.with(lv("2026-10-22T18:00:00Z", 13.7))
        val usual = JournalFacts.usual(d, JournalQuantity.LV_VOLTS, now)!!
        assertEquals(5, usual.count)
        assertEquals(13.54, usual.p10, 1e-9)
        assertEquals(13.76, usual.p90, 1e-9)

        val days = JournalFacts.daily(d, JournalQuantity.LV_VOLTS, now, zone)
        assertEquals(30, days.size)
        assertEquals(LocalDate.parse("2026-09-24"), days.first().day)
        assertEquals(LocalDate.parse("2026-10-23"), days.last().day)
        assertNull(days.last().value)
        // Two scans on 22.10: their median.
        assertEquals(13.75, days.first { it.day == LocalDate.parse("2026-10-22") }.value!!, 1e-9)
        assertEquals(13.5, days.first { it.day == LocalDate.parse("2026-10-10") }.value!!, 1e-9)
        assertEquals(4, days.count { it.value != null })
    }

    @Test
    fun theWeakestCellAndTheSpreadAtFullCharge() {
        val base = at("2026-10-14T20:00:00Z")
        val mins = listOf(9, 9, 40, 9, 9, 9, 40, 9, 9)
        var d = data
        mins.forEachIndexed { i, cell ->
            val full = i % 3 == 2
            d = d.with(JournalFixtures.cells(base + i * 86_400_000L, cell, soc = if (full) 97.0 else 74.0, spread = if (full) 11 + i % 2 else 5))
        }
        assertEquals(JournalWeakestCell(9, 7, 9), JournalFacts.weakestCell(d))
        // Since the 18th only: 5 snapshots, cell 9 four times.
        assertEquals(JournalWeakestCell(9, 4, 5), JournalFacts.weakestCell(d, at("2026-10-18T00:00:00Z")))
        // A tie goes to the lower number.
        val tie = JournalData(cells = listOf(JournalFixtures.cells(1, 40), JournalFixtures.cells(2, 9)))
        assertEquals(JournalWeakestCell(9, 1, 2), JournalFacts.weakestCell(tie))
        assertNull(JournalFacts.weakestCell(JournalData()))

        val full = JournalFacts.points(d, JournalQuantity.FULL_CHARGE_SPREAD_MV)
        assertEquals(listOf(11.0, 12.0, 11.0), full.map { it.value })
        assertEquals(9, JournalFacts.points(d, JournalQuantity.SNAPSHOT_SPREAD_MV).size)
        assertEquals(base + 8 * 86_400_000L, JournalFacts.lastFullCharge(d)!!.atMillis)
    }
}
