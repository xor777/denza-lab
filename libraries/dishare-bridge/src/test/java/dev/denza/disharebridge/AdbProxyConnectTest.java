package dev.denza.disharebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;

import org.junit.Test;

/**
 * Exercises {@link LocalAdbClient#proxyAdbConnect}: the client side of the ADB handshake, driven on
 * a bare key pair with no device and no {@link android.content.Context}.
 *
 * <p>These prove the car can authenticate a tunnelled computer with its own key: the client's CNXN
 * reaches adbd unchanged, adbd's AUTH token is answered with a real signature the public key
 * verifies, adbd's CNXN comes back verbatim, and the public key is never offered — a refused
 * signature or an STLS request closes the path by type.
 */
public final class AdbProxyConnectTest {
    private static final int A_CNXN = cmd("CNXN");
    private static final int A_AUTH = cmd("AUTH");
    private static final int A_STLS = cmd("STLS");
    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;
    private static final int ADB_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    @Test
    public void signsAdbdTokenWithTheAppKeyAndRelaysTheConnect() throws Exception {
        KeyPair appKey = rsa();
        byte[] clientBanner = "host::features=cmd,shell_v2\0".getBytes(StandardCharsets.UTF_8);
        byte[] adbdBanner = "device::ro.product.name=denza\0".getBytes(StandardCharsets.UTF_8);
        byte[] token = randomToken();

        Streams streams = new Streams();
        AtomicReference<AdbProxyResultHolder> holder = new AtomicReference<>();
        Thread proxy = runProxy(appKey, streams, holder);

        // adbd: reads the client's CNXN, challenges, verifies the signature, then accepts.
        AtomicReference<Msg> adbdCnxn = new AtomicReference<>();
        AtomicReference<Msg> adbdAuth = new AtomicReference<>();
        Thread adbd = new Thread(() -> {
            try {
                adbdCnxn.set(readMsg(streams.proxyToAdbdIn));
                writeMsg(streams.adbdToProxyOut, A_AUTH, ADB_AUTH_TOKEN, 0, token);
                adbdAuth.set(readMsg(streams.proxyToAdbdIn));
                writeMsg(streams.adbdToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD, adbdBanner);
            } catch (IOException ignored) {
                // surfaced through the assertions below
            }
        }, "fake-adbd");
        adbd.start();

        // client: sends its CNXN, then expects adbd's CNXN with no auth of its own.
        writeMsg(streams.clientToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD, clientBanner);
        Msg clientReceived = readMsg(streams.proxyToClientIn);

        adbd.join(5_000);
        proxy.join(5_000);

        assertEquals("adbd saw the client's CNXN", A_CNXN, adbdCnxn.get().command);
        assertEquals("the client's version is carried through", ADB_VERSION, adbdCnxn.get().arg0);
        assertEquals("the client's max-payload is carried through", MAX_PAYLOAD, adbdCnxn.get().arg1);
        assertArrayEquals("the client's banner is unchanged", clientBanner, adbdCnxn.get().payload);

        assertEquals("adbd was answered with a signature, not a key",
                ADB_AUTH_SIGNATURE, adbdAuth.get().authKind());
        assertTrue("the signature verifies against the app public key",
                signatureMatches(appKey.getPublic(), token, adbdAuth.get().payload));

        assertEquals("the client got adbd's CNXN", A_CNXN, clientReceived.command);
        assertArrayEquals("adbd's banner reaches the client verbatim", adbdBanner, clientReceived.payload);

        AdbProxyResultHolder result = holder.get();
        assertEquals(ADB_VERSION, result.result.version);
        assertEquals(MAX_PAYLOAD, result.result.maxData);
        assertArrayEquals(adbdBanner, result.result.systemIdentity);
    }

