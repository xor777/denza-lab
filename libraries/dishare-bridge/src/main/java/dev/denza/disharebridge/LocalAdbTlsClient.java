package dev.denza.disharebridge;

import android.content.Context;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/** Local-only AOSP STLS transport. It never pairs or submits a public-key authorization request. */
public final class LocalAdbTlsClient {
    static final int VERSION = 0x01000001;
    static final int STLS_VERSION = 0x01000000;
    static final int MAX_PAYLOAD = 262144;
    static final int CNXN = command("CNXN"), STLS = command("STLS"), AUTH = command("AUTH");
    static final int OPEN = command("OPEN"), OKAY = command("OKAY"), WRTE = command("WRTE");
    static final int CLSE = command("CLSE");
    private final Context context;
    private final AdbKeyStore keys;

    public LocalAdbTlsClient(Context context, String comment) {
        this.context = context.getApplicationContext();
        keys = new AdbKeyStore(context, comment);
    }

    public void restartTcpip(String host, int port, BooleanSupplier abandoned) throws Exception {
        if (port < 1 || port > 65535 || !isLocalAddress(InetAddress.getByName(host))) {
            throw new IOException("Wireless ADB endpoint is not local");
        }
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), 5000);
            raw.setSoTimeout(5000);
            raw.setTcpNoDelay(true);
            KeyPair key = keys.keyPair();
            X509Certificate certificate = AdbCertificate.load(context.getNoBackupFilesDir(), key);
            SSLContext tls = SSLContext.getInstance("TLSv1.3");
            tls.init(new javax.net.ssl.KeyManager[] {new Identity(key, certificate)},
                    new javax.net.ssl.TrustManager[] {new LocalTrust()}, null);
            exchange(raw.getInputStream(), raw.getOutputStream(), (input, output) -> {
                SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, host, port, true);
                socket.setUseClientMode(true);
                socket.setEnabledProtocols(new String[] {"TLSv1.3"});
                socket.setSoTimeout(5000);
                socket.startHandshake();
                return new Streams(socket.getInputStream(), socket.getOutputStream());
            }, keys::signToken, abandoned);
        }
    }

    public static boolean isLocalAddress(InetAddress address) throws IOException {
        if (address.isAnyLocalAddress()) return false;
        if (address.isLoopbackAddress()) return true;
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (Collections.list(network.getInetAddresses()).contains(address)) return true;
        }
        return false;
    }

    interface Upgrade { Streams start(InputStream input, OutputStream output) throws Exception; }
    interface Sign { byte[] token(byte[] token) throws Exception; }
    static final class Streams {
        final InputStream input; final OutputStream output;
        Streams(InputStream input, OutputStream output) { this.input = input; this.output = output; }
    }

    static void exchange(InputStream input, OutputStream output, Upgrade upgrade, Sign sign,
            BooleanSupplier abandoned) throws Exception {
        check(abandoned);
        write(output, CNXN, VERSION, MAX_PAYLOAD, ascii("host::\0"));
        boolean encrypted = false, signed = false, connected = false;
        for (int step = 0; step < 8; step++) {
            check(abandoned);
            Packet packet = read(input);
            if (packet.command == STLS && !encrypted) {
                write(output, STLS, STLS_VERSION, 0, new byte[0]);
                check(abandoned);
                Streams streams = upgrade.start(input, output);
                input = streams.input; output = streams.output; encrypted = true;
            } else if (packet.command == AUTH && packet.arg0 == 1 && !signed) {
                write(output, AUTH, 2, 0, sign.token(packet.payload));
                signed = true;
            } else if (packet.command == CNXN && encrypted) {
                connected = true;
                break;
            } else {
                throw new IOException("Wireless ADB did not authenticate the existing key");
            }
        }
        if (!connected) throw new IOException("Wireless ADB handshake did not finish");
        check(abandoned);
        write(output, OPEN, 1, 0, ascii("tcpip:5555\0"));
        // Restarting adbd can close the stream without a reply. Only a later classic probe proves
        // restoration; EOF is allowed here only after OPEN may have reached the daemon.
        try {
            int remote = 0;
            for (int step = 0; step < 16; step++) {
                check(abandoned);
                Packet packet = read(input);
                if (packet.arg1 != 1 || (remote != 0 && packet.arg0 != remote)) {
                    throw new IOException("Unexpected wireless ADB stream");
                }
                if (packet.command == OKAY && remote == 0) remote = packet.arg0;
                else if (packet.command == WRTE) {
                    remote = packet.arg0;
                    write(output, OKAY, 1, remote, new byte[0]);
                } else if (packet.command == CLSE) {
                    write(output, CLSE, 1, packet.arg0, new byte[0]);
                    return;
                } else throw new IOException("Unexpected wireless ADB reply");
            }
            throw new IOException("Wireless ADB reply exceeded message limit");
        } catch (EOFException expectedRestart) { /* Poll classic 5555 in the manager. */ }
    }

    static void check(BooleanSupplier abandoned) throws InterruptedIOException {
        if (abandoned.getAsBoolean()) throw new InterruptedIOException("ADB restore cancelled");
    }
    static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    static int command(String value) {
        return ByteBuffer.wrap(ascii(value)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    static void write(OutputStream output, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length)
                .putInt(0).putInt(command ^ -1).put(payload); // v1.0.1 skips checksum
        output.write(bytes.array()); output.flush();
    }
    static Packet read(InputStream input) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(exactly(input, 24)).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
        int length = header.getInt(); header.getInt();
        if (header.getInt() != (command ^ -1) || length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("Invalid wireless ADB packet");
        }
        return new Packet(command, arg0, arg1, exactly(input, length));
    }
    static byte[] exactly(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count]; int offset = 0;
        while (offset < count) {
            int read = input.read(bytes, offset, count - offset);
            if (read < 0) throw new EOFException("Wireless ADB stream closed");
            offset += read;
        }
        return bytes;
    }
    static final class Packet {
        final int command, arg0, arg1; final byte[] payload;
        Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }

    private static final class Identity extends X509ExtendedKeyManager {
        final KeyPair key; final X509Certificate certificate;
        Identity(KeyPair key, X509Certificate certificate) { this.key = key; this.certificate = certificate; }
        public String[] getClientAliases(String type, Principal[] issuers) {
            return type.equals("RSA") ? new String[] {"adb"} : null;
        }
        public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
            for (String type : types) if (type.equals("RSA")) return "adb";
            return null;
        }
        public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) {
            return chooseClientAlias(types, issuers, null);
        }
        public String[] getServerAliases(String type, Principal[] issuers) { return null; }
        public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return null; }
        public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {certificate}; }
        public PrivateKey getPrivateKey(String alias) { return key.getPrivate(); }
    }
    // AOSP adbd uses a self-signed server certificate. Restricted to the device's own interfaces
    // above; authorization at adbd is the existing RSA client key, not a CA chain or pairing code.
    private static final class LocalTrust extends X509ExtendedTrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
