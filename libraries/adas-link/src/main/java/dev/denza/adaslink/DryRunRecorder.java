package dev.denza.adaslink;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The default publisher: nothing leaves the process. Every message the feed would fire becomes a
 * JSONL line {@code {"kind":"fire","monotonicMs","service","event","topic","name","payloadHex",
 * "decoded"}}, inputs and state changes {@code {"kind":"input"|"state",...}}; the bench reads the
 * fire lines. The writer is the caller's (a bounded file on the device, memory in tests).
 */
public final class DryRunRecorder implements Publisher {
    public static final String SCHEMA = "denza-adas-dry-run-v1";
    private final Writer out;
    private final List<Map<String, Object>> memory;
    private int fires;

    /** Writes JSONL to {@code out}; when {@code keep}, also keeps the records for tests. */
    public DryRunRecorder(Writer out, boolean keep) {
        this.out = out;
        this.memory = keep ? new ArrayList<Map<String, Object>>() : null;
    }

    @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.DRY_RUN; }

    @Override public int startService(long serviceTopic) {
        Map<String, Object> f = Json.object();
        f.put("serviceTopic", serviceTopic);
        f.put("service", Topics.service(serviceTopic));
        note("startService", f, -1);
        return 0;
    }

    @Override public int fire(long topic, byte[] payload, long monotonicMs) {
        Map<String, Object> r = Json.object();
        r.put("kind", "fire");
        r.put("monotonicMs", monotonicMs);
        r.put("service", (long) Topics.service(topic));
        r.put("event", String.format("0x%04x", Topics.id(topic)));
        r.put("topic", topic);
        r.put("name", Topics.name(topic));
        r.put("payloadHex", Pb.hex(payload));
        Pb.Msg schema = Topics.schema(topic);
        Object decoded;
        try { decoded = schema == null ? null : schema.decode(payload); }
        catch (RuntimeException e) { decoded = "undecodable: " + e.getMessage(); }
        r.put("decoded", decoded);
        write(r);
        fires++;
        return 0;
    }

    @Override public void note(String kind, Map<String, Object> fields, long monotonicMs) {
        Map<String, Object> r = Json.object();
        r.put("kind", kind.equals("state") || kind.equals("header") ? kind : "input");
        if (!kind.equals("state") && !kind.equals("header")) r.put("input", kind);
        if (monotonicMs >= 0) r.put("monotonicMs", monotonicMs);
        r.putAll(fields);
        write(r);
    }

    public int fires() { return fires; }

    public List<Map<String, Object>> records() { return memory; }

    private void write(Map<String, Object> record) {
        if (memory != null) memory.add(record);
        if (out == null) return;
        try {
            out.write(Json.write(record));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("dry-run trace write failed", e);
        }
    }
}
