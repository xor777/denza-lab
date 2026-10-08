package dev.denza.disharebridge;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.denza.disharebridge.LocalAdbTlsClient.*;
import static org.junit.Assert.*;

public class LocalAdbTlsClientTest {
    @Test public void stlsUpgradesBeforeOpeningTcpipAndAcceptsRestartEof() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        AtomicBoolean upgraded = new AtomicBoolean();
        exchange(packets(STLS, STLS_VERSION, 0), sent, (in, out) -> {
            upgraded.set(true);
            assertEquals(Arrays.asList(CNXN, STLS), commands(sent));
            return new Streams(packets(CNXN, VERSION, MAX_PAYLOAD, OKAY, 42, 1), out);
        }, token -> { throw new AssertionError("No classic AUTH expected"); }, () -> false);
        assertTrue(upgraded.get());
        assertEquals(Arrays.asList(CNXN, STLS, OPEN), commands(sent));
        assertTrue(new String(sent.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII)
                .contains("tcpip:5555\0"));
    }

    @Test public void outputIsAcknowledgedAndStreamClosed() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        exchange(packets(STLS, STLS_VERSION, 0), sent, (in, out) -> new Streams(
                packets(CNXN, VERSION, MAX_PAYLOAD, OKAY, 42, 1, WRTE, 42, 1, CLSE, 42, 1), out),
                token -> new byte[256], () -> false);
        assertEquals(Arrays.asList(CNXN, STLS, OPEN, OKAY, CLSE), commands(sent));
    }

    @Test public void unknownKeyNeverSubmitsItsPublicKey() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try {
            exchange(packets(AUTH, 1, 0, AUTH, 1, 0), sent,
                    (in, out) -> { throw new AssertionError(); }, token -> new byte[256], () -> false);
            fail();
        } catch (IOException expected) {
            assertEquals(Arrays.asList(CNXN, AUTH), commands(sent));
            ByteArrayInputStream bytes = new ByteArrayInputStream(sent.toByteArray());
            read(bytes);
            assertEquals(2, read(bytes).arg0); // signature; never AUTH type 3
        }
    }

    @Test public void cancellationAfterTlsNeverSendsTcpip() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try {
            exchange(packets(STLS, STLS_VERSION, 0), sent, (in, out) -> {
                cancelled.set(true);
                return new Streams(packets(CNXN, VERSION, MAX_PAYLOAD), out);
            }, token -> new byte[256], cancelled::get);
            fail();
        } catch (InterruptedIOException expected) {
            assertEquals(Arrays.asList(CNXN, STLS), commands(sent));
        }
    }

    @Test public void eofBeforeOpenIsHandshakeFailure() throws Exception {
        try {
            exchange(packets(STLS, STLS_VERSION, 0), new ByteArrayOutputStream(),
                    (in, out) -> new Streams(packets(), out), token -> new byte[256], () -> false);
            fail();
        } catch (EOFException expected) { }
    }

    @Test public void certificateWrapsAndVerifiesWithTheSameIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        X509Certificate certificate = AdbCertificate.create(key, System.currentTimeMillis());
        certificate.checkValidity();
        certificate.verify(key.getPublic());
        assertArrayEquals(key.getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate.getEncoded()));
        assertArrayEquals(certificate.getEncoded(), parsed.getEncoded());
    }

    @Test public void discoveryRejectsRemoteAndUnspecifiedHosts() throws Exception {
        assertTrue(isLocalAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(isLocalAddress(InetAddress.getByName("::1")));
        assertFalse(isLocalAddress(InetAddress.getByName("0.0.0.0")));
        assertFalse(isLocalAddress(InetAddress.getByName("192.0.2.25")));
    }

    private static ByteArrayInputStream packets(int... values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < values.length; i += 3) write(bytes, values[i], values[i+1], values[i+2], new byte[0]);
        return new ByteArrayInputStream(bytes.toByteArray());
    }
    private static List<Integer> commands(ByteArrayOutputStream sent) throws Exception {
        ByteArrayInputStream bytes = new ByteArrayInputStream(sent.toByteArray());
        List<Integer> commands = new ArrayList<>();
        while (bytes.available() > 0) commands.add(read(bytes).command);
        return commands;
    }
}
