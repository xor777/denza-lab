package dev.denza.disharebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
        assertTrue(LocalAdbClient.isAuthorizationPending(
                new LocalAdbClient.AuthorizationRequiredException()));
        assertFalse(LocalAdbClient.isAuthorizationPending(
                new IOException("Connection refused")));
    }

    @Test
    public void passiveClientNeverSubmitsPublicKey() {
        assertEquals(
                LocalAdbClient.AuthChallengeAction.REQUIRE_EXPLICIT_REQUEST,
                LocalAdbClient.authChallengeAction(
                        LocalAdbClient.AuthorizationPolicy.PASSIVE,
                        false,
                        false,
                        true));
    }

    @Test
    public void explicitRequestCanSubmitExactlyOnePublicKey() {
        assertEquals(
                LocalAdbClient.AuthChallengeAction.SEND_PUBLIC_KEY,
                LocalAdbClient.authChallengeAction(
                        LocalAdbClient.AuthorizationPolicy.PASSIVE,
                        true,
                        false,
                        true));
        assertEquals(
                LocalAdbClient.AuthChallengeAction.REPORT_PENDING,
                LocalAdbClient.authChallengeAction(
                        LocalAdbClient.AuthorizationPolicy.PASSIVE,
                        true,
                        true,
                        true));
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
        // The echo carries the marker back as the four characters `\036`, never as the byte the
        // frame is found by, so a shell that repeats the command cannot fake the start of an
        // answer - which is what lets the command itself travel in plain sight, single-quoted.
        assertTrue(sent.contains(
                "( eval 'if [ -d /storage/FFFF-FFFC ]; then echo ready; else echo missing; fi' )"));
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

    /**
     * Ф2 волны 15: an arbitrary command survives the frame byte for byte, through a real shell.
     *
     * <p>The wrapper stopped decoding base64 in a subprocess and stopped calling
     * {@code /system/bin/printf} twice, which is 23 ms off every single command on the car. What
     * protects the command now is single quoting, so identity of execution is proven rather than
     * assumed: each command is run bare, then run through the frame and read back through the very
     * parser the transport uses, and the two answers must be the same string.
     *
     * <p>The host shell has no {@code print} builtin, so this exercises the {@code printf}
     * fallback half of the frame; the mksh half was compared against the previous frame on the car
     * itself for these same cases.
     */
    @Test
    public void framedCommandsExecuteExactlyLikeBareOnes() throws Exception {
        assumeTrue("this host has no /bin/sh", new File("/bin/sh").canExecute());
        List<String> commands = Arrays.asList(
                "echo hi",
                "echo 'it'\\''s here'",
                "echo \"double \\\"quoted\\\" text\"",
                "echo '$HOME $(id) `id` ${x}'",
                "echo one\necho two",
                "echo 'привет мир ✓ 日本語'",
                "echo 'a\\\\b\\tc'",
                "echo a; echo b",
                "echo before; exit 7",
                "echo out; echo err 1>&2",
                "sh -c 'echo \"nested '\\''quotes'\\''\"'",
                "printf 'no trailing newline'");

        for (String command : commands) {
            assertEquals(command, bare(command), throughFrame(command));
        }
    }

    /**
     * The wrapper itself starts no process, and cannot write a marker twice.
     *
     * <p>The second half is the load-bearing one. An emitter picked by "try this, otherwise that"
     * would append a marker after a first one that had already been half-written, and a doubled
     * BEGIN marker turns a good answer into a hard transport failure. So there is no {@code ||} in
     * the frame at all: the probe writes the empty string, and exactly one emitter can ever run.
     */
    @Test
    public void theWrapperStartsNoProcessOfItsOwnAndCannotEmitAMarkerTwice() {
        String framed = new String(
                LocalAdbClient.frameInteractiveCommand("echo hi", "MARK"),
                StandardCharsets.UTF_8);

        assertFalse(framed, framed.contains("base64"));
        assertFalse("no command substitution in the wrapper", framed.contains("$("));
        assertFalse("no emitter may run because another one failed", framed.contains("||"));
        assertTrue("the emitter is chosen by a probe that writes nothing",
                framed.startsWith("if print -nr '' 2>/dev/null; then "));
        assertTrue(framed, framed.contains("__denza_emit '\u001eMARK:BEGIN\u001f';"));
        assertTrue(framed, framed.contains("( eval 'echo hi' ) 2>&1;"));
        assertTrue("the shell is still waiting on this line", framed.endsWith("\n"));
        // Pinned character for character against the text that was compared with the previous
        // frame on the car itself (tools/split_frame_identity.py), so the two cannot drift apart
        // and leave the proof describing a frame the product no longer sends.
        assertEquals(
                "if print -nr '' 2>/dev/null; then __denza_emit() { print -nr \"$1\"; }; "
                        + "else __denza_emit() { printf '%s' \"$1\"; }; fi; "
                        + "__denza_emit '\u001eMARK:BEGIN\u001f'; "
                        + "( eval 'echo hi' ) 2>&1; "
                        + "__denza_adb_status=$?; "
                        + "__denza_emit \"\u001eMARK:$__denza_adb_status\u001f\"\n",
                framed);
    }

    /**
     * Ф3 волны 15: an ADB message reaches the socket in one write, never as a header and a body.
     *
     * <p>ADB answers nothing until a whole message has arrived, so a payload that Nagle holds back
     * waiting for the header's acknowledgement is an answer held back with it - on every single
     * command, and the product sends dozens to build one scene.
     */
    @Test
    public void everyAdbMessageLeavesInASingleWrite() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, ""),
                message("OKAY", 41, 1, ""),
                message("WRTE", 41, 1, "\u001eMARK_1:BEGIN\u001fanswer\u001eMARK_1:0\u001f"),
                message("OKAY", 41, 1, "")));
        CountingOutputStream output = new CountingOutputStream();

        int remoteId = LocalAdbClient.openInteractiveShell(input, output, 1);
        LocalAdbClient.runInteractiveCommand(input, output, 1, remoteId, "echo hi", "MARK_1");

        assertEquals(
                "one write per message: OPEN, WRTE(command), OKAY(payload receipt)",
                3,
                output.writes);
        assertEquals(3, messageHeaders(output.toByteArray()).size());
    }

    /** The socket seam every ADB connection of this client goes through. */
    @Test
    public void everyAdbSocketDisablesNagleAndCarriesTheReadTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    server.getLocalPort()), 2_000);

            LocalAdbClient.prepareTransport(socket, 1_234);

            assertTrue("Nagle must be off on an ADB socket", socket.getTcpNoDelay());
            assertEquals(1_234, socket.getSoTimeout());
        }
    }

    private static final class CountingOutputStream extends ByteArrayOutputStream {
        int writes;

        @Override
        public synchronized void write(byte[] source, int offset, int length) {
            writes += 1;
            super.write(source, offset, length);
        }

        @Override
        public void write(byte[] source) {
            writes += 1;
            super.write(source, 0, source.length);
        }

        @Override
        public synchronized void write(int value) {
            writes += 1;
            super.write(value);
        }
    }

    /**
     * Ф1 волны 15: an answer from the resident helper is recognised only when it is whole.
     *
     * <p>Everything else the stream may carry - the shell's own noise before the helper started,
     * half of an answer, a stale one - is not an answer, because the caller's nonce wraps every
     * one of them and only a complete pair of sentinels ends the wait.
     */
    @Test
    public void aResidentAnswerIsReadOnlyWhenBothSentinelsAreThere() throws Exception {
        String begin = "DENZA_SERVE_n1:BEGIN";
        String end = "DENZA_SERVE_n1:END";

        assertNull(LocalAdbClient.findResidentAnswer("noise\n", begin, end));
        assertNull(LocalAdbClient.findResidentAnswer("noise\n" + begin + "\npart", begin, end));
        assertNull(LocalAdbClient.findResidentAnswer(
                "noise\n" + begin + "\nRootTask id=4\n" + end + " ok", begin, end));
        assertEquals(
                "RootTask id=4\n",
                LocalAdbClient.findResidentAnswer(
                        "shell noise\n" + begin + "\nRootTask id=4\n" + end + " ok\n",
                        begin,
                        end));
        assertEquals(
                "an empty answer is an answer",
                "",
                LocalAdbClient.findResidentAnswer(begin + "\n" + end + " ok\n", begin, end));
        assertEquals(
                "a stream that turns newlines into CRLF is read by the same parsers as ever",
                "RootTask id=4\r\n",
                LocalAdbClient.findResidentAnswer(
                        begin + "\r\nRootTask id=4\r\n" + end + " ok\r\n", begin, end));
    }

    /** A helper that refused is not an answer at all: the caller runs the command it stood in for. */
    @Test(expected = IOException.class)
    public void aRefusedResidentAnswerIsAFailureAndNotAnEmptyResult() throws Exception {
        LocalAdbClient.findResidentAnswer(
                "DENZA_SERVE_n1:BEGIN\nDENZA_SERVE_n1:END err no activity_task service\n",
                "DENZA_SERVE_n1:BEGIN",
                "DENZA_SERVE_n1:END");
    }

    @Test
    public void aResidentRequestIsOneLineAndOneAnswerOnTheSameStream() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, ""),
                message("WRTE", 41, 1, "DENZA_SERVE_n1:READY\n"),
                message("OKAY", 41, 1, ""),
                message("WRTE", 41, 1, "DENZA_SERVE_n1:BEGIN\nRootTask id=4\n"),
                message("WRTE", 41, 1, "DENZA_SERVE_n1:END ok\n")));
        CountingOutputStream output = new CountingOutputStream();

        LocalAdbClient.awaitResident(input, output, 1, 41, "app_process serve n1",
                "DENZA_SERVE_n1:READY");
        assertEquals(
                "RootTask id=4\n",
                LocalAdbClient.runResidentRequest(
                        input, output, 1, 41, "world",
                        "DENZA_SERVE_n1:BEGIN", "DENZA_SERVE_n1:END"));

        String sent = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(sent, sent.contains("app_process serve n1\n"));
        assertTrue(sent, sent.contains("world\n"));
    }

    /** The helper's stream going away is a failure, never a silently empty world. */
    @Test(expected = IOException.class)
    public void aResidentStreamThatClosesIsAFailure() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, ""),
                message("CLSE", 41, 1, "")));

        LocalAdbClient.runResidentRequest(
                input, new ByteArrayOutputStream(), 1, 41, "world",
                "DENZA_SERVE_n1:BEGIN", "DENZA_SERVE_n1:END");
    }

    /** What the command prints on its own, with the streams merged exactly as the frame merges. */
    private static String bare(String command) throws Exception {
        return new String(
                run(new String[] {"/bin/sh", "-c", command}, new byte[0], null),
                StandardCharsets.UTF_8);
    }

    /** The same command through the frame and back through the transport's own parser. */
    private static String throughFrame(String command) throws Exception {
        String marker = "MARK_SH";
        ByteArrayOutputStream leaked = new ByteArrayOutputStream();
        byte[] answered = run(
                new String[] {"/bin/sh"},
                LocalAdbClient.frameInteractiveCommand(command, marker),
                leaked);
        // Everything the command says belongs inside the frame, including what it says on stderr,
        // and the wrapper's own probe may not add a word of its own.
        assertEquals(
                "the frame leaked to stderr",
                "",
                new String(leaked.toByteArray(), StandardCharsets.UTF_8));
        ByteArrayInputStream input = new ByteArrayInputStream(concat(
                message("OKAY", 41, 1, new byte[0]),
                message("WRTE", 41, 1, answered)));
        return LocalAdbClient.runInteractiveCommand(
                input, new ByteArrayOutputStream(), 1, 41, command, marker);
    }

    /** Runs [argv], feeding [stdin]; stderr is merged into the answer unless a sink is given. */
    private static byte[] run(String[] argv, byte[] stdin, ByteArrayOutputStream stderrSink)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (stderrSink == null) {
            builder.redirectErrorStream(true);
        }
        Process process = builder.start();
        Thread drain = null;
        if (stderrSink != null) {
            drain = new Thread(() -> {
                try {
                    copy(process.getErrorStream(), stderrSink);
                } catch (IOException ignored) {
                    // The assertion on the collected bytes is the report; this thread has none.
                }
            });
            drain.start();
        }
        process.getOutputStream().write(stdin);
        process.getOutputStream().close();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        copy(process.getInputStream(), collected);
        process.waitFor();
        if (drain != null) {
            drain.join();
        }
        return collected.toByteArray();
    }

    private static void copy(java.io.InputStream from, ByteArrayOutputStream into)
            throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = from.read(buffer)) != -1) {
            into.write(buffer, 0, read);
        }
    }

    private static byte[] message(String command, int arg0, int arg1, String payload) {
        return message(command, arg0, arg1, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] message(String command, int arg0, int arg1, byte[] body) {
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
