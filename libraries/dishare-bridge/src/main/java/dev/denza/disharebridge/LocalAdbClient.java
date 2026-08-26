package dev.denza.disharebridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class LocalAdbClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 900;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 262144;

    private static final int A_CNXN = command("CNXN");
    private static final int A_OPEN = command("OPEN");
    private static final int A_OKAY = command("OKAY");
    private static final int A_CLSE = command("CLSE");
    private static final int A_WRTE = command("WRTE");
    private static final int A_AUTH = command("AUTH");

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;
    private static final long AUTH_PROMPT_COOLDOWN_NANOS = 15_000_000_000L;
    private static final long RECONNECT_BACKOFF_NANOS = 500_000_000L;
    private static final int INTERACTIVE_SHELL_LOCAL_ID = 1;

    /** The two bytes that frame an answer, carried as themselves rather than as shell escapes. */
    private static final String RECORD = "\u001e";
    private static final String UNIT = "\u001f";

    /**
     * Picks the marker emitter without writing a byte, so only one of the two can ever write.
     *
     * <p>{@code print -nr} is an mksh builtin and costs nothing; {@code printf} is a process on
     * this firmware and costs 7&nbsp;ms. A shell that has neither cannot happen: {@code printf} is
     * POSIX.
     */
    private static final String EMITTER_PRELUDE =
            "if print -nr '' 2>/dev/null; "
                    + "then __denza_emit() { print -nr \"$1\"; }; "
                    + "else __denza_emit() { printf '%s' \"$1\"; }; fi; ";
    private static final AuthorizationPromptGate AUTH_PROMPT_GATE =
            new AuthorizationPromptGate(AUTH_PROMPT_COOLDOWN_NANOS);

    private final AdbKeyStore keyStore;
    private final List<String> hosts;
    private final AuthorizationPolicy authorizationPolicy;

    public enum AuthorizationPolicy {
        /** Preserve the legacy behaviour for callers which intentionally own the auth prompt. */
        AUTOMATIC,
        /** Prove existing trust, but never add a public-key request to the system prompt queue. */
        PASSIVE
    }

    public enum AuthorizationRequestResult {
        ALREADY_AUTHORIZED,
        REQUEST_SENT
    }

    public static final class AuthorizationRequiredException extends IOException {
        AuthorizationRequiredException() {
            super("ADB authorization required; no request was sent");
        }
    }

    public LocalAdbClient(Context context) {
        this(context, null, AuthorizationPolicy.AUTOMATIC);
    }

    public LocalAdbClient(Context context, String publicKeyComment) {
        this(context, publicKeyComment, AuthorizationPolicy.AUTOMATIC);
    }

    public LocalAdbClient(
            Context context,
            String publicKeyComment,
            AuthorizationPolicy authorizationPolicy) {
        keyStore = new AdbKeyStore(context, publicKeyComment);
        hosts = candidateHosts();
        this.authorizationPolicy = authorizationPolicy;
    }

    /**
     * Submits this client's public key once, or proves that it is already trusted.
     *
     * <p>The method closes the transport immediately after the public key is sent. It never
     * retries and does not wait for the vehicle UI, so the caller must persist its own
     * one-shot state before invoking it and later verify trust with a passive connection.
     */
    public synchronized AuthorizationRequestResult requestAuthorization()
            throws IOException, GeneralSecurityException {
        IOException lastIoFailure = null;
        for (String host : hosts) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            } catch (IOException error) {
                closeQuietly(socket);
                lastIoFailure = error;
                continue;
            }
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                return connectForAuthorizationRequest(
                        socket.getInputStream(), socket.getOutputStream());
            } finally {
                closeQuietly(socket);
            }
        }
        if (lastIoFailure != null) {
            throw lastIoFailure;
        }
        throw new IOException("No ADB hosts available");
    }

    /**
     * Creates a lazily connected, long-lived non-interactive shell.
     *
     * <p>Commands are executed sequentially inside one {@code shell:sh} ADB stream. This avoids
     * authenticating a new transport for every poll without depending on repeated logical OPENs,
     * which are not reliable on every DiLink adbd build. Callers must close the session with their
     * lifecycle.
     */
    public PersistentShellSession openPersistentShell() {
        return new PersistentShellSession();
    }

    public synchronized String shell(String command) throws IOException, GeneralSecurityException {
        return shell(command, READ_TIMEOUT_MS);
    }

    public synchronized String shell(String command, int readTimeoutMs)
            throws IOException, GeneralSecurityException {
        if (readTimeoutMs < 1) {
            throw new IllegalArgumentException("readTimeoutMs must be positive");
        }
        IOException lastIoFailure = null;
        GeneralSecurityException lastSecurityFailure = null;
        for (String host : hosts) {
            try {
                return shell(host, command, readTimeoutMs);
            } catch (GeneralSecurityException e) {
                lastSecurityFailure = e;
            } catch (IOException e) {
                if (isAuthorizationPending(e)) {
                    throw e;
                }
                lastIoFailure = e;
            }
        }
        if (lastSecurityFailure != null) {
            throw lastSecurityFailure;
        }
        if (lastIoFailure != null) {
            throw lastIoFailure;
        }
        throw new IOException("No ADB hosts available");
    }

    private String shell(String host, String command, int readTimeoutMs)
            throws IOException, GeneralSecurityException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(readTimeoutMs);
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            connect(input, output);
            return runShell(input, output, command);
        } finally {
            socket.close();
        }
    }

    private static List<String> candidateHosts() {
        ArrayList<String> result = new ArrayList<>();
        result.add(HOST);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addUnique(result, address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // Keep the loopback fallback; callers surface the actual ADB failure.
        }
        return result;
    }

    private static void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private void connect(InputStream input, OutputStream output)
            throws IOException, GeneralSecurityException {
        connect(input, output, false);
    }

    private AuthorizationRequestResult connectForAuthorizationRequest(
            InputStream input,
            OutputStream output) throws IOException, GeneralSecurityException {
        return connect(input, output, true);
    }

    private AuthorizationRequestResult connect(
            InputStream input,
            OutputStream output,
            boolean explicitRequest) throws IOException, GeneralSecurityException {
        writeMessage(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\0".getBytes(
                StandardCharsets.US_ASCII));
        boolean publicKeySent = false;
        while (true) {
            Message message = readMessage(input);
            if (message.command == A_CNXN) {
                return AuthorizationRequestResult.ALREADY_AUTHORIZED;
            }
            if (message.command != A_AUTH || message.arg0 != ADB_AUTH_TOKEN) {
                throw new IOException("Unexpected ADB handshake message " + message.commandName());
            }
            if (!publicKeySent) {
                writeMessage(output, A_AUTH, ADB_AUTH_SIGNATURE, 0,
                        keyStore.signToken(message.payload));
                Message reply = readMessage(input);
                if (reply.command == A_CNXN) {
                    return AuthorizationRequestResult.ALREADY_AUTHORIZED;
                }
                if (reply.command != A_AUTH || reply.arg0 != ADB_AUTH_TOKEN) {
                    throw new IOException("Unexpected ADB auth reply " + reply.commandName());
                }
                AuthChallengeAction action = authChallengeAction(
                        authorizationPolicy,
                        explicitRequest,
                        false,
                        explicitRequest || AUTH_PROMPT_GATE.tryAcquire(System.nanoTime()));
                if (action == AuthChallengeAction.REQUIRE_EXPLICIT_REQUEST) {
                    throw new AuthorizationRequiredException();
                }
                if (action == AuthChallengeAction.REPORT_PENDING) {
                    throw authorizationPending();
                }
                writeMessage(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0,
                        keyStore.publicKeyPayload());
                publicKeySent = true;
                if (explicitRequest) {
                    return AuthorizationRequestResult.REQUEST_SENT;
                }
            } else {
                throw authorizationPending();
            }
        }
    }

    private static IOException authorizationPending() {
        return new IOException("ADB authorization pending; confirm the ADB request");
    }

    static boolean isAuthorizationPending(IOException error) {
        return error instanceof AuthorizationRequiredException
                || (error.getMessage() != null
                && error.getMessage().contains("ADB authorization pending"));
    }

    enum AuthChallengeAction {
        SEND_PUBLIC_KEY,
        REQUIRE_EXPLICIT_REQUEST,
        REPORT_PENDING
    }

    static AuthChallengeAction authChallengeAction(
            AuthorizationPolicy policy,
            boolean explicitRequest,
            boolean publicKeySent,
            boolean promptGateAcquired) {
        if (publicKeySent) {
            return AuthChallengeAction.REPORT_PENDING;
        }
        if (explicitRequest) {
            return AuthChallengeAction.SEND_PUBLIC_KEY;
        }
        if (policy == AuthorizationPolicy.PASSIVE) {
            return AuthChallengeAction.REQUIRE_EXPLICIT_REQUEST;
        }
        return promptGateAcquired
                ? AuthChallengeAction.SEND_PUBLIC_KEY
                : AuthChallengeAction.REPORT_PENDING;
    }

    private String runShell(InputStream input, OutputStream output, String command)
            throws IOException {
        int localId = 1;
        writeMessage(output, A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(
                StandardCharsets.UTF_8));
        int remoteId = -1;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Message message = readMessage(input);
            if (message.command == A_OKAY) {
                remoteId = message.arg0;
            } else if (message.command == A_WRTE) {
                if (remoteId < 0) {
                    remoteId = message.arg0;
                }
                result.write(message.payload);
                writeMessage(output, A_OKAY, localId, message.arg0, new byte[0]);
            } else if (message.command == A_CLSE) {
                writeMessage(output, A_CLSE, localId, message.arg0, new byte[0]);
                return result.toString(StandardCharsets.UTF_8.name());
            } else {
                throw new IOException("Unexpected shell message " + message.commandName());
            }
        }
    }

    static int openInteractiveShell(
            InputStream input,
            OutputStream output,
            int localId) throws IOException {
        writeMessage(output, A_OPEN, localId, 0, "shell:sh\0".getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message message = readMessage(input);
            verifyStreamMessage(message, localId, -1);
            if (message.command == A_OKAY) {
                return message.arg0;
            }
            if (message.command == A_WRTE) {
                writeMessage(output, A_OKAY, localId, message.arg0, new byte[0]);
                continue;
            }
            if (message.command == A_CLSE) {
                writeMessage(output, A_CLSE, localId, message.arg0, new byte[0]);
                throw new EOFException("ADB interactive shell was rejected");
            }
            throw new IOException(
                    "Unexpected interactive shell message " + message.commandName());
        }
    }

    static String runInteractiveCommand(
            InputStream input,
            OutputStream output,
            int localId,
            int remoteId,
            String command,
            String marker) throws IOException {
        byte[] framedCommand = frameInteractiveCommand(command, marker);
        if (framedCommand.length > MAX_PAYLOAD) {
            throw new IOException("ADB interactive command exceeds max payload");
        }
        writeMessage(output, A_WRTE, localId, remoteId, framedCommand);

        byte[] outputStartMarker = (RECORD + marker + ":BEGIN" + UNIT)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] statusMarkerPrefix = (RECORD + marker + ":")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        boolean writeAcknowledged = false;
        FramedShellResult framedResult = null;
        while (true) {
            Message message = readMessage(input);
            verifyStreamMessage(message, localId, remoteId);
            if (message.command == A_OKAY) {
                writeAcknowledged = true;
            } else if (message.command == A_WRTE) {
                received.write(message.payload);
                writeMessage(output, A_OKAY, localId, remoteId, new byte[0]);
                framedResult = findFramedResult(
                        received.toByteArray(), outputStartMarker, statusMarkerPrefix);
            } else if (message.command == A_CLSE) {
                writeMessage(output, A_CLSE, localId, remoteId, new byte[0]);
                throw new EOFException("ADB interactive shell closed during command");
            } else {
                throw new IOException(
                        "Unexpected interactive command message " + message.commandName());
            }
            if (writeAcknowledged && framedResult != null) {
                return framedResult.output;
            }
        }
    }

    /**
     * The command, quoted for {@code sh} and wrapped in the two markers, spawning nothing to do it.
     *
     * <p>The wrapper used to cost more than most of the commands it carried. Decoding base64 in a
     * command substitution is a process, and each of the two {@code printf} calls that wrote the
     * markers was another, because on this head unit {@code printf} is {@code /system/bin/printf}
     * and not a builtin. Measured on the car, 41 repeats each: base64 8.2&nbsp;ms, the two printfs
     * 14.8&nbsp;ms - 23&nbsp;ms of wrapper around an {@code am stack list} that costs 18.7&nbsp;ms
     * itself, and around a {@code service call} that costs 8.1&nbsp;ms.
     *
     * <p>Nothing is weakened to remove them. The command is single-quoted the way every other shell
     * caller of this project quotes one, which is exactly what base64 was protecting against, and
     * {@code eval} still receives the very same single word. The markers are written by mksh's
     * {@code print} builtin - {@code /system/bin/sh} on this firmware family is mksh - and the two
     * marker bytes travel as themselves rather than as escapes, so neither emitter has to interpret
     * anything.
     *
     * <p>The emitter is chosen by a probe that <em>writes nothing at all</em> ({@code print -nr ''}
     * on the empty string) rather than by trying one emitter and falling back on its failure. That
     * is deliberate: a "try, then fall back" would append a second marker after a first one that
     * had already been half-written, and a doubled BEGIN marker fails the whole command. Here
     * exactly one emitter can ever write, and a shell with no usable {@code print} pays the old
     * price for the markers instead of getting a wrong answer. The prelude is part of every frame
     * and not of the session, so the frame stays self-contained: nothing about a command depends on
     * state some earlier command left in the shell.
     */
    static byte[] frameInteractiveCommand(String command, String marker) {
        String framed = EMITTER_PRELUDE
                + "__denza_emit " + singleQuoted(RECORD + marker + ":BEGIN" + UNIT) + "; "
                + "( eval " + singleQuoted(command) + " ) 2>&1; "
                + "__denza_adb_status=$?; "
                + "__denza_emit \"" + RECORD + marker + ":$__denza_adb_status" + UNIT + "\"\n";
        return framed.getBytes(StandardCharsets.UTF_8);
    }

    /** The one quoting rule of a POSIX shell: everything is literal until the quote is closed. */
    private static String singleQuoted(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static FramedShellResult findFramedResult(
            byte[] received,
            byte[] outputStartMarker,
            byte[] statusMarkerPrefix)
            throws IOException {
        int outputMarkerStart = indexOf(received, outputStartMarker, 0);
        if (outputMarkerStart < 0) {
            return null;
        }
        int outputStart = outputMarkerStart + outputStartMarker.length;
        int statusMarkerStart = indexOf(received, statusMarkerPrefix, outputStart);
        if (statusMarkerStart < 0) {
            return null;
        }
        int statusStart = statusMarkerStart + statusMarkerPrefix.length;
        int markerEnd = indexOf(received, new byte[] {0x1f}, statusStart);
        if (markerEnd < 0) {
            return null;
        }
        String statusText = new String(
                received,
                statusStart,
                markerEnd - statusStart,
                StandardCharsets.US_ASCII);
        try {
            Integer.parseInt(statusText);
        } catch (NumberFormatException error) {
            throw new IOException("Bad ADB interactive shell status " + statusText, error);
        }
        return new FramedShellResult(
                new String(
                        received,
                        outputStart,
                        statusMarkerStart - outputStart,
                        StandardCharsets.UTF_8));
    }

    private static int indexOf(byte[] value, byte[] target, int fromIndex) {
        int lastStart = value.length - target.length;
        for (int start = Math.max(0, fromIndex); start <= lastStart; start++) {
            int offset = 0;
            while (offset < target.length && value[start + offset] == target[offset]) {
                offset += 1;
            }
            if (offset == target.length) {
                return start;
            }
        }
        return -1;
    }

    private static void verifyStreamMessage(Message message, int localId, int remoteId)
            throws IOException {
        if (message.arg1 != localId) {
            throw new IOException(
                    "Unexpected ADB local stream id " + message.arg1 + " for " + localId);
        }
        if (remoteId >= 0 && message.arg0 != remoteId) {
            throw new IOException(
                    "Unexpected ADB remote stream id " + message.arg0 + " for " + remoteId);
        }
    }

    public final class PersistentShellSession implements AutoCloseable {
        private final String markerNonce =
                Long.toUnsignedString(System.nanoTime(), 16);
        private Socket socket;
        private InputStream input;
        private OutputStream output;
        private int remoteId = -1;
        private long commandSequence;
        private long nextConnectAtNanos = Long.MIN_VALUE;
        private boolean closed;

        public synchronized String shell(String command)
                throws IOException, GeneralSecurityException {
            return shell(command, READ_TIMEOUT_MS);
        }

        public synchronized String shell(String command, int readTimeoutMs)
                throws IOException, GeneralSecurityException {
            if (readTimeoutMs < 1) {
                throw new IllegalArgumentException("readTimeoutMs must be positive");
            }
            if (closed) {
                throw new IOException("Persistent ADB shell is closed");
            }
            ensureConnected(readTimeoutMs);
            String marker = "DENZA_ADB_" + markerNonce + "_"
                    + Long.toUnsignedString(++commandSequence, 16);
            try {
                return runInteractiveCommand(
                        input,
                        output,
                        INTERACTIVE_SHELL_LOCAL_ID,
                        remoteId,
                        command,
                        marker);
            } catch (IOException error) {
                closeConnection();
                scheduleReconnect(RECONNECT_BACKOFF_NANOS);
                throw error;
            }
        }

        private void ensureConnected(int readTimeoutMs)
                throws IOException, GeneralSecurityException {
            if (socket != null) {
                socket.setSoTimeout(readTimeoutMs);
                return;
            }
            waitForReconnectWindow();

            IOException lastIoFailure = null;
            GeneralSecurityException lastSecurityFailure = null;
            for (String host : hosts) {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
                    candidate.setSoTimeout(readTimeoutMs);
                    InputStream candidateInput = candidate.getInputStream();
                    OutputStream candidateOutput = candidate.getOutputStream();
                    connect(candidateInput, candidateOutput);
                    int candidateRemoteId = openInteractiveShell(
                            candidateInput,
                            candidateOutput,
                            INTERACTIVE_SHELL_LOCAL_ID);
                    socket = candidate;
                    input = candidateInput;
                    output = candidateOutput;
                    remoteId = candidateRemoteId;
                    nextConnectAtNanos = Long.MIN_VALUE;
                    return;
                } catch (GeneralSecurityException error) {
                    closeQuietly(candidate);
                    lastSecurityFailure = error;
                } catch (IOException error) {
                    closeQuietly(candidate);
                    if (isAuthorizationPending(error)) {
                        scheduleReconnect(AUTH_PROMPT_COOLDOWN_NANOS);
                        throw error;
                    }
                    lastIoFailure = error;
                }
            }

            scheduleReconnect(RECONNECT_BACKOFF_NANOS);
            if (lastSecurityFailure != null) {
                throw lastSecurityFailure;
            }
            if (lastIoFailure != null) {
                throw lastIoFailure;
            }
            throw new IOException("No ADB hosts available");
        }

        private void waitForReconnectWindow() throws InterruptedIOException {
            long remainingNanos = nextConnectAtNanos - System.nanoTime();
            if (nextConnectAtNanos == Long.MIN_VALUE || remainingNanos <= 0L) {
                return;
            }
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted =
                        new InterruptedIOException("Interrupted during ADB reconnect backoff");
                interrupted.initCause(error);
                throw interrupted;
            }
        }

        private void scheduleReconnect(long delayNanos) {
            nextConnectAtNanos = System.nanoTime() + delayNanos;
        }

        @Override
        public synchronized void close() {
            closed = true;
            closeConnection();
        }

        private void closeConnection() {
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            remoteId = -1;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The transport is already unusable; callers handle the original failure.
        }
    }

    private static Message readMessage(InputStream input) throws IOException {
        byte[] header = readExactly(input, 24);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buffer.getInt();
        int arg0 = buffer.getInt();
        int arg1 = buffer.getInt();
        int length = buffer.getInt();
        int checksum = buffer.getInt();
        int magic = buffer.getInt();
        if ((command ^ 0xffffffff) != magic) {
            throw new IOException("Bad ADB magic for " + commandToString(command));
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("Bad ADB payload length " + length);
        }
        byte[] payload = readExactly(input, length);
        int actualChecksum = 0;
        for (byte b : payload) {
            actualChecksum += b & 0xff;
        }
        if (actualChecksum != checksum) {
            throw new IOException("Bad ADB checksum for " + commandToString(command));
        }
        return new Message(command, arg0, arg1, payload);
    }

    private static void writeMessage(OutputStream output, int command, int arg0, int arg1,
            byte[] payload) throws IOException {
        int checksum = 0;
        for (byte b : payload) {
            checksum += b & 0xff;
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(payload.length);
        header.putInt(checksum);
        header.putInt(command ^ 0xffffffff);
        output.write(header.array());
        output.write(payload);
        output.flush();
    }

    private static byte[] readExactly(InputStream input, int byteCount) throws IOException {
        byte[] buffer = new byte[byteCount];
        int offset = 0;
        while (offset < byteCount) {
            int read = input.read(buffer, offset, byteCount - offset);
            if (read == -1) {
                throw new EOFException("Expected " + byteCount + " bytes, got " + offset);
            }
            offset += read;
        }
        return buffer;
    }

    private static int command(String command) {
        byte[] bytes = command.getBytes(StandardCharsets.US_ASCII);
        return (bytes[0] & 0xff)
                | ((bytes[1] & 0xff) << 8)
                | ((bytes[2] & 0xff) << 16)
                | ((bytes[3] & 0xff) << 24);
    }

    private static String commandToString(int command) {
        byte[] bytes = new byte[] {
                (byte) (command & 0xff),
                (byte) ((command >> 8) & 0xff),
                (byte) ((command >> 16) & 0xff),
                (byte) ((command >> 24) & 0xff)
        };
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    static final class AuthorizationPromptGate {
        private final long cooldownNanos;
        private long nextAllowedNanos = Long.MIN_VALUE;

        AuthorizationPromptGate(long cooldownNanos) {
            this.cooldownNanos = cooldownNanos;
        }

        synchronized boolean tryAcquire(long nowNanos) {
            if (nextAllowedNanos != Long.MIN_VALUE && nowNanos < nextAllowedNanos) {
                return false;
            }
            nextAllowedNanos = nowNanos + cooldownNanos;
            return true;
        }
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }

        String commandName() {
            return commandToString(command);
        }
    }

    private static final class FramedShellResult {
        final String output;

        FramedShellResult(String output) {
            this.output = output;
        }
    }
}
