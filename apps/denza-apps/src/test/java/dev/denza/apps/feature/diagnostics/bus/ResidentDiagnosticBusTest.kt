package dev.denza.apps.feature.diagnostics.bus

import dev.denza.disharebridge.LocalAdbClient
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's side of the resident helper, on a scripted link: what is opened, sent, closed and when. */
class ResidentDiagnosticBusTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val f194 = DiagRequest(0x7E0, bytes("22F194"))
    private val count = DiagRequest(0x782, bytes("190109"))

    /** Every link of one bus writes here, in order; [answers] are used in order across links. */
    private class Car {
        val events = mutableListOf<String>()
        val answers = ArrayDeque<(String) -> String>()
        var startFailure: (() -> Unit)? = null
        var now = 0L
        private var nonces = 0

        fun bus(executor: Executor = Executor(Runnable::run)) = ResidentDiagnosticBus(
            links = { nonce -> Link(nonce) },
            stage = { events += "stage"; "/data/local/tmp/denza-diag-bus-abc.jar" },
            gapMs = 120,
            nonce = { "n${++nonces}" },
            clock = { now },
            wallClock = { 5L },
            blocking = executor,
        )

        fun answer(payload: String) = answers.addLast { payload }
        fun lose() = answers.addLast { throw EOFException("the resident helper's stream closed") }
        fun refuse(code: String, why: String) = answers.addLast {
            throw DiagnosticBusLines.refusal(IOException("the resident helper refused: err $code $why"))!!
        }

        inner class Link(private val nonce: String) : DiagnosticBusLink {
            override fun start(command: String, readyTimeoutMs: Int) {
                events += "start $nonce"
                assertTrue(command, command.endsWith("serve $nonce 120"))
                startFailure?.invoke()
            }

            override fun request(line: String, readTimeoutMs: Int): String {
                events += "$nonce $line"
                return answers.removeFirst()(line)
            }

            override fun close() {
                events += "close $nonce"
            }
        }
    }

    @Test
    fun theHelperOpensAtTheFirstExchangeAndServesTheNext() = runBlocking {
        val car = Car()
        val bus = car.bus()
        assertEquals(emptyList<String>(), car.events)
        car.answer("a 0 1 41 1000 62F19432440011E004000000\n")
        car.answer("n 1 3 18 2000\n")

        val first = bus.exchange(f194) as DiagReply.Answer
        val second = bus.exchange(count) as DiagReply.NoReply

        assertEquals(listOf("stage", "start n1", "n1 x 0 7E0 22F194 4000", "n1 x 1 782 190109 4000"), car.events)
        assertEquals(41, first.latencyMs)
        assertEquals(1000L, first.receivedNanos)
        assertEquals(3, second.gatewayResult)
        assertEquals(5L, second.atMillis)
    }

    @Test
    fun theLaunchCommandRunsTheStagedJarAsItsOwnShell() {
        assertEquals(
            "CLASSPATH='/data/local/tmp/denza-diag-bus-abc.jar' exec app_process /system/bin " +
                "--nice-name=denza_diag_bus dev.denza.apps.feature.diagnostics.bus.DiagnosticBusMain serve 0f1e 100",
            ResidentDiagnosticBus.launchCommand("/data/local/tmp/denza-diag-bus-abc.jar", "0f1e", 100),
        )
        assertThrows(IllegalArgumentException::class.java) { Car().run { ResidentDiagnosticBus({ Link(it) }, { "" }, gapMs = 501) } }
    }

    @Test
    fun aGapGoesDownTheLinkBeforeTheNextExchangeAndANewHelperIsToldAgain() = runBlocking<Unit> {
        val car = Car()
        val bus = car.bus()
        // The launch parameter is the gap already: nothing to say.
        bus.setGap(120)
        car.answer("n 0 3 18 1\n")
        bus.exchange(count)
        assertEquals(listOf("stage", "start n1", "n1 x 0 782 190109 4000"), car.events)

        // Asked for 20: one line before the next exchange, and not again while it holds.
        bus.setGap(20)
        assertEquals(3, car.events.size)
        car.answer("g 20\n")
        car.answer("n 1 3 18 2\n")
        car.answer("n 2 3 18 3\n")
        bus.exchange(count)
        bus.exchange(count)
        assertEquals(listOf("n1 g 20", "n1 x 1 782 190109 4000", "n1 x 2 782 190109 4000"), car.events.drop(3))

        // A lost helper: the new one starts at its launch parameter and is told 20 again.
        car.lose()
        car.answer("g 20\n")
        car.answer("n 4 3 18 4\n")
        bus.exchange(count)
        assertEquals(
            listOf("n1 x 3 782 190109 4000", "close n1", "stage", "start n2", "n2 g 20", "n2 x 4 782 190109 4000"),
            car.events.drop(6),
        )

        // Back to the launch gap is a line too.
        bus.setGap(120)
        car.answer("g 120\n")
        car.answer("n 5 3 18 5\n")
        bus.exchange(count)
        assertEquals(listOf("n2 g 120", "n2 x 5 782 190109 4000"), car.events.drop(12))
        assertThrows(IllegalArgumentException::class.java) { bus.setGap(501) }
    }

    @Test
    fun aGapAnsweredOtherwiseIsAProgramErrorAndARefusedOneTheHelpersCode() {
        val car = Car()
        val bus = car.bus()
        bus.setGap(20)
        car.answer("g 40\n")
        assertThrows(DiagFailure.Refused::class.java) { runBlocking { bus.exchange(count) } }
        assertEquals(listOf("stage", "start n1", "n1 g 20", "close n1"), car.events)

        // A helper whose session is over answers the gap with its verdict.
        car.refuse("GUARD_PARK", "gear 3")
        assertThrows(DiagFailure.NotParked::class.java) { runBlocking { bus.exchange(count) } }
        assertEquals(listOf("stage", "start n2", "n2 g 20", "close n2"), car.events.drop(4))
    }

    @Test
    fun aLostLinkIsOpenedAgainOnceAndTheRequestSentAgain() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.lose()
        car.answer("a 1 1 30 5 5901FF000000\n")

        assertTrue(bus.exchange(count) is DiagReply.Answer)
        assertEquals(
            listOf("stage", "start n1", "n1 x 0 782 190109 4000", "close n1", "stage", "start n2", "n2 x 1 782 190109 4000"),
            car.events,
        )
    }

    @Test
    fun aSecondLossReachesTheCaller() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.lose()
        car.lose()

        assertThrows(DiagFailure.ChannelLost::class.java) { runBlocking { bus.exchange(count) } }
        assertEquals(2, car.events.count { it.startsWith("start") })

        // The bus is not spent: the caller's next exchange opens a helper again.
        car.answer("a 2 1 30 5 5901FF000000\n")
        assertTrue(bus.exchange(count) is DiagReply.Answer)
        assertEquals(3, car.events.count { it.startsWith("start") })
    }

    @Test
    fun aHelperThatNeverSaysReadyIsLostTwiceAtMost() {
        val car = Car()
        car.startFailure = { throw EOFException("the resident helper's stream closed") }
        assertThrows(DiagFailure.ChannelLost::class.java) { runBlocking { car.bus().exchange(count) } }
        assertEquals(listOf("stage", "start n1", "close n1", "stage", "start n2", "close n2"), car.events)
    }

    @Test
    fun aBusyChannelIsNotRetried() {
        listOf("LOCK_BUSY", "FOREIGN_TRAFFIC", "MANAGER_BUSY").forEach { code ->
            val car = Car()
            car.refuse(code, "why")
            val failure = assertThrows(DiagFailure.ChannelBusy::class.java) { runBlocking { car.bus().exchange(count) } }
            assertEquals("$code why", failure.why)
            assertEquals(listOf("stage", "start n1", "n1 x 0 782 190109 4000", "close n1"), car.events)
        }
    }

    @Test
    fun aStartThatWasRefusedReachesTheAppAsItsReason() = runBlocking {
        // The helper could not take the lock or found the car moving; it said READY anyway, and
        // the first line learns why.
        val car = Car()
        val bus = car.bus()
        car.refuse("GUARD_PARK", "gear 3")
        assertThrows(DiagFailure.NotParked::class.java) { runBlocking { bus.exchange(count) } }
        assertEquals(listOf("stage", "start n1", "n1 x 0 782 190109 4000", "close n1"), car.events)

        car.answer("a 1 1 30 5 5901FF000000\n")
        assertTrue(bus.exchange(count) is DiagReply.Answer)
        assertEquals("start n2", car.events[5])
    }

    @Test
    fun noTrustedAdbIsNoAccessAndNotRetried() {
        val car = Car()
        val required = LocalAdbClient.AuthorizationRequiredException::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance()
        car.startFailure = { throw IOException("start", required) }
        assertThrows(DiagFailure.NoAccess::class.java) { runBlocking { car.bus().exchange(count) } }
        assertEquals(1, car.events.count { it.startsWith("start") })
    }

    @Test
    fun aMalformedAnswerIsAProgramErrorAndEndsTheLink() {
        val car = Car()
        car.answer("a 9 1 30 5 5901FF000000\n")
        assertThrows(DiagFailure.Refused::class.java) { runBlocking { car.bus().exchange(count) } }
        assertEquals("close n1", car.events.last())
    }

    @Test
    fun anIdleHelperIsNotTrusted() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.answer("n 0 3 4000 1\n")
        car.answer("n 1 3 4000 2\n")
        car.answer("n 2 3 4000 3\n")
        bus.exchange(count)
        car.now += ResidentDiagnosticBus.IDLE_REUSE_MS - 1
        bus.exchange(count)
        car.now += ResidentDiagnosticBus.IDLE_REUSE_MS
        bus.exchange(count)
        assertEquals(
            listOf(
                "stage", "start n1", "n1 x 0 782 190109 4000", "n1 x 1 782 190109 4000",
                "close n1", "stage", "start n2", "n2 x 2 782 190109 4000",
            ),
            car.events,
        )
    }

    @Test
    fun closeEndsTheLinkAndTheBus() = runBlocking {
        val car = Car()
        val bus = car.bus()
        car.answer("n 0 3 4000 1\n")
        bus.exchange(count)
        bus.close()
        assertThrows(DiagFailure.ChannelLost::class.java) { runBlocking { bus.exchange(count) } }
        assertEquals(listOf("stage", "start n1", "n1 x 0 782 190109 4000", "close n1"), car.events)
    }

    @Test
    fun aCancelledExchangeClosesItsLinkAtOnce() = runBlocking {
        val inside = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val car = Car()
        val pool = Executors.newCachedThreadPool()
        val bus = ResidentDiagnosticBus(
            links = { _ ->
                object : DiagnosticBusLink {
                    override fun start(command: String, readyTimeoutMs: Int) = Unit
                    override fun request(line: String, readTimeoutMs: Int): String {
                        inside.countDown()
                        // The socket's close is what ends a blocked read.
                        closed.await(5, TimeUnit.SECONDS)
                        throw IOException("socket closed")
                    }
                    override fun close() = closed.countDown()
                }
            },
            stage = { car.events += "stage"; "jar" },
            blocking = pool,
        )
        val job = launch(Dispatchers.Default) { bus.exchange(count) }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        job.join()
        pool.shutdown()
    }

    @Test
    fun oneExchangeAtATime() = runBlocking {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val pool = Executors.newCachedThreadPool()
        val bus = ResidentDiagnosticBus(
            links = { _ ->
                object : DiagnosticBusLink {
                    override fun start(command: String, readyTimeoutMs: Int) = Unit
                    override fun request(line: String, readTimeoutMs: Int): String {
                        most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        Thread.sleep(20)
                        inFlight.decrementAndGet()
                        return "n ${line.split(' ')[1]} 3 20 1\n"
                    }
                    override fun close() = Unit
                }
            },
            stage = { "jar" },
            blocking = pool,
        )
        (1..4).map { async(Dispatchers.Default) { bus.exchange(count) } }.awaitAll()
        assertEquals(1, most.get())
        pool.shutdown()
    }
}
