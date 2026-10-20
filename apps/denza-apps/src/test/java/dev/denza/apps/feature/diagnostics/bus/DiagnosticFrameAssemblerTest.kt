package dev.denza.apps.feature.diagnostics.bus

import dev.denza.apps.feature.diagnostics.bus.DiagnosticFrameAssembler.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame assembler on frames the car delivered: the 2026-10-18 and 2026-10-20 research runs
 * (`captures/diagnostics-*`), whole 240-byte buffers where the trace kept them, and synthetic
 * frames only for shapes the car has not yet sent - more than one frame, a broken sequence.
 */
class DiagnosticFrameAssemblerTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun opened(address: Int, uds: String) = DiagnosticFrameAssembler().apply { begin(address, bytes(uds)) }

    private fun DiagnosticFrameAssembler.feed(hex: String) = accept(bytes(hex))

    private fun assertAnswer(expected: String, frames: Int, outcome: DiagnosticFrameAssembler.Outcome) {
        assertEquals(outcome.toString(), Kind.ANSWER, outcome.kind)
        assertArrayEquals(bytes(expected), outcome.uds)
        assertEquals(frames, outcome.frames)
    }

    @Test
    fun aSingleFrameIdentificationIsTheAnswer() {
        val bus = opened(0x7E0, "22F194")
        assertAnswer("62F19432440011E004000000", 1, bus.feed("0A07E80C01010162F19432440011E004000000"))
        assertEquals(false, bus.isOpen)
    }

    @Test
    fun onlyTheDeclaredLengthOfA240ByteBufferIsRead() {
        // ECU 762's DTC list: three declared bytes, and in the stale tail the eight records an
        // earlier answer from 720 left in the same buffer. They are not this ECU's codes.
        assertEquals(240, bytes(LBMS_LIST_240).size)
        assertAnswer("590209", 1, opened(0x762, "190209").feed(LBMS_LIST_240))

        assertAnswer(
            "5902FF51600628C29D8728C1218728A1480228A2500028A25B0028C2298728C22A8728",
            1,
            opened(0x720, "1902FF").feed(BODY_LIST_240),
        )
        assertAnswer("5901FF000008", 1, opened(0x720, "1901FF").feed(BODY_COUNT_240))
    }

    @Test
    fun theSameFrameTwiceIsOneFrame() {
        val bus = opened(0x782, "190109")
        val frame = "0A078A060101015901FF000000"
        assertAnswer("5901FF000000", 1, bus.feed(frame))
        assertEquals(Kind.WAITING, bus.feed(frame).kind)
        assertEquals(Kind.WAITING, bus.feed(frame + "55555555").kind)
    }

    @Test
    fun aNegativeAnswerIsAnAnswer() {
        assertAnswer("7F2210", 1, opened(0x7E2, "22F187").feed("0A07EA030101027F2210"))
    }

    @Test
    fun theGatewaysResultsThreeToFive() {
        val silent = opened(0x7A1, "22F194").feed("0A07A91001010322F19405E20007A10322F19455555555")
        assertEquals(Kind.NO_REPLY, silent.kind)
        assertEquals(3, silent.gatewayResult)

        val timedOut = opened(0x7E0, "190104").feed("0A07E80301010419010455")
        assertEquals(Kind.NO_REPLY, timedOut.kind)
        assertEquals(4, timedOut.gatewayResult)

        val busy = opened(0x7E0, "0101").feed("0A07E8020101050101")
        assertEquals(Kind.BUSY, busy.kind)
        assertEquals(5, busy.gatewayResult)

        assertEquals(Kind.SHAPE, opened(0x7E0, "0101").feed("0A07E8020101060101").kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "0101").feed("0A07E8020101000101").kind)
    }

    @Test
    fun responsePendingDoesNotCloseTheRequest() {
        val bus = opened(0x7E0, "190209")
        assertEquals(Kind.WAITING, bus.feed("0A07E8030101027F1978").kind)
        assertEquals(true, bus.isOpen)
        assertEquals(Kind.WAITING, bus.feed("0A07E8030101027F1978").kind)
        assertAnswer("590209", 1, bus.feed("0A07E803010101590209"))
    }

    @Test
    fun aMultiFrameAnswerIsJoinedInOrder() {
        val bus = opened(0x7E0, "190209")
        assertEquals(Kind.WAITING, bus.feed("0A07E8070301015902FF51600628").kind)
        assertEquals(Kind.WAITING, bus.feed("0A07E8040302C29D8728FFFF").kind)
        assertEquals(Kind.WAITING, bus.feed("0A07E8040302C29D8728").kind) // the same frame again
        assertAnswer("5902FF51600628C29D8728C1218728", 3, bus.feed("0A07E8040303C1218728"))
    }

    @Test
    fun aBrokenSequenceIsABrokenAnswer() {
        val skipped = opened(0x7E0, "190209")
        skipped.feed("0A07E8070301015902FF51600628")
        assertEquals(Kind.SHAPE, skipped.feed("0A07E8040303C1218728").kind)

        val recounted = opened(0x7E0, "190209")
        recounted.feed("0A07E8070301015902FF51600628")
        assertEquals(Kind.SHAPE, recounted.feed("0A07E8040402C29D8728").kind)

        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E8040302C29D8728").kind)

        val restarted = opened(0x7E0, "190209")
        restarted.feed("0A07E8070301015902FF51600628")
        assertEquals(Kind.SHAPE, restarted.feed("0A07E8070301015902FF51600629").kind)
    }

    @Test
    fun framesOutsideTheBoundsAreBroken() {
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E803110101590209").kind) // 17 frames
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E803000101590209").kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E803010201590209").kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E80901010159020900").kind) // shorter than declared
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E8").kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").accept(null).kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").accept(ByteArray(1025)).kind)
        assertEquals(Kind.SHAPE, opened(0x7E0, "190209").feed("0A07E800010101").kind) // no data
    }

    @Test
    fun aFloodOfOneFrameEndsTheExchange() {
        val bus = opened(0x7E0, "190209")
        repeat(DiagnosticFrameAssembler.MAX_BUFFERS_PER_EXCHANGE) {
            assertEquals(Kind.WAITING, bus.feed("0A07E8030101027F1978").kind)
        }
        assertEquals(Kind.SHAPE, bus.feed("0A07E8030101027F1978").kind)
    }

    @Test
    fun anotherEcuOrOwnerIsForeignTraffic() {
        assertEquals(Kind.FOREIGN, opened(0x782, "190109").feed("0A07E80C01010162F19432440011E004000000").kind)
        assertEquals(Kind.FOREIGN, opened(0x7E0, "22F194").feed("0B07E80C01010162F19432440011E004000000").kind)
        // Before the first request, every frame is somebody else's.
        assertEquals(Kind.FOREIGN, DiagnosticFrameAssembler().feed("0A07E80C01010162F19432440011E004000000").kind)
    }

    @Test
    fun theLastExchangesEchoesAreHarmlessAndNothingElseIs() {
        val bus = opened(0x782, "22F194")
        assertAnswer("62F19432330011DF0B000101", 1, bus.feed("0A078A0C01010162F19432330011DF0B000101"))
        assertEquals(Kind.WAITING, bus.feed("0A078A0C01010162F19432330011DF0B000101").kind)
        assertEquals(Kind.WAITING, bus.feed("0A078A030101027F2278").kind)
        assertEquals(Kind.FOREIGN, opened(0x782, "22F194").apply { end() }.feed("0A07E8030101027F2210").kind)

        bus.begin(0x7E0, bytes("22F194"))
        assertEquals(Kind.WAITING, bus.feed("0A078A0C01010162F19432330011DF0B000101").kind)
        assertAnswer("62F19432440011E004000000", 1, bus.feed("0A07E80C01010162F19432440011E004000000"))
        assertEquals(Kind.FOREIGN, bus.feed("0A07EA030101027F2210").kind)
    }

    @Test
    fun aLateAnswerFromTheSameEcuToAnEarlierServiceIsNotTheAnswer() {
        val bus = opened(0x782, "22F194")
        bus.end() // the helper's own wait ran out
        bus.begin(0x782, bytes("190109"))
        assertEquals(Kind.WAITING, bus.feed("0A078A0C01010162F19432330011DF0B000101").kind)
        assertAnswer("5901FF000000", 1, bus.feed("0A078A060101015901FF000000"))
    }

    @Test
    fun anAnswerToAnotherServiceFromAFreshEcuIsBroken() {
        assertEquals(Kind.SHAPE, opened(0x782, "190109").feed("0A078A0C01010162F19432330011DF0B000101").kind)
    }

    private companion object {
        /** ECU 762 `19 02 09` on 2026-10-18, as delivered (`diagnostics-fill-20261018/lbms-probe/list-live.txt`). */
        const val LBMS_LIST_240 = "0A076A0301010159020900000028C29D8728C1218728A1480228A2500028A25B0028C2298728C22A872832FFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        /** ECU 720 `19 02 FF` on 2026-10-18, as delivered (`diagnostics-menu-20261018/dtc-list-live.txt`). */
        const val BODY_LIST_240 = "0A0728230101015902FF51600628C29D8728C1218728A1480228A2500028A25B0028C2298728C22A872832FFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        /** ECU 720 `19 01 FF`, the count in front of that list. */
        const val BODY_COUNT_240 = "0A0728060101015901FF00000828C29D8728C1218728A1480228A2500028A25B0028C2298728C22A872832FFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
    }
}
