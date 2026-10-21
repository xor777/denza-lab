package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Тяговая батарея · Потоковые данные» as a stream (architecture 6, 6.3, 7.2; board
 * `scanner-sys-pack-live`): while the tab is in front the batch is read round after round, every
 * reading keeps its last minute and its extremes, «Пауза» freezes the table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackLiveTest {

    // ------------------------------------------------------------------ the cadence

    /** A shell whose batch answers SOC and costs [cost] ms of the head unit's clock. */
    private class BatchShell : ExtrasShell {
        val commands = mutableListOf<String>()
        var cost = 0L
        var spent = 0L
        var fail = false
        override fun shell(command: String): String {
            if (command.startsWith("getprop")) return "170\n"
            commands += command
            if (fail) throw IOException("shell gone")
            spent += cost
            return "@@extra:soc\nResult: Parcel(00000000 %08x   '........')\n".format(74f.toBits())
        }
        override fun close() {}
    }

    @Test
    fun whileThePacksStreamShowsTheBatchGoesBackToBackAndFiveSecondsApartOtherwise() = runTest {
        val shell = BatchShell().apply { cost = 60 }
        val poller = ExtrasPoller(
            open = { shell },
            log = {},
            context = backgroundScope.coroutineContext,
            wallClock = { testScheduler.currentTime },
            elapsed = { testScheduler.currentTime + shell.spent },
            nanos = { 0L },
        )
        poller.start()
        runCurrent()
        assertEquals(1, shell.commands.size)
        assertEquals("every batch says how long it took", 60L, poller.latest.tookMs)
        assertEquals(74.0, poller.latest.values.getValue(DiagnosticExtra.SOC), 0.0)
        // Not shown: five seconds after an answer, as always.
        advanceTimeBy(4_900)
        assertEquals(1, shell.commands.size)
        advanceTimeBy(200)
        assertEquals(2, shell.commands.size)

        // Shown: the next batch at once, then one every 100 ms from start to start.
        shell.cost = 0
        poller.show(ExtraPage.PACK_LIVE, true)
        runCurrent()
        assertEquals(3, shell.commands.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(13, shell.commands.size)
        assertTrue("no GETs of its own: the shared batch", shell.commands.all { it == DiagnosticExtraReader.command })
        assertEquals(DiagnosticExtraReader.command, DiagnosticExtraReader.command(setOf(ExtraPage.PACK_LIVE)))

        // A failure still waits five seconds, shown or not.
        shell.fail = true
        advanceTimeBy(100)
        runCurrent()
        val failed = shell.commands.size
        advanceTimeBy(4_800)
        assertEquals(failed, shell.commands.size)
        shell.fail = false
        advanceTimeBy(300)
        assertEquals(failed + 1, shell.commands.size)

        // Left: the next batch at once, then five seconds again.
        poller.show(ExtraPage.PACK_LIVE, false)
        runCurrent()
        val left = shell.commands.size
        advanceTimeBy(4_900)
        assertEquals(left, shell.commands.size)
        advanceTimeBy(200)
        assertEquals(left + 1, shell.commands.size)
        poller.stop()
    }
}