    @Test
    public void aRefusedSignatureClosesWithoutSendingThePublicKey() throws Exception {
        KeyPair appKey = rsa();
        Streams streams = new Streams();
        AtomicReference<AdbProxyResultHolder> holder = new AtomicReference<>();
        Thread proxy = runProxy(appKey, streams, holder);

        AtomicReference<Msg> afterSignature = new AtomicReference<>();
        AtomicReference<Boolean> sawPublicKey = new AtomicReference<>(false);
        Thread adbd = new Thread(() -> {
            try {
                readMsg(streams.proxyToAdbdIn); // client CNXN
                writeMsg(streams.adbdToProxyOut, A_AUTH, ADB_AUTH_TOKEN, 0, randomToken());
                Msg signature = readMsg(streams.proxyToAdbdIn);
                // Refuse: challenge again. A correct proxy signs once and never offers the key.
                writeMsg(streams.adbdToProxyOut, A_AUTH, ADB_AUTH_TOKEN, 0, randomToken());
                try {
                    Msg maybeKey = readMsg(streams.proxyToAdbdIn);
                    afterSignature.set(maybeKey);
                    if (maybeKey.authKind() == ADB_AUTH_RSAPUBLICKEY) {
                        sawPublicKey.set(true);
                    }
                } catch (EOFException expected) {
                    // The proxy closed adbdOut instead of sending the key.
                }
            } catch (IOException ignored) {
                // closed stream once the proxy gave up
            }
        }, "fake-adbd-refusing");
        adbd.start();

        writeMsg(streams.clientToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD,
                "host::\0".getBytes(StandardCharsets.UTF_8));
        adbd.join(5_000);
        proxy.join(5_000);
        streams.closeAll();

        assertTrue("the proxy reports the rejection by type",
                holder.get().failure instanceof LocalAdbClient.AdbProxySignatureRejectedException);
        assertFalse("the public key is never sent from this path", sawPublicKey.get());
    }

    @Test
    public void modernPeersWithZeroChecksumsAreRelayed() throws Exception {
        // adb and adbd at version 0x01000001 send a zero data checksum; an adbd that needs no
        // authentication answers the CNXN straight away.
        KeyPair appKey = rsa();
        byte[] clientBanner = "host::features=cmd,shell_v2\0".getBytes(StandardCharsets.UTF_8);
        byte[] adbdBanner = "device::ro.product.name=sdk\0".getBytes(StandardCharsets.UTF_8);
        Streams streams = new Streams();
        AtomicReference<AdbProxyResultHolder> holder = new AtomicReference<>();
        Thread proxy = runProxy(appKey, streams, holder);

        Thread adbd = new Thread(() -> {
            try {
                readMsg(streams.proxyToAdbdIn);
                writeMsg(streams.adbdToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD, adbdBanner, 0);
            } catch (IOException ignored) {
                // surfaced through the assertions below
            }
        }, "fake-adbd-modern");
        adbd.start();

        writeMsg(streams.clientToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD, clientBanner, 0);
        Msg clientReceived = readMsg(streams.proxyToClientIn);
        adbd.join(5_000);
        proxy.join(5_000);

        assertEquals("no failure", null, holder.get().failure);
        assertEquals(A_CNXN, clientReceived.command);
        assertArrayEquals(adbdBanner, clientReceived.payload);
    }

    @Test
    public void anStlsRequestClosesByType() throws Exception {
        KeyPair appKey = rsa();
        Streams streams = new Streams();
        AtomicReference<AdbProxyResultHolder> holder = new AtomicReference<>();
        Thread proxy = runProxy(appKey, streams, holder);

        Thread adbd = new Thread(() -> {
            try {
                readMsg(streams.proxyToAdbdIn); // client CNXN
                writeMsg(streams.adbdToProxyOut, A_STLS, 0x01000000, 0, new byte[0]);
            } catch (IOException ignored) {
                // closed once the proxy gives up
            }
        }, "fake-adbd-stls");
        adbd.start();

        writeMsg(streams.clientToProxyOut, A_CNXN, ADB_VERSION, MAX_PAYLOAD,
                "host::\0".getBytes(StandardCharsets.UTF_8));
        adbd.join(5_000);
        proxy.join(5_000);
        streams.closeAll();

        assertTrue("STLS is reported by type",
                holder.get().failure instanceof LocalAdbClient.AdbProxyTlsException);
    }

    private static Thread runProxy(KeyPair appKey, Streams streams, AtomicReference<AdbProxyResultHolder> holder) {
        Thread proxy = new Thread(() -> {
            AdbProxyResultHolder result = new AdbProxyResultHolder();
            try {
                result.result = LocalAdbClient.proxyAdbConnect(
                        appKey,
                        streams.clientToProxyIn,
                        streams.proxyToClientOut,
                        streams.adbdToProxyIn,
                        streams.proxyToAdbdOut);
            } catch (Throwable failure) {
                result.failure = failure;
            } finally {
                holder.set(result);
            }
        }, "adb-proxy");
        proxy.start();
        return proxy;
    }

