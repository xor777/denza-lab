package dev.denza.hudlink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Protobuf encodings, compared with the bytes the protobuf wire format prescribes. */
public final class HudMessageTest {

    @Test
    public void varintsAreSevenBitGroupsLowFirst() {
        assertEquals("0800", hex(new HudMessage().number(1, 0).toByteArray()));
        assertEquals("087f", hex(new HudMessage().number(1, 127).toByteArray()));
        assertEquals("088001", hex(new HudMessage().number(1, 128).toByteArray()));
        assertEquals("08ac02", hex(new HudMessage().number(1, 300).toByteArray()));
        // A negative long is ten bytes on the wire, as protobuf's int64.
        assertEquals("08ffffffffffffffffff01", hex(new HudMessage().number(1, -1).toByteArray()));
    }

    @Test
    public void fieldNumbersAboveFifteenTakeTwoKeyBytes() {
        assertEquals("800102", hex(new HudMessage().number(16, 2).toByteArray()));
        assertEquals("e00102", hex(new HudMessage().number(28, 2).toByteArray()));
    }

    @Test
    public void doublesAreLittleEndianFixed64AndNonFiniteOnesAreLeftOut() {
        assertEquals("09000000000000f03f", hex(new HudMessage().decimal(1, 1.0).toByteArray()));
        assertEquals("", hex(new HudMessage().decimal(1, Double.NaN).toByteArray()));
        assertEquals("", hex(new HudMessage().decimal(1, Double.POSITIVE_INFINITY).toByteArray()));
    }

    @Test
    public void textIsUtf8AndEmptyTextOrBytesAreLeftOut() {
        assertEquals("0a04d0bcd0b8", hex(new HudMessage().text(1, "ми").toByteArray()));
        assertEquals("", hex(new HudMessage().text(1, "").text(2, null).toByteArray()));
        assertEquals("", hex(new HudMessage().bytes(1, new byte[0]).bytes(2, null).toByteArray()));
    }

    @Test
    public void fieldsKeepTheOrderTheyWereAddedIn() {
        assertEquals("10010808", hex(new HudMessage().number(2, 1).number(1, 8).toByteArray()));
    }

    @Test
    public void theEnvelopeIsWrittenEvenAroundAnEmptyMessage() {
        assertEquals("0a00", hex(new HudMessage().embedded(1)));
        assertEquals("0a051002800101", hex(new HudMessage().number(2, 2).number(16, 1).embedded(1)));
    }

    @Test
    public void theMapFrameIsBase64InFieldOneWithoutAnEnvelope() {
        byte[] payload = HudProtocol.mapPayload(new byte[] {0, 1, 2});
        assertArrayEquals(new byte[] {10, 4, 'A', 'A', 'E', 'C'}, payload);
    }

    @Test
    public void theRouteSummaryReadsAsTheNavigatorPrintsIt() {
        assertEquals("950 м", HudProtocol.routeDistance(950));
        assertEquals("1,5 км", HudProtocol.routeDistance(1_500));
        assertEquals("12 км", HudProtocol.routeDistance(12_345));
        assertEquals("1 мин", HudProtocol.duration(59));
        assertEquals("1 ч", HudProtocol.duration(3_600));
        assertEquals("1 ч 5 мин", HudProtocol.duration(3_900));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }
}
