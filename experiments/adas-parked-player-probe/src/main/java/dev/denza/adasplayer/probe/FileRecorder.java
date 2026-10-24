package dev.denza.adasplayer.probe;

import android.util.Base64;
import java.io.PrintStream;

/** Writes one JSON object per line to the run file: every service start/stop, fire result, ADAS
 * answer (service 13/44) and note, each with elapsedRealtime and wall time. Thread-safe. */
final class FileRecorder implements Recorder {
    private final PrintStream out;
    private final String scenarioId;

    FileRecorder(PrintStream out, String scenarioId) { this.out = out; this.scenarioId = scenarioId; }

    public synchronized void service(String op, long topic, int result, long elapsedMs, long wallMs) {
        line("service", "\"op\":\"" + op + "\",\"topic\":" + topic + ",\"result\":" + result
                + ",\"elapsedMs\":" + elapsedMs + ",\"wallMs\":" + wallMs);
    }

    public synchronized void fire(String label, long topic, int result, long elapsedMs, long wallMs) {
        line("fire", "\"label\":\"" + esc(label) + "\",\"topic\":" + topic + ",\"result\":" + result
                + ",\"elapsedMs\":" + elapsedMs + ",\"wallMs\":" + wallMs);
    }

    public synchronized void answer(long topic, byte[] payload, long elapsedMs, long wallMs) {
        line("answer", "\"topic\":" + topic + ",\"bytes\":" + payload.length
                + ",\"base64\":\"" + Base64.encodeToString(payload, Base64.NO_WRAP) + "\""
                + ",\"elapsedMs\":" + elapsedMs + ",\"wallMs\":" + wallMs);
    }

    public synchronized void note(String event, String detail) {
        line("note", "\"event\":\"" + esc(event) + "\",\"detail\":\"" + esc(detail) + "\"");
    }

    private void line(String kind, String body) {
        out.println("{\"kind\":\"" + kind + "\",\"scenario\":\"" + esc(scenarioId) + "\"," + body + "}");
        out.flush();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
