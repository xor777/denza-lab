package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identification, NRC, the request/answer checks and data quality. */
class DiagDecoderTest {
    private fun request(address: Int, uds: String) = DiagRequest(address, hexBytes(uds))
    private fun answer(address: Int, uds: String, reply: String, at: Long = 1_000) =
        DiagReply.Answer(request(address, uds), hexBytes(reply), 1, at)

    private fun <T> ok(d: Decoded<T>): T {
        assertTrue("expected Ok, got $d", d is Decoded.Ok)
        return (d as Decoded.Ok).value
    }

    @Test
    fun softwareCodesAreHexAndNamesAreAscii() {
        // Run 4 and run 2 of 2026-10-20, as the trace recorded them.
        val f194 = ok(DiagDecoder.identification(answer(0x7E0, "22F194", "62F19432440011E004000000")))
        assertEquals(IdentDid.F194, f194.did)
        assertEquals("32440011E004000000", f194.text)
        assertFalse(f194.ascii)
        assertEquals("9C4A19031900", ok(DiagDecoder.identification(answer(0x7E0, "22F195", "62F1959C4A19031900"))).text)
        val supplier = ok(DiagDecoder.identification(answer(0x7E0, "22F18A", "62F18A55414553202020202020")))
        assertEquals("UAES", supplier.text)
        assertTrue(supplier.ascii)
        assertEquals("55414553202020202020", supplier.raw)
        val system = ok(DiagDecoder.identification(answer(0x720, "22F197", "62F197424F44590000")))
        assertEquals("BODY", system.text)
        // ASCII that does not print stays hex.
        val binary = ok(DiagDecoder.identification(answer(0x720, "22F197", "62F1970102")))
        assertEquals("0102", binary.text)
        assertFalse(binary.ascii)
        // A DID that was not asked.
        assertTrue(DiagDecoder.identification(answer(0x7E0, "22F194", "62F1959C4A19031900")) is Decoded.Malformed)
    }

    @Test
    fun negativeAnswersAreSortedByMeaning() {
        // 7E0 F197 → 31, 7E2 F197 → 10, 7E5 F194 → 11.
        val outOfRange = Polled.of(answer(0x7E0, "22F197", "7F2231")) { DiagDecoder.identification(it) }
        assertTrue(outOfRange is Polled.Unsupported)
        assertEquals("requestOutOfRange", (outOfRange as Polled.Unsupported).nrc?.name)
        val reject = Polled.of(answer(0x7E2, "22F197", "7F2210")) { DiagDecoder.identification(it) }
        assertTrue(reject is Polled.Denied)
        assertEquals(DataQuality.DENIED, reject.quality(1_000, 60_000))
        val noService = Polled.of(answer(0x7E5, "22F194", "7F2211")) { DiagDecoder.identification(it) }
        assertTrue(noService is Polled.Unsupported)
        // A last word of 78 means the answer never came.
        val pending = Polled.of(answer(0x7E0, "190109", "7F1978")) { DiagDecoder.dtcCount(it) }
        assertTrue(pending is Polled.NoReply)
        // A 7F for another service, or of the wrong length, is not an answer.
        assertTrue(DiagDecoder.identification(answer(0x7E0, "22F194", "7F1931")) is Decoded.Malformed)
        assertTrue(DiagDecoder.identification(answer(0x7E0, "22F194", "7F22")) is Decoded.Malformed)
        // A positive answer to another service.
        assertTrue(DiagDecoder.dtcCount(answer(0x7E0, "190109", "62F19432440011E004000000")) is Decoded.Malformed)
        assertTrue(DiagDecoder.dtcCount(answer(0x7E0, "22F194", "590109000000")) is Decoded.Malformed)
        assertTrue(DiagDecoder.dtcCount(answer(0x7E0, "190209", "590109000000")) is Decoded.Malformed)
    }

    @Test
    fun nrcNamesFollowIso14229() {
        val names = mapOf(
            0x10 to "generalReject", 0x11 to "serviceNotSupported", 0x12 to "subFunctionNotSupported",
            0x13 to "incorrectMessageLengthOrInvalidFormat", 0x14 to "responseTooLong", 0x22 to "conditionsNotCorrect",
            0x31 to "requestOutOfRange", 0x33 to "securityAccessDenied", 0x35 to "invalidKey",
            0x36 to "exceedNumberOfAttempts", 0x37 to "requiredTimeDelayNotExpired",
            0x78 to "requestCorrectlyReceivedResponsePending", 0x7E to "subFunctionNotSupportedInActiveSession",
            0x7F to "serviceNotSupportedInActiveSession",
        )
        names.forEach { (code, name) ->
            assertEquals(name, Nrc.of(code).name)
            assertTrue(Nrc.of(code).label.isNotBlank())
        }
        assertEquals("служба не поддерживается", Nrc.of(0x11).label)
        assertEquals("NRC 93", Nrc.of(0x93).label)
        assertEquals(setOf(0x11, 0x12, 0x31, 0x7E, 0x7F), (0..0xFF).filter { Nrc.of(it).unsupported }.toSet())
        assertEquals(Nrc.of(0x31), Nrc.of(0x31))
    }

    @Test
    fun theGatewaysOwnWordsBecomeQualities() {
        val req = request(0x7E1, "0100")
        val none = Polled.of(DiagReply.NoReply(req, 3, 5)) { DiagDecoder.obdPids(it) }
        assertEquals(Polled.NoReply(3, 5), none)
        assertEquals(DataQuality.NO_REPLY, none.quality(10, 60_000))
        val busy = Polled.of(DiagReply.GatewayBusy(req, 6)) { DiagDecoder.obdPids(it) }
        assertEquals(DataQuality.BUSY, busy.quality(10, 60_000))
        assertEquals(6L, busy.atMillis)
        assertEquals(DataQuality.NOT_POLLED, Polled.NotPolled.quality(10, 60_000))
        assertEquals(null, Polled.NotPolled.atMillis)
    }

    @Test
    fun onlyACompleteFreshEmptyAnswerProvesNoCodes() {
        val empty = Polled.of(answer(0x7F2, "190109", "590109000000", at = 1_000)) { DiagDecoder.dtcCount(it) }
        // A count alone is not MaybeEmpty: it says how many, not which.
        assertEquals(DataQuality.COMPLETE, empty.quality(1_000, 60_000))
        val memory = ok(DiagDecoder.dtcMemory(answer(0x7F2, "190109", "590109000000"), null))
        assertTrue(memory.isEmpty)
        assertEquals(DataQuality.COMPLETE_EMPTY, Polled.Got(memory, 1_000).quality(1_000, 60_000))
        assertEquals(DataQuality.STALE, Polled.Got(memory, 1_000).quality(61_001, 60_000))
        assertEquals(listOf(DataQuality.COMPLETE_EMPTY), DataQuality.entries.filter { it.provesNone })
        assertEquals(
            listOf("не читали", "нет ответа", "шлюз занят", "отказано", "не поддерживается", "несогласованный ответ", "устарело"),
            listOf(DataQuality.NOT_POLLED, DataQuality.NO_REPLY, DataQuality.BUSY, DataQuality.DENIED,
                DataQuality.UNSUPPORTED, DataQuality.INCONSISTENT, DataQuality.STALE).map { it.label },
        )
    }
}
