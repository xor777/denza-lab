package dev.denza.disharebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Lines a resident helper pushes between its answers: kept around an answer, taken by
 * {@code ResidentSession.pushed}, and never confused with the answer, the shell's echo or noise.
 */
public class ResidentPushTest {
    private static final String BEGIN = "DENZA_SERVE_n1:BEGIN";
    private static final String END = "DENZA_SERVE_n1:END";
    private static final String PUSH = "DENZA_SERVE_n1:PUSH ";

    @Test
    public void whatCameAroundAnAnswerIsKeptAndTheAnswerIsNot() {
        String received = PUSH + "b 1 43D 00\r\nx 7 7E0 22F194 4000\r\n" + BEGIN + "\r\na 7 1\r\n" + END
                + " ok\r\n" + PUSH + "b 2 43C 01\r\n" + PUSH + "b 3";
        String around = LocalAdbClient.residentOutside(received, BEGIN, END);
        assertEquals(PUSH + "b 1 43D 00\r\nx 7 7E0 22F194 4000\r\n" + PUSH + "b 2 43C 01\r\n" + PUSH + "b 3", around);
        assertEquals("no answer at all: everything is outside", "noise\n", LocalAdbClient.residentOutside("noise\n", BEGIN, END));
    }

    @Test
    public void onlyPushedLinesOfThisNonceAreKeptAndALineOnItsWayWaits() {
        StringBuilder outside = new StringBuilder(PUSH + "b 1 43D 00\r\nx 7 7E0 22F194 4000\r\nDENZA_SERVE_n2:PUSH b 9 43D 00\n"
                + "shell noise\n" + PUSH + "b 2 43C 01\r\n" + PUSH + "b 3 43");
        LocalAdbClient.keepPushed(outside, PUSH);
        assertEquals(PUSH + "b 1 43D 00\n" + PUSH + "b 2 43C 01\n" + PUSH + "b 3 43", outside.toString());

        assertEquals(Arrays.asList("b 1 43D 00", "b 2 43C 01"), LocalAdbClient.takePushed(outside, PUSH));
        assertEquals(PUSH + "b 3 43", outside.toString());
        outside.append("A 0102\n");
        assertEquals(Collections.singletonList("b 3 43A 0102"), LocalAdbClient.takePushed(outside, PUSH));
        assertEquals("", outside.toString());
    }

    @Test
    public void pushedLinesNobodyTakesAreBounded() {
        StringBuilder outside = new StringBuilder();
        String line = PUSH + "b 1 43D 0001020304050607\n";
        while (outside.length() < LocalAdbClient.MAX_PUSHED_CHARS + 4096) outside.append(line);
        LocalAdbClient.keepPushed(outside, PUSH);
        assertTrue(outside.length() <= LocalAdbClient.MAX_PUSHED_CHARS);
        assertTrue(outside.toString().startsWith(PUSH));

        StringBuilder noise = new StringBuilder();
        while (noise.length() <= LocalAdbClient.MAX_PUSHED_CHARS) noise.append('x');
        LocalAdbClient.keepPushed(noise, PUSH);
        assertEquals("", noise.toString());
    }

    @Test
    public void aRequestKeepsThePushesAroundItsAnswer() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("WRTE", 41, 1, PUSH + "b 1 43D 00\n"),
                message("OKAY", 41, 1, ""),
                message("WRTE", 41, 1, BEGIN + "\na 7 1\n" + END + " ok\n" + PUSH + "b 2 43C 01\n")));
        StringBuilder around = new StringBuilder();
        String answer = LocalAdbClient.runResidentRequest(input, new ByteArrayOutputStream(), 1, 41, "x 7", BEGIN, END, around);
        assertEquals("a 7 1\n", answer);
        assertEquals(PUSH + "b 1 43D 00\n" + PUSH + "b 2 43C 01\n", around.toString());
    }

    @Test
    public void aMessageWithNoRequestOpenIsReadAndAcknowledged() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("WRTE", 41, 1, PUSH + "b 1 43D 00\n"),
                message("OKAY", 41, 1, ""),
                message("CLSE", 41, 1, "")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StringBuilder outside = new StringBuilder();
        LocalAdbClient.readOutside(input, output, 1, 41, outside);
        assertEquals(PUSH + "b 1 43D 00\n", outside.toString());
        String sent = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue("a write is acknowledged", sent.startsWith("OKAY"));
        LocalAdbClient.readOutside(input, output, 1, 41, outside);
        try {
            LocalAdbClient.readOutside(input, output, 1, 41, outside);
            throw new AssertionError("a closed stream is a failure");
        } catch (EOFException expected) {
            // the helper is gone
        }
    }

    private static byte[] message(String command, int arg0, int arg1, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] name = command.getBytes(StandardCharsets.US_ASCII);
        int value = (name[0] & 0xff) | ((name[1] & 0xff) << 8) | ((name[2] & 0xff) << 16) | ((name[3] & 0xff) << 24);
        int checksum = 0;
        for (byte b : body) checksum += b & 0xff;
        ByteBuffer buffer = ByteBuffer.allocate(24 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value).putInt(arg0).putInt(arg1).putInt(body.length).putInt(checksum).putInt(value ^ 0xffffffff);
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
