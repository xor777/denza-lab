package dev.denza.disharebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class LocalAdbClientTest {
    @Test
    public void authorizationPendingStopsHostFallback() {
        assertTrue(LocalAdbClient.isAuthorizationPending(
                new IOException("ADB authorization pending; confirm the ADB request")));
        assertFalse(LocalAdbClient.isAuthorizationPending(
                new IOException("Connection refused")));
    }

    @Test
    public void promptGateSuppressesDuplicateRequestsDuringCooldown() {
        LocalAdbClient.AuthorizationPromptGate gate =
                new LocalAdbClient.AuthorizationPromptGate(10L);

        assertTrue(gate.tryAcquire(100L));
        assertFalse(gate.tryAcquire(109L));
        assertTrue(gate.tryAcquire(110L));
    }

    @Test
    public void interactiveCommandsReuseOneOpenShellStream() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, ""),
                message("OKAY", 41, 1, ""),
                message("WRTE", 41, 1, "\u001eMARK_1:BEGIN\u001ffirst\u001eMARK_1:0"),
                message("WRTE", 41, 1, "\u001f"),
                message(
                        "WRTE",
                        41,
                        1,
                        "\u001eMARK_2:BEGIN\u001fsecond\u001eMARK_2:7\u001f"),
                message("OKAY", 41, 1, "")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int remoteId = LocalAdbClient.openInteractiveShell(input, output, 1);
        assertEquals(41, remoteId);
        assertEquals(
                "first",
                LocalAdbClient.runInteractiveCommand(
                        input, output, 1, remoteId, "echo first", "MARK_1"));
        assertEquals(
                "second",
                LocalAdbClient.runInteractiveCommand(
                        input, output, 1, remoteId, "echo second", "MARK_2"));

        assertEquals(
                Arrays.asList(
                        "OPEN:1:0",
                        "WRTE:1:41",
                        "OKAY:1:41",
                        "OKAY:1:41",
                        "WRTE:1:41",
                        "OKAY:1:41"),
                messageHeaders(output.toByteArray()));
        String sent = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(sent.contains("shell:sh"));
        assertTrue(sent.contains("MARK_1"));
        assertTrue(sent.contains("MARK_2"));
    }

    @Test
    public void interactiveCommandIgnoresLegacyShellPromptAndEcho() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, ""),
                message(
                        "WRTE",
                        41,
                        1,
                        "IVI:/ $ printf '\\036MARK_PROMPT:BEGIN\\037'; echoed command\r\n"
                                + "\u001eMARK_PROMPT:BEGIN\u001f"
                                + "ready\r\n"
                                + "\u001eMARK_PROMPT:0\u001f"
                                + "IVI:/ $ ")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(
                "ready\r\n",
                LocalAdbClient.runInteractiveCommand(
                        input,
                        output,
                        1,
                        41,
                        "if [ -d /storage/FFFF-FFFC ]; then echo ready; else echo missing; fi",
                        "MARK_PROMPT"));
        String sent = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(sent.contains("MARK_PROMPT:BEGIN"));
        assertFalse(sent.contains("/storage/FFFF-FFFC"));
    }

    @Test(expected = IOException.class)
    public void interactiveCommandRejectsAnotherLogicalStream() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(
                message("OKAY", 41, 2, ""));

        LocalAdbClient.runInteractiveCommand(
                input,
                new ByteArrayOutputStream(),
                1,
                41,
                "echo wrong",
                "MARK_WRONG");
    }

    private static byte[] message(String command, int arg0, int arg1, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int commandValue = command(command);
        int checksum = 0;
        for (byte value : body) {
            checksum += value & 0xff;
        }
        ByteBuffer buffer = ByteBuffer.allocate(24 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(commandValue);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        buffer.putInt(body.length);
        buffer.putInt(checksum);
        buffer.putInt(commandValue ^ 0xffffffff);
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) {
            size += value.length;
        }
        ByteBuffer result = ByteBuffer.allocate(size);
        for (byte[] value : values) {
            result.put(value);
        }
        return result.array();
    }

    private static List<String> messageHeaders(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        ArrayList<String> result = new ArrayList<>();
        while (buffer.remaining() >= 24) {
            int command = buffer.getInt();
            int arg0 = buffer.getInt();
            int arg1 = buffer.getInt();
            int length = buffer.getInt();
            buffer.getInt();
            buffer.getInt();
            result.add(commandName(command) + ":" + arg0 + ":" + arg1);
            buffer.position(buffer.position() + length);
        }
        return result;
    }

    private static int command(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        return (bytes[0] & 0xff)
                | ((bytes[1] & 0xff) << 8)
                | ((bytes[2] & 0xff) << 16)
                | ((bytes[3] & 0xff) << 24);
    }

    private static String commandName(int value) {
        return new String(new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff),
                (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 24) & 0xff)
        }, StandardCharsets.US_ASCII);
    }
}
