package dev.denza.adsversion.probe;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/** Fixed ADS identity/root-directory reads, with an explicit guarded OEM session-3 option. */
public final class AdsDoipReadProbe {
    private static final String IDLE = "r@-1_0&w@-1_0";
    private static Socket socket;
    private static DataOutputStream output;
    private static volatile boolean restoreNeeded;
    private static Object auto;
    private static long deadline;
    private static void out(String s) { System.out.println(s); System.out.flush(); }
    private static String hex(byte[] value) {
        StringBuilder b = new StringBuilder();
        for (byte v : value) b.append(String.format(Locale.ROOT, "%02X", v & 255));
        return b.toString();
    }
    private static String prop(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
    }
    private static void state() throws Exception {
        if (!"170".equals(prop("persist.sys.AutoType")) || !IDLE.equals(prop("sys.diagmgrsrv.task_bk")))
            throw new IllegalStateException("PROFILE_OR_MANAGER_CHANGED");
        Class<?> c = auto.getClass();
        int gear = (Integer) c.getMethod("getInt", int.class, int.class).invoke(auto, 1011, 555745336);
        int brake = (Integer) c.getMethod("getInt", int.class, int.class).invoke(auto, 1011, 89129008);
        double speed = ((Number) c.getMethod("getDouble", int.class, int.class).invoke(auto, 1013, -1807745016)).doubleValue();
        out("STATIONARY gear=" + gear + " park_brake=" + brake + " speed=" + speed);
        if (gear != 1 || brake != 1 || speed != 0d) throw new IllegalStateException("NOT_STATIONARY");
    }
    private static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    private static void send(DataOutputStream stream, int type, byte[] payload) throws Exception {
        if (System.nanoTime() >= deadline) throw new IllegalStateException("DEADLINE");
        stream.writeByte(2); stream.writeByte(0xfd); stream.writeShort(type);
        stream.writeInt(payload.length); stream.write(payload); stream.flush();
        out("TX type=" + String.format(Locale.ROOT, "%04X", type) + " payload=" + hex(payload));
    }
    private static byte[] receive(DataInputStream stream, int[] type) throws Exception {
        long left = (deadline - System.nanoTime()) / 1000000;
        if (left <= 0) throw new IllegalStateException("DEADLINE");
        socket.setSoTimeout((int) Math.min(2500, left));
        if (stream.readUnsignedByte() != 2 || stream.readUnsignedByte() != 0xfd)
            throw new IllegalStateException("INVALID_PROTOCOL_HEADER");
        type[0] = stream.readUnsignedShort();
        int length = stream.readInt();
        if (length < 0 || length > 1024) throw new IllegalStateException("INVALID_LENGTH");
        byte[] result = new byte[length]; stream.readFully(result);
        out("RX type=" + String.format(Locale.ROOT, "%04X", type[0]) + " bytes=" + length);
        return result;
    }
    private static byte[] diagnosticReply(DataInputStream input) throws Exception {
        int[] type = new int[1];
        for (int i = 0; i < 2; i++) {
            byte[] body = receive(input, type);
            if (body.length < 5 || u16(body, 0) != 0x71a || u16(body, 2) != 1)
                throw new IllegalStateException("FOREIGN_RESPONSE");
            if (i == 0 && type[0] == 0x8002 && body[4] == 0) continue;
            if (type[0] != 0x8001) throw new IllegalStateException("UNEXPECTED_RESPONSE_TYPE");
            return Arrays.copyOfRange(body, 4, body.length);
        }
        throw new IllegalStateException("MISSING_DIAGNOSTIC_RESPONSE");
    }
    private static void requireDefaultSession(DataInputStream input) throws Exception {
        send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x22, (byte) 0xf1, (byte) 0x86});
        byte[] reply = diagnosticReply(input);
        if (reply.length <= 8) out("SESSION_STATE_RESPONSE " + hex(reply));
        if (!Arrays.equals(reply, new byte[]{0x62, (byte) 0xf1, (byte) 0x86, 1}))
            throw new IllegalStateException("DEFAULT_SESSION_NOT_CONFIRMED");
    }
    private static void enterExtended(DataInputStream input) throws Exception {
        requireDefaultSession(input);
        state();
        restoreNeeded = true;
        send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x10, 3});
        byte[] reply = diagnosticReply(input);
        if (reply.length < 2 || reply[0] != 0x50 || reply[1] != 3)
            throw new IllegalStateException("EXTENDED_SESSION_NOT_ACCEPTED");
        out("EXTENDED_SESSION_ACCEPTED");
    }
    private static void restoreDefault(DataInputStream input) throws Exception {
        if (!restoreNeeded) return;
        // Cleanup has its own bounded window, even if the read window expired.
        deadline = System.nanoTime() + 3000000000L;
        send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x10, 1});
        byte[] reply = diagnosticReply(input);
        if (reply.length < 2 || reply[0] != 0x50 || reply[1] != 1)
            throw new IllegalStateException("DEFAULT_SESSION_RESTORE_NOT_ACKNOWLEDGED");
        requireDefaultSession(input);
        restoreNeeded = false;
        out("DEFAULT_SESSION_RESTORED");
    }
    public static void main(String[] args) {
        boolean extended = args.length == 1 && args[0].equals("root-directory-extended");
        boolean directory = extended || (args.length == 1 && args[0].equals("root-directory"));
        if (args.length != 0 && !directory) throw new IllegalArgumentException("Unknown fixed read mode");
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
            if (restoreNeeded && output != null) {
                try {
                    // Last-resort request only; never report this as verified restoration.
                    output.write(new byte[]{2, (byte) 0xfd, (byte) 0x80, 1, 0, 0, 0, 6, 0, 1, 7, 0x1a, 0x10, 1});
                    output.flush(); out("EMERGENCY_DEFAULT_REQUEST_SENT_UNVERIFIED");
                } catch (Exception ignored) { }
            }
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
            out("HARD_TIMEOUT"); System.exit(3);
        }, "ads-doip-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        int rc = 2;
        try (RandomAccessFile file = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw")) {
            if (Process.myUid() != 2000) throw new IllegalStateException("NOT_SHELL");
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            vm.getMethod("setHiddenApiExemptions", String[].class).invoke(vm.getMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context context = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            auto = context.getSystemService("auto");
            if (auto == null) throw new IllegalStateException("AUTO_UNAVAILABLE");
            try (FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("OTHER_READER_ACTIVE");
                try {
                state();
                // Refuse an existing TCP session to ADS; do not compete for tester address 1.
                for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
                    for (String line : Files.readAllLines(Paths.get(path))) {
                        String[] fields = line.trim().split("\\s+");
                        if (fields.length > 3 && fields[2].endsWith("03C3A8C0:3458") && !fields[3].equals("06"))
                            throw new IllegalStateException("EXISTING_ADS_TCP_SESSION");
                    }
                }
                Thread.sleep(1000);
                state();
                deadline = System.nanoTime() + 7000000000L;
                socket = new Socket();
                socket.bind(new InetSocketAddress(InetAddress.getByName("192.168.195.2"), 0));
                socket.connect(new InetSocketAddress("192.168.195.3", 13400), 1800);
                out("CONNECTED local=192.168.195.2 target=192.168.195.3:13400");
                output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                // libdoip createRoutingActivationRequestMessage: protocol2, type5,
                // 11-byte body: source1, activation0, eight reserved zero bytes.
                send(output, 5, new byte[]{0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
                int[] type = new int[1];
                byte[] routing = receive(input, type);
                if (type[0] != 6 || (routing.length != 9 && routing.length != 13) || u16(routing, 0) != 1)
                    throw new IllegalStateException("ROUTING_RESPONSE_MISMATCH");
                out("ROUTING code=" + (routing[4] & 255));
                if ((routing[4] & 255) != 0x10) throw new IllegalStateException("ROUTING_NOT_ACCEPTED");
                state();
                send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x22, (byte) 0xf1, (byte) 0x95});
                boolean acknowledged = false;
                for (int i = 0; i < 2; i++) {
                    byte[] body = receive(input, type);
                    if (body.length < 5 || u16(body, 0) != 0x71a || u16(body, 2) != 1)
                        throw new IllegalStateException("FOREIGN_RESPONSE");
                    if (type[0] == 0x8002 && body[4] == 0 && !acknowledged) {
                        acknowledged = true; out("DIAGNOSTIC_ACK"); continue;
                    }
                    if (type[0] != 0x8001) throw new IllegalStateException("UNEXPECTED_RESPONSE_TYPE");
                    byte[] uds = Arrays.copyOfRange(body, 4, body.length);
                    if (Arrays.equals(uds, new byte[]{0x62, (byte) 0xf1, (byte) 0x95, (byte) 0xa1, (byte) 0xbf, 0x1a, 6, 0x1d, 0})) {
                        out("UDS " + hex(uds)); out("IDENTITY_MATCH software=4.14.07"); rc = 0; break;
                    }
                    if (uds.length == 3 && uds[0] == 0x7f && uds[1] == 0x22)
                        out("UDS_NEGATIVE nrc=" + (uds[2] & 255));
                    throw new IllegalStateException("IDENTITY_NOT_MATCHED");
                }
                if (rc == 0 && directory) {
                    try {
                    if (extended) enterExtended(input);
                    state();
                    // libuds mode5: SID, mode, big-endian path length, path;
                    // no data-format or size fields. Only the literal root path.
                    send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x38, 5, 0, 1, 0x2f});
                    byte[] reply = diagnosticReply(input);
                    if (reply.length == 3 && reply[0] == 0x7f && reply[1] == 0x38) {
                        out("DIRECTORY_NEGATIVE nrc=" + String.format(Locale.ROOT, "%02X", reply[2] & 255));
                        rc = 2;
                    } else if (reply.length >= 2 && reply[0] == 0x78 && reply[1] == 5) {
                        if (reply.length <= 64) out("DIRECTORY_METADATA " + hex(reply));
                        // No content transferred. Close only our accepted read transaction.
                        send(output, 0x8001, new byte[]{0, 1, 7, 0x1a, 0x37});
                        byte[] exit = diagnosticReply(input);
                        if (exit.length < 1 || exit[0] != 0x77)
                            throw new IllegalStateException("READ_TRANSFER_EXIT_NOT_CONFIRMED");
                        out("READ_TRANSFER_EXIT_CONFIRMED");
                    } else throw new IllegalStateException("DIRECTORY_RESPONSE_UNEXPECTED");
                    } finally {
                        restoreDefault(input);
                    }
                }
                } finally {
                    // Release the diagnostic lock only after disconnecting our socket.
                    if (socket != null) socket.close();
                }
            }
        } catch (Exception e) {
            rc = 2;
            out("STOP " + e.getClass().getSimpleName() + " " + e.getMessage());
            if (restoreNeeded) out("DEFAULT_SESSION_RESTORE_NOT_CONFIRMED");
        } finally {
            try { if (socket != null) socket.close(); out("SOCKET_CLOSED"); }
            catch (Exception e) { out("CLOSE_ERROR"); rc = 2; }
            watchdog.interrupt();
        }
        System.exit(rc);
    }
}
