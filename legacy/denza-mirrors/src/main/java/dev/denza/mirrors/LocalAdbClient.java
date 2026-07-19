package dev.denza.mirrors;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

final class LocalAdbClient {
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

    private final AdbKeyStore keyStore;

    LocalAdbClient(Context context) {
        keyStore = new AdbKeyStore(context);
    }

    synchronized String shell(String command) throws IOException, GeneralSecurityException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            connect(input, output);
            return runShell(input, output, command);
        } finally {
            socket.close();
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
                writeMessage(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0,
                        keyStore.publicKeyPayload());
                publicKeySent = true;
            } else {
                throw new IOException("ADB authorization pending; confirm the debugging prompt");
            }
        }
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
}
