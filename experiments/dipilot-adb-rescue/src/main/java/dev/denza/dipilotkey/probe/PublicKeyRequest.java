package dev.denza.dipilotkey.probe;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** One public-key AUTH request. No private-key access, signature, shell, TLS or retry after send. */
final class PublicKeyRequest implements DenzaKeyRescue.Request {
    static final int CNXN = 0x4e584e43;
    static final int AUTH = 0x48545541;
    private final Socket socket;

    private PublicKeyRequest(Socket socket) { this.socket = socket; }

    static PublicKeyRequest open(String key, DenzaKeyRescue.BeforeSend beforeSend) throws Exception {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface network : interfaces == null ? Collections.<NetworkInterface>emptyList()
                : Collections.list(interfaces)) {
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    hosts.add(address.getHostAddress());
                }
            }
        }
        IOException last = new IOException("Локальный adbd недоступен");
        for (String host : hosts) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, 5555), 2000);
            } catch (IOException failedConnect) {
                socket.close();
                last = failedConnect;
                continue;
            }
            // Once connected, an ambiguous write must never be retried on another address.
            try {
                return start(socket, key, beforeSend);
            } catch (Exception failure) {
                socket.close();
                throw failure;
            }
        }
        throw last;
    }

    static PublicKeyRequest start(Socket socket, String key, DenzaKeyRescue.BeforeSend beforeSend)
            throws Exception {
        socket.setSoTimeout(4000);
        write(socket.getOutputStream(), CNXN, 0x01000000, 4096,
                "host::\0".getBytes(StandardCharsets.US_ASCII));
        requireChallenge(socket);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        beforeSend.run();
        write(socket.getOutputStream(), AUTH, 3, 0,
                (key + '\0').getBytes(StandardCharsets.US_ASCII));
        return new PublicKeyRequest(socket);
    }

    static void requireChallenge(Socket socket) throws IOException {
        Message message = read(socket);
        if (message.command != AUTH || message.arg0 != 1 || message.arg1 != 0 || message.payload.length != 20) {
            throw new IOException("adbd не прислал ожидаемый RSA-запрос; ключ не отправлен");
        }
    }

    static void write(OutputStream output, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        int checksum = 0;
        for (byte b : payload) checksum += b & 255;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length)
                .putInt(checksum).putInt(command ^ -1);
        output.write(header.array());
        output.write(payload);
        output.flush();
    }

    static final class Message {
        final int command, arg0, arg1;
        final byte[] payload;
        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }

    static Message read(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[24];
        input.readFully(header);
        ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = bytes.getInt(), arg0 = bytes.getInt(), arg1 = bytes.getInt();
        int length = bytes.getInt(), checksum = bytes.getInt(), magic = bytes.getInt();
        if (magic != (command ^ -1) || length < 0 || length > 4096) {
            throw new IOException("Некорректный пакет ADB");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        int actual = 0;
        for (byte b : payload) actual += b & 255;
        if (checksum != actual) throw new IOException("Некорректная контрольная сумма ADB");
        return new Message(command, arg0, arg1, payload);
    }

    @Override public void close() throws IOException { socket.close(); }
}
