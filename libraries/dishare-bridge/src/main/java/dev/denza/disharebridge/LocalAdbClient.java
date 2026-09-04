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
     * <p>{@code print -n} is an mksh builtin and costs nothing; {@code printf} is a process on this
     * firmware and costs 6.8&nbsp;ms. A shell that has neither cannot happen: {@code printf} is
     * POSIX. Both branches take the marker <em>body</em> and add the two frame bytes themselves,
     * which is what keeps those bytes out of the command line - see
     * {@link #frameInteractiveCommand}.
     */
    private static final String EMITTER_PRELUDE =
            "if print -n '' 2>/dev/null; "
                    + "then __denza_emit() { print -n \"\\036$1\\037\"; }; "
                    + "else __denza_emit() { printf '\\036%s\\037' \"$1\"; }; fi; ";

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
     * This client's identity as the vehicle's authorization prompt spells it.
     *
     * <p>Each app that owns a {@link LocalAdbClient} has a key of its own, in its own data
     * directory. When a prompt does appear, the fingerprint is the only thing that says which of
     * them is asking.
     */
    public String publicKeyFingerprint() throws GeneralSecurityException {
        return keyStore.publicKeyFingerprint();
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
                prepareTransport(socket, READ_TIMEOUT_MS);
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
        prepareTransport(socket, readTimeoutMs);
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
        return runInteractiveCommand(input, output, localId, remoteId, command, marker, null);
    }

    /**
     * The same command, with two marks so a caller can tell sending from waiting.
     *
     * <p>[spent], when given, receives nanoseconds: index 0 is everything up to the command being
     * handed to the socket, index 1 is the wait for a whole framed answer. That is the only
     * division that leads anywhere - the first is work this process does and can do less of, the
     * second is the car, and wave 16 exists because 70 ms of a 89 ms round trip had no owner.
     */
    static String runInteractiveCommand(
            InputStream input,
            OutputStream output,
            int localId,
            int remoteId,
            String command,
            String marker,
            long[] spent) throws IOException {
        long startedAt = System.nanoTime();
        byte[] framedCommand = frameInteractiveCommand(command, marker);
        if (framedCommand.length > MAX_PAYLOAD) {
            throw new IOException("ADB interactive command exceeds max payload");
        }
        writeMessage(output, A_WRTE, localId, remoteId, framedCommand);
        long sentAt = System.nanoTime();
        if (spent != null) {
            spent[0] += sentAt - startedAt;
        }

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
                if (spent != null) {
                    spent[1] += System.nanoTime() - sentAt;
                }
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
     * itself, and around a {@code service call} that costs 8.1&nbsp;ms. Nothing is weakened to
     * remove them: the command is single-quoted the way the rest of this project quotes one, which
     * is exactly what base64 was protecting against, and {@code eval} still receives the very same
     * single word.
     *
     * <p><b>The frame is printable text, and must stay printable text.</b> The stream it is written
     * into is the legacy {@code shell:sh} service, which on this adbd hands back a terminal, so
     * mksh runs its line editor on everything the product types. An editor reads control bytes as
     * the keystrokes they are: v31 carried the two frame bytes literally inside the command line,
     * the editor ate them, no answer ever carried a marker, and every single command of the product
     * timed out. So the marker bytes exist only in the <em>answer</em>: the frame names the marker
     * in plain characters and the emitter turns {@code \036} and {@code \037} into bytes on its
     * own side. mksh's {@code print} expands those escapes itself - which is why the builtin is
     * called without {@code -r}, since {@code -r} is precisely "do not expand" - and the POSIX
     * fallback puts them in a {@code printf} format string, exactly as the wrapper did before v31.
     *
     * <p>The emitter is chosen by a probe that writes nothing at all ({@code print -n} on the empty
     * string) rather than by trying one emitter and falling back on its failure: a "try, then fall
     * back" would append a second marker after a first one that had already been half-written, and
     * a doubled BEGIN marker fails the whole command. The prelude is part of every frame and not of
     * the session, so nothing about a command depends on state an earlier one left in the shell.
     */
    static byte[] frameInteractiveCommand(String command, String marker) throws IOException {
        rejectTerminalKeystrokes(command);
        String framed = EMITTER_PRELUDE
                + "__denza_emit " + singleQuoted(marker + ":BEGIN") + "; "
                + "( eval " + singleQuoted(command) + " ) 2>&1; "
                + "__denza_adb_status=$?; "
                + "__denza_emit \"" + marker + ":$__denza_adb_status\"\n";
        return framed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Refuses a command that would reach the shell's line editor as keystrokes rather than as text.
     *
     * <p>No caller in this project sends one, and this is not the place to start: on this channel a
     * control byte is a key, and a command carrying one would not fail - it would hang until the
     * read timeout and roll its operation back with nothing to say about why. A newline is the one
     * exception, and it is not a guess: it is one of the cases compared byte for byte against the
     * previous frame on the car, through this very service (tools/split_frame_pty_identity.py).
     */
    private static void rejectTerminalKeystrokes(String command) throws IOException {
        for (int at = 0; at < command.length(); at++) {
            char symbol = command.charAt(at);
            if (symbol < 0x20 && symbol != '\n') {
                throw new IOException(String.format(
                        "ADB command carries a control character 0x%02x at %d, which the shell's "
                                + "line editor would read as a keystroke",
                        (int) symbol,
                        at));
            }
        }
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

    /**
     * Opens a channel to one long-lived shell-UID helper on an ADB stream of its own.
     *
     * <p>No socket is listened on and nothing is exposed: this is the same right the product
     * already exercises for every command, used once for a process that stays. The helper dies
     * with the stream - closing this session closes the stream, the shell it hangs off goes with
     * it, and the helper reaches end of file on its own stdin - so there is no state on the car
     * that can outlive the caller.
     *
     * <p>[nonce] must be unique per channel: it is what every answer is wrapped in, so a late or
     * half-written answer can never be read as the reply to a later request.
     */
    public ResidentSession openResidentSession(String nonce) {
        return new ResidentSession(nonce);
    }

    public final class ResidentSession implements AutoCloseable {
        private final String readyMarker;
        private final String beginMarker;
        private final String endMarker;
        private volatile Socket socket;
        private volatile Socket connectingSocket;
        private InputStream input;
        private OutputStream output;
        private int remoteId = -1;
        private volatile boolean closed;

        private ResidentSession(String nonce) {
            readyMarker = "DENZA_SERVE_" + nonce + ":READY";
            beginMarker = "DENZA_SERVE_" + nonce + ":BEGIN";
            endMarker = "DENZA_SERVE_" + nonce + ":END";
        }

        /** Runs [launchCommand] and waits for the helper to say it is up. */
        public synchronized void start(String launchCommand, int readyTimeoutMs)
                throws IOException, GeneralSecurityException {
            if (closed) {
                throw new IOException("this resident session is closed");
            }
            if (socket != null) {
                throw new IOException("this resident session is already running");
            }
            IOException lastIoFailure = null;
            GeneralSecurityException lastSecurityFailure = null;
            for (String host : hosts) {
                Socket candidate = new Socket();
                connectingSocket = candidate;
                try {
                    if (closed) {
                        throw new IOException("this resident session was closed while starting");
                    }
                    candidate.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
                    prepareTransport(candidate, readyTimeoutMs);
                    InputStream candidateInput = candidate.getInputStream();
                    OutputStream candidateOutput = candidate.getOutputStream();
                    connect(candidateInput, candidateOutput);
                    int candidateRemoteId = openInteractiveShell(
                            candidateInput, candidateOutput, INTERACTIVE_SHELL_LOCAL_ID);
                    awaitResident(
                            candidateInput,
                            candidateOutput,
                            INTERACTIVE_SHELL_LOCAL_ID,
                            candidateRemoteId,
                            launchCommand,
                            readyMarker);
                    if (closed) {
                        closeQuietly(candidate);
                        throw new IOException("this resident session was closed while starting");
                    }
                    socket = candidate;
                    input = candidateInput;
                    output = candidateOutput;
                    remoteId = candidateRemoteId;
                    if (closed) {
                        socket = null;
                        input = null;
                        output = null;
                        remoteId = -1;
                        closeQuietly(candidate);
                        throw new IOException("this resident session was closed while starting");
                    }
                    connectingSocket = null;
                    return;
                } catch (GeneralSecurityException error) {
                    if (connectingSocket == candidate) connectingSocket = null;
                    closeQuietly(candidate);
                    if (closed) {
                        throw new IOException(
                                "this resident session was closed while starting", error);
                    }
                    lastSecurityFailure = error;
                } catch (IOException error) {
                    if (connectingSocket == candidate) connectingSocket = null;
                    closeQuietly(candidate);
                    if (closed) {
                        throw new IOException(
                                "this resident session was closed while starting", error);
                    }
                    lastIoFailure = error;
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

        /** One request, one answer. Any failure at all closes the channel; the caller falls back. */
        public synchronized String request(String line, int readTimeoutMs) throws IOException {
            if (closed) {
                throw new IOException("the resident session is closed");
            }
            if (socket == null) {
                throw new IOException("the resident helper is not running");
            }
            try {
                socket.setSoTimeout(readTimeoutMs);
                return runResidentRequest(
                        input,
                        output,
                        INTERACTIVE_SHELL_LOCAL_ID,
                        remoteId,
                        line,
                        beginMarker,
                        endMarker);
            } catch (IOException error) {
                close();
                throw error;
            }
        }

        public synchronized boolean isRunning() {
            return !closed && socket != null;
        }

        @Override
        public void close() {
            closed = true;
            // Socket.close() is the interruption primitive for a blocking read. Do it before
            // taking this object's monitor: request() holds that monitor while it waits for a
            // resident answer, and lifecycle teardown must not wait for the helper's long-poll.
            closeQuietly(socket);
            // start() also owns this monitor. Publishing the candidate before connect/handshake
            // gives close() the same interruption primitive while startup is still in flight.
            closeQuietly(connectingSocket);
            synchronized (this) {
                socket = null;
                connectingSocket = null;
                input = null;
                output = null;
                remoteId = -1;
            }
        }
    }

    static void awaitResident(
            InputStream input,
            OutputStream output,
            int localId,
            int remoteId,
            String launchCommand,
            String readyMarker) throws IOException {
        exchangeWithResident(
                input,
                output,
                localId,
                remoteId,
                launchCommand,
                received -> received.contains(readyMarker) ? "" : null);
    }

    static String runResidentRequest(
            InputStream input,
            OutputStream output,
            int localId,
            int remoteId,
            String request,
            String beginMarker,
            String endMarker) throws IOException {
        return exchangeWithResident(
                input,
                output,
                localId,
                remoteId,
                request,
                received -> findResidentAnswer(received, beginMarker, endMarker));
    }

    /**
     * The payload of a finished answer, or {@code null} while it is still on its way.
     *
     * <p>Line endings are deliberately not normalised. Some DiLink builds hand a shell stream that
     * turns every newline into a carriage return and a newline, and the product's parsers have
     * always read the car's answers through that; an answer from the helper is read by exactly the
     * same parsers, so it must arrive in exactly the same shape.
     */
    static String findResidentAnswer(String received, String beginMarker, String endMarker)
            throws IOException {
        int begin = received.indexOf(beginMarker);
        if (begin < 0) {
            return null;
        }
        int payloadStart = received.indexOf('\n', begin + beginMarker.length());
        if (payloadStart < 0) {
            return null;
        }
        payloadStart += 1;
        int end = received.indexOf(endMarker, payloadStart);
        if (end < 0) {
            return null;
        }
        int statusEnd = received.indexOf('\n', end);
        if (statusEnd < 0) {
            return null;
        }
        String status = received.substring(end + endMarker.length(), statusEnd).trim();
        if (!"ok".equals(status)) {
            throw new IOException("the resident helper refused: " + status);
        }
        return received.substring(payloadStart, end);
    }

    private interface ResidentScan {
        /** The finished answer inside [received], or {@code null} to keep reading. */
        String found(String received) throws IOException;
    }

    private static String exchangeWithResident(
            InputStream input,
            OutputStream output,
            int localId,
            int remoteId,
            String line,
            ResidentScan scan) throws IOException {
        byte[] request = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (request.length > MAX_PAYLOAD) {
            throw new IOException("ADB resident request exceeds max payload");
        }
        writeMessage(output, A_WRTE, localId, remoteId, request);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        boolean writeAcknowledged = false;
        while (true) {
            Message message = readMessage(input);
            verifyStreamMessage(message, localId, remoteId);
            if (message.command == A_OKAY) {
                writeAcknowledged = true;
            } else if (message.command == A_WRTE) {
                received.write(message.payload);
                writeMessage(output, A_OKAY, localId, remoteId, new byte[0]);
            } else if (message.command == A_CLSE) {
                writeMessage(output, A_CLSE, localId, remoteId, new byte[0]);
                throw new EOFException("the resident helper's stream closed");
            } else {
                throw new IOException("Unexpected resident message " + message.commandName());
            }
            String answer = scan.found(received.toString(StandardCharsets.UTF_8.name()));
            if (writeAcknowledged && answer != null) {
                return answer;
            }
        }
    }

    public final class PersistentShellSession implements AutoCloseable {
        private final String markerNonce =
                Long.toUnsignedString(System.nanoTime(), 16);
        private volatile Socket socket;
        private volatile Socket connectingSocket;
        private InputStream input;
        private OutputStream output;
        private int remoteId = -1;
        private long commandSequence;
        private long nextConnectAtNanos = Long.MIN_VALUE;
        private volatile boolean closed;
        private final ShellSpend spend = new ShellSpend();

        public String shell(String command)
                throws IOException, GeneralSecurityException {
            return shell(command, READ_TIMEOUT_MS);
        }

        /**
         * One command on the one shell of this session, with its time attributed as it is spent.
         *
         * <p>The wait for the session is measured rather than hidden inside the call, because this
         * session is shared: anything else that speaks to the car through it - a background poll,
         * another feature - makes a caller queue, and queuing looks exactly like a slow car from
         * the outside. Wave 16 could not tell those apart, so now the transport says which it was.
         */
        public String shell(String command, int readTimeoutMs)
                throws IOException, GeneralSecurityException {
            if (readTimeoutMs < 1) {
                throw new IllegalArgumentException("readTimeoutMs must be positive");
            }
            return timed(this, spend, spent -> {
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
                            marker,
                            spent);
                } catch (IOException error) {
                    closeConnection();
                    scheduleReconnect(RECONNECT_BACKOFF_NANOS);
                    throw error;
                }
            });
        }

        /**
         * Everything this session has spent since the last call, and it starts counting again.
         *
         * <p>Four numbers in nanoseconds: how many commands, how long they waited for the session,
         * how long it took to hand them to the socket, how long the car took to answer.
         */
        public long[] drainTimings() {
            return spend.drain();
        }

        private void ensureConnected(int readTimeoutMs)
                throws IOException, GeneralSecurityException {
            if (socket != null) {
                prepareTransport(socket, readTimeoutMs);
                return;
            }
            waitForReconnectWindow();

            IOException lastIoFailure = null;
            GeneralSecurityException lastSecurityFailure = null;
            for (String host : hosts) {
                Socket candidate = new Socket();
                connectingSocket = candidate;
                try {
                    if (closed) {
                        throw new IOException("Persistent ADB shell was closed while connecting");
                    }
                    candidate.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
                    prepareTransport(candidate, readTimeoutMs);
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
                    if (closed) {
                        closeConnection();
                        throw new IOException("Persistent ADB shell was closed while connecting");
                    }
                    connectingSocket = null;
                    return;
                } catch (GeneralSecurityException error) {
                    if (connectingSocket == candidate) connectingSocket = null;
                    closeQuietly(candidate);
                    if (closed) {
                        throw new IOException(
                                "Persistent ADB shell was closed while connecting", error);
                    }
                    lastSecurityFailure = error;
                } catch (IOException error) {
                    if (connectingSocket == candidate) connectingSocket = null;
                    closeQuietly(candidate);
                    if (closed) {
                        throw new IOException(
                                "Persistent ADB shell was closed while connecting", error);
                    }
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
        public void close() {
            closed = true;
            // shell() owns this object's monitor while waiting for ADB. Close its socket first so
            // cancellation never waits for the very read it needs to interrupt.
            closeQuietly(socket);
            closeQuietly(connectingSocket);
            synchronized (this) {
                closeConnection();
                connectingSocket = null;
            }
        }

        private void closeConnection() {
            closeQuietly(socket);
            socket = null;
            input = null;
            output = null;
            remoteId = -1;
        }
    }

    /**
     * The two things every ADB socket of this client needs before a single byte goes out.
     *
     * <p>Nagle is the one that was missing. ADB is strictly request/response - nothing comes back
     * until a whole message has arrived - so a delayed segment is a delayed answer, every time,
     * and the product sends dozens of messages to build one scene.
     */
    static void prepareTransport(Socket socket, int readTimeoutMs) throws SocketException {
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
    }

    /** One command of a shared session, with the array it reports its own two marks through. */
    interface ShellBody {
        String run(long[] spent) throws IOException, GeneralSecurityException;
    }

    /**
     * Runs [body] under [lock] and books the time three ways: queued, sent, answered.
     *
     * <p>The wait for the lock is taken before it is held, which is the whole point: a command
     * that waited for someone else is not a slow car, and until wave 16 the two were one number.
     * A command that failed is booked as well - the caller waited for it either way.
     */
    static String timed(Object lock, ShellSpend spend, ShellBody body)
            throws IOException, GeneralSecurityException {
        long requestedAt = System.nanoTime();
        synchronized (lock) {
            long waitedNanos = System.nanoTime() - requestedAt;
            long[] spent = new long[2];
            try {
                return body.run(spent);
            } finally {
                spend.record(waitedNanos, spent);
            }
        }
    }

    /** The running total of one session, in nanoseconds, handed over and reset on demand. */
    static final class ShellSpend {
        private long calls;
        private long queuedNanos;
        private long sentNanos;
        private long answeredNanos;

        synchronized void record(long waitedNanos, long[] spent) {
            calls += 1;
            queuedNanos += waitedNanos;
            sentNanos += spent[0];
            answeredNanos += spent[1];
        }

        synchronized long[] drain() {
            long[] drained = {calls, queuedNanos, sentNanos, answeredNanos};
            calls = 0;
            queuedNanos = 0;
            sentNanos = 0;
            answeredNanos = 0;
            return drained;
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
        // One message, one write. The header used to go out on its own and the payload after it,
        // which asks Nagle to hold the payload until the header is acknowledged - the answer to an
        // ADB message only comes after both halves arrive, so the stall lands on every command.
        ByteBuffer message = ByteBuffer.allocate(24 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(command);
        message.putInt(arg0);
        message.putInt(arg1);
        message.putInt(payload.length);
        message.putInt(checksum);
        message.putInt(command ^ 0xffffffff);
        message.put(payload);
        output.write(message.array());
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
