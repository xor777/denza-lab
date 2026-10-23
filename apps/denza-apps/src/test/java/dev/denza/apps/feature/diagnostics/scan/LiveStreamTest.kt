package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live stream of architecture §6.1: one frame per answer, the car's times, gaps, measured rate. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStreamTest {
    /** Every Mode 01 PID the ECM offers, by its own maps (ObdCodecTest), A0 and A6 included. */
    private val ecmOffered = listOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20,
        0x21, 0x23, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x3C, 0x40,
        0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x54, 0x56, 0x60,
        0x67, 0x6B, 0x7A, 0x80, 0xA0, 0xA6,
    )

    private fun ecm() = LiveStream(0x7E0, ecmOffered, carRows = true)
    private fun pidsOf(r: DiagRequest) = r.uds.drop(1).map { it.toInt() and 0xFF }
    private fun answer(r: DiagRequest, uds: String, at: Long, latency: Int = 30) =
        DiagReply.Answer(r, hexBytes(uds), 1, 0, latency, at)
    private fun ask(vararg pids: Int) = DiagRequest(0x7E0, byteArrayOf(0x01) + ByteArray(pids.size) { pids[it].toByte() })
    private fun LiveView.row(pid: Int) = rows.first { it.pid == pid }

    @Test
    fun everyRequestIsAnsweredInOneCanFrame() {
        val s = ecm()
        // The board's table first, then the rest by number; no maps, monitors, standard or O2 list,
        // and not 7A, whose seven bytes cannot share a frame with anything or even fit one.
        assertEquals(listOf(0x05, 0x0F, 0x0B, 0x0C, 0x04, 0x11, 0x0E, 0x06, 0x07, 0x03, 0x15, 0x3C, 0x42), s.pids.take(13))
        // Nor the odometer, read once with the identification.
        listOf(0x00, 0x01, 0x13, 0x1C, 0x20, 0x40, 0x41, 0x60, 0x7A, 0x80, 0xA0, 0xA6).forEach { assertFalse("%02X".format(it), it in s.pids) }
        assertEquals(37, s.pids.size)
        assertEquals(s.pids.sorted(), s.packs.flatten().sorted())
        s.packs.forEach { pack ->
            val bytes = pack.sumOf { 1 + ObdPids.of(it)!!.length }
            assertTrue("$pack answers in $bytes bytes after 41", bytes <= 6)
            assertTrue(pack.size <= 6)
        }
        // Three one-byte values share one request.
        assertEquals(listOf(0x05, 0x0F, 0x0B), s.packs.first())
        // PID 56 is asked alone from the start: its one-byte answer cannot be split inside a pack.
        assertTrue(listOf(0x56) in s.packs)
        assertEquals(1, s.packs.count { 0x56 in it })
        // A request of every pack passes the whitelist on the way out.
        repeat(s.packs.size) { assertTrue(s.next() != null) }
    }

    @Test
    fun theFocusIsAskedFirstAndEveryOtherExchange() {
        val s = ecm()
        s.setFocus(listOf(0x0C, 0x0D))
        val first = s.next()!!
        assertEquals("010C0D", hex(first.uds))
        val sequence = (0 until 8).map { pidsOf(s.next()!!) }
        // table, focus, table, focus, ...
        sequence.forEachIndexed { i, pids ->
            if (i % 2 == 1) assertEquals(listOf(0x0C, 0x0D), pids) else assertTrue(pids.none { it == 0x0C || it == 0x0D })
        }
        // Three focus PIDs of two and three bytes need two requests: both before any table pack.
        s.setFocus(listOf(0x0C, 0x42, 0x15, 0x05, 0x06))
        assertEquals(listOf(0x0C, 0x42, 0x15, 0x05), s.focus)
        val round = (0 until 3).map { pidsOf(s.next()!!) }
        assertEquals(listOf(0x0C, 0x42), round[0])
        assertEquals(listOf(0x15, 0x05), round[1])
        assertTrue(round[2].none { it in s.focus })
    }

    @Test
    fun valuesSitAtTheCarsTimeAndSilenceIsAGap() {
        val s = ecm()
        val r = ask(0x0C, 0x0D)
        s.accept(answer(r, "410C0FA00D00", at = 1_000_000_000L))
        // The gateway said nothing.
        s.accept(DiagReply.NoReply(r, 3, 0, 40, 1_130_000_000L))
        // The module answered rpm and left speed out.
        s.accept(answer(r, "410C1F40", at = 1_260_000_000L))
        val v = s.view(running = true, recording = false, engineRunning = true)
        assertEquals(listOf(Sample(1_000_000_000L, 1000f), Sample(1_130_000_000L, null), Sample(1_260_000_000L, 2000f)), v.row(0x0C).samples)
        assertEquals(listOf(Sample(1_000_000_000L, 0f), Sample(1_130_000_000L, null), Sample(1_260_000_000L, null)), v.row(0x0D).samples)
        assertEquals("2 000", v.row(0x0C).value)
        assertEquals("—", v.row(0x0D).value)
        assertEquals("об/мин", v.row(0x0C).unit)
        assertEquals("PID", v.row(0x0C).source)
        // Older than 60 s falls off the ring, but for the one sample before the minute: the line's start at its left edge.
        s.accept(answer(r, "410C0FA00D00", at = 62_000_000_000L))
        assertEquals(listOf(Sample(1_260_000_000L, 2000f), Sample(62_000_000_000L, 1000f)), s.view(true, false, null).row(0x0C).samples)
        s.accept(answer(r, "410C0FA00D00", at = 63_000_000_000L))
        assertEquals(listOf(1_260_000_000L, 62_000_000_000L, 63_000_000_000L), s.view(true, false, null).row(0x0C).samples.map { it.atNanos })
        s.accept(answer(r, "410C0FA00D00", at = 122_500_000_000L))
        // 62 is the last before 62,5: kept; 1,26 goes
        assertEquals(listOf(62_000_000_000L, 63_000_000_000L, 122_500_000_000L), s.view(true, false, null).row(0x0C).samples.map { it.atNanos })
    }

    @Test
    fun theRateAndDelayAreMeasuredNotSet() = runBlocking {
        val s = ecm()
        assertEquals("—", s.rate())
        val bus = FakeDiagnosticBus(gapMs = 100, latencyMs = 30)
        Fixtures.ecmAnswers(bus)
        s.setFocus(listOf(0x0C))
        repeat(60) { s.accept(bus.exchange(s.next()!!)) }
        // 130 ms an exchange, the focus every other one: 1 / 0,26 s.
        assertEquals("3,8 Гц · 30 мс", s.rate())
        assertEquals("3,8 Гц · 30 мс", s.view(true, false, false).rate)
    }

    @Test
    fun minimumAndMaximumRunFromTheOpeningAndReset() {
        val s = ecm()
        val r = ask(0x0C)
        listOf("0FA0" to 1L, "1F40" to 2L, "1770" to 3L).forEach { (rpm, t) -> s.accept(answer(r, "410C$rpm", at = t * 100_000_000L)) }
        var row = s.view(true, false, true).row(0x0C)
        assertEquals("1 000", row.min)
        assertEquals("2 000", row.max)
        assertEquals("1 500", row.value)
        s.resetMinMax()
        row = s.view(true, false, true).row(0x0C)
        assertEquals("1 500", row.min)
        assertEquals("1 500", row.max)
        s.accept(DiagReply.NoReply(r, 3, 0, 0, 400_000_000L))
        row = s.view(true, false, true).row(0x0C)
        assertEquals("—", row.value)
        assertEquals("1 500", row.min)
        // Trims keep their sign.
        s.accept(answer(ask(0x06), "410670", at = 500_000_000L))
        assertEquals("${Figures.MINUS}12,5", s.view(true, false, true).row(0x06).value)
        s.accept(answer(ask(0x06), "410684", at = 600_000_000L))
        assertEquals("+3,1", s.view(true, false, true).row(0x06).value)
    }

    @Test
    fun normsJudgeOnlyWhatIsKnown() {
        val s = ecm()
        fun at(n: Long) = n * 100_000_000L
        s.accept(answer(ask(0x05), "410596", at(1))) // 110 °C
        assertTrue(s.view(true, false, false).row(0x05).outOfNorm)
        s.accept(answer(ask(0x05), "410564", at(2))) // 60 °C, warming up
        assertFalse(s.view(true, false, true).row(0x05).outOfNorm)
        assertEquals("80…105", s.view(true, false, true).row(0x05).norm)
        // The downstream O2 sensor at 1,275 V on a parked, cold car is not a fault; running, it is.
        s.accept(answer(ask(0x15), "4115FFFF", at(3)))
        assertFalse(s.view(true, false, false).row(0x15).outOfNorm)
        assertTrue(s.view(true, false, true).row(0x15).outOfNorm)
        // 12,1 V out of 13,2…14,8; intake air has no norm.
        s.accept(answer(ask(0x42), "41422F44", at(4)))
        assertTrue(s.view(true, false, null).row(0x42).outOfNorm)
        s.accept(answer(ask(0x0F), "410F34", at(5)))
        assertEquals("—", s.view(true, false, null).row(0x0F).norm)
        assertFalse(s.view(true, false, null).row(0x0F).outOfNorm)
    }

    @Test
    fun aPackTheModuleRefusesIsAskedOnePidAtATime() {
        val s = ecm()
        val pack = s.packs.first()
        assertEquals(3, pack.size)
        s.accept(answer(ask(*pack.toIntArray()), "7F0112", at = 1L))
        pack.forEach { p -> assertTrue(s.packs.contains(listOf(p))) }
        pack.forEach { p -> assertNull(s.view(true, false, null).row(p).samples.last().value) }
    }

    @Test
    fun theOneBytePid56LeavesEveryOtherPackAsItWas() {
        val s = ecm()
        val before = s.packs
        // The car's own answer, 2026-10-21: B1 alone.
        val lines = s.accept(answer(ask(0x56), "415680", at = 1L))
        assertEquals(before, s.packs)
        assertEquals("0,0", s.view(true, false, null).row(0x56).value)
        assertEquals(listOf("1,7E0,56,80,0"), lines)
        // A pack the module breaks still goes single, 56 among the singles or not.
        val pack = s.packs.first()
        s.accept(answer(ask(*pack.toIntArray()), "41", at = 2L))
        pack.forEach { p -> assertTrue(s.packs.contains(listOf(p))) }
    }

    @Test
    fun commandedLambdaWithoutAValueIsNdAndAGap() {
        val s = ecm()
        s.accept(answer(ask(0x44), "41448000", at = 1L))
        assertEquals("1,000", s.view(true, false, null).row(0x44).value)
        // 41 44 FF FF, the stopped engine of 2026-10-21: no λ of 2,000.
        val lines = s.accept(answer(ask(0x44), "4144FFFF", at = 2L))
        val row = s.view(true, false, null).row(0x44)
        assertEquals("н/д", row.value)
        assertEquals(listOf(Sample(1L, 1f), Sample(2L, null)), row.samples)
        assertEquals(listOf("2,7E0,44,FFFF,"), lines)
    }

    @Test
    fun fastCarSignalsSitBesideTheEnginesPidsWithTheirSource() {
        val s = ecm()
        val car = CarReadings(hub = mapOf(VehicleSignal.ENGINE_RPM to 1250.0, VehicleSignal.ENGINE_RUNNING to 3.0,
            VehicleSignal.GEARBOX_PARK to 1.0), sweep = 5, atNanos = 7_000_000_000L)
        assertEquals(8, s.carTick(car).size)
        assertTrue(s.carTick(car).isEmpty()) // the same sweep is not a new sample
        val rows = s.view(true, false, true).rows.filter { it.pid < 0 }
        assertEquals(8, rows.size)
        val rpm = rows.first { it.abbreviation == "RPM" }
        assertEquals("1 250", rpm.value)
        assertEquals(LiveStream.CAR_SOURCE, rpm.source)
        assertEquals(listOf(Sample(7_000_000_000L, 1250f)), rpm.samples)
        assertEquals("работает", rows.first { it.abbreviation == "ENG" }.value)
        assertEquals("P", rows.first { it.abbreviation == "P" }.value)
        assertEquals("—", rows.first { it.abbreviation == "GEN" }.value)
        // Not on a module that is not the engine.
        assertTrue(LiveStream(0x7E2, listOf(0x05, 0x0C, 0x0D), carRows = false).carTick(car).isEmpty())
    }

    @Test
    fun theSessionStreamsOnlyWhileTheTabIsVisibleAndTheAutoscanStands() = runTest {
        val bus = carNow()
        CarNow.scriptEcmIdent(bus)
        Fixtures.ecmAnswers(bus)
        bus.delayMs = 130
        val dir = Files.createTempDirectory("diag-stream").toFile()
        val s = session(bus, sinks = CsvStreamSinks(dir, ZoneOffset.UTC))
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        s.setLiveVisible(0x7E0, true)
        // the two PIDs ticked for «Графики» are the stream's focus
        s.pickGraphs(GraphScope.Block(0x7E0), listOf(ParamKey.Pid(0x7E0, 0x0C), ParamKey.Pid(0x7E0, 0x0D)))
        s.setRecording(0x7E0, true)
        advanceTimeBy(10_000)
        runCurrent()

        val r = bus.requests
        // ECM's scan step ends, its identification runs, its freeze frame and tests (the opened screen's, 6), then
        // only the stream: no other module is asked.
        assertEquals(listOf("7E0 22F194", "7E0 190109", "7E0 0101", "7E0 03", "7E0 07", "7E0 0A", "7E0 0902"), r.take(7))
        // The maps as far as they point (80 lists A0, A0 lists the odometer), the static PIDs, then Mode 09
        // as 09 00 lists it: calibration and name.
        assertEquals(listOf("7E0 22F194", "7E0 22F195", "7E0 22F193", "7E0 22F18A", "7E0 22F197", "7E0 0100", "7E0 0120", "7E0 0140",
            "7E0 0160", "7E0 0180", "7E0 01A0", "7E0 011C", "7E0 0101", "7E0 0141", "7E0 01A6",
            "7E0 0900", "7E0 0904", "7E0 090A"), r.subList(7, 25))
        // No frame and no tests answered here: one exchange each.
        assertEquals(listOf("7E0 020200", "7E0 0600"), r.subList(25, 27))
        assertEquals("7E0 010C0D", r[27])
        assertTrue(r.all { it.startsWith("7E0 ") })
        val live = s.model.value.modules.getValue(0x7E0).obd!!.live
        assertTrue(live.running)
        assertTrue(live.recording)
        assertEquals(listOf(ParamKey.Pid(0x7E0, 0x0C), ParamKey.Pid(0x7E0, 0x0D)), live.picks)
        assertEquals("3,8 Гц · 30 мс", live.rate)
        assertEquals("9", live.rows.first { it.pid == 0x05 }.value)
        // The odometer is read once, never streamed.
        assertTrue(live.rows.none { it.pid == 0xA6 })
        assertTrue(r.none { it.startsWith("7E0 01") && it.length > 9 && "A6" in it.drop(6).chunked(2) })
        val ident = s.model.value.modules.getValue(0x7E0).ident
        assertEquals("0C", ident.pids[7])
        assertEquals(listOf("20", "40", "60", "80", "A0"), ident.pids.takeLast(5))
        assertEquals(48, ident.pids.size)
        assertTrue("A6" in ident.pids)
        fun own(key: String) = ident.own.first { it.key == key }
        assertEquals("0x29", own("Стандарт OBD · PID 1C").value)
        assertEquals("1.0.0", own("Версия железа · 22 F193").value)
        assertEquals("не отдаёт · NRC 31", own("Имя системы · 22 F197").value)
        assertTrue(own("Имя системы · 22 F197").muted)
        assertEquals("ECM-EngineControl", own("Имя блока · 09 0A").value)
        assertEquals("Z31L07AB01", own("Калибровка · 09 04").value)
        assertEquals("13 199,7 км", own("Пробег (ECM) · PID A6").value)
        assertEquals(
            listOf("Запрос → ответ", "Протоколы", "Поставщик · 22 F18A", "Номер ПО поставщика · 22 F194", "Версия ПО · 22 F195",
                "Версия железа · 22 F193", "Имя системы · 22 F197", "Имя блока · 09 0A", "Калибровка · 09 04", "Стандарт OBD · PID 1C",
                "Пробег (ECM) · PID A6", "Формат DTC · 19 01"),
            ident.own.map { it.key },
        )

        // The tab closes: the stream stops, the recording closes, the autoscan goes on.
        s.setRecording(0x7E0, false)
        s.setLiveVisible(0x7E0, false)
        val streamed = bus.requests.size
        advanceTimeBy(30_000)
        runCurrent()
        // Only the exchange already on the wire when the tab closed.
        assertTrue(bus.requests.drop(streamed).count { it.startsWith("7E0 01") && it.length > 9 } <= 1)
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        assertFalse(s.model.value.modules.getValue(0x7E0).obd!!.live.running)

        val files = dir.listFiles()!!.filter { it.name.startsWith("stream-") }
        assertEquals(listOf("stream-20261020-133100.csv"), files.map(File::getName))
        val lines = files.single().readLines()
        assertEquals(CsvStreamSinks.HEADER, lines.first())
        assertTrue(lines.any { Regex("^\\d+,7E0,0C,0000,0$").matches(it) })
        assertTrue(lines.any { Regex("^\\d+,7E0,05,31,9$").matches(it) })
        s.stop()
        dir.deleteRecursively()
    }
}
