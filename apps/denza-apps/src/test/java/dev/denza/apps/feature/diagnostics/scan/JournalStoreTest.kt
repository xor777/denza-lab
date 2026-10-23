package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.protocol.Monitor
import dev.denza.apps.feature.diagnostics.protocol.MonitorState
import dev.denza.apps.feature.diagnostics.scan.JournalFixtures.at
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journal's entries out of real replies, its lines there and back, and its files across a power cut and its limits. */
class JournalStoreTest {
    private val dir: File = Files.createTempDirectory("journal").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun full(at: Long) = JournalFixtures.scan(at, JournalFixtures.car2010())

    @Test
    fun aScanOfThe2010AnswersSaysWhatTheyHeld() {
        val scan = full(at("2026-10-20T19:31:29Z"))
        assertEquals(18, scan.modules.sumOf { it.records.size })
        val lbcm = scan.module(0x720)!!
        assertEquals("LA_BCM", lbcm.abbreviation)
        assertEquals(ModuleStatus.HISTORY, lbcm.status)
        assertTrue(lbcm.memoryRead)
        assertEquals(0x09, lbcm.requestMask)
        assertEquals(0x09, lbcm.availability)
        assertEquals(
            listOf("C1160-06", "U029D-87", "U0121-87", "B2148-02", "B2250-00", "B225B-00", "U0229-87", "U022A-87"),
            lbcm.records.map { it.code },
        )
        val u0121 = lbcm.records[2]
        assertEquals(JournalRecord("U0121-87", "C12187", 0x00, 0x08, "19 02 09"), u0121)
        // Scanned before its ungated F194: no code, and no guess at one.
        assertNull(lbcm.f194)
        assertEquals(59, lbcm.medianMs)
        // IDC's own availability 29 and its records as read.
        val idc = scan.module(0x597)!!
        assertEquals(0x29, idc.availability)
        assertEquals(listOf("C1E20-49", "C12D0-1C", "C12D0-1D", "C3200-7B"), idc.records.map { it.code })
        assertEquals("4B4D0011DE01140600", idc.f194)
        // The ECM: its software code, a clean memory, and its OBD lists empty.
        val ecm = scan.module(0x7E0)!!
        assertEquals("32440011E004000000", ecm.f194)
        assertEquals(emptyList<JournalRecord>(), ecm.records)
        assertEquals(false, ecm.mil)
        // 22 exchanges, their median 19 ms.
        assertEquals(22, scan.exchanges)
        assertEquals(19, scan.medianMs)
    }

    @Test
    fun theVitalsAreTheReadingsAtTheScansEnd() {
        val car = CarReadings(
            hub = mapOf(VehicleSignal.ODOMETER_KM to 13_199.7, VehicleSignal.CELL_MIN_MV to 3280.0, VehicleSignal.CELL_MAX_MV to 3284.0),
            extras = mapOf(
                DiagnosticExtra.LV to 13.7, DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0,
                DiagnosticExtra.INSULATION to 14_400.0, DiagnosticExtra.INSULATION_2 to 19_700.0,
                DiagnosticExtra.TEMP_MIN to 10.0, DiagnosticExtra.TEMP_MAX to 16.0, DiagnosticExtra.CHARGE_CURRENT to -0.4,
            ),
            extrasFresh = true,
        )
        val scan = JournalEntries.scan(JournalScanKind.FULL, JournalFixtures.car2010(), emptyMap(), car, at("2026-10-20T19:31:29Z"))
        assertEquals(13_199.7, scan.odometerKm!!, 0.0)
        val v = scan.vitals
        assertEquals(13.7, v.lvVolts!!, 0.0)
        assertEquals(74.0, v.soc!!, 0.0)
        assertEquals(14.4, v.insulationMohm!!, 1e-9)
        assertEquals(19.7, v.insulation2Mohm!!, 1e-9)
        assertEquals(4.0, v.cellSpreadMv!!, 0.0)
        assertEquals(6.0, v.probeSpreadC!!, 0.0)
        assertEquals(false, v.mil)
        // 41 01 00 07 F5 F4: the ECM's readiness since the codes were cleared.
        assertEquals(MonitorState.READY, v.readiness[Monitor.MISFIRE])
        // Stale extras are no reading.
        val stale = JournalEntries.vitals(car.copy(extrasFresh = false), emptyList())
        assertNull(stale.lvVolts)
        assertNull(stale.mil)
    }

