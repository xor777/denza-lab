package dev.denza.apps.feature.hud

import dev.denza.apps.feature.hud.HudNativeSpeedLimitEngine.Companion.ADAS_CHANGE_DELAY_MS
import dev.denza.apps.feature.hud.HudNativeSpeedLimitEngine.Companion.BASE_DELAY_MS
import dev.denza.apps.feature.hud.HudNativeSpeedLimitEngine.Companion.CONFIRM_MS
import dev.denza.apps.feature.hud.HudNativeSpeedLimitEngine.Companion.READ_MS
import dev.denza.apps.feature.hud.HudNativeSpeedLimitEngine.Companion.RETRY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudNativeSpeedLimitTest {

    // --- the wire ---

    @Test
    fun theWriteIsRoadSevenTheLimitThenRoadSixOnTheSettingDevice() {
        assertEquals(
            "echo @@0; service call autoservice 6 i32 1023 i32 1285554256 i32 7 null; sleep 0.1; " +
                "echo @@1; service call autoservice 6 i32 1023 i32 1285554240 i32 60 null; sleep 0.1; " +
                "echo @@2; service call autoservice 6 i32 1023 i32 1285554256 i32 6 null",
            HudNativeSpeedLimitProtocol.writeCommand(60),
        )
    }

    @Test
    fun theReadIsTheAdasSignOutputAndNeverAWrite() {
        val read = HudNativeSpeedLimitProtocol.readCommand()
        assertEquals("service call autoservice 5 i32 1038 i32 760217632", read)
        assertEquals(
            "service call autoservice 7 i32 1038 i32 760217632",
            HudNativeSpeedLimitProtocol.readCommand(alternateTransact = true),
        )
        assertFalse(read.contains("autoservice 6 "))
    }

    @Test
    fun onlyTheSettingsGridIsWritten() {
        listOf(5, 60, 130).forEach { assertTrue(HudNativeSpeedLimitProtocol.supported(it)) }
        listOf(0, 3, 62, 135, 150).forEach { assertFalse(HudNativeSpeedLimitProtocol.supported(it)) }
        assertEquals(13, HudNativeSpeedLimitProtocol.expectedRaw(60))
        assertEquals(1, HudNativeSpeedLimitProtocol.expectedRaw(0))
    }

    @Test
    fun readsTheValueWordAndRefusesSentinels() {
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Value(13),
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000 0000000d   '........')"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Value(0),
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000 00000000   '........')"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.WrongTransact,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000 ffffd8e3   '........')"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Failed,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000 ffffd8e5   '........')"),
        )
        assertEquals(HudNativeSpeedLimitProtocol.Read.Failed, HudNativeSpeedLimitProtocol.parseRead(""))
    }

    /** A one-word reply is a status alone: -10013 still says so, anything else is no value. */
    @Test
    fun aOneWordOrNullReadIsNotAValue() {
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.WrongTransact,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(ffffd8e3    '....')"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Failed,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000    '....')"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Failed,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(NULL)"),
        )
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Failed,
            HudNativeSpeedLimitProtocol.parseRead("Result: Parcel(00000000 fffffff6   '........')"),
        )
    }

    /**
     * The multi-line form of a long reply never matched the read's own expression, whatever its
     * words: no value. Its first offset, `0x00000000`, must not pass for a status.
     */
    @Test
    fun aMultiLineReplyIsNoValue() {
        assertEquals(
            HudNativeSpeedLimitProtocol.Read.Failed,
            HudNativeSpeedLimitProtocol.parseRead(
                "Result: Parcel(\n" +
                    "  0x00000000: 00000000 0000000d 00000000 00000000 '................'\n" +
                    "  0x00000010: 00000000                            '....')\n",
            ),
        )
    }

    @Test
    fun aWriteCountsOnlyWhenAllThreeCallsAnswered() {
        val answer = "Result: Parcel(00000001    '....')"
        assertTrue(
            HudNativeSpeedLimitProtocol.writeAnswered("@@0\n$answer\n@@1\n$answer\n@@2\n$answer\n"),
        )
        assertFalse(HudNativeSpeedLimitProtocol.writeAnswered("@@0\n$answer\n@@1\n$answer\n@@2\n"))
        assertFalse(
            HudNativeSpeedLimitProtocol.writeAnswered(
                "@@0\n$answer\n@@1\nResult: Parcel(ffffd8e5    '....')\n@@2\n$answer\n",
            ),
        )
        assertFalse(HudNativeSpeedLimitProtocol.writeAnswered(""))
        assertFalse(
            HudNativeSpeedLimitProtocol.writeAnswered(
                "@@0\n$answer\n@@1\nResult: Parcel(NULL)\n@@2\n$answer\n",
            ),
        )
    }

    // --- when to write ---

    @Test
    fun aCarAlreadyShowingTheLimitIsLeftAlone() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)

        assertEquals(0L, engine.nextReadDelayMs(0))
        assertNull(engine.onRead(13, 0))
        assertNull(engine.onRead(13, 5_000))
        assertNull(engine.onRead(13, 20_000))
    }

    @Test
    fun aDifferentSignIsWrittenAfterItStaysDifferentForASecond() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)

        assertNull(engine.onRead(9, 0))
        assertNull(engine.onRead(9, BASE_DELAY_MS - 1))
        assertEquals(60, engine.onRead(9, BASE_DELAY_MS))
    }

    @Test
    fun aWriteTheCarShowsIsNotRepeated() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)
        engine.onRead(9, 0)
        assertEquals(60, engine.onRead(9, 1_000))
        engine.onWritten(true, 1_400)

        assertNull(engine.onRead(13, 1_700))
        assertNull(engine.onRead(13, 30_000))
    }

    @Test
    fun anUnconfirmedWriteIsTriedOnceMoreTenSecondsLaterAndThenLeft() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)
        engine.onRead(9, 0)
        assertEquals(60, engine.onRead(9, 1_000))
        engine.onWritten(true, 1_400)

        assertNull(engine.onRead(9, 1_400 + CONFIRM_MS + 1))
        assertNull(engine.onRead(9, 1_000 + RETRY_MS - 1))
        assertEquals(60, engine.onRead(9, 1_000 + RETRY_MS))
        engine.onWritten(true, 11_400)

        assertNull(engine.onRead(9, 11_400 + CONFIRM_MS + 1))
        assertNull(engine.onRead(9, 60_000))
    }

    @Test
    fun aCameraSignAfterOursIsAnsweredAfterSixSeconds() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)
        engine.onRead(9, 0)
        engine.onRead(9, 1_000)
        engine.onWritten(true, 1_400)
        engine.onRead(13, 1_700)

        assertNull(engine.onRead(11, 10_000))
        assertNull(engine.onRead(11, 10_000 + BASE_DELAY_MS + ADAS_CHANGE_DELAY_MS - 1))
        assertEquals(60, engine.onRead(11, 10_000 + BASE_DELAY_MS + ADAS_CHANGE_DELAY_MS))
    }

    @Test
    fun aNewYandexLimitIsWrittenAfterOneSecond() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)
        engine.onRead(9, 0)
        engine.onRead(9, 1_000)
        engine.onWritten(true, 1_400)
        engine.onRead(13, 1_700)

        engine.setTarget(40, 20_000)
        assertNull(engine.onRead(13, 20_000))
        assertEquals(40, engine.onRead(13, 20_000 + BASE_DELAY_MS))
    }

    @Test
    fun noTargetMeansNoReadsAndNoWrites() {
        val engine = HudNativeSpeedLimitEngine()

        assertNull(engine.nextReadDelayMs(0))
        assertNull(engine.onRead(9, 0))

        engine.setTarget(60, 0)
        engine.setTarget(null, 100)
        assertNull(engine.nextReadDelayMs(100))
        assertNull(engine.onRead(9, 5_000))
    }

    @Test
    fun aLimitOffTheGridIsLeftToTheCar() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(150, 0)

        assertNull(engine.nextReadDelayMs(0))
    }

    @Test
    fun aFailedReadWaitsTenSecondsAndNeverWrites() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)

        assertNull(engine.onRead(null, 0))
        assertEquals(10_000L, engine.nextReadDelayMs(0))
    }

    @Test
    fun readsAreOnceASecondWhileATargetStands() {
        val engine = HudNativeSpeedLimitEngine()
        engine.setTarget(60, 0)
        engine.onRead(13, 0)

        assertEquals(READ_MS, engine.nextReadDelayMs(0))
    }
}
