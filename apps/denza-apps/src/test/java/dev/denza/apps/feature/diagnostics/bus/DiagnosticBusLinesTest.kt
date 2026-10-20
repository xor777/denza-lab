package dev.denza.apps.feature.diagnostics.bus

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBusLinesTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val count = DiagRequest(0x782, bytes("190109"))

    @Test
    fun aRequestIsOneLine() {
        assertEquals("x 7 782 190109 4000", DiagnosticBusLines.request(7, count))
        assertEquals("x 0 7E0 010C0D050B110F 1500", DiagnosticBusLines.request(0, DiagRequest(0x7E0, bytes("010C0D050B110F"), 1_500)))
    }

    @Test
    fun anAnswerCarriesItsBytesFramesAndTiming() {
        val reply = DiagnosticBusLines.reply(count, 7, "a 7 1 38 123456789012 5901FF000000\r\n", atMillis = 99)
        reply as DiagReply.Answer
        assertArrayEquals(bytes("5901FF000000"), reply.uds)
        assertEquals(1, reply.frames)
        assertEquals(38, reply.latencyMs)
        assertEquals(123_456_789_012L, reply.receivedNanos)
        assertEquals(99, reply.atMillis)
        assertNull(reply.nrc)

        val negative = DiagnosticBusLines.reply(count, 8, "a 8 1 12 5 7F1931\n", 0) as DiagReply.Answer
        assertEquals(0x31, negative.nrc)
    }

    @Test
    fun noAnswerAndBusyAreRepliesNotFailures() {
        listOf("3" to 3, "4" to 4, "T" to 0).forEach { (word, result) ->
            val reply = DiagnosticBusLines.reply(count, 1, "n 1 $word 4000 77\n", 0)
            reply as DiagReply.NoReply
            assertEquals(result, reply.gatewayResult)
            assertEquals(4_000, reply.latencyMs)
            assertEquals(77L, reply.receivedNanos)
        }
        val busy = DiagnosticBusLines.reply(count, 2, "n 2 5 3 9\n", 0)
        assertTrue(busy is DiagReply.GatewayBusy)
        assertEquals(3, busy.latencyMs)
    }

    @Test
    fun anythingElseIsAProgramError() {
        listOf(
            "",
            "a 6 1 38 5 5901FF000000", // another seq
            "a 7 1 38 5 5901FF000000\na 7 1 38 5 5901FF000000",
            "a 7 0 38 5 5901FF000000",
            "a 7 17 38 5 5901FF000000",
            "a 7 1 -1 5 5901FF000000",
            "a 7 1 38 -5 5901FF000000",
            "a 7 1 38 5 5901FF00000",
            "a 7 1 38 5 5901ff000000",
            "a 7 1 38 5",
            "n 7 6 4000 5",
            "n 7 3 4000",
            "x 7 782 190109 4000",
            "ok",
        ).forEach { payload ->
            assertThrows(payload, DiagFailure.Refused::class.java) { DiagnosticBusLines.reply(count, 7, payload, 0) }
        }
    }

    @Test
    fun theStreamsRefusalCarriesTheHelpersCode() {
        val refusal = DiagnosticBusLines.refusal(IOException("the resident helper refused: err GUARD_PARK gear 3"))
        assertEquals("GUARD_PARK", refusal?.code)
        assertEquals("gear 3", refusal?.why)
        assertEquals("LOCK_BUSY", DiagnosticBusLines.refusal(IOException("the resident helper refused: err LOCK_BUSY"))?.code)

        assertNull(DiagnosticBusLines.refusal(IOException("the resident helper refused: busy")))
        assertNull(DiagnosticBusLines.refusal(IOException("the resident helper's stream closed")))
        assertNull(DiagnosticBusLines.refusal(IOException()))
    }

    @Test
    fun everyCodeHasItsFailure() {
        fun failure(code: String) = DiagnosticBusLines.failure(HelperRefusal(code, "why"))
        listOf("GUARD_PROFILE", "GUARD_PARK", "GUARD_SPEED").forEach {
            assertTrue(it, failure(it) is DiagFailure.NotParked)
        }
        listOf("LOCK_BUSY", "MANAGER_BUSY", "FOREIGN_TRAFFIC").forEach {
            assertTrue(it, failure(it) is DiagFailure.ChannelBusy)
        }
        listOf("FRAME_SHAPE", "TRANSPORT_REJECTED", "HELPER_FAILED").forEach {
            assertTrue(it, failure(it) is DiagFailure.ChannelLost)
        }
        listOf("REFUSED", "WRONG_UID", "SOMETHING_NEW").forEach {
            assertTrue(it, failure(it) is DiagFailure.Refused)
        }
        assertEquals("GUARD_PARK why", failure("GUARD_PARK").message)
    }

    @Test
    fun theHelperAndTheAppNameTheSameCodes() {
        // Every code the helper can write is one the app maps on purpose, not by the fallback.
        val helper = java.io.File("src/main/java/dev/denza/apps/feature/diagnostics/bus/DiagnosticBusMain.java").readText()
        val written = Regex(""""([A-Z_]{4,}) """).findAll(helper).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "REFUSED", "WRONG_UID", "LOCK_BUSY", "HELPER_FAILED", "GUARD_PROFILE", "GUARD_PARK",
                "GUARD_SPEED", "MANAGER_BUSY", "FOREIGN_TRAFFIC", "FRAME_SHAPE", "TRANSPORT_REJECTED",
            ),
            written,
        )
        written.filter { it != "REFUSED" && it != "WRONG_UID" }.forEach {
            assertTrue(it, DiagnosticBusLines.failure(HelperRefusal(it, "")) !is DiagFailure.Refused)
        }
    }
}
