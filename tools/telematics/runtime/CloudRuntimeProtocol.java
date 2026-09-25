package dev.denza.tools.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict, redacted resident control plane. Vehicle protocol never crosses this parser. */
public final class CloudRuntimeProtocol {
    private static final int MAX_LINE = 4096;
    private final CloudRuntimeSupervisor supervisor;
    private final int pid;
    private long lastId;

    public CloudRuntimeProtocol(CloudRuntimeSupervisor supervisor, int pid) {
        if (supervisor == null || pid <= 0) throw new IllegalArgumentException("protocol arguments");
        this.supervisor = supervisor; this.pid = pid;
    }
    public synchronized String handle(String raw) {
        final JSONObject request = parse(raw);
        long id = requiredLong(request, "id");
        if (id <= lastId || id <= 0) throw new IllegalArgumentException("request id");
        Object opValue = request.opt("op");
        if (!(opValue instanceof String)) throw new IllegalArgumentException("request op");
        String op = (String)opValue;
        if (!Arrays.asList("PROBE", "START", "PERMIT", "STATUS", "STOP").contains(op))
            throw new IllegalArgumentException("request op");
        Set<String> keys = new HashSet<>();
        for (java.util.Iterator<String> it = request.keys(); it.hasNext();) keys.add(it.next());
        Set<String> expected = op.equals("START")
            ? new HashSet<>(Arrays.asList("id", "op", "iccid", "imsi"))
            : new HashSet<>(Arrays.asList("id", "op"));
        if(op.equals("START")||op.equals("PERMIT")||op.equals("STATUS"))
            expected.add("lease_until_uptime_ms");
        if (!keys.equals(expected)) throw new IllegalArgumentException("request keys");
        CloudRuntimeSupervisor.Identity identity = null;
        if (op.equals("START")) {
            Object iccid = request.opt("iccid"), imsi = request.opt("imsi");
            if (!(iccid instanceof String) || !(imsi instanceof String))
                throw new IllegalArgumentException("identity required");
            identity = new CloudRuntimeSupervisor.Identity((String)iccid, (String)imsi);
        }
        long ceiling=expected.contains("lease_until_uptime_ms")
            ? requiredLong(request,"lease_until_uptime_ms") : 0;
        // Only the guardian's explicit PERMIT extends the worker deadline.
        lastId = id;
        CloudRuntimeSupervisor.Snapshot state;
        boolean ok = true;
        try {
            switch (op) {
                case "PROBE": state = supervisor.probe(); break;
                case "START": supervisor.initialLease(ceiling); state = supervisor.start(identity); break;
                case "PERMIT": supervisor.renewLease(ceiling); state = supervisor.snapshot(); break;
                case "STOP": state = supervisor.stop(); break;
                default: supervisor.observeLease(ceiling); state = supervisor.snapshot(); break;
            }
        } catch (Exception failure) {
            // Keep arbitrary exception text, including possible identifiers, off stdout.
            ok = false; state = supervisor.snapshot();
        }
        return response(id, op, ok, state);
    }
    private static JSONObject parse(String raw) {
        if (raw == null || raw.indexOf('\n') >= 0 || raw.indexOf('\0') >= 0 ||
            utf8Length(raw) > MAX_LINE) throw new IllegalArgumentException("request bound");
        assertFlatUniqueKeys(raw);
        try { return new JSONObject(raw); }
        catch (Exception bad) { throw new IllegalArgumentException("request JSON"); }
    }
    /** Android's bundled JSONObject may replace duplicate keys; reject them before parsing. */
    static void assertFlatUniqueKeys(String raw) {
        int i = skipSpace(raw, 0), n = raw.length();
        if (i >= n || raw.charAt(i++) != '{') throw new IllegalArgumentException("request object");
        Set<String> seen = new HashSet<>();
        i = skipSpace(raw, i);
        if (i < n && raw.charAt(i) == '}') {
            if (skipSpace(raw, i + 1) == n) return;
            throw new IllegalArgumentException("request trailing content");
        }
        for (;;) {
            i = skipSpace(raw, i);
            if (i >= n || raw.charAt(i++) != '"') throw new IllegalArgumentException("request key");
            int start = i;
            while (i < n && raw.charAt(i) != '"') {
                char c = raw.charAt(i++);
                if (!((c >= 'a' && c <= 'z') || c == '_')) throw new IllegalArgumentException("request key");
            }
            if (i >= n || !seen.add(raw.substring(start, i))) throw new IllegalArgumentException("duplicate request key");
            i = skipSpace(raw, i + 1);
            if (i >= n || raw.charAt(i++) != ':') throw new IllegalArgumentException("request colon");
            i = skipSpace(raw, i);
            if (i >= n) throw new IllegalArgumentException("request value");
            if (raw.charAt(i) == '"') {
                i++;
                boolean ended = false;
                while (i < n) {
                    char c = raw.charAt(i++);
                    if (c == '\\') { if (i >= n) break; i++; }
                    else if (c == '"') { ended = true; break; }
                    else if (c < 0x20) throw new IllegalArgumentException("request string");
                }
                if (!ended) throw new IllegalArgumentException("request string");
            } else {
                int valueStart = i;
                while (i < n && raw.charAt(i) != ',' && raw.charAt(i) != '}') {
                    char c = raw.charAt(i++);
                    if (c == '{' || c == '[' || c == ']' || c == '"')
                        throw new IllegalArgumentException("request nested value");
                }
                if (!raw.substring(valueStart, i).trim().matches("-?(0|[1-9][0-9]*)"))
                    throw new IllegalArgumentException("request numeric value");
            }
            i = skipSpace(raw, i);
            if (i >= n) throw new IllegalArgumentException("request end");
            char separator = raw.charAt(i++);
            if (separator == '}') {
                if (skipSpace(raw, i) != n) throw new IllegalArgumentException("request trailing content");
                return;
            }
            if (separator != ',') throw new IllegalArgumentException("request separator");
        }
    }
    private static int skipSpace(String raw, int i) {
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            i++;
        }
        return i;
    }
    private static int utf8Length(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            return bytes.remaining();
        } catch (CharacterCodingException bad) { throw new IllegalArgumentException("request UTF-8"); }
    }
    private static long requiredLong(JSONObject value, String key) {
        Object number = value.opt(key);
        if (!(number instanceof Number)) throw new IllegalArgumentException("request id type");
        try { return Long.parseLong(number.toString()); }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("request id integral"); }
    }
    private String response(long id, String op, boolean ok, CloudRuntimeSupervisor.Snapshot state) {
        try {
        JSONObject result = new JSONObject();
        result.put("id", id).put("op", op).put("protocol", 1).put("ok", ok)
            .put("pid", pid).put("session_live", state.sessionLive)
            .put("stage", state.stage.name().toLowerCase(Locale.ROOT))
            .put("code", (ok ? state.code.name() : "OPERATION_REJECTED").toLowerCase(Locale.ROOT))
            .put("updated_elapsed_ms", state.updatedMs)
            .put("connected_elapsed_ms", state.connectedMs)
            .put("last_rx_elapsed_ms", state.lastRxMs)
            .put("last_tx_elapsed_ms", state.lastTxMs)
            .put("last_report_elapsed_ms", state.lastReportMs)
            .put("next_retry_elapsed_ms", state.nextRetryMs)
            .put("attempts", state.attempts).put("reports_sent", state.reports)
            .put("status_replies", state.statusReplies)
            .put("commands_forwarded", state.commandsForwarded)
            .put("commands_completed", state.commandsCompleted)
            .put("reconnects", state.reconnects).put("callback_age_ms", state.callbackAgeMs);
        JSONArray events = new JSONArray();
        for (CloudRuntimeSupervisor.Event event : state.events) {
            events.put(new JSONObject().put("seq", event.sequence).put("t_ms", event.atMs)
                .put("event", event.code.name().toLowerCase(Locale.ROOT)).put("value", 0));
        }
        result.put("native_events", events);
        return result.toString();
        } catch (JSONException impossible) { throw new IllegalStateException("response serialization"); }
    }
    /** One READY per process; each valid request receives BEGIN, JSON, END ok. */
    public void serve(InputStream input, PrintStream output, String nonce) throws IOException {
        if (input == null || output == null || nonce == null || !nonce.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("resident arguments");
        String marker = "DENZA_SERVE_" + nonce;
        try {
            output.print(marker + ":READY\n"); output.flush();
            String line;
            while ((line = readLine(input)) != null) {
                String answer;
                try { answer = handle(line); }
                catch (IllegalArgumentException malformed) { return; }
                output.print(marker + ":BEGIN\n");
                output.print(answer); output.print('\n');
                output.print(marker + ":END ok\n"); output.flush();
                if (output.checkError()) throw new IOException("resident output failed");
            }
        } finally { supervisor.closeAndAwait(); }
    }
    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            int next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) return null;
                throw new IOException("partial resident line");
            }
            if (next == '\n') break;
            if (bytes.size() >= MAX_LINE) throw new IOException("oversized resident line");
            bytes.write(next);
        }
        try { return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("malformed resident UTF-8"); }
    }
}
