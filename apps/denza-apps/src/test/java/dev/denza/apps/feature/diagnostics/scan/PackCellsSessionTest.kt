package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.BmsPage
import dev.denza.apps.feature.diagnostics.bus.DiagFailure
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Ячейки» in the session (architecture 6.3): listened to only while shown and not paused, beside the autoscan, once a showing. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackCellsSessionTest {
    private fun TestScope.session(bus: FakeDiagnosticBus) = DiagnosticsSession(
        buses = { bus },
        store = MemoryStore(),
        car = FakeCar(),
        sinks = StreamSinks { null },
        wallClock = { Fixtures.EPOCH + testScheduler.currentTime },
        zone = ZoneOffset.UTC,
        context = backgroundScope.coroutineContext,
        carNanos = { testScheduler.currentTime * 1_000_000L },
    )

    private fun FakeDiagnosticBus.pages(atMs: Long) {
        page(BmsPage.COUNTS, "A60ECC38041B0028", atMs * 1_000_000L)
        page(BmsPage.CELLS, "00EE0CEF0CEE0C47", atMs * 1_000_000L)
        page(BmsPage.PROBES, "0036373839363700".let { p ->
            val body = p.chunked(2).take(7).map { it.toInt(16) }
            p.take(14) + "%02X".format((255 - body.sum()).mod(256))
        }, atMs * 1_000_000L)
    }

    @Test
    fun theCellsAreListenedToOnlyWhileShownAndTheAutoscanGoesOn() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        runCurrent()
        assertNull(s.model.value.packCells)
        assertEquals(emptyList<String>(), bus.subscriptions)

        s.setCellsVisible(true)
        runCurrent()
        assertEquals(listOf("s"), bus.subscriptions)
        assertEquals("ждём страницы BMS", s.model.value.packCells!!.age)

        bus.pages(atMs = testScheduler.currentTime)
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        val v = s.model.value.packCells!!
        assertEquals("НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · 166", v.cellsHead)
        assertEquals("ТЕМПЕРАТУРА ДАТЧИКОВ · 14", v.probesHead)
        assertEquals(listOf("3 310", "3 311", "3 310"), v.cells.take(3).map { it.text })
        assertEquals("—", v.cells[3].text)
        assertEquals(listOf("14", "15", "16", "17", "14", "15"), v.probes.take(6).map { it.text })
        assertEquals("обход не завершён · 0,5 с назад", v.age)

        // Listening only: the autoscan ran to its end beside it.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)

        // A second «shown» is the same showing; leaving ends it and the view.
        s.setCellsVisible(true)
        s.setCellsVisible(false)
        runCurrent()
        assertEquals(listOf("s", "u"), bus.subscriptions)
        assertNull(s.model.value.packCells)
        s.stop()
    }

    @Test
    fun aLostSubscriptionIsSaidAndAskedAgainOnlyOnTheNextShowing() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        s.setCellsVisible(true)
        bus.pages(atMs = 0)
        bus.failPages(DiagFailure.ChannelLost(IllegalStateException("helper gone")))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("s", "lost"), bus.subscriptions)
        assertTrue(s.model.value.packCells!!.age.startsWith("поток BMS прерван · "))
        assertEquals("the pages that came stay", "3 310", s.model.value.packCells!!.cells[0].text)

        s.setCellsVisible(false)
        bus.failSubscribe(DiagFailure.ChannelBusy("LOCK_BUSY"))
        s.setCellsVisible(true)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("s", "lost", "lost"), bus.subscriptions)
        assertEquals("поток BMS прерван", s.model.value.packCells!!.age)
        s.stop()
    }

    @Test
    fun pausedNothingIsListenedToAndTheDrawnSweepStays() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        s.setCellsVisible(true)
        bus.pages(atMs = 0)
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        val before = s.model.value.packCells!!
        assertEquals(false, before.paused)
        assertEquals(166, before.cells.size)

        s.setCellsPaused(true)
        runCurrent()
        assertEquals("the subscription is left", listOf("s", "u"), bus.subscriptions)
        val held = s.model.value.packCells!!
        assertTrue(held.paused)
        assertNull(held.sweep)
        // Fixtures.EPOCH at 0 ms of the test's clock, UTC: the page drawn came then.
        assertEquals("пауза · снимок ${ScanClock(ZoneOffset.UTC).seconds(Fixtures.EPOCH)}", held.age)
        assertEquals(before.cells.map { it.value }, held.cells.map { it.value })

        // A minute on, nothing read and nothing faded.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("s", "u"), bus.subscriptions)
        assertEquals(held.cells, s.model.value.packCells!!.cells)
        assertEquals(held.age, s.model.value.packCells!!.age)

        // «Продолжить»: listened to again.
        s.setCellsPaused(false)
        runCurrent()
        assertEquals(listOf("s", "u", "s"), bus.subscriptions)
        assertEquals(false, s.model.value.packCells!!.paused)
        assertTrue(s.model.value.packCells!!.age.startsWith("обход не завершён · "))
        s.stop()
    }

    @Test
    fun leavingTheTabEndsThePause() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        s.setCellsVisible(true)
        runCurrent()
        s.setCellsPaused(true)
        runCurrent()
        s.setCellsVisible(false)
        runCurrent()
        assertNull(s.model.value.packCells)
        s.setCellsVisible(true)
        runCurrent()
        assertEquals("shown again: listened to", listOf("s", "u", "s"), bus.subscriptions)
        assertEquals(false, s.model.value.packCells!!.paused)
        assertEquals("ждём страницы BMS", s.model.value.packCells!!.age)
        s.stop()
    }

    @Test
    fun resumingAfterALostSubscriptionAsksAgain() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        s.setCellsVisible(true)
        bus.failPages(DiagFailure.ChannelLost(IllegalStateException("helper gone")))
        runCurrent()
        assertEquals("поток BMS прерван", s.model.value.packCells!!.age)
        s.setCellsPaused(true)
        s.setCellsPaused(false)
        runCurrent()
        assertEquals(listOf("lost", "s"), bus.subscriptions.drop(1))
        assertEquals("ждём страницы BMS", s.model.value.packCells!!.age)
        s.stop()
    }

    @Test
    fun stopEndsTheSubscription() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        s.setCellsVisible(true)
        runCurrent()
        s.stop()
        runCurrent()
        assertEquals(listOf("s", "u"), bus.subscriptions)
        assertNull(s.model.value.packCells)
    }

    @Test
    fun thePackHasItsCellsTab() = runTest {
        val s = session(carNow())
        s.start()
        runCurrent()
        assertEquals(
            listOf(SystemTab.STATE, SystemTab.CELLS, SystemTab.LIVE, SystemTab.GB_FLAGS, SystemTab.DTC, SystemTab.BLOCKS),
            s.model.value.systemPages.getValue(CarSystem.PACK).tabs,
        )
        s.stop()
    }
}
