package dev.denza.apps.feature.diagnostics.bus

import java.io.EOFException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BMS pages on the helper's stream (architecture 3.3, 6.3): the helper's `s gb` / `u gb` and
 * the records it forwards, the app's reading of a pushed line, and the bus that subscribes, reads
 * between exchanges and leaves.
 */
class BatteryPagesLinesTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A GB record as the listener gets it: the ten-byte envelope (id, sub, channel, counter), then the payload. */
    private fun record(id: Int, payload: String, sub: Int = 0, channel: Int = 2, counter: Long = 71_752_279) =
        bytes("%08X%02X%02X%08X".format(id, sub, channel, counter) + payload)

    // ------------------------------------------------------------------ the helper's side

    @Test
    fun theHelperTakesExactlySubscribeAndLeave() {
        assertEquals("s", DiagnosticBusMain.pagesOf("s gb"))
        assertEquals("u", DiagnosticBusMain.pagesOf("  u   gb "))
        listOf("s", "u", "s gb x", "s yun", "S gb", "x gb", "sgb", "u GB").forEach {
            assertNull(it, DiagnosticBusMain.pagesOf(it))
        }
        assertEquals("s", DiagnosticBusMain.pagesOf(DiagnosticBusLines.SUBSCRIBE))
        assertEquals("u", DiagnosticBusMain.pagesOf(DiagnosticBusLines.LEAVE))
    }

    @Test
    fun theHelperForwardsOnlyTheBmsPagesAtSubZeroChannelTwo() {
        // Real records of the correlation window (research/vehicle-diagnostics/battery-page-telemetry-20261021.json).
        assertEquals("b 145197845045228 43A A60E813805270066",
            DiagnosticBusMain.pageLine(record(0x43A, "a60e813805270066"), 145_197_845_045_228))
        assertEquals("b 5 43D FD8613F00CED0C76", DiagnosticBusMain.pageLine(record(0x43D, "fd8613f00ced0c76"), 5))
        assertEquals("b 5 43C 00363738393A3B00", DiagnosticBusMain.pageLine(record(0x43C, "00363738393a3b00"), 5))

        assertNull("another id", DiagnosticBusMain.pageLine(record(0x43B, "0001020304050607"), 5))
        assertNull("another id", DiagnosticBusMain.pageLine(record(0x122, "0001020304050607"), 5))
        assertNull("sub 1", DiagnosticBusMain.pageLine(record(0x43D, "0001020304050607", sub = 1), 5))
        assertNull("channel 1", DiagnosticBusMain.pageLine(record(0x43D, "0001020304050607", channel = 1), 5))
        assertNull("no payload", DiagnosticBusMain.pageLine(record(0x43D, ""), 5))
        assertNull("longer than a CAN-FD frame", DiagnosticBusMain.pageLine(record(0x43D, "00".repeat(65)), 5))
        assertNull(DiagnosticBusMain.pageLine(null, 5))
        assertNull(DiagnosticBusMain.pageLine(bytes("0000043D0002"), 5))
    }

    @Test
    fun whatTheHelperForwardsTheAppReadsBack() {
        val line = DiagnosticBusMain.pageLine(record(0x43D, "00fc0cfb0cfc0c47"), 145_199_105_664_613)!!
        val page = (DiagnosticBusLines.pushed(line) as DiagnosticBusLines.Pushed.Page).page
        assertEquals(BmsPage.CELLS, page.canId)
        assertEquals(145_199_105_664_613, page.receivedNanos)
        assertArrayEquals(bytes("00fc0cfb0cfc0c47"), page.payload)
        assertEquals(0xFC, page[1])
    }

    @Test
    fun theHelperEndsASubscriptionWithACodeTheAppKnows() {
        val helper = java.io.File("src/main/java/dev/denza/apps/feature/diagnostics/bus/DiagnosticBusMain.java").readText()
        assertTrue(helper.contains("push + \"e \" + verdict"))
        assertTrue(helper.contains("\"s gb\"") && helper.contains("\"u gb\""))
    }

    // ------------------------------------------------------------------ the app's reading

    @Test
    fun aPushedLineIsAPageOrTheEnd() {
        val page = DiagnosticBusLines.pushed("b 77 43C 00363738393A3B00\r") as DiagnosticBusLines.Pushed.Page
        assertEquals(BmsPage.PROBES, page.page.canId)
        assertEquals(77L, page.page.receivedNanos)

        val lost = DiagnosticBusLines.pushed("e FOREIGN_TRAFFIC a frame not of this exchange") as DiagnosticBusLines.Pushed.End
        assertTrue(lost.failure is DiagFailure.ChannelBusy)
        assertEquals("FOREIGN_TRAFFIC a frame not of this exchange", lost.failure.message)
        assertTrue((DiagnosticBusLines.pushed("e HELPER_FAILED") as DiagnosticBusLines.Pushed.End).failure is DiagFailure.ChannelLost)
    }

    @Test
    fun anythingElseIsNotAPageAndNotAFailure() {
        listOf(
            "",
            "b",
            "b 77 43C",
            "b -1 43C 00",
            "b x 43C 00",
            "b 77 43B 0001",
            "b 77 122 0001",
            "b 77 43c 0001",
            "b 77 43C 0",
            "b 77 43C 00zz",
            "b 77 43C 00 extra",
            "b 77 43C " + "00".repeat(65),
            "e",
            "e lower case",
            "x 7 7E0 22F194 4000",
            "a 7 1 38 5 5901FF000000",
        ).forEach { assertNull(it, DiagnosticBusLines.pushed(it)) }
    }

    @Test
    fun subscribeAndLeaveAreAnsweredWithThemselves() {
        DiagnosticBusLines.pagesTook("s gb", "s gb\r\n")
        DiagnosticBusLines.pagesTook("u gb", "u gb\n")
        listOf("", "u gb", "s gb\ns gb", "g 20", "s").forEach {
            assertThrows(it, DiagFailure.Refused::class.java) { DiagnosticBusLines.pagesTook("s gb", it) }
        }
    }

    // ------------------------------------------------------------------ the bus

    /** One link at a time; [pushes] are handed out one batch per read, then nothing. */
    private class Car {
        val events = mutableListOf<String>()
        val answers = ArrayDeque<(String) -> String>()
        val pushes = ArrayDeque<() -> List<String>>()
        private var nonces = 0

        fun bus(executor: java.util.concurrent.Executor = java.util.concurrent.Executor(Runnable::run)) = ResidentDiagnosticBus(
            links = { nonce -> Link(nonce) },
            stage = { "jar" },
            gapMs = 100,
            nonce = { "n${++nonces}" },
            clock = { 0L },
            wallClock = { 5L },
            blocking = executor,
        )

        inner class Link(private val nonce: String) : DiagnosticBusLink {
            override fun start(command: String, readyTimeoutMs: Int) {
                synchronized(events) { events += "start $nonce" }
            }

            override fun request(line: String, readTimeoutMs: Int): String {
                synchronized(events) { events += "$nonce $line" }
                return synchronized(events) { answers.removeFirst() }(line)
            }

            override fun pushed(waitMs: Int): List<String> {
                val next = synchronized(events) { pushes.removeFirstOrNull() }
                if (next == null) {
                    Thread.sleep(2)
                    return emptyList()
                }
                synchronized(events) { events += "$nonce read" }
                return next()
            }

            override fun close() {
                synchronized(events) { events += "close $nonce" }
            }
        }
    }

    private fun page(id: Int, payload: String, at: Long) = "b $at %03X $payload".format(id)

    @Test
    fun theBusSubscribesReadsThePagesAndLeaves() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.answers += { "s gb\n" }
        car.pushes += { listOf(page(0x43A, "A60E813805270066", 1), "b broken", page(0x43D, "00FC0CFB0CFC0C47", 2)) }
        car.pushes += { listOf(page(0x43C, "00363738393A3B00", 3)) }
        car.answers += { "u gb\n" }

        val pages = bus.batteryPages().take(3).toList()

        assertEquals(listOf(0x43A, 0x43D, 0x43C), pages.map { it.canId })
        assertEquals(listOf(1L, 2L, 3L), pages.map { it.receivedNanos })
        assertEquals(listOf("start n1", "n1 s gb", "n1 read", "n1 read", "n1 u gb"), car.events)
    }

    @Test
    fun anExchangeKeepsItsTurnWhileThePagesFlow() = runBlocking {
        val car = Car()
        val pool = Executors.newCachedThreadPool()
        val bus = car.bus(pool)
        car.answers += { "s gb\n" }
        car.answers += { "n 0 3 18 1\n" }
        car.answers += { "u gb\n" }
        val listening = async(Dispatchers.Default) {
            withTimeout(5_000) { bus.batteryPages().take(1).toList() }
        }
        // The flow reads nothing until the exchange has gone: the page comes after it.
        while (synchronized(car.events) { "n1 s gb" !in car.events }) Thread.sleep(1)
        val reply = withTimeout(5_000) { bus.exchange(DiagRequest(0x782, bytes("190109"))) }
        assertTrue(reply is DiagReply.NoReply)
        synchronized(car.events) { car.pushes += { listOf(page(0x43D, "00FC0CFB0CFC0C47", 9)) } }
        assertEquals(9L, listening.await().single().receivedNanos)
        val events = synchronized(car.events) { car.events.toList() }
        assertEquals(listOf("start n1", "n1 s gb", "n1 x 0 782 190109 4000", "n1 read", "n1 u gb"), events)
        pool.shutdown()
    }

    @Test
    fun theHelpersEndEndsThePagesAndItsLink() = runBlocking<Unit> {
        val car = Car()
        val bus = car.bus()
        car.answers += { "s gb\n" }
        car.pushes += { listOf(page(0x43D, "00FC0CFB0CFC0C47", 1), "e FOREIGN_TRAFFIC a frame not of this exchange") }
        val seen = mutableListOf<BmsPage>()
        val failure = assertThrows(DiagFailure.ChannelBusy::class.java) {
            runBlocking { bus.batteryPages().collect { seen += it } }
        }
        assertEquals("FOREIGN_TRAFFIC a frame not of this exchange", failure.message)
        assertEquals(1, seen.size)
        // No `u gb` to a helper whose session is over: its link is closed instead.
        assertEquals(listOf("start n1", "n1 s gb", "n1 read", "close n1"), car.events)
    }

    @Test
    fun aHelperLostUnderAnExchangeEndsThePagesAndIsNotSubscribedAgain() = runBlocking<Unit> {
        val car = Car()
        val pool = Executors.newCachedThreadPool()
        val bus = car.bus(pool)
        car.answers += { "s gb\n" }
        car.answers += { throw EOFException("the resident helper's stream closed") }
        car.answers += { "n 1 3 18 1\n" }
        val listening = async(Dispatchers.Default) {
            runCatching { withTimeout(5_000) { bus.batteryPages().collect {} } }.exceptionOrNull()
        }
        while (synchronized(car.events) { "n1 s gb" !in car.events }) Thread.sleep(1)
        bus.exchange(DiagRequest(0x782, bytes("190109")))
        val failure = listening.await()
        assertTrue("$failure", failure is DiagFailure.ChannelLost)
        val events = synchronized(car.events) { car.events.toList() }
        assertEquals(listOf("start n1", "n1 s gb", "n1 x 0 782 190109 4000", "close n1", "start n2", "n2 x 1 782 190109 4000"), events)
        pool.shutdown()
    }

    @Test
    fun aLostStreamWhileReadingIsALostChannel() = runBlocking<Unit> {
        val car = Car()
        val bus = car.bus()
        car.answers += { "s gb\n" }
        car.pushes += { throw IOException("socket closed") }
        assertThrows(DiagFailure.ChannelLost::class.java) { runBlocking { bus.batteryPages().collect {} } }
        assertEquals(listOf("start n1", "n1 s gb", "n1 read", "close n1"), car.events)
    }

    @Test
    fun aRefusedSubscriptionIsTheHelpersCode() = runBlocking<Unit> {
        val car = Car()
        val bus = car.bus()
        car.answers += { throw DiagnosticBusLines.refusal(IOException("the resident helper refused: err GUARD_PARK gear 4"))!! }
        assertThrows(DiagFailure.NotParked::class.java) { runBlocking { bus.batteryPages().collect {} } }
        assertEquals(listOf("start n1", "n1 s gb", "close n1"), car.events)
    }

    @Test
    fun aCancelledSubscriptionLeaves() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.answers += { "s gb\n" }
        car.answers += { "u gb\n" }
        car.pushes += { listOf(page(0x43D, "00FC0CFB0CFC0C47", 1)) }
        try {
            bus.batteryPages().collect { throw CancellationException("the tab was left") }
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("start n1", "n1 s gb", "n1 read", "n1 u gb"), car.events)
    }
}
