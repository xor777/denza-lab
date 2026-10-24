package dev.denza.hud.probe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.yandex.mapkit.offline_cache.OfflineCacheManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only SDK requestPath, with a real SDK listener rather than a reflective Proxy. */
final class YandexOfflineCachePath {
    static final long POLL_INTERVAL_MS = 5000, TIMEOUT_MS = 5000, TTL_MS = 15000;
    static final class PathSnapshot {
        final String root, reasonCode;
        final long generation, observedAt;
        final boolean ready;
        PathSnapshot(String root, long generation, long at, boolean ready, String reason) {
            this.root = root; this.generation = generation; observedAt = at;
            this.ready = ready; reasonCode = reason;
        }
        boolean ready(long now) {
            return ready && now >= observedAt && now - observedAt < TTL_MS;
        }
        Map<String,Object> status() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("schema", "denza-yandex-offline-cache-path-v1");
            m.put("ready", ready); m.put("generation", generation);
            m.put("observedAt", observedAt); m.put("reasonCode", reasonCode);
            m.put("ttlMs", TTL_MS);
            // The configured path is app-private diagnostics; do not include it in public status.
            return Collections.unmodifiableMap(m);
        }
    }
    private final Thread owner = Thread.currentThread();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PathSnapshot last = new PathSnapshot(null, 0, 0, false, "not_requested");
    private long ticket, lastRequestAt = -POLL_INTERVAL_MS;
    private Request pending;
    private Runnable timeout;
    private boolean closed;

    YandexOfflineCachePath() { owner(); }

    PathSnapshot sample(Reflect api, long now) {
        owner();
        if (closed) return last;
        if (now < 0 || now < last.observedAt || now < lastRequestAt) {
            cancel(); publish(null, now, false, "clock_invalid"); return last;
        }
        if (last.ready && !last.ready(now)) publish(null, now, false, "path_stale");
        if (pending != null || now - lastRequestAt < POLL_INTERVAL_MS) return last;
        lastRequestAt = now;
        final long requestTicket = ++ticket;
        try {
            if (api == null) throw new IllegalStateException("reflection bridge missing");
            Class<?> sdk = api.type("com.yandex.mapkit.offline_cache.OfflineCacheManager$PathGetterListener");
            if (sdk != OfflineCacheManager.PathGetterListener.class)
                throw new IllegalStateException("SDK listener classloader mismatch");
            Object manager = api.call(api.call(api.type("com.yandex.mapkit.MapKitFactory"), "getInstance"),
                    "getOfflineCacheManager");
            if (manager == null) throw new IllegalStateException("offline manager missing");
            pending = new Request(requestTicket);
            timeout = () -> finish(requestTicket, null, "path_timeout");
            main.postDelayed(timeout, TIMEOUT_MS);
            api.call(manager, "requestPath", pending);
        } catch (RuntimeException | LinkageError error) {
            if (pending == null) publish(null, SystemClock.elapsedRealtime(), false, "path_api_error");
            else finish(requestTicket, null, "path_api_error");
        }
        return last;
    }

    PathSnapshot snapshot() { owner(); return last; }
    void close() { owner(); closed = true; cancel(); publish(null, SystemClock.elapsedRealtime(), false, "closed"); }

    private final class Request implements OfflineCacheManager.PathGetterListener {
        private final long requestTicket;
        Request(long ticket) { requestTicket = ticket; }
        @Override public void onPathReceived(String path) {
            if (Thread.currentThread() == owner) finish(requestTicket, path, null);
            else main.post(() -> finish(requestTicket, path, null));
        }
    }
    private void finish(long requestTicket, String path, String error) {
        owner();
        if (requestTicket != ticket || pending == null) return;
        pending = null;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        long now = SystemClock.elapsedRealtime();
        // Handler delivery itself may be delayed: the clock, not queue ordering, owns the deadline.
        if (now < lastRequestAt) error = "clock_invalid";
        else if (now - lastRequestAt >= TIMEOUT_MS) error = "path_timeout";
        boolean valid = error == null && path != null && path.length() > 1 && path.length() <= 4096
                && path.charAt(0) == '/' && path.indexOf('\0') < 0;
        publish(valid ? path : null, now, valid, valid ? "ready" : error == null ? "path_invalid" : error);
    }
    private void cancel() {
        ++ticket; pending = null;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }
    private void publish(String root, long now, boolean ready, String reason) {
        boolean same = last.ready == ready && (root == null ? last.root == null : root.equals(last.root));
        last = new PathSnapshot(root, last.generation + (same ? 0 : 1), now, ready, reason);
    }
    private void owner() {
        if (Thread.currentThread() != owner || Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("cache path main-thread owner required");
    }
}
