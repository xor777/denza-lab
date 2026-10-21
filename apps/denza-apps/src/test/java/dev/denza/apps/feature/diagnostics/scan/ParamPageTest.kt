package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagFailure
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One parameter on its own page (architecture, 6.2): what is polled, the gap, the trace and its windows. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParamPageTest {
    private val rpm = ParamKey.Pid(0x7E0, 0x0C)
    private val ect = ParamKey.Pid(0x7E0, 0x05)

    /** The car now, ECM answering every PID, each exchange 10 ms of virtual time. */
    private fun TestScope.ecm(data: Map<Int, String> = Fixtures.ECM_DATA + (0x0C to "1388")) = carNow().apply {
        Fixtures.ecmAnswers(this) { data }
        delayMs = 10
    }

    @Test
    fun onlyThePidIsPolledWhileItsPageIsShownAndTheAutoscanStands() = runTest {
        val bus = ecm()
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        val done = s.model.value.scan.done
        val before = bus.requests.size

        s.setParam(rpm)
        assertEquals(listOf(20), bus.gaps)
        advanceTimeBy(1_000)
        val during = bus.requests.drop(before)
        // The module on the wire finishes - the autoscan never yields inside one - then only 01 0C.
        val rest = during.takeWhile { it != "7E0 010C" }
        assertTrue(rest.map { it.substringBefore(' ') }.distinct().size <= 1)
        val polled = during.drop(rest.size)
        assertTrue(polled.size.toString(), polled.size >= 80)
        assertTrue(polled.all { it == "7E0 010C" })
        assertEquals(ScanPhase.RUNNING, s.model.value.scan.phase)
        assertTrue(s.model.value.scan.done <= done + 1)

        // Leaving the page gives the stream its gap back and the autoscan goes on.
        s.setParam(null)
        assertNull(s.model.value.param)
        assertEquals(listOf(20, 100), bus.gaps)
        val left = bus.requests.size
        advanceTimeBy(1_000)
        assertTrue(bus.requests.drop(left + 1).none { it == "7E0 010C" })
        assertTrue(s.model.value.scan.done > done + 1)
        s.stop()
    }

    @Test
    fun aButtonReadStillGoesFirst() = runTest {
        val bus = ecm()
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(500)
        val at = bus.requests.size
        s.readModule(0x747)
        advanceTimeBy(500)
        val after = bus.requests.drop(at + 1)
        val read = after.filter { it != "7E0 010C" }
        assertTrue(read.toString(), read.isNotEmpty() && read.all { it.startsWith("747 ") })
        assertEquals("7E0 010C", after.last())
        s.stop()
    }

    @Test
    fun theGapDoublesOnNoAnswerOrBusyUpToTheStreamsAndStaysForThePage() = runTest {
        val bus = ecm()
        bus.on(0x7E0, "010C").answer("410C1388").noReply().answer("410C1388").busy().answer("410C1388")
            .noReply().noReply().answer("410C1388")
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(1_000)
        // 20, then 40 on the gateway's 3, 80 on its 5, 100 at the cap; the second 3 asks nothing more.
        assertEquals(listOf(20, 40, 80, 100), bus.gaps)
        // The answers after it do not bring the gap down while the page stands.
        assertEquals(100, bus.gapMs)
        s.setParam(null)
        assertEquals(listOf(20, 40, 80, 100), bus.gaps)

        // Another page starts at 20 again; leaving it after one back-off restores 100.
        bus.on(0x7E0, "0105").noReply().answer("410531")
        s.setParam(ect)
        advanceTimeBy(500)
        s.setParam(null)
        assertEquals(listOf(20, 40, 80, 100, 20, 40, 100), bus.gaps)
        s.stop()
    }

    @Test
    fun thePageSaysWhatTheTableSaysAndMeasuresItsRate() = runTest {
        val bus = ecm()
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(2_000)
        val p = s.model.value.param!!
        assertEquals(rpm, p.key)
        assertEquals("Обороты коленвала", p.name)
        assertEquals("· PID 0C · RPM", p.aside)
        // The fake car: a gap of 20 ms and 30 ms an exchange, one reading every 50 ms.
        assertEquals("7E0 → 7E8 · Mode 01 · только 0C · 20,0 Гц · обмен 30 мс", p.line)
        assertEquals("20,0 Гц · 30 мс", p.rate)
        assertEquals("1 250", p.value)
        assertEquals("об/мин", p.unit)
        assertEquals("—", p.normText)
        assertNull(p.norm)
        assertFalse(p.outOfNorm)
        assertTrue(p.running)
        assertTrue(p.recordable)
        assertFalse(p.recording)
        assertEquals(p.samples.last().atNanos, p.nowNanos)
        assertEquals(setOf(10, 60, 300), p.windows.keys)
        assertEquals("1 250", p.windows.getValue(60).min)

        s.setParam(ect)
        advanceTimeBy(500)
        val c = s.model.value.param!!
        assertEquals("Температура ОЖ", c.name)
        assertEquals("· PID 05 · ECT", c.aside)
        assertEquals("80…105", c.normText)
        assertEquals(80f..105f, c.norm)
        assertEquals("9", c.value)
        assertEquals("°C", c.unit)
        // Warming up is no fault; only overheating is out of the norm.
        assertFalse(c.outOfNorm)
        s.stop()
    }

    @Test
    fun overheatingIsOutOfTheNorm() = runTest {
        val bus = ecm(Fixtures.ECM_DATA + (0x05 to "96"))
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(ect)
        advanceTimeBy(300)
        assertEquals("110", s.model.value.param!!.value)
        assertTrue(s.model.value.param!!.outOfNorm)
        s.stop()
    }

    @Test
    fun pauseStopsThePollingAndResetClearsTheFiguresNotTheTrace() = runTest {
        val bus = ecm()
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(500)
        s.setParamPaused(true)
        val at = bus.requests.size
        val samples = s.model.value.param!!.samples.size
        advanceTimeBy(2_000)
        assertTrue(bus.requests.size <= at + 1)
        val paused = s.model.value.param!!
        assertFalse(paused.running)
        assertTrue(paused.samples.size <= samples + 1)
        assertEquals(rpm, paused.key)

        s.resetParam()
        val reset = s.model.value.param!!
        assertEquals(paused.samples.size, reset.samples.size)
        assertEquals("1", reset.windows.getValue(60).points)
        assertEquals("1 250", reset.windows.getValue(60).min)
        assertEquals(reset.windows.getValue(60).min, reset.windows.getValue(60).max)

        s.setParamPaused(false)
        advanceTimeBy(500)
        assertTrue(bus.requests.size > at + 20)
        assertTrue(s.model.value.param!!.running)
        assertTrue(bus.requests.drop(at + 1).all { it == "7E0 010C" })
        s.stop()
    }

    @Test
    fun nothingLoopsOnAFailure() = runTest {
        val bus = ecm()
        val s = session(bus)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(300)
        bus.failNext(DiagFailure.ChannelBusy("FOREIGN_TRAFFIC"))
        advanceTimeBy(100)
        val at = bus.requests.size
        advanceTimeBy(30_000)
        assertEquals(at, bus.requests.size)
        assertEquals(LinkState.BUSY, s.model.value.header.link)
        assertFalse(s.model.value.param!!.running)
        // The same page again is not a new request; resuming a page that was not paused neither.
        s.setParam(rpm)
        s.setParamPaused(false)
        advanceTimeBy(5_000)
        assertEquals(at, bus.requests.size)
        s.stop()
    }

    @Test
    fun notParkedPausesThePageLikeTheStream() = runTest {
        val bus = ecm()
        val car = FakeCar()
        val s = session(bus, car)
        s.start()
        advanceTimeBy(300)
        s.setParam(rpm)
        advanceTimeBy(300)
        bus.failNext(DiagFailure.NotParked("GUARD_PARK gear 3"))
        advanceTimeBy(100)
        val at = bus.requests.size
        advanceTimeBy(10_000)
        assertEquals(at, bus.requests.size)
        assertEquals(LinkState.NOT_PARKED, s.model.value.header.link)
        // The hub sees the car leave P and come back: the page polls again at its gap.
        car.readings = FakeCar.moving()
        advanceTimeBy(500)
        car.readings = FakeCar.parked(sweep = 3)
        advanceTimeBy(500)
        assertTrue(bus.requests.drop(at).isNotEmpty())
        assertTrue(bus.requests.drop(at).all { it == "7E0 010C" })
        assertEquals(20, bus.gapMs)
        s.stop()
    }

    @Test
    fun aKeyThatCannotBeReadAloneOpensNothing() = runTest {
        val s = session(ecm())
        s.start()
        advanceTimeBy(100)
        s.setParam(ParamKey.Pid(0x7E0, 0x01))
        assertNull(s.model.value.param)
        s.setParam(ParamKey.Pid(0x747, 0x0C))
        assertNull(s.model.value.param)
        s.setParam(ParamKey.Signal("nothing"))
        assertNull(s.model.value.param)
        s.stop()
    }

    // ------------------------------------------------------------------ a car signal

    @Test
    fun aGetSignalIsTheCarFeedsFocusAndNeverTouchesTheBus() = runTest {
        val bus = ecm()
        val car = FakeCar()
        val s = session(bus, car)
        s.start()
        advanceTimeBy(300)
        s.setParam(ParamKey.Signal("soc"))
        val get = car.focusLog.single()!!
        assertEquals(DiagnosticExtra.SOC.device, get.device)
        assertEquals(DiagnosticExtra.SOC.fid, get.fid)
        assertEquals("service call autoservice 7 i32 1014 i32 1246777400", get.command)
        // The gap stays the stream's; the autoscan stands behind the page.
        assertEquals(emptyList<Int>(), bus.gaps)
        advanceTimeBy(500)
        val at = bus.requests.size
        (0..10).forEach { car.pending += SignalReading(5_000_000_000L + it * 100_000_000L, 61.0 + it) }
        advanceTimeBy(1_000)
        assertEquals(at, bus.requests.size)

        val p = s.model.value.param!!
        assertEquals("Уровень заряда (SOC)", p.name)
        assertEquals("· 1014·4A505038", p.aside)
        assertEquals("autoservice · только этот сигнал · 10,0 Гц", p.line)
        assertEquals("10,0 Гц", p.rate)
        assertEquals("71", p.value)
        assertEquals("%", p.unit)
        assertEquals(11, p.samples.size)
        assertEquals(6_000_000_000L, p.nowNanos)
        assertFalse(p.recordable)
        assertTrue(p.running)
        assertEquals("+10", p.windows.getValue(10).delta)

        // A gap is a gap.
        car.pending += SignalReading(6_100_000_000L, null)
        advanceTimeBy(200)
        assertEquals("—", s.model.value.param!!.value)
        assertNull(s.model.value.param!!.samples.last().value)

        s.setParamPaused(true)
        assertNull(car.focusLog.last())
        s.setParamPaused(false)
        assertEquals(get, car.focusLog.last())
        s.setParam(null)
        assertNull(car.focusLog.last())
        assertEquals(emptyList<Int>(), bus.gaps)
        s.stop()
    }

    @Test
    fun aHotHubSignalIsTheHubsSweepsAndAColdOneAGetOfItsOwn() = runTest {
        val bus = ecm()
        val car = FakeCar()
        val s = session(bus, car)
        s.start()
        advanceTimeBy(300)
        fun sweep(n: Long, rpm: Double) {
            car.readings = FakeCar.parked(sweep = n, extra = mapOf(VehicleSignal.ENGINE_RPM to rpm)).copy(atNanos = n * 250_000_000L)
        }
        sweep(10, 1250.0)
        s.setParam(ParamKey.Signal(VehicleSignal.ENGINE_RPM.name))
        assertTrue(car.focusLog.isEmpty())
        advanceTimeBy(250)
        advanceTimeBy(250) // the same sweep twice is one reading
        sweep(11, 1300.0)
        advanceTimeBy(250)
        sweep(12, 1290.0)
        advanceTimeBy(250)
        val p = s.model.value.param!!
        assertEquals("Обороты ДВС", p.name)
        assertEquals("· 1012·14400012", p.aside)
        assertEquals(listOf(1250f, 1300f, 1290f), p.samples.map { it.value })
        assertEquals("1 290", p.value)
        assertEquals("об/мин", p.unit)
        assertTrue(p.line, p.line.startsWith("autoservice · 100 мс · "))

        s.setParam(ParamKey.Signal(VehicleSignal.CELL_MIN_MV.name))
        val cold = car.focusLog.single()!!
        assertEquals(1014, cold.device)
        assertEquals(0x44600010, cold.fid)
        assertEquals("· 1014·44600010", s.model.value.param!!.aside)
        assertEquals("Напряжение ячейки, мин.", s.model.value.param!!.name)
        s.stop()
    }

    // ------------------------------------------------------------------ the keys on the rows

    @Test
    fun theLiveRowsAndThePacksReadingsCarryTheirKeys() = runTest {
        val stream = LiveStream(0x7E0, listOf(0x05, 0x0C), carRows = true)
        val rows = stream.view(running = true, recording = false, engineRunning = null).rows
        assertEquals(ParamKey.Pid(0x7E0, 0x05), rows.first { it.pid == 0x05 }.key)
        assertEquals(ParamKey.Pid(0x7E0, 0x0C), rows.first { it.pid == 0x0C }.key)
        assertEquals(ParamKey.Signal("ENGINE_RPM"), rows.first { it.pid == LiveStream.CarRow.RPM.pid }.key)
        assertTrue(rows.all { it.key != null })
        // Every key a row carries opens a page.
        rows.forEach { assertNotNull(it.name, ParamSpec.of(it.key!!)) }

        val s = session(ecm(), FakeCar())
        s.start()
        advanceTimeBy(100)
        val pack = s.model.value.systems.pack
        assertEquals(ParamKey.Signal("PACK_VOLT"), pack.first { it.name == "Напряжение ВВБ" }.key)
        assertEquals(ParamKey.Signal("charge_current"), pack.first { it.name == "Ток ВВБ" }.key)
        assertEquals(ParamKey.Signal("CELL_MIN_MV"), pack.first { it.source == "1014·44600010" }.key)
        assertNull(pack.single { it.source == "расчёт" }.key)
        pack.filter { it.key != null }.forEach { r ->
            val spec = ParamSpec.of(r.key!!)
            assertNotNull(r.name, spec)
            assertEquals(r.name, spec!!.name)
            assertEquals("· ${r.source}", spec.aside)
        }
        s.stop()
    }

    // ------------------------------------------------------------------ the windows

    private fun rpmTrace() = ParamTrace(ParamSpec.of(ParamKey.Signal("ENGINE_RPM"))!!)

    @Test
    fun theWindowsCountMinMaxMeanAndTheirScaleOnAKnownSeries() {
        val t = rpmTrace()
        // 0..60 s, a reading every 100 ms, 1200 rising by 2 a reading.
        (0..600).forEach { i -> t.put(i * 100_000_000L, 1200.0 + 2 * i) }
        val p = t.view(running = true, recording = false, car = CarReadings())
        assertEquals(60_000_000_000L, p.nowNanos)

        val ten = p.windows.getValue(10)
        assertEquals("2 202", ten.min)
        assertEquals("2 400", ten.max)
        assertEquals("2 301", ten.mean)
        assertEquals(2202f, ten.minValue)
        assertEquals(2400f, ten.maxValue)
        assertEquals("100", ten.points)
        assertEquals(2100f..2500f, ten.scale)
        assertEquals(listOf(2100f, 2200f, 2300f, 2400f, 2500f), ten.grid)
        assertEquals(listOf("2 100", "2 200", "2 300", "2 400", "2 500"), ten.gridText)

        val minute = p.windows.getValue(60)
        assertEquals("1 202", minute.min)
        assertEquals("1 801", minute.mean)
        assertEquals("600", minute.points)
        assertEquals(1000f..2500f, minute.scale)
        assertEquals(listOf("1 000", "1 500", "2 000", "2 500"), minute.gridText)

        val five = p.windows.getValue(300)
        assertEquals("1 200", five.min)
        assertEquals("1 800", five.mean)
        assertEquals("601", five.points)

        // The change over the last second: 2400 against 2380.
        assertEquals("+20", ten.delta)
        assertEquals("1 441", Figures.number(1441.0, 0))
    }

    @Test
    fun aResetStartsTheFiguresOverAndKeepsTheTraceAndScale() {
        val t = rpmTrace()
        (0..100).forEach { i -> t.put(i * 100_000_000L, 1000.0 + 10 * i) }
        t.reset()
        t.put(10_100_000_000L, 1990.0)
        val w = t.view(true, false, CarReadings()).windows.getValue(60)
        assertEquals("1 990", w.min)
        assertEquals("2 000", w.max)
        assertEquals("2", w.points)
        // The chart still holds the whole trace.
        assertEquals(102, t.view(true, false, CarReadings()).samples.size)
        assertTrue(w.scale.start <= 1000f)
    }

    @Test
    fun theDeltaHasTheFiguresMinusAndAGapHasNone() {
        val t = ParamTrace(ParamSpec.of(ParamKey.Signal(VehicleSignal.GENERATION_KW.name))!!)
        t.put(1_000_000_000L, 5.0)
        assertEquals("—", t.view(true, false, CarReadings()).windows.getValue(10).delta)
        t.put(2_000_000_000L, 4.9)
        assertEquals("${Figures.MINUS}0,1", t.view(true, false, CarReadings()).windows.getValue(10).delta)
        t.put(2_100_000_000L, null)
        val v = t.view(true, false, CarReadings())
        assertEquals("—", v.windows.getValue(10).delta)
        assertEquals("—", v.value)
        assertEquals("2", v.windows.getValue(10).points)
        // Before any reading the value is unread and the scale a plain one.
        val empty = rpmTrace().view(true, false, CarReadings())
        assertEquals("н/д", empty.value)
        assertEquals("—", empty.rate)
        assertEquals("—", empty.windows.getValue(60).min)
        assertNull(empty.windows.getValue(60).minValue)
    }

    @Test
    fun theFiveMinuteRingDropsTheOldest() {
        val t = rpmTrace()
        (0..3100).forEach { i -> t.put(i * 100_000_000L, 1000.0) }
        val samples = t.view(true, false, CarReadings()).samples
        assertEquals(3000, samples.size)
        assertEquals(10_100_000_000L, samples.first().atNanos)
    }

    @Test
    fun theScaleIsRoundedOutwardToFourOrFiveLines() {
        // The coolant's board: readings 88…91 inside the norm's band 80…105.
        ParamScale.of(88.0, 91.0, 80f..105f).let {
            assertEquals(70.0, it.low, 0.0)
            assertEquals(110.0, it.high, 0.0)
            assertEquals(listOf(70.0, 80.0, 90.0, 100.0, 110.0), it.lines)
            assertEquals(0, it.decimals)
        }
        // The engine speed's board: idle with a blip.
        ParamScale.of(1225.0, 2160.0, null).let {
            assertEquals(listOf(1000.0, 1500.0, 2000.0, 2500.0), it.lines)
        }
        // An oxygen sensor's volts: quarter steps, two decimals.
        ParamScale.of(0.45, 0.7, 0.1f..0.9f).let {
            assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), it.lines)
            assertEquals(2, it.decimals)
        }
        // A flat line still has room around it.
        ParamScale.of(1250.0, 1250.0, null).let {
            assertTrue(it.low < 1250.0 && it.high > 1250.0)
            assertTrue(it.lines.size in 3..5)
        }
        // Every scale in a spread of ranges stays within five lines and holds what it was given.
        listOf(0.0 to 0.3, -12.5 to 3.1, 13.6 to 14.2, 0.0 to 8000.0, 553.0 to 556.0, -0.1 to 0.0).forEach { (a, b) ->
            val sc = ParamScale.of(a, b, null)
            assertTrue("$a..$b ${sc.lines}", sc.lines.size in 3..5)
            assertTrue(sc.low <= a && sc.high >= b)
        }
    }

    // ------------------------------------------------------------------ the focused GET

    private class FakeShell : ExtrasShell {
        val commands = mutableListOf<String>()
        var fail = false
        var closed = 0
        override fun shell(command: String): String {
            commands += command
            if (command.startsWith("getprop")) return "170\n"
            if (fail) throw IOException("shell gone")
            if (command.startsWith("echo @@extra:")) return "@@extra:soc\nResult: Parcel(00000000 %08x   '........')\n".format(61f.toBits())
            return "Result: Parcel(00000000 %08x   '........')\n".format(61.5f.toBits())
        }
        override fun close() { closed++ }
    }

    @Test
    fun theFocusedPollerAsksOneGetEveryHundredMillisecondsAndNothingLoopsOnAFailure() = runTest {
        val shell = FakeShell()
        val poller = ExtrasPoller(
            open = { shell },
            log = {},
            context = backgroundScope.coroutineContext,
            wallClock = { testScheduler.currentTime },
            elapsed = { testScheduler.currentTime },
            nanos = { testScheduler.currentTime * 1_000_000L },
        )
        poller.start()
        runCurrent()
        assertEquals(listOf("getprop persist.sys.AutoType", dev.denza.apps.feature.diagnostics.DiagnosticExtraReader.command), shell.commands)
        assertEquals(61.0, poller.latest.values.getValue(DiagnosticExtra.SOC), 0.0)
        advanceTimeBy(1_000)
        assertEquals(2, shell.commands.size)

        val get = SignalGet.of(DiagnosticExtra.SOC)
        poller.focus(get)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val focused = shell.commands.drop(2)
        assertEquals(11, focused.size)
        assertTrue(focused.all { it == get.command })
        val readings = poller.drain()
        assertEquals((0..10).map { 1_000_000_000L + it * 100_000_000L }, readings.map { it.atNanos })
        assertTrue(readings.all { it.value == 61.5 })
        assertTrue(poller.drain().isEmpty())

        // A failed GET is a gap and five seconds' wait, then a new shell: never a loop.
        shell.fail = true
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(null), poller.drain().map { it.value })
        assertEquals(1, shell.closed)
        val failed = shell.commands.size
        advanceTimeBy(4_800)
        assertEquals(failed, shell.commands.size)
        shell.fail = false
        advanceTimeBy(300)
        assertEquals(listOf("getprop persist.sys.AutoType", get.command), shell.commands.drop(failed).take(2))

        // No focus: the batch at once, then every five seconds.
        poller.focus(null)
        runCurrent()
        assertEquals(dev.denza.apps.feature.diagnostics.DiagnosticExtraReader.command, shell.commands.last())
        val batch = shell.commands.size
        advanceTimeBy(4_900)
        assertEquals(batch, shell.commands.size)
        poller.stop()
    }
}
