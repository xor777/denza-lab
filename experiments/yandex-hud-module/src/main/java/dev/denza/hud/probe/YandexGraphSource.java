package dev.denza.hud.probe;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import dev.denza.adaslink.GraphTile;
import dev.denza.adaslink.GraphTileCache;
import dev.denza.adaslink.Json;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/** Process-local native graph source. Parsing and evidence I/O never run in Navigator's callback. */
final class YandexGraphSource {
    private static volatile YandexGraphSource instance;
    static synchronized void start(Context context) {
        if (instance == null) instance = new YandexGraphSource(context.getApplicationContext());
        YandexVectorSource.start(context);
    }
    static GraphTileCache.Snapshot snapshot() {
        YandexGraphSource value = instance;
        return value == null ? null : value.cache.snapshot();
    }
    private final Context context;
    private final GraphTileCache cache = new GraphTileCache(16, 16L << 20);
    private final Handler worker;
    private final File directory;
    private final boolean evidence;
    private final ArrayDeque<File> saved = new ArrayDeque<>();
    private boolean installed;
    private long attemptAt, reportedAt, received, rejected, lastTileAt, maxDecodeMicros, totalDecodeMicros;
    private String lastError, lastTileSha;
    private final String session = android.os.Process.myPid()+":"+SystemClock.elapsedRealtime();
    private boolean filesLoaded;
    private long savedBytes;

    private YandexGraphSource(Context context) {
        this.context = context;
        evidence = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish")
                || new File(context.getFilesDir(), "denza-drive-capture.enabled").isFile();
        directory = new File(context.getFilesDir(), "denza-yandex-graph");
        HandlerThread thread = new HandlerThread("denza-yandex-graph", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper()); worker.post(this::tick);
    }
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        try {
            if (!installed && now >= attemptAt) {
                attemptAt = now + 5000;
                installed = NativeGraphCollector.start(context);
                if (installed) Log.i("DenzaYandexGraph", "native graph collector installed");
            }
            if (installed) for (int i=0;i<4;i++) {
                byte[] raw = NativeGraphCollector.poll();
                if (raw == null) break;
                received++;
                long begin = SystemClock.elapsedRealtimeNanos();
                try {
                    GraphTile tile = cache.put(raw,"navigator-online-graph");
                    long micros = (SystemClock.elapsedRealtimeNanos()-begin)/1000;
                    totalDecodeMicros += micros; maxDecodeMicros = Math.max(maxDecodeMicros,micros);
                    if (tile != null) { lastTileAt = now; if (evidence) save(tile,raw); }
                    else rejected++;
                } catch (RuntimeException error) {
                    rejected++; lastError = error.toString(); Log.w("DenzaYandexGraph","graph tile rejected",error);
                }
            }
            if (now-reportedAt>=5000) { reportedAt=now; report(now); }
        } catch (Throwable error) {
            lastError = error.toString(); Log.w("DenzaYandexGraph","graph collector step failed",error);
        } finally { worker.postDelayed(this::tick,100); }
    }
    private void save(GraphTile tile,byte[] raw) throws java.io.IOException {
        if(!directory.isDirectory() && !directory.mkdirs())throw new java.io.IOException("graph evidence directory");
        if (!filesLoaded) {
            File[] old = directory.listFiles((dir,name) -> name.matches("tile-[0-9a-f]{64}\\.bin"));
            if (old != null) {
                Arrays.sort(old,Comparator.comparingLong(File::lastModified));
                for (File file: old) { saved.addLast(file); savedBytes += file.length(); }
            }
            filesLoaded=true;
        }
        lastTileSha=tile.sha256();
        File file=new File(directory,"tile-"+tile.sha256()+".bin");
        if(!file.isFile()) {
            File temp=new File(directory,"tile.tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(raw);}
            if(!temp.renameTo(file))throw new java.io.IOException("graph tile rename");
            saved.addLast(file);
            savedBytes += file.length();
        }
        while(saved.size()>128 || savedBytes>(64L<<20)) {
            File old=saved.removeFirst(); long bytes=old.length();
            if(!old.delete() && old.exists()) {
                saved.addFirst(old); throw new java.io.IOException("cannot remove old graph evidence "+old);
            }
            savedBytes=Math.max(0,savedBytes-bytes);
        }
        RouteSnapshot.writeAtomic(new File(directory,"latest-tile.json"),tile.toMap());
    }
    private void report(long now) throws java.io.IOException {
        if(!directory.isDirectory() && !directory.mkdirs())throw new java.io.IOException("graph status directory");
        Map<String,Object> status=Json.object();
        status.put("schema","denza-yandex-graph-source-v1"); status.put("source","yandex-native-online");
        status.put("session",session); status.put("lastTileSha256",lastTileSha);
        status.put("evidenceEnabled",evidence); status.put("savedTileFiles",saved.size()); status.put("savedBytes",savedBytes);
        status.put("capturedUptimeMs",now); status.put("installed",installed);
        status.put("nativeStatus",NativeGraphCollector.status()); status.put("native",NativeGraphCollector.statsMap());
        status.put("received",received); status.put("rejected",rejected); status.put("lastTileAtUptimeMs",lastTileAt);
        status.put("decodeMaxUs",maxDecodeMicros); status.put("decodeMeanUs",received==0?0:totalDecodeMicros/received);
        status.put("cache",cache.summary()); status.put("lastError",lastError);
        RouteSnapshot.writeAtomic(new File(directory,"status.json"),status);
    }
}