    @Test
    fun aScanAndASnapshotComeBackFromTheirLines() {
        val scan = full(at("2026-10-20T19:31:29Z")).copy(vin = "LGXCE4CB0P0000000", vitals = JournalFixtures.VITALS.copy(
            readiness = mapOf(Monitor.MISFIRE to MonitorState.READY, Monitor.CATALYST to MonitorState.NOT_READY),
        ))
        val line = JournalJson.encode(scan)
        assertFalse(line.contains('\n'))
        assertEquals(scan, JournalJson.decodeScan(line))
        // A module without records is under 200 bytes: 68 of them and their records, about 14 KB a full
        // scan, is what the limits are counted in.
        val header = JournalJson.encode(scan.copy(modules = emptyList())).length
        val module = JournalJson.encode(scan.copy(modules = scan.modules.take(1))).length - header
        assertTrue("one module $module B", module < 200)

        val cells = JournalFixtures.cells(at("2026-10-22T23:40:00Z"), min = 9, soc = 97.0, spread = 12).copy(
            cellsMv = JournalFixtures.cells(0, 9, spread = 12).cellsMv.toMutableList().also { it[100] = null },
        )
        assertEquals(cells, JournalJson.decodeCells(JournalJson.encode(cells)))
        assertTrue(cells.fullCharge)
        assertEquals(12, cells.spreadMv)
        assertTrue(JournalJson.encode(cells).length < 1500)

        // Not an entry: cut, another format, an extreme outside the cells.
        assertNull(JournalJson.decodeScan(line.take(line.length / 2)))
        assertNull(JournalJson.decodeScan(line.replace("\"format\":1", "\"format\":2")))
        assertNull(JournalJson.decodeCells(JournalJson.encode(cells.copy(minCell = 167))))
    }

    @Test
    fun theFilesKeepEveryEntryInTimeOrder() {
        val store = FileJournalStore(File(dir, "journal"))
        assertEquals(JournalData(), store.load())
        val b = full(at("2026-10-21T08:05:00Z"))
        val a = full(at("2026-10-20T19:31:29Z"))
        store.add(b)
        store.add(a)
        store.add(JournalFixtures.cells(at("2026-10-21T07:36:12Z"), min = 9))
        val back = FileJournalStore(File(dir, "journal")).load()
        assertEquals(listOf(a, b), back.scans)
        assertEquals(1, back.cells.size)
        assertTrue(File(dir, "journal/${FileJournalStore.SCANS}").isFile)
        assertTrue(File(dir, "journal/${FileJournalStore.CELLS}").isFile)
    }

    @Test
    fun aLineCutByAPowerCutCostsOnlyItself() {
        val store = FileJournalStore(dir)
        val first = full(at("2026-10-20T19:31:29Z"))
        store.add(first)
        // The power goes mid-write: half of the second line reached the disk.
        val file = File(dir, FileJournalStore.SCANS)
        file.appendText(JournalJson.encode(full(at("2026-10-21T08:05:00Z"))).take(3000))
        assertEquals(listOf(first), FileJournalStore(dir).load().scans)
        // The next entry starts a line of its own; the cut one stays unreadable and alone.
        val third = full(at("2026-10-22T07:55:00Z"))
        FileJournalStore(dir).add(third)
        assertEquals(listOf(first, third), FileJournalStore(dir).load().scans)
        assertEquals(3, file.readLines().size)
        // Rubbish in the middle is skipped the same way.
        file.appendText("{not json\n")
        assertEquals(listOf(first, third), FileJournalStore(dir).load().scans)
    }

    @Test
    fun aFileGrownPastItsLimitKeepsItsNewest() {
        val store = FileJournalStore(dir, maxScans = 10, maxCells = 4)
        val base = at("2026-10-01T00:00:00Z")
        val scans = (0 until 12).map { JournalFixtures.scan(base + it * 3_600_000L, listOf(JournalFixtures.lbcm())) }
        scans.forEach(store::add)
        val file = File(dir, FileJournalStore.SCANS)
        // A tenth more is let in before a rewrite: 11 lines, then the 12th rewrote it to the newest ten.
        assertEquals(10, file.readLines().size)
        assertEquals(scans.takeLast(10), FileJournalStore(dir, maxScans = 10).load().scans)
        assertFalse(File(dir, FileJournalStore.SCANS + ".tmp").exists())

        // The byte budget bounds a file whatever its count: a rewrite keeps what fits in half of it.
        val small = FileJournalStore(dir, maxScans = 100, scanBytes = 4_000)
        repeat(20) { small.add(scans[it % 12].copy(atMillis = base + 100 * 3_600_000L + it)) }
        assertTrue(file.length() <= 4_000)
        assertTrue(FileJournalStore(dir, maxScans = 100).load().scans.isNotEmpty())

        // The snapshots have their own limit.
        repeat(6) { store.add(JournalFixtures.cells(base + it, min = 9)) }
        assertEquals(4, FileJournalStore(dir, maxCells = 4).load().cells.size)
        // And the memory the same as the default files.
        var data = JournalData()
        repeat(JournalLimits.SCANS + 3) { data = data.with(scans[0].copy(atMillis = it.toLong())) }
        assertEquals(JournalLimits.SCANS, data.scans.size)
        assertEquals(3L, data.scans.first().atMillis)
    }
}