    private static final class AdbProxyResultHolder {
        LocalAdbClient.AdbProxyResult result;
        Throwable failure;
    }

    /** The four duplex streams a proxy sits between: client <-> proxy <-> adbd. */
    private static final class Streams {
        final PipedInputStream clientToProxyIn = new PipedInputStream(1 << 16);
        final PipedOutputStream clientToProxyOut;
        final PipedInputStream proxyToClientIn = new PipedInputStream(1 << 16);
        final PipedOutputStream proxyToClientOut;
        final PipedInputStream proxyToAdbdIn = new PipedInputStream(1 << 16);
        final PipedOutputStream proxyToAdbdOut;
        final PipedInputStream adbdToProxyIn = new PipedInputStream(1 << 16);
        final PipedOutputStream adbdToProxyOut;

        Streams() {
            try {
                clientToProxyOut = new PipedOutputStream(clientToProxyIn);
                proxyToClientOut = new PipedOutputStream(proxyToClientIn);
                proxyToAdbdOut = new PipedOutputStream(proxyToAdbdIn);
                adbdToProxyOut = new PipedOutputStream(adbdToProxyIn);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        }

        void closeAll() {
            for (java.io.Closeable c : new java.io.Closeable[] {
                    clientToProxyIn, clientToProxyOut, proxyToClientIn, proxyToClientOut,
                    proxyToAdbdIn, proxyToAdbdOut, adbdToProxyIn, adbdToProxyOut}) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static boolean signatureMatches(PublicKey publicKey, byte[] token, byte[] signature)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, publicKey);
        byte[] block = cipher.doFinal(signature);
        byte[] expectedTail = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, expectedTail, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, expectedTail, SHA1_DIGEST_INFO_PREFIX.length, token.length);
        byte[] actualTail = Arrays.copyOfRange(block, block.length - expectedTail.length, block.length);
        if (!Arrays.equals(expectedTail, actualTail)) {
            return false;
        }
        // PKCS#1 v1.5 type-1 padding: 0x00 0x01 0xFF.. 0x00 before the DigestInfo.
        return block[0] == 0x00 && block[1] == 0x01;
    }

    private static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] randomToken() {
        byte[] token = new byte[20];
        new java.util.Random(42).nextBytes(token);
        return token;
    }

    private static int cmd(String command) {
        byte[] bytes = command.getBytes(StandardCharsets.US_ASCII);
        return (bytes[0] & 0xff)
                | ((bytes[1] & 0xff) << 8)
                | ((bytes[2] & 0xff) << 16)
                | ((bytes[3] & 0xff) << 24);
    }

    private static void writeMsg(OutputStream out, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        int checksum = 0;
        for (byte b : payload) {
            checksum += b & 0xff;
        }
        writeMsg(out, command, arg0, arg1, payload, checksum);
    }

    private static void writeMsg(OutputStream out, int command, int arg0, int arg1, byte[] payload,
            int checksum) throws IOException {
        ByteBuffer message = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(command);
        message.putInt(arg0);
        message.putInt(arg1);
        message.putInt(payload.length);
        message.putInt(checksum);
        message.putInt(command ^ 0xffffffff);
        message.put(payload);
        out.write(message.array());
        out.flush();
    }

    private static Msg readMsg(InputStream in) throws IOException {
        byte[] header = readExactly(in, 24);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buffer.getInt();
        int arg0 = buffer.getInt();
        int arg1 = buffer.getInt();
        int length = buffer.getInt();
        buffer.getInt(); // checksum
        buffer.getInt(); // magic
        byte[] payload = readExactly(in, length);
        return new Msg(command, arg0, arg1, payload);
    }

    private static byte[] readExactly(InputStream in, int count) throws IOException {
        byte[] buffer = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(buffer, offset, count - offset);
            if (read == -1) {
                throw new EOFException("Expected " + count + " bytes, got " + offset);
            }
            offset += read;
        }
        return buffer;
    }

    private static final class Msg {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        Msg(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }

        /** For an AUTH message, arg0 is the auth kind (token/signature/key). */
        int authKind() {
            return arg0;
        }
    }
}
