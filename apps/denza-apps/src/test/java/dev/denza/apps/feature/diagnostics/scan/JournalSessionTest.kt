package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.BmsPage
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journal in the session (architecture, 6.4): only what the user started goes in. */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalSessionTest {
    private val car = FakeCar(
        CarReadings(
            hub = mapOf(
                VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.ODOMETER_KM to 13_199.7,
                VehicleSignal.CELL_MIN_MV to 3280.0, VehicleSignal.CELL_MAX_MV to 3284.0,
            ),
            sweep = 1,
            extras = mapOf(
                DiagnosticExtra.LV to 13.7, DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0,
                DiagnosticExtra.INSULATION to 14_400.0, DiagnosticExtra.INSULATION_2 to 19_700.0, DiagnosticExtra.CHARGE_CURRENT to 0.0,
            ),
            extrasFresh = true,
        ),
    )

    private fun TestScope.session(bus: FakeDiagnosticBus, journal: JournalStore, store: ScanStore = MemoryStore()) = DiagnosticsSession(
        buses = { bus },
        store = store,
        car = car,
        sinks = StreamSinks { null },
        wallClock = { Fixtures.EPOCH + testScheduler.currentTime },
        zone = ZoneOffset.UTC,
        context = backgroundScope.coroutineContext,
        carNanos = { testScheduler.currentTime * 1_000_000L },
        journalStore = journal,
    )

    @Test
    fun theAutoscanOnOpeningIsOneEntryAndAModuleReadAfterItAnother() = runTest {
        val earlier = JournalFixtures.scan(Fixtures.EPOCH - 86_400_000L, listOf(JournalFixtures.lbcm()), JournalScanKind.MODULE)
        val store = MemoryJournalStore(JournalData(scans = listOf(earlier)))
        val s = session(carNow(), store)
        s.start()
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        val journal = s.journal.value
        assertEquals(listOf(earlier.atMillis, Fixtures.EPOCH), journal.scans.map { it.atMillis })
        val scan = journal.latest!!
        assertEquals(JournalScanKind.FULL, scan.kind)
        assertEquals(68, scan.modules.size)
        // The scan of 2026-10-20 with the reads of 2026-10-23: the VIN at VCU and ECM, mask 0D where it is asked.
        assertEquals(155, scan.exchanges)
        assertEquals(13_199.7, scan.odometerKm!!, 0.0)
        assertEquals(13.7, scan.vitals.lvVolts!!, 0.0)
        assertEquals(14.4, scan.vitals.insulationMohm!!, 1e-9)
        assertEquals(4.0, scan.vitals.cellSpreadMv!!, 0.0)
        val lbcm = scan.module(0x720)!!
        assertEquals(8, lbcm.records.size)
        assertEquals("4B310011E0019A0002", lbcm.f194)
        assertEquals(store.data, journal)
        // LA_BCM's codes are there since the reading the day before.
        assertEquals(earlier.atMillis, JournalFacts.life(journal, JournalCodeKey(0x720, "U0121-87"))!!.firstAtMillis)

        s.readModule(0x720)
        runCurrent()
        val read = s.journal.value.latest!!
        assertEquals(JournalScanKind.MODULE, read.kind)
        assertEquals(listOf(0x720), read.modules.map { it.address })
        assertEquals(3, store.data.scans.size)
        s.stop()
    }

    @Test
    fun aScanShownFromDiskIsNoEntryUntilAskedAgain() = runTest {
        // A scan five minutes old is shown, not made: nothing goes into the journal.
        val stored = MemoryStore()
        val first = session(carNow(), MemoryJournalStore(), stored)
        first.start()
        runCurrent()
        first.stop()
        advanceTimeBy(5 * 60_000L)
        val journal = MemoryJournalStore()
        val s = session(carNow(), journal, stored)
        s.start()
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        assertTrue(s.journal.value.scans.isEmpty())
        // «Опросить снова» is.
        s.rescan()
        runCurrent()
        assertEquals(1, journal.data.scans.size)
        assertEquals(JournalScanKind.FULL, journal.data.scans.single().kind)
        s.stop()
    }

    /** A pack of six cells and six probes: `43A` says so, two pages of cells and one of probes. */
    private fun FakeDiagnosticBus.counts(atMs: Long) = page(BmsPage.COUNTS, "06064C38041B0028", atMs * 1_000_000L)

    private fun FakeDiagnosticBus.cells(page: Int, atMs: Long) =
        page(BmsPage.CELLS, if (page == 0) "00EE0CEF0CEE0C47" else "01E80CF10CEE0C47", atMs * 1_000_000L)

    private fun FakeDiagnosticBus.probes(atMs: Long) = page(BmsPage.PROBES, "0036373839363700".let { p ->
        val body = p.chunked(2).take(7).map { it.toInt(16) }
        p.take(14) + "%02X".format((255 - body.sum()).mod(256))
    }, atMs * 1_000_000L)

    /** Pages 0 and 1 and the probes: a whole sweep, which ends when page 0 comes again. */
    private fun FakeDiagnosticBus.sweep(atMs: Long) {
        cells(0, atMs)
        probes(atMs)
        cells(1, atMs + 100)
    }

    @Test
    fun theFirstWholeSweepOfAnOpeningIsTheSnapshotAndRefreshTakesAnother() = runTest {
        val bus = carNow()
        val journal = MemoryJournalStore()
        val s = session(bus, journal)
        s.start()
        runCurrent()
        // Not shown: nothing is listened to, and «Обновить» does nothing.
        s.refreshCells()
        runCurrent()
        assertTrue(s.journal.value.cells.isEmpty())

        s.setCellsVisible(true)
        runCurrent()
        val t = testScheduler.currentTime
        bus.counts(t)
        // Joined mid-sweep: page 1, then page 0 ends a sweep that holds half the cells - no snapshot.
        bus.cells(1, t)
        bus.sweep(t + 200)
        runCurrent()
        assertTrue("half a sweep is not a snapshot", s.journal.value.cells.isEmpty())

        // Page 0 again: the sweep 0, 1 ended whole and is drawn - the opening's snapshot, by itself.
        bus.cells(0, t + 500)
        runCurrent()
        val snap = s.journal.value.cells.single()
        assertEquals(journal.data.cells, listOf(snap))
        assertEquals(listOf(3310, 3311, 3310, 3304, 3313, 3310), snap.cellsMv)
        assertEquals(4, snap.minCell)
        assertEquals(5, snap.maxCell)
        assertEquals(9, snap.spreadMv)
        assertEquals(listOf(14, 15, 16, 17, 14, 15), snap.probesC)
        assertEquals(74.0, snap.soc!!, 0.0)
        assertFalse(snap.fullCharge)
        assertEquals(1, snap.minProbe)

        // More sweeps of the same opening: no second snapshot.
        bus.sweep(t + 600)
        bus.cells(0, t + 900)
        runCurrent()
        assertEquals(1, s.journal.value.cells.size)

        // «Обновить»: the cells start over, and their first whole sweep is one more.
        s.refreshCells()
        runCurrent()
        bus.counts(t + 1_000)
        bus.sweep(t + 1_000)
        bus.cells(0, t + 1_300)
        runCurrent()
        assertEquals(2, s.journal.value.cells.size)

        // Left and opened again: one more.
        s.setCellsVisible(false)
        s.setCellsVisible(true)
        runCurrent()
        bus.counts(t + 2_000)
        bus.sweep(t + 2_000)
        bus.cells(0, t + 2_300)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, s.journal.value.cells.size)
        // A snapshot is no scan.
        assertEquals(1, s.journal.value.scans.size)
        s.stop()
    }
}
