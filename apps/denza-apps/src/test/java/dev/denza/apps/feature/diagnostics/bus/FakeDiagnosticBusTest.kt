package dev.denza.apps.feature.diagnostics.bus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake the engine is tested on does what its KDoc promises. */
class FakeDiagnosticBusTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val rpm = DiagRequest(0x7E0, bytes("010C"))
    private val count = DiagRequest(0x782, bytes("190109"))

    @Test
    fun stepsAreUsedInOrderAndTheLastOneRepeats() = runBlocking {
        val bus = FakeDiagnosticBus(gapMs = 100, latencyMs = 30)
        bus.on(0x7E0, "010c").answer("410C0FA0").answer("410C1F40", latencyMs = 50)

        val first = bus.exchange(rpm) as DiagReply.Answer
        val second = bus.exchange(rpm) as DiagReply.Answer
        val third = bus.exchange(rpm) as DiagReply.Answer

        assertArrayEquals(bytes("410C0FA0"), first.uds)
        assertArrayEquals(bytes("410C1F40"), second.uds)
        assertArrayEquals(bytes("410C1F40"), third.uds)
        assertEquals(30, first.latencyMs)
        assertEquals(50, second.latencyMs)
        assertEquals(150_000_000L, second.receivedNanos - first.receivedNanos)
    }

    @Test
    fun theGapIsRecordedAndRunsTheCarClockFromTheNextExchange() = runBlocking<Unit> {
        val bus = FakeDiagnosticBus(gapMs = 100, latencyMs = 30)
        bus.on(0x7E0, "010C").answer("410C0FA0")
        val first = bus.exchange(rpm)
        bus.setGap(20)
        val second = bus.exchange(rpm)
        bus.setGap(40)
        val third = bus.exchange(rpm)
        assertEquals(listOf(20, 40), bus.gaps)
        assertEquals(50_000_000L, second.receivedNanos - first.receivedNanos)
        assertEquals(70_000_000L, third.receivedNanos - second.receivedNanos)
        assertThrows(IllegalArgumentException::class.java) { bus.setGap(501) }
    }

    @Test
    fun anUnscriptedAddressIsSilentAndFailuresAreLogged() = runBlocking {
        val bus = FakeDiagnosticBus()
        assertEquals(3, (bus.exchange(count) as DiagReply.NoReply).gatewayResult)

        bus.on(0x782, "190109").busy().fail(DiagFailure.NotParked("GUARD_PARK gear 3"))
        assertTrue(bus.exchange(count) is DiagReply.GatewayBusy)
        assertThrows(DiagFailure.NotParked::class.java) { runBlocking { bus.exchange(count) } }

        bus.failNext(DiagFailure.ChannelBusy("FOREIGN_TRAFFIC"))
        assertThrows(DiagFailure.ChannelBusy::class.java) { runBlocking { bus.exchange(rpm) } }

        assertEquals(listOf("782 190109", "782 190109", "782 190109", "7E0 010C"), bus.requests)
        assertTrue(bus.exchanges[2].failure is DiagFailure.NotParked)

        bus.close()
        assertThrows(DiagFailure.ChannelLost::class.java) { runBlocking { bus.exchange(rpm) } }
        assertTrue(bus.closed)
    }
}
