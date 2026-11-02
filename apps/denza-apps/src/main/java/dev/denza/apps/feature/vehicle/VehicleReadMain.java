package dev.denza.apps.feature.vehicle;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;

import dev.denza.apps.platform.shell.ShellProxyBootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The vehicle batch's resident reader: one shell-UID process that asks {@code autoservice} over
 * Binder what the batch used to ask with one {@code service call} process per value.
 *
 * <p>Measured on the car on 2026-11-01: the shell that ran the batch, with the processes it waited
 * for, used 26.5% of one CPU, most of it the kernel's, and the system started 67-83 processes a
 * second (docs/vehicle-data-findings.md, "autoservice FID protocol"). Here a value is one
 * transaction on a binder the process already holds.
 *
 * <p>Started by {@code VehicleReaders} through {@code LocalAdbClient.ResidentSession}:
 * {@code app_process ... VehicleReadMain serve <nonce>}. It prints {@code READY}, then answers one
 * line at a time and ends with its stream (end of file on stdin) or on {@code quit}:
 *
 * <pre>
 * r 5:1007:638582792 7:1013:-1807745016 ...    one call per word: transact:device:fid
 * </pre>
 *
 * <p>The answer, between {@code BEGIN} and {@code END ok}, is what the shell batch printed: an
 * {@code @@<index>} line per call and, when the call answered, {@code Result: Parcel(<words>)} in
 * the short form {@code service call} prints, every word of the reply. {@code AutoserviceShell.parse}
 * reads both alike, sentinels included. A call that failed prints its marker alone, as a
 * {@code service call} that printed nothing did, and a dead {@code autoservice} is asked for again
 * at the next line. Only the two read transactions, {@code getInt} (5) and {@code getFloat} (7),
 * are ever sent; a line that asks for anything else is refused whole with {@code END err}.
 */
public final class VehicleReadMain {
    /** {@code getInt} and {@code getFloat}: the only transactions the batch has. */
    static final int GET_INT = 5;
    static final int GET_FLOAT = 7;
    /** More than the hot, cold and event sets together; a longer line is not the batch. */
    static final int MAX_CALLS = 64;
    static final String MARKER = "@@";

    private final String begin;
    private final String end;
    private final String ready;
    private IBinder autoService;
    private String descriptor;

    private VehicleReadMain(String nonce) {
        begin = "DENZA_SERVE_" + nonce + ":BEGIN";
        end = "DENZA_SERVE_" + nonce + ":END";
        ready = "DENZA_SERVE_" + nonce + ":READY";
    }

    public static void main(String[] args) {
        if (args.length != 2 || !"serve".equals(args[0]) || !args[1].matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("serve <hex-nonce> required");
        }
        new VehicleReadMain(args[1]).serve();
    }

    private void serve() {
        ShellProxyBootstrap.exemptHiddenApis();
        emit(ready + "\n");
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                String request = line.trim();
                if (request.isEmpty()) continue;
                if ("quit".equals(request)) break;
                int[][] calls = calls(request);
                if (calls == null) {
                    emit(begin + "\n" + end + " err REFUSED not a read batch\n");
                    continue;
                }
                StringBuilder answer = new StringBuilder();
                for (int index = 0; index < calls.length; index++) {
                    answer.append(MARKER).append(index).append('\n');
                    int[] words = read(calls[index][0], calls[index][1], calls[index][2]);
                    if (words != null) answer.append(parcel(words)).append('\n');
                }
                emit(begin + "\n" + answer + end + " ok\n");
            }
        } catch (Throwable error) {
            // The stream is the session; without it there is nobody to answer.
        }
        System.out.flush();
        System.exit(0);
    }

    /** Writes [text] whole: one answer goes out in one flush. */
    private static void emit(String text) {
        System.out.print(text);
        System.out.flush();
    }

    /**
     * The words of the reply to one call, or null when there is none to print: no
     * {@code autoservice}, a transaction it refused, a binder that died.
     */
    private int[] read(int transaction, int deviceId, int fid) {
        IBinder binder = service();
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(deviceId);
            data.writeInt(fid);
            if (!binder.transact(transaction, data, reply, 0)) return null;
            int[] words = new int[reply.dataSize() / 4];
            reply.setDataPosition(0);
            for (int i = 0; i < words.length; i++) words[i] = reply.readInt();
            return words;
        } catch (DeadObjectException dead) {
            autoService = null;
            return null;
        } catch (Exception error) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** The binder and its descriptor, asked for again after one died; null while there is none. */
    private IBinder service() {
        if (autoService != null) return autoService;
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
            if (binder == null) return null;
            descriptor = binder.getInterfaceDescriptor();
            autoService = binder;
            return binder;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * {@code r <transact>:<device>:<fid> ...} as {@code {transact, device, fid}} triples, or null
     * when the line is anything else: another verb, a word that is not three integers, a
     * transaction that is not a read, no calls or more than {@link #MAX_CALLS}.
     */
    static int[][] calls(String line) {
        String[] words = line.trim().split(" +");
        if (words.length < 2 || words.length - 1 > MAX_CALLS || !"r".equals(words[0])) return null;
        int[][] calls = new int[words.length - 1][];
        for (int i = 1; i < words.length; i++) {
            String[] parts = words[i].split(":", -1);
            if (parts.length != 3) return null;
            int[] call = new int[3];
            for (int part = 0; part < 3; part++) {
                if (!parts[part].matches("-?[0-9]{1,10}")) return null;
                long value = Long.parseLong(parts[part]);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
                call[part] = (int) value;
            }
            if (call[0] != GET_INT && call[0] != GET_FLOAT) return null;
            calls[i - 1] = call;
        }
        return calls;
    }

    /** {@code Result: Parcel(00000000 0000002b)}: the short form {@code service call} prints. */
    static String parcel(int[] words) {
        if (words.length == 0) return "Result: Parcel(NULL)";
        StringBuilder text = new StringBuilder("Result: Parcel(");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) text.append(' ');
            text.append(String.format("%08x", words[i]));
        }
        return text.append(')').toString();
    }
}
