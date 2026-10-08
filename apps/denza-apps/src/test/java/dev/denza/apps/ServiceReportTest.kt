package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceReportTest {
    private val clock = ManualReportClock()
    private var builds = 0
    private val published = mutableListOf<ServiceReport.Pages>()
    private val report = ServiceReport(
        clock = clock,
        periodMs = 1_000L,
        build = {
            builds += 1
            ServiceReport.Pages("report $builds", "journal $builds")
        },
        publish = { published += it },
    )

    @Test
    fun `a closed panel costs nothing`() {
        clock.tick()
        report.rebuildNow()
        clock.runPending()

        assertEquals(0, builds)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `opening builds the pages at once, then on every tick until it closes`() {
        report.setOpen(true)
        clock.runPending()
        assertEquals(listOf(ServiceReport.Pages("report 1", "journal 1")), published)

        clock.tick()
        clock.tick()
        assertEquals(3, builds)

        report.setOpen(false)
        clock.tick()
        report.rebuildNow()
        clock.runPending()
        assertEquals(3, builds)
        assertEquals(0, clock.running)
    }

    @Test
    fun `a change worth showing is built before the next tick`() {
        report.setOpen(true)
        clock.runPending()

        report.rebuildNow()
        clock.runPending()

        assertEquals(2, builds)
    }

    @Test
    fun `opening twice keeps one schedule, and closing twice is harmless`() {
        report.setOpen(true)
        report.setOpen(true)
        assertEquals(1, clock.running)

        report.setOpen(false)
        report.setOpen(false)
        assertFalse(report.isOpen)
        assertEquals(0, clock.running)
    }

    @Test
    fun `a build already queued when the panel closes publishes nothing`() {
        report.setOpen(true)
        report.setOpen(false)
        clock.runPending()

        assertEquals(0, builds)
    }
}
