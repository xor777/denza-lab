package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PublicKeyRequestTest {
    @Test public void sendsOnlyOneNullTerminatedPublicKeyAfterTokenAndPersistedLatch() throws Exception {
        AtomicBoolean marked = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> daemon = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    assertEquals(PublicKeyRequest.CNXN, packet(socket).getInt(0));
                    PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.AUTH, 1, 0, new byte[20]);
                    ByteBuffer packet = packet(socket);
                    assertTrue(marked.get());
                    assertEquals(PublicKeyRequest.AUTH, packet.getInt(0));
                    assertEquals(3, packet.getInt(4));
                    byte[] payload = new byte[packet.getInt(12)];
                    packet.position(24); packet.get(payload);
                    assertTrue(new String(payload, StandardCharsets.US_ASCII).equals("test-public-key\0"));
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort());
                 PublicKeyRequest ignored = PublicKeyRequest.start(socket, "test-public-key", () -> marked.set(true))) {
                assertTrue(marked.get());
            }
            daemon.get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void unexpectedConnectionWithoutChallengeSendsNoKeyAndDoesNotMark() throws Exception {
        AtomicBoolean marked = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> daemon = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4000); packet(socket);
                    PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.CNXN, 0x01000000, 4096, new byte[0]);
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                try {
                    PublicKeyRequest.start(socket, "test-public-key", () -> marked.set(true));
                    fail("Should reject absent token");
                } catch (java.io.IOException expected) { }
            }
            daemon.get(5, TimeUnit.SECONDS);
            assertFalse(marked.get());
        }
    }

    private static ByteBuffer packet(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[24]; input.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(buffer.getInt(0) ^ -1, buffer.getInt(20));
        int size = buffer.getInt(12);
        assertTrue(size >= 0 && size <= 4096);
        byte[] payload = new byte[size]; input.readFully(payload);
        int checksum = 0; for (byte b : payload) checksum += b & 255;
        assertEquals(checksum, buffer.getInt(16));
        return ByteBuffer.allocate(24 + size).order(ByteOrder.LITTLE_ENDIAN).put(header).put(payload);
    }
}
