package dev.denza.adaslink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Host tool for the ADAS bench (tools/adas_bridge/bridge_trace.py, engine "java"): runs the real
 * NavigatorDriver + StockFeed on virtual time over recorded or synthetic Navigator observations and
 * writes the dry-run JSONL of every message the feed would fire.
 *
 * Input JSON: {"frame": "wgs84"|"gcj", "acceptSimulated": bool, "tailMs": n, "roads": "store.bin" (optional),
 *              "steps": [{"t_ms": n, "sample": {denza-yandex-guidance-v1}, "snapshot": {route}|null}]}
 * Usage: ReplayTool input.json out.jsonl
 */
public final class ReplayTool {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Object> input = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8));
        StockFeed.Config config = new StockFeed.Config();
        config.frame = "wgs84".equals(input.get("frame")) ? Coordinates.Frame.WGS84 : Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        config.acceptSimulatedLocations = Boolean.TRUE.equals(input.get("acceptSimulated"));
        config.emulatedAckDelayMs = input.get("ackDelayMs") instanceof Number ? ((Number) input.get("ackDelayMs")).longValue() : 80;
        List<Object> steps = (List<Object>) input.get("steps");
        ManualScheduler clock = new ManualScheduler(0);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(args[1]), StandardCharsets.UTF_8)) {
            DryRunRecorder recorder = new DryRunRecorder(out, false);
            StockFeed feed = new StockFeed(clock, recorder, config);
            NavigatorDriver driver = new NavigatorDriver(feed, config.frame, clock);
            if (input.get("roads") instanceof String)
                driver.setRoads(new RoadStore(java.nio.ByteBuffer.wrap(Files.readAllBytes(new File((String) input.get("roads")).toPath()))));
            long start = input.get("startMs") instanceof Number ? ((Number) input.get("startMs")).longValue() : 0;
            clock.advanceTo(start);
            feed.start(true, true, true);
            clock.drain();
            long last = start;
            for (Object o : steps) {
                Map<String, Object> step = (Map<String, Object>) o;
                long now = Math.round(((Number) step.get("t_ms")).doubleValue());
                clock.advanceTo(now);
                Map<String, Object> snapshot = (Map<String, Object>) step.get("snapshot");
                YandexRoute route = snapshot == null ? null : YandexRoute.fromSnapshot(snapshot);
                driver.observe(NavigatorDriver.fromSample((Map<String, Object>) step.get("sample"), now, route));
                clock.drain();
                last = now;
            }
            long tail = input.get("tailMs") instanceof Number ? ((Number) input.get("tailMs")).longValue() : 5000;
            clock.advanceTo(last + tail);
        }
    }
}
