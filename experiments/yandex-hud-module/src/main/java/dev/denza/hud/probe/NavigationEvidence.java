package dev.denza.hud.probe;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import java.io.File;
import java.util.Map;

/**
 * Bounded emulator-only input recorder for the local ADAS converter; never publishes to a vehicle.
 * Its decisions (generation, sequence, slot, archive retry, back-pressure) are {@link EvidencePolicy}'s.
 */
final class NavigationEvidence {
    /** Common identity for every evidence file written by this Navigator process. */
    static final String SOURCE_SESSION_ID = java.util.UUID.randomUUID().toString();
    private final boolean enabled = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private final File directory;
    private final Handler worker;
    private final GuidanceSnapshot source;
    private final Reflect api;
    private final EvidencePolicy policy = new EvidencePolicy();

    NavigationEvidence(Context context, Reflect api, Handler worker) {
        this.directory = context.getFilesDir(); this.source = new GuidanceSnapshot(api); this.worker = worker; this.api = api;
    }

    void sample(NavigatorSource.Frame frame, long now) {
        if (!enabled) return;
        try {
            // Observe cancellation immediately even between periodic samples.
            if (!policy.due(frame.mode, now)) return;
            Map<String, Object> value = source.read(frame, android.os.SystemClock.elapsedRealtime(),
                    System.currentTimeMillis(), android.os.SystemClock.uptimeMillis());
            Object id = value.get("routeId");
            final String routeId = id instanceof String ? (String) id : null;
            EvidencePolicy.Decision decision = policy.take(frame.mode, routeId, now);
            final RouteArchive archive = decision.archive ? RouteArchive.read(frame.route, routeId, api) : null;
            value.put("generation", decision.generation);
            value.put("sequence", decision.sequence);
            value.put("sourceSessionId", SOURCE_SESSION_ID);
            final int slot = decision.slot;
            if (!worker.post(() -> {
                try {
                    if (archive != null) {
                        // A transient serializer or write failure must not poison the success cache.
                        // Keep progress independent of archive availability and retry after ten seconds.
                        boolean saved = false;
                        try {
                            archive.write(directory);
                            saved = archive.bytes != null;
                        } catch (Exception error) { Log.w("DenzaYandexHud", "route archive write failed", error); }
                        policy.archived(routeId, saved, archive.unsupported);
                    }
                    RouteSnapshot.writeAtomic(new File(directory, "denza-adas-progress.json"), value);
                    File ring = new File(directory, "denza-adas-progress");
                    if (!ring.isDirectory() && !ring.mkdir()) throw new java.io.IOException("Cannot create evidence directory");
                    RouteSnapshot.writeAtomic(new File(ring, String.format("sample-%03d.json", slot)), value);
                } catch (Exception error) { Log.w("DenzaYandexHud", "guidance evidence failed", error); }
                finally { policy.done(); }
            })) policy.done();
        } catch (RuntimeException error) {
            policy.done();
            Log.w("DenzaYandexHud", "guidance copy failed", error);
        }
    }
}
