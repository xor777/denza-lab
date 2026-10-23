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
        assertEquals(148, scan.exchanges)
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

    private fun FakeDiagnosticBus.sweep(atMs: Long) {
        val ns = atMs * 1_000_000L
        page(BmsPage.COUNTS, "A60ECC38041B0028", ns)
        page(BmsPage.CELLS, "00EE0CEF0CEE0C47", ns)
        page(BmsPage.CELLS, "01E80CF10CEE0C47", ns + 100_000_000L)
        page(BmsPage.PROBES, "0036373839363700".let { p ->
            val body = p.chunked(2).take(7).map { it.toInt(16) }
            p.take(14) + "%02X".format((255 - body.sum()).mod(256))
        }, ns)
    }

    @Test
    fun theCellsAreSavedOnlyAsAWholeSweepAndOnlyWhenAsked() = runTest {
        val bus = carNow()
        val journal = MemoryJournalStore()
        val s = session(bus, journal)
        s.start()
        runCurrent()
        // Not shown: nothing to save.
        s.saveCells()
        runCurrent()
        assertTrue(s.journal.value.cells.isEmpty())

        s.setCellsVisible(true)
        runCurrent()
        bus.sweep(atMs = testScheduler.currentTime)
        runCurrent()
        // The first sweep is still filling: half a sweep is not a snapshot.
        s.saveCells()
        runCurrent()
        assertTrue(s.journal.value.cells.isEmpty())

        // The page numbers go back: the sweep ended and is drawn.
        bus.page(BmsPage.CELLS, "00EE0CEF0CEE0C47", (testScheduler.currentTime + 200) * 1_000_000L)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        s.saveCells()
        runCurrent()
        val snap = s.journal.value.cells.single()
        assertEquals(journal.data.cells, listOf(snap))
        assertEquals(listOf(3310, 3311, 3310, 3304, 3313, 3310), snap.cellsMv.take(6))
        assertEquals(166, snap.cellsMv.size)
        assertEquals(4, snap.minCell)
        assertEquals(5, snap.maxCell)
        assertEquals(9, snap.spreadMv)
        assertEquals(listOf(14, 15, 16, 17, 14, 15), snap.probesC.take(6))
        assertEquals(74.0, snap.soc!!, 0.0)
        assertFalse(snap.fullCharge)
        assertEquals(1, snap.minProbe)
        // Saving is no scan.
        assertEquals(1, s.journal.value.scans.size)
        s.stop()
    }
}
