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
    private static final AuthorizationPromptGate AUTH_PROMPT_GATE =
            new AuthorizationPromptGate(AUTH_PROMPT_COOLDOWN_NANOS);

    private final AdbKeyStore keyStore;
    private final List<String> hosts;

    public LocalAdbClient(Context context) {
        this(context, null);
    }

    public LocalAdbClient(Context context, String publicKeyComment) {
        keyStore = new AdbKeyStore(context, publicKeyComment);
        hosts = candidateHosts();
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
        writeMessage(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\0".getBytes(
                StandardCharsets.US_ASCII));
        boolean publicKeySent = false;
        while (true) {
            Message message = readMessage(input);
            if (message.command == A_CNXN) {
                return;
            }
            if (message.command != A_AUTH || message.arg0 != ADB_AUTH_TOKEN) {
                throw new IOException("Unexpected ADB handshake message " + message.commandName());
            }
            if (!publicKeySent) {
                writeMessage(output, A_AUTH, ADB_AUTH_SIGNATURE, 0,
                        keyStore.signToken(message.payload));
                Message reply = readMessage(input);
                if (reply.command == A_CNXN) {
                    return;
                }
                if (reply.command != A_AUTH || reply.arg0 != ADB_AUTH_TOKEN) {
                    throw new IOException("Unexpected ADB auth reply " + reply.commandName());
                }
                if (!AUTH_PROMPT_GATE.tryAcquire(System.nanoTime())) {
                    throw authorizationPending();
                }
                writeMessage(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0,
                        keyStore.publicKeyPayload());
                publicKeySent = true;
            } else {
                throw authorizationPending();
            }
        }
    }

    private static IOException authorizationPending() {
        return new IOException("ADB authorization pending; confirm the ADB request");
    }

    static boolean isAuthorizationPending(IOException error) {
        return error.getMessage() != null
                && error.getMessage().contains("ADB authorization pending");
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

        byte[] markerPrefix = ("\u001e" + marker + ":").getBytes(StandardCharsets.US_ASCII);
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
                framedResult = findFramedResult(received.toByteArray(), markerPrefix);
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

    private static byte[] frameInteractiveCommand(String command, String marker) {
        String framed = "(\n"
                + command
                + "\n) 2>&1\n"
                + "__denza_adb_status=$?\n"
                + "printf '\\036"
                + marker
                + ":%s\\037' \"$__denza_adb_status\"\n";
        return framed.getBytes(StandardCharsets.UTF_8);
    }

    private static FramedShellResult findFramedResult(byte[] received, byte[] markerPrefix)
            throws IOException {
        int markerStart = indexOf(received, markerPrefix, 0);
        if (markerStart < 0) {
            return null;
        }
        int statusStart = markerStart + markerPrefix.length;
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
                new String(received, 0, markerStart, StandardCharsets.UTF_8));
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
