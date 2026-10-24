package dev.denza.hud.probe;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import dev.denza.adaslink.VectorTilePacket;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/** Receives actual Yandex vector decodes, including requests from our owned offscreen window. */
final class YandexVectorSource {
    private static volatile YandexVectorSource instance;
    static synchronized void start(Context context) {
        if (instance == null) instance = new YandexVectorSource(context.getApplicationContext());
    }
    static VectorRoadTileCache.Snapshot snapshot() {
        YandexVectorSource current = instance;
        // Do not warm the SDK's decoded cache before our observer can see its input bytes.
        return current == null || !current.installed ? null : current.cache.snapshot();
    }
    private final Context context;
    private final Handler worker;
    private final VectorRoadTileCache cache = new VectorRoadTileCache(32, 32L << 20);
    private volatile boolean installed;
    private long retryAt, reportedAt, received, rejected, lastTileAt;
    private long evidenceAt;
    private final boolean emulator = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private String lastTileHash;
    private String lastError;

    private YandexVectorSource(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("denza-yandex-vector", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper()); worker.post(this::tick);
    }
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        try {
            if (!installed && now >= retryAt) {
                retryAt = now + 5000;
                installed = NativeVectorCollector.start(context);
            }
            if (installed) for (int i = 0; i < 2; i++) {
                byte[] bytes = NativeVectorCollector.poll();
                if (bytes == null) break;
                received++;
                try {
                    VectorTilePacket packet = VectorTilePacket.decode(bytes);
                    VectorRoadTile tile = cache.put(packet.protobuf, packet.wrapper, packet.render, packet.source, packet.sequence);
                    if (tile == null) rejected++; else {
                        lastTileAt = now; lastTileHash = tile.sha256();
                        // One bounded envelope for local decoder reproduction, never an accumulating dump.
                        if (emulator && now - evidenceAt >= 5000) {
                            evidenceAt = now;
                            File target = new File(context.getFilesDir(), "denza-yandex-vector-latest.yvt");
                            File temporary = new File(context.getFilesDir(), "denza-yandex-vector-latest.tmp");
                            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); }
                            if (!temporary.renameTo(target)) throw new java.io.IOException("vector evidence rename");
                        }
                    }
                } catch (RuntimeException error) {
                    rejected++; lastError = error.toString();
                    Log.w("DenzaYandexVector", "vector tile rejected", error);
                }
            }
            if (now - reportedAt >= 5000) {
                reportedAt = now;
                Map<String, Object> status = Json.object();
                status.put("schema", "denza-yandex-vector-source-v1");
                status.put("source", "navigator-vector-native-success");
                status.put("installed", installed); status.put("nativeStatus", NativeVectorCollector.status());
                status.put("native", NativeVectorCollector.statsMap());
                status.put("received", received); status.put("rejected", rejected);
                status.put("lastTileSha256", lastTileHash);
                status.put("lastTileAtUptimeMs", lastTileAt); status.put("capturedUptimeMs", now);
                status.put("cache", cache.summary()); status.put("lastError", lastError);
                status.put("coverage", "actual observed tiles only; requested corridor is not complete-route coverage");
                RouteSnapshot.writeAtomic(new File(context.getFilesDir(), "denza-yandex-vector-status.json"), status);
            }
        } catch (Throwable error) {
            lastError = error.toString(); Log.w("DenzaYandexVector", "vector collector step failed", error);
        } finally { worker.postDelayed(this::tick, 100); }
    }
}
