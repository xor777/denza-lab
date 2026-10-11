package dev.denza.dipilotkey.probe;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Runs through run-as in Denza Apps' UID. The private key never crosses that process boundary. */
public final class TargetIdentityCheck {
    static final String MARKER = "DENZA_TARGET_SHELL_OK";
    private static final int OPEN = 0x4e45504f, OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;

    public static void main(String[] args) {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { return; }
            System.out.println("DENZA_TARGET_CHECK=TIMEOUT");
            System.exit(0);
        });
        deadline.setDaemon(true); deadline.start();
        try {
            File directory = new File(args[0]).getCanonicalFile();
            if (!directory.getName().equals("dev.denza.apps")) throw new SecurityException();
            KeyPair pair = readPair(directory);
            DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKey.PREFIX
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            System.out.println("DENZA_TARGET_FP=" + key.fingerprint);
            List<String> hosts = hosts();
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555; // Local protocol fixture only.
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String first = check(pair, hosts, port);
            System.out.println("DENZA_TARGET_FIRST=" + first);
            // A new signed transport proves reconnect, rather than the public-only request socket.
            String second = first.equals("TRUSTED") ? check(pair, hosts, port) : "NOT_RUN";
            System.out.println("DENZA_TARGET_SECOND=" + second);
            System.out.println("DENZA_TARGET_CHECK=" + (first.equals("TRUSTED") ? second : first));
        } catch (Exception failure) {
            System.out.println("DENZA_TARGET_CHECK=ERROR");
            System.out.println("DENZA_TARGET_ERROR=" + failure.getClass().getSimpleName());
        }
    }

    static KeyPair readPair(File dir) throws Exception {
        byte[] privateDer, publicDer;
        File file = new File(dir, "no_backup/adb_auth_key_v1");
        File backup = new File(file + ".bak");
        if (backup.isFile()) file = backup;
        if (file.isFile()) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                if (input.readInt() != 0x44414b31) throw new IllegalArgumentException();
                privateDer = encoded(input); publicDer = encoded(input);
            }
        } else {
            java.nio.file.Path path = new File(dir, "shared_prefs/adb_auth.xml").toPath();
            if (java.nio.file.Files.size(path) > 131072) throw new IllegalArgumentException();
            String xml = new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);
            // SharedPreferences stores these two members as plain base64 strings. Do not
            // resolve XML entities or rely on Xerces features absent from Android's parser.
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IllegalArgumentException();
            privateDer = legacyMember(xml, "private_pkcs8");
            publicDer = legacyMember(xml, "public_x509");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(privateDer));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(publicDer));
            if (!privateKey.getModulus().equals(publicKey.getModulus()) || publicKey.getModulus().bitLength() != 2048) {
                throw new IllegalArgumentException();
            }
            return new KeyPair(publicKey, privateKey);
        } finally { Arrays.fill(privateDer, (byte) 0); }
    }

    private static byte[] legacyMember(String xml, String name) {
        java.util.regex.Matcher value = java.util.regex.Pattern.compile("<string\\s+name=[\"']"
                + name + "[\"']\\s*>\\s*([A-Za-z0-9+/=\\r\\n]+)\\s*</string>").matcher(xml);
        if (!value.find()) throw new IllegalArgumentException();
        return Base64.getDecoder().decode(value.group(1).replace("\r", "").replace("\n", ""));
    }

    private static byte[] encoded(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 1 || length > 16384) throw new IllegalArgumentException();
        byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
    }

    static byte[] sign(RSAPrivateKey key, byte[] token) {
        if (token.length != 20) throw new IllegalArgumentException();
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] block = new byte[256]; block[1] = 1;
        Arrays.fill(block, 2, 256 - prefix.length - token.length - 1, (byte) 0xff);
        System.arraycopy(prefix, 0, block, 256 - prefix.length - token.length, prefix.length);
        System.arraycopy(token, 0, block, 256 - token.length, token.length);
        byte[] signature = new BigInteger(1, block).modPow(key.getPrivateExponent(), key.getModulus()).toByteArray();
        byte[] fixed = new byte[256];
        int n = Math.min(signature.length, 256);
        System.arraycopy(signature, signature.length - n, fixed, 256 - n, n);
        return fixed;
    }

    private static List<String> hosts() throws Exception {
        List<String> hosts = new ArrayList<>(); hosts.add("127.0.0.1");
        java.util.Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null) return hosts;
        for (NetworkInterface network : Collections.list(all)) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress ip : Collections.list(network.getInetAddresses())) {
                if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()
                        && !hosts.contains(ip.getHostAddress()) && hosts.size() < 4) hosts.add(ip.getHostAddress());
            }
        }
        return hosts;
    }

    static String check(KeyPair pair, List<String> hosts, int port) throws Exception {
        for (String host : hosts) {
            try (Socket socket = new Socket()) {
                try { socket.connect(new InetSocketAddress(host, port), 1200); }
                catch (java.io.IOException unavailable) { continue; }
                return exchange(socket, (RSAPrivateKey) pair.getPrivate());
            }
        }
        return "UNAVAILABLE";
    }

    static String exchange(Socket socket, RSAPrivateKey privateKey) throws Exception {
        socket.setSoTimeout(3000);
        PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.CNXN, 0x01000000, 4096,
                "host::\0".getBytes(StandardCharsets.US_ASCII));
        PublicKeyRequest.Message challenge = PublicKeyRequest.read(socket);
        if (challenge.command == PublicKeyRequest.CNXN) return "NO_RSA_CHALLENGE";
        if (challenge.command != PublicKeyRequest.AUTH || challenge.arg0 != 1) return "PROTOCOL_ERROR";
        PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.AUTH, 2, 0,
                sign(privateKey, challenge.payload));
        PublicKeyRequest.Message auth = PublicKeyRequest.read(socket);
        if (auth.command == PublicKeyRequest.AUTH && auth.arg0 == 1) return "AUTH_REQUIRED";
        if (auth.command != PublicKeyRequest.CNXN) return "PROTOCOL_ERROR";
        PublicKeyRequest.write(socket.getOutputStream(), OPEN, 1, 0,
                ("shell:printf " + MARKER + '\0').getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = -1;
        for (int count = 0; count < 32; count++) {
            PublicKeyRequest.Message reply = PublicKeyRequest.read(socket);
            if (reply.arg1 != 1) return "PROTOCOL_ERROR";
            if (reply.command == OKAY) remote = reply.arg0;
            else if (reply.command == WRTE) {
                if (reply.arg0 != remote || text.size() + reply.payload.length > 8192) return "PROTOCOL_ERROR";
                text.write(reply.payload);
                PublicKeyRequest.write(socket.getOutputStream(), OKAY, 1, remote, new byte[0]);
            } else if (reply.command == CLSE) {
                PublicKeyRequest.write(socket.getOutputStream(), CLSE, 1, reply.arg0, new byte[0]);
                return text.toString(StandardCharsets.US_ASCII).contains(MARKER) ? "TRUSTED" : "SHELL_FAILED";
            } else return "PROTOCOL_ERROR";
        }
        return "PROTOCOL_ERROR";
    }

    static String command(String apk) {
        return "run-as dev.denza.apps sh -c " + DenzaPublicKey.quote("d=$(pwd); CLASSPATH="
                + DenzaPublicKey.quote(apk) + " exec app_process /system/bin "
                + TargetIdentityCheck.class.getName() + " \"$d\"");
    }
}
