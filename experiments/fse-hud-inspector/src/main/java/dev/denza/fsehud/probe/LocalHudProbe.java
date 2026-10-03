package dev.denza.fsehud.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Session mutations run on main. Immutable report snapshots are written on a separate worker. */
final class LocalHudProbe {
    static final long DURATION_MS = 20_000;
    private static final String REPORT = "local-hud-last.json";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private static Session current;

    private LocalHudProbe() { }

    static void start(Activity control) {
        if (current != null && current.active) return;
        Session session = new Session(control);
        current = session;
        try {
            session.event("preflight", "fingerprint", Build.FINGERPRINT, "sdk", Build.VERSION.SDK_INT);
            if (!Build.FINGERPRINT.startsWith("BYD-AUTO/FSE/FSE:"))
                throw new IllegalStateException("Тест запускается только на FSE");
            Display selected = null;
            for (Display d : session.displays.getDisplays()) {
                session.event("display_inventory", "display", describe(d));
                if ("arhud".equalsIgnoreCase(d.getName())) {
                    if (selected != null) throw new IllegalStateException("Найдено несколько arhud");
                    selected = d;
                }
            }
            // 0x80 is FLAG_TRUSTED in the inspected FSE framework (not a public SDK constant).
            if (selected == null || !selected.isValid() || selected.getDisplayId() == 0 ||
                    selected.getState() != Display.STATE_ON ||
                    (selected.getFlags() & Display.FLAG_PRIVATE) != 0 || (selected.getFlags() & 0x80) == 0)
                throw new IllegalStateException("Нет активного публичного доверенного arhud");
            session.displayId = selected.getDisplayId();
            Intent intent = new Intent(control, LocalHudActivity.class)
                    .putExtra("session", session.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            boolean allowed = control.getSystemService(ActivityManager.class)
                    .isActivityStartAllowedOnDisplay(control, session.displayId, intent);
            session.event("launch_check", "allowed", allowed, "requested_display", describe(selected));
            if (!allowed) throw new IllegalStateException("Android запретил запуск на arhud");
            session.deadline = SystemClock.elapsedRealtime() + DURATION_MS;
            session.displays.registerDisplayListener(session, MAIN);
            session.listening = true;
            MAIN.postDelayed(session.expire, DURATION_MS);
            session.event("launch_requested", "deadline_elapsed_ms", session.deadline);
            control.startActivity(intent, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(session.displayId).toBundle());
            session.event("start_activity_returned");
        } catch (Exception e) {
            session.event("launch_error", "type", e.getClass().getName(), "message", e.getMessage());
            session.finish("launch_failed");
        }
    }

    static Session attach(LocalHudActivity activity, String id) {
        if (current == null || !current.active || !current.id.equals(id) ||
                SystemClock.elapsedRealtime() >= current.deadline) return null;
        current.activity = new WeakReference<>(activity);
        return current;
    }

    static void stop(String reason) { if (current != null) current.finish(reason); }

    static String summary() {
        if (current == null) return "HUD ещё не запущен. Предыдущий отчёт можно экспортировать.";
        return (current.active ? "Тест идёт" : "Завершён: " + current.reason) +
                " · display=" + current.displayId + " · draw=" + current.draws +
                " · window frames=" + current.windowFrames +
                (current.error == null ? "" : "\n" + current.error);
    }

    static JSONObject describe(Display display) {
        if (display == null) return json("missing", true);
        DisplayMetrics size = new DisplayMetrics();
        display.getRealMetrics(size);
        return json("id", display.getDisplayId(), "name", display.getName(),
                "valid", display.isValid(), "state", display.getState(), "flags", display.getFlags(),
                "width", size.widthPixels, "height", size.heightPixels,
                "mode_width", display.getMode().getPhysicalWidth(),
                "mode_height", display.getMode().getPhysicalHeight(),
                "hz", display.getMode().getRefreshRate(), "rotation", display.getRotation());
    }

    static void export(Activity control) {
        Context app = control.getApplicationContext();
        String snapshot = current == null ? null : current.snapshot();
        boolean live = current != null && current.active;
        FILES.execute(() -> {
            Uri uri = null;
            try {
                JSONObject report = snapshot == null ? new JSONObject(new String(
                        new AtomicFile(new File(app.getFilesDir(), REPORT)).readFully(), StandardCharsets.UTF_8))
                        : new JSONObject(snapshot);
                identify(app, report);
                report.put("session_live_in_exporting_process", live);
                report.put("export_epoch_ms", System.currentTimeMillis());
                ProbeApplication.append(app, report);
                String name = "fse-local-hud-" + System.currentTimeMillis() + ".json";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/FseHudInspector");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
                try (OutputStream stream = app.getContentResolver().openOutputStream(uri)) {
                    if (stream == null) throw new IllegalStateException("No export stream");
                    stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                if (app.getContentResolver().update(uri, done, null, null) != 1)
                    throw new IllegalStateException("MediaStore finalize failed");
                toast(app, "Сохранено: FseHudInspector/" + name);
            } catch (Exception e) {
                if (uri != null) {
                    try { app.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
                toast(app, "Экспорт HUD: " + e.getMessage());
            }
        });
    }

    private static void identify(Context app, JSONObject report) throws Exception {
        var info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try (FileInputStream in = new FileInputStream(app.getApplicationInfo().sourceDir)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) { digest.update(buffer, 0, n); bytes += n; }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        report.put("exporting_package", json("name", app.getPackageName(), "version", info.versionName,
                "version_code", info.getLongVersionCode(), "sha256", hex.toString(), "bytes", bytes));
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            try { result.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]); }
            catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
        }
        return result;
    }

    static final class Session implements DisplayManager.DisplayListener {
        final String id = UUID.randomUUID().toString();
        final Context app;
        final DisplayManager displays;
        final JSONArray events = new JSONArray();
        final long started = SystemClock.elapsedRealtime();
        final long epoch = System.currentTimeMillis();
        final Runnable expire = () -> finish("timeout_20_seconds");
        WeakReference<LocalHudActivity> activity = new WeakReference<>(null);
        boolean active = true;
        boolean listening;
        int displayId = -1;
        int draws;
        int windowFrames;
        long deadline;
        String reason;
        String error;

        Session(Context context) {
            app = context.getApplicationContext();
            displays = app.getSystemService(DisplayManager.class);
        }

        void event(String name, Object... values) {
            JSONObject data = json(values);
            try {
                data.put("event", name);
                data.put("epoch_ms", System.currentTimeMillis());
                data.put("elapsed_ms", SystemClock.elapsedRealtime());
                data.put("draw_calls", draws);
                data.put("window_frames", windowFrames);
                events.put(data);
                if ("launch_error".equals(name)) error = data.optString("message");
            } catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
            // Late onDestroy from an older task must not replace a newer experiment's report.
            if (current != this) return;
            String snapshot = snapshot();
            FILES.execute(() -> {
                AtomicFile file = new AtomicFile(new File(app.getFilesDir(), REPORT));
                FileOutputStream out = null;
                try {
                    out = file.startWrite();
                    out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    file.finishWrite(out);
                } catch (Exception e) {
                    if (out != null) file.failWrite(out);
                    android.util.Log.e("FseLocalHud", "save failed", e);
                }
            });
        }

        String snapshot() {
            return json("schema", 1, "probe_version", "0.2.1", "kind", "local_arhud_activity",
                    "session", id, "fingerprint", Build.FINGERPRINT, "uid", android.os.Process.myUid(),
                    "pid", android.os.Process.myPid(),
                    "started_epoch_ms", epoch, "started_elapsed_ms", started,
                    "requested_display_id", displayId, "deadline_elapsed_ms", deadline,
                    "active", active, "end_reason", reason, "draw_calls", draws,
                    "window_frames", windowFrames, "physical_visibility", "requires_owner_observation",
                    "events", events).toString();
        }

        void finish(String why) {
            if (!active) return;
            active = false;
            reason = why;
            MAIN.removeCallbacks(expire);
            if (listening) { displays.unregisterDisplayListener(this); listening = false; }
            event("session_finished", "reason", why);
            LocalHudActivity renderer = activity.get();
            if (renderer != null) renderer.closeOwnTask();
        }

        @Override public void onDisplayAdded(int id) { event("display_added", "id", id); }
        @Override public void onDisplayRemoved(int id) {
            event("display_removed", "id", id);
            if (id == displayId) finish("display_removed");
        }
        @Override public void onDisplayChanged(int id) {
            event("display_changed", "display", describe(displays.getDisplay(id)));
            if (id == displayId) {
                Display d = displays.getDisplay(id);
                if (d == null || !d.isValid() || d.getState() != Display.STATE_ON)
                    finish("display_not_on");
            }
        }
    }
}
