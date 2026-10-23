package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.diagnostics.ui.inWindow
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Графики» chosen on the table (architecture, 7.3; boards `scanner-sys-engine`, `-sys-engine-graphs`): which rows
 * have a square, the default, a tap on a square and on «×», the one choice the table, the graphs and ECM's focus
 * share in the session, the recording's time, and the line that enters the window from its left edge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphPicksTest {
    private fun row(name: String, key: ParamKey?, vararg values: Float?) =
        LiveRow(0, name, "", "", "", "", "", "", false, "", values.mapIndexed { i, v -> Sample(i * 1_000_000_000L, v) }, key)

    private val volts = row("Напряжение", ParamKey.Signal("PACK_VOLT"), 553f, 554f)
    private val contactor = row("Контактор", ParamKey.Signal("CONTACTOR"))
    private val spread = row("Разброс", null, 4f, 4f)
    private val missing = row("Обороты задний левый", ParamKey.Signal("REAR_LEFT_RPM"), null, null)
    private fun signal(i: Int) = row("Сигнал $i", ParamKey.Signal("S$i"), i.toFloat())

    @Test
    fun aRowWithAKeyAndALineHasASquareAndTheDefaultIsTheFirstFour() {
        // a state in words, a calculation and a reading with nothing but gaps have none
        assertTrue(GraphPicks.pickable(volts))
        listOf(contactor, spread, missing).forEach { assertTrue(it.name, !GraphPicks.pickable(it)) }
        val rows = listOf(contactor, volts, spread, missing) + (1..5).map(::signal)
        assertEquals(listOf(volts.key, signal(1).key, signal(2).key, signal(3).key), GraphPicks.default(rows))
        // nothing ticked: the default; ticked, the ticks - even none
        assertEquals(GraphPicks.default(rows), GraphPicks.of(null, rows))
        assertEquals(emptyList<ParamKey>(), GraphPicks.of(emptyList(), rows))
        assertEquals(emptyList<ParamKey>(), GraphPicks.default(listOf(contactor, spread)))
    }

    @Test
    fun aSquareTicksAndUnticksAndAFifthCannotBeTicked() {
        val (a, b, c, d, e) = (1..5).map { signal(it).key!! }
        assertEquals(listOf(a), GraphPicks.toggle(emptyList(), a))
        // a new tick is the last lane
        assertEquals(listOf(b, a), GraphPicks.toggle(listOf(b), a))
        // off again, «×» on the graphs being the same: the others keep their order
        assertEquals(listOf(a, c), GraphPicks.toggle(listOf(a, b, c), b))
        assertEquals(listOf(a, b, c, d), GraphPicks.toggle(listOf(a, b, c, d), e))
        assertEquals(listOf(a, b, c), GraphPicks.toggle(listOf(a, b, c, d), d))
    }

    @Test
    fun theLineStartsAtTheSampleBeforeTheWindowNotAtTheFirstInsideIt() {
        val s = (0..130).map { Sample(it * 5_000_000_000L, it.toFloat()) }
        val now = 650_000_000_000L
        val shown = inWindow(s, now)
        // 590 s is before the window that starts at 590: the line's start, x at the left edge
        assertEquals(590_000_000_000L, shown.first().atNanos)
        assertEquals(now, shown.last().atNanos)
        assertEquals(13, shown.size)
        // the window moved by less than a step: the same start, so the line does not jump
        assertEquals(590_000_000_000L, inWindow(s, now + 2_000_000_000L).first().atNanos)
        assertEquals(595_000_000_000L, inWindow(s, now + 6_000_000_000L).first().atNanos)
        // a series that starts inside the window starts there
        assertEquals(0L, inWindow(s.take(3), 10_000_000_000L).first().atNanos)
    }

    @Test
    fun theTableTheGraphsAndTheEnginesFocusAreOneChoice() = runTest {
        val bus = carNow()
        CarNow.scriptEcmIdent(bus)
        Fixtures.ecmAnswers(bus)
        bus.delayMs = 130
        val dir = Files.createTempDirectory("diag-picks").toFile()
        val s = session(bus, sinks = CsvStreamSinks(dir, ZoneOffset.UTC))
        val ecm = GraphScope.Block(0x7E0)
        fun live() = s.model.value.modules.getValue(0x7E0).obd!!.live
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        s.setLiveVisible(0x7E0, true)
        s.setLiveTab(ecm, true)
        advanceTimeBy(10_000)
        runCurrent()

        // nothing ticked: the first four rows with a line - ECT, IAT, MAP, rpm - and they are read first: rpm alone
        // in a request of its own, which the table's packs never ask
        val pid = { p: Int -> ParamKey.Pid(0x7E0, p) }
        assertEquals(listOf(pid(0x05), pid(0x0F), pid(0x0B), pid(0x0C)), live().picks)
        assertEquals(GraphPicks.default(live().rows), live().picks)
        assertTrue("7E0 010C" in bus.requests)
        // the graphs already keep them, before they are shown
        assertEquals(live().picks, live().graphs!!.lanes.map { it.key })

        // the throttle and rpm ticked: the table's squares, the lanes and the focus - one request now
        s.pickGraphs(ecm, listOf(pid(0x11), pid(0x0C)))
        val before = bus.requests.size
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(pid(0x11), pid(0x0C)), live().picks)
        assertEquals(listOf(pid(0x11), pid(0x0C)), live().graphs!!.lanes.map { it.key })
        assertTrue("7E0 01110C" in bus.requests.drop(before))
        // and their lines are there at once: the rows' minute
        assertTrue(live().graphs!!.lanes.all { lane -> lane.samples.count { it.value != null } >= 2 })

        // «×» on the throttle: rpm alone, on both
        s.pickGraphs(ecm, GraphPicks.toggle(live().picks, pid(0x11)))
        assertEquals(listOf(pid(0x0C)), live().picks)
        assertEquals(listOf(pid(0x0C)), live().graphs!!.lanes.map { it.key })

        // a recording says its time while it runs, its length when stopped, until the tab is left
        s.setRecording(0x7E0, true)
        advanceTimeBy(42_000)
        runCurrent()
        assertTrue(live().recording)
        assertTrue("${live().recordingMs}", live().recordingMs!! in 41_000L..42_000L)
        assertNull(live().recordedMs)
        s.setRecording(0x7E0, false)
        assertNull(live().recordingMs)
        assertEquals(42_000L, live().recordedMs)
        s.setLiveTab(ecm, false)
        assertNull(live().recordedMs)
        assertNull(live().graphs)
        // the choice stays with the block
        assertEquals(listOf(pid(0x0C)), live().picks)
        s.stop()
    }

    @Test
    fun aSystemsTableHasTheDefaultUntilTickedAndTheGraphsFollowIt() = runTest {
        val s = session(carNow())
        val pack = GraphScope.System(CarSystem.PACK)
        s.start()
        runCurrent()
        s.setSystemLiveVisible(CarSystem.PACK, true)
        s.setLiveTab(pack, true)
        advanceTimeBy(3_000)
        runCurrent()
        val table = s.model.value.systems.live.getValue(CarSystem.PACK)
        // the fake car reads nothing: no line, nothing by default
        assertEquals(GraphPicks.default(table.rows), table.picks)
        assertEquals(table.picks, table.graphs!!.lanes.map { it.key })
        val cell = ParamKey.Signal("CELL_MIN_MV")
        s.pickGraphs(pack, listOf(cell))
        val ticked = s.model.value.systems.live.getValue(CarSystem.PACK)
        assertEquals(listOf(cell), ticked.picks)
        assertEquals(listOf(cell), ticked.graphs!!.lanes.map { it.key })
        s.stop()
    }
}
