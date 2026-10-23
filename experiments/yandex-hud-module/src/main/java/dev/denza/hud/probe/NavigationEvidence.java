package dev.denza.hud.probe;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import java.io.File;
import java.util.Map;

/** Bounded emulator-only input recorder for the local ADAS converter; never publishes to a vehicle. */
final class NavigationEvidence {
    /** Common identity for every evidence file written by this Navigator process. */
    static final String SOURCE_SESSION_ID = java.util.UUID.randomUUID().toString();
    private final boolean enabled = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private final File directory;
    private final Handler worker;
    private final GuidanceSnapshot source;
    private final Reflect api;
    private long nextAt, sequence, generation;
    private String state = "";
    private volatile String archiveRoute;
    private String archiveAttemptRoute;
    private long archiveAttemptAt;
    private volatile boolean pending;

    NavigationEvidence(Context context, Reflect api, Handler worker) {
        this.directory = context.getFilesDir(); this.source = new GuidanceSnapshot(api); this.worker = worker; this.api = api;
    }

    void sample(NavigatorSource.Frame frame, long now) {
        if (!enabled || pending) return;
        try {
            // Observe cancellation immediately even between periodic samples.
            String mode = frame.mode;
            if (now < nextAt && state.startsWith(mode + ":")) return;
            Map<String, Object> value = source.read(frame, now, System.currentTimeMillis());
            Object id = value.get("routeId");
            final RouteArchive archive;
            if (id instanceof String && !id.equals(archiveRoute)
                    && (!id.equals(archiveAttemptRoute) || now - archiveAttemptAt >= 10_000)) {
                archiveAttemptRoute = (String) id;
                archiveAttemptAt = now;
                archive = RouteArchive.read(frame.route, (String) id, api);
            } else archive = null;
            String key = mode + ":" + value.get("routeId");
            if (!key.equals(state)) { state = key; generation++; }
            nextAt = now + 1000;
            value.put("generation", generation);
            value.put("sequence", sequence++);
            value.put("sourceSessionId", SOURCE_SESSION_ID);
            final long slot = ((Number) value.get("sequence")).longValue() % 120;
            pending = true;
            if (!worker.post(() -> {
                try {
                    if (archive != null) {
                        // A transient serializer or write failure must not poison the success cache.
                        // Keep progress independent of archive availability and retry after ten seconds.
                        try {
                            archive.write(directory);
                            if (archive.bytes != null) archiveRoute = (String) id;
                        } catch (Exception error) { Log.w("DenzaYandexHud", "route archive write failed", error); }
                    }
                    RouteSnapshot.writeAtomic(new File(directory, "denza-adas-progress.json"), value);
                    File ring = new File(directory, "denza-adas-progress");
                    if (!ring.isDirectory() && !ring.mkdir()) throw new java.io.IOException("Cannot create evidence directory");
                    RouteSnapshot.writeAtomic(new File(ring, String.format("sample-%03d.json", slot)), value);
                } catch (Exception error) { Log.w("DenzaYandexHud", "guidance evidence failed", error); }
                finally { pending = false; }
            })) pending = false;
        } catch (RuntimeException error) { Log.w("DenzaYandexHud", "guidance copy failed", error); }
    }
}
