package dev.denza.fsehud.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.AtomicFile;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed read list plus an explicit, independent 20-second local HUD display experiment. */
public final class InspectorActivity extends Activity {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<String, Object> devices = new HashMap<>();
    private final Map<String, JSONObject> hashes = new HashMap<>();
    private ScheduledFuture<?> ticker;
    private JSONObject report;
    private JSONArray samples;
    private int readToken;
    private TextView status;
    private TextView preview;
    private TextView hudStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hudRefresh = new Runnable() {
        @Override public void run() {
            hudStatus.setText(LocalHudProbe.summary());
            ui.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("FSE HUD Inspector · 0.2.2");
        title.setTextSize(24);
        root.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("Снимок конфигурации или запись до 60 секунд. При уходе с экрана запись останавливается. Экспорт — JSON в Downloads/FseHudInspector.");
        root.addView(explanation);
        TextView hudExplanation = new TextView(this);
        hudExplanation.setText("Прямой HUD: локальная рамка и счётчик на 20 секунд. Первый тест — в P. После завершения нажмите «Экспорт HUD». Сеанс не включает видеовход автомобиля.");
        root.addView(hudExplanation);
        LinearLayout hudButtons = new LinearLayout(this);
        addButton(hudButtons, "HUD 20 с", () -> {
            stop("Чтение остановлено для прямого теста HUD");
            LocalHudProbe.start(this);
            hudStatus.setText(LocalHudProbe.summary());
        });
        addButton(hudButtons, "Стоп HUD", () -> LocalHudProbe.stop("user_stop"));
        addButton(hudButtons, "Экспорт HUD", () -> LocalHudProbe.export(this));
        root.addView(hudButtons);
        hudStatus = new TextView(this);
        root.addView(hudStatus);
        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, "Снимок", () -> start(false));
        addButton(buttons, "Запись 60 с", () -> start(true));
        addButton(buttons, "Стоп", () -> stop("Запись остановлена"));
        addButton(buttons, "Экспорт JSON", () -> worker.execute(this::export));
        root.addView(buttons);
        LinearLayout accessButtons = new LinearLayout(this);
        addButton(accessButtons, "Доступ FSE → JSON", () -> {
            stop("Считываю файлы и доступ FSE…");
            worker.execute(this::accessSnapshot);
        });
        addButton(accessButtons, "Повторить экспорт доступа", () -> worker.execute(() -> {
            try {
                byte[] data = new AtomicFile(new File(getFilesDir(), "access-last.json")).readFully();
                exportDocument(new JSONObject(new String(data, StandardCharsets.UTF_8)), "fse-access-");
            } catch (Exception e) { show("Сначала нажмите «Доступ FSE → JSON»", error(e).toString()); }
        }));
        root.addView(accessButtons);
        status = new TextView(this);
        status.setText("Готов. Чтение начнётся по кнопке.");
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        preview = new TextView(this);
        preview.setTextIsSelectable(true);
        scroll.addView(preview);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        worker.execute(() -> {
            File saved = new File(getFilesDir(), "last-report.json");
            if (!saved.isFile()) return;
            try {
                report = new JSONObject(new String(new AtomicFile(saved).readFully(), StandardCharsets.UTF_8));
                show("Загружен предыдущий отчёт; можно экспортировать", report.toString(2));
            } catch (Exception e) { show("Ошибка чтения сохранённого отчёта", error(e).toString()); }
        });
    }

    private void addButton(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void start(boolean continuous) {
        stop("Считываю конфигурацию…");
        int token = generation.get();
        if (continuous) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            if (token != generation.get()) return;
            devices.clear();
            hashes.clear();
            report = object("schema", 1, "probe_version", "0.2.2", "started_epoch_ms",
                    System.currentTimeMillis(), "started_elapsed_ms", SystemClock.elapsedRealtime(),
                    "kind", continuous ? "foreground_60_seconds" : "snapshot", "metadata", metadata());
            samples = new JSONArray();
            put(report, "samples", samples);
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            if (token != generation.get()) { persist(); return; }
            sample(token);
            if (continuous) {
                // Fixed delay avoids overlapping slow vendor Binder reads; actual times are recorded.
                runOnUiThread(() -> {
                    if (token != generation.get()) return;
                    ticker = worker.scheduleWithFixedDelay(() -> {
                        if (token != generation.get()) return;
                        if (SystemClock.elapsedRealtime() >= deadline || samples.length() >= 60) {
                            runOnUiThread(() -> {
                                if (token == generation.get()) stop("Запись завершена; экспортируйте JSON");
                            });
                        } else sample(token);
                    }, 1, 1, TimeUnit.SECONDS);
                });
            }
        });
    }

    private void sample(int token) {
        if (token != generation.get()) return;
        readToken = token;
        JSONObject data = object("epoch_ms", System.currentTimeMillis(),
                "elapsed_ms", SystemClock.elapsedRealtime(), "displays", displays());
        JSONArray signals = new JSONArray();
        read(signals, "access_type", "setting", "Setting", 0x34C00010, false);
        read(signals, "present_4c5", "setting", "Setting", 0x4C50000F, false);
        read(signals, "available_4c5", "setting", "Setting", 0x4C500014, false);
        read(signals, "present_38b", "setting", "Setting", 0x38B0003A, false);
        read(signals, "available_38b", "setting", "Setting", 0x38B00036, false);
        read(signals, "play_state", "setting", "Setting", 0x1B60A010, false);
        read(signals, "fse_hud_config", "setting", "Setting", 0x4C50000B, false);
        read(signals, "hud_type", "setting", "Setting", 0x38B00015, false);
        read(signals, "hud_on_38b", "setting", "Setting", 0x38B0001C, false);
        read(signals, "hud_on_301", "instrument", "Instrument", 0x3010001C, false);
        read(signals, "gear", "gearbox", "Gearbox", 0x21200038, false);
        read(signals, "speed", "speed", "Speed", 0x94400008, true);
        read(signals, "power", "bodywork", "Bodywork", 0x12D0002A, false);
        put(data, "signals", signals);
        put(data, "interrupted", token != generation.get());
        put(data, "completed_elapsed_ms", SystemClock.elapsedRealtime());
        samples.put(data);
        persist();
        if (token == generation.get()) {
            String count = "Сохранено отсчётов: " + samples.length();
            runOnUiThread(() -> {
                if (!isDestroyed() && token == generation.get()) {
                    status.setText(count);
                    preview.setText(data.toString());
                }
            });
        }
    }

    private void read(JSONArray into, String name, String group, String type, int fid, boolean floating) {
        if (readToken != generation.get()) return;
        JSONObject value = object("name", name, "fid", String.format("0x%08X", fid),
                "device", group, "elapsed_ms", SystemClock.elapsedRealtime(),
                "requested_type", floating ? "double" : "int");
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto." + group + ".BYDAuto" + type + "Device");
            Object device = devices.get(group);
            if (device == null) {
                device = cls.getMethod("getInstance", Context.class).invoke(null, getApplicationContext());
                if (device == null) throw new IllegalStateException("getInstance returned null");
                devices.put(group, device);
            }
            Object event = cls.getMethod("get", int[].class, Class.class)
                    .invoke(device, new int[]{fid}, floating ? Double.TYPE : Integer.TYPE);
            if (event == null) throw new IllegalStateException("get returned null");
            // FSE AbsBYDAutoDevice accepts Double.TYPE for a scalar, float[].class for arrays.
            Number raw = (Number) event.getClass().getField(floating ? "doubleValue" : "intValue").get(event);
            double number = raw.doubleValue();
            boolean invalid = !Double.isFinite(number) || (floating ?
                    number == -999999999d || number == -1.0E9 || number == (double) -2.1474826E9f :
                    raw.intValue() == -999999999 || raw.intValue() == 65535 ||
                    (raw.intValue() >= -2147482648 && raw.intValue() <= -2147482644));
            put(value, "status", invalid ? "sentinel_or_invalid" : "returned_uninterpreted");
            put(value, "raw", Double.isFinite(number) ? raw : raw.toString());
        } catch (Exception | LinkageError e) { put(value, "error", error(e)); }
        into.put(value);
    }

    private JSONObject metadata() {
        JSONObject data = object("fingerprint", Build.FINGERPRINT, "sdk", Build.VERSION.SDK_INT,
                "model", Build.MODEL, "uid", android.os.Process.myUid(),
                "note", "App UID observation, not a privileged system dump; empty reads are unknown.");
        JSONObject props = new JSONObject();
        for (String key : new String[]{"sys.hud.direct.config", "sys.piex.light.type",
                "persist.dilink.hud.id", "sys.dilink.hud.online"}) put(props, key, property(key));
        put(data, "properties", props);
        put(data, "inspector_package", packageInfo(getPackageName()));
        put(data, "bydhud_package", packageInfo("com.byd.hud"));
        put(data, "dishare_package", packageInfo("com.byd.dishare"));
        put(data, "stock_bydhud_file", hash("/system/app/BydHud/BydHud.apk"));
        return data;
    }

    private JSONObject property(String key) {
        Process process = null;
        try {
            // Fixed binary and allowlisted keys, no shell parsing or caller-supplied commands.
            process = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("getprop timeout");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            for (int n; (n = process.getInputStream().read(chunk)) != -1;) bytes.write(chunk, 0, n);
            String raw = bytes.toString(StandardCharsets.UTF_8.name()).trim();
            return object("status", process.exitValue() == 0 && !raw.isEmpty() ? "returned" : "unknown",
                    "exit_code", process.exitValue(), "raw", raw);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return error(e);
        } finally { if (process != null) process.destroy(); }
    }

    private JSONObject packageInfo(String name) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(name, 0);
            return object("status", "registered", "package", name, "version", info.versionName,
                    "version_code", info.getLongVersionCode(), "base_apk", hash(info.applicationInfo.sourceDir));
        } catch (Exception e) { return object("package", name, "status", "unresolved", "error", error(e)); }
    }

    private JSONObject hash(String path) {
        if (hashes.containsKey(path)) return hashes.get(path);
        JSONObject result = object("path", path);
        try (FileInputStream in = new FileInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long size = 0;
            for (int n; (n = in.read(buffer)) != -1;) { digest.update(buffer, 0, n); size += n; }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            put(result, "sha256", hex.toString());
            put(result, "bytes", size);
        } catch (Exception e) { put(result, "error", error(e)); }
        hashes.put(path, result);
        return result;
    }

    private JSONArray displays() {
        JSONArray result = new JSONArray();
        try {
            for (Display d : getSystemService(DisplayManager.class).getDisplays()) {
                DisplayMetrics metrics = new DisplayMetrics();
                d.getRealMetrics(metrics);
                Display.Mode mode = d.getMode();
                result.put(object("id", d.getDisplayId(), "name", d.getName(), "flags", d.getFlags(),
                        "state", d.getState(), "valid", d.isValid(), "rotation", d.getRotation(),
                        "logical_width", metrics.widthPixels, "logical_height", metrics.heightPixels,
                        "density_dpi", metrics.densityDpi, "physical_width", mode.getPhysicalWidth(),
                        "physical_height", mode.getPhysicalHeight(), "refresh_hz", mode.getRefreshRate()));
            }
        } catch (Exception e) { result.put(error(e)); }
        return result;
    }

    private void persist() {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "last-report.json"));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            show("Не удалось сохранить отчёт", error(e).toString());
        }
    }

    private void export() {
        if (report == null) { show("Сначала сделайте снимок", null); return; }
        exportDocument(report, "fse-hud-");
    }

    private void accessSnapshot() {
        hashes.clear();
        JSONObject data = object("schema", 1, "kind", "fse_access_snapshot",
                "started_epoch_ms", System.currentTimeMillis(), "started_elapsed_ms", SystemClock.elapsedRealtime(),
                "fingerprint", Build.FINGERPRINT, "build_type", Build.TYPE, "sdk", Build.VERSION.SDK_INT,
                "uid", android.os.Process.myUid(), "inspector", packageInfo(getPackageName()),
                "note", "Read-only app UID snapshot. No vehicle Binder calls, network connection or diagnostic UI launch.");
        JSONObject props = new JSONObject();
        for (String key : new String[]{"sys.hud.direct.config", "sys.dilink.hud.online",
                "apps.setting.product.inswver", "apps.setting.product.outswver", "ro.dilink.rse.name",
                "ro.vehicle.type", "ro.build.car.region", "ro.dilink.tv.name", "sys.which.rse",
                "ro.build.multi_display_user", "ro.dilink.board.soc", "ro.byd.ui.integrate",
                "ro.product.name", "ro.product.device", "ro.debuggable", "ro.adb.secure",
                "init.svc.adbd", "service.adb.tcp.port", "persist.internet_adb_enable", "persist.sys.adb_enable",
                "persist.sys.adb.wiress.enable", "sys.connect.adb.wiress"}) put(props, key, property(key));
        put(data, "properties", props);
        try {
            put(data, "adb_enabled", Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED));
        } catch (Exception e) { put(data, "adb_enabled", error(e)); }
        put(data, "hal", corpusFile("/system/lib64/hw/auto.default.so",
                "29e712e309a9bf547a2c4ad9cfe38d34f56c71acf7721630c3c4ec33d3977868"));
        put(data, "cross_service", corpusFile("/system/lib64/libbydcrossservice.so",
                "50073cc57f42f5e885a3d160db257342ab5b5bd8166541ee6d94b69598b03949"));
        put(data, "diagnostics", packageInfo("com.byd.byddevelopmenttools"));
        put(data, "settings", packageInfo("com.byd.carsettings.fse"));
        put(data, "diagnostics_system_file", corpusFile("/system/priv-app/BydDevelopmentTools/BydDevelopmentTools.apk",
                "f9fcba8adb20cd0cf0eac850ab4318887a26db73f70fa26abfcdf466cb826e18"));
        put(data, "settings_system_file", corpusFile("/system/priv-app/CarSettingFsePlatformRk/CarSettingFsePlatformRk.apk",
                "576e996d81d0abe6678fff104c3c8703c65abbb5ce029950f20bfeacaab620de"));
        JSONArray components = new JSONArray();
        for (String cls : new String[]{"MainActivity", "ChooseActivity", "VerificationActivity",
                "BydAuthVerificationActivity", "LogControlAndTestToolsActivity"}) {
            String pkg = "com.byd.byddevelopmenttools";
            JSONObject component = object("component", pkg + "/." + cls);
            try {
                ActivityInfo info = getPackageManager().getActivityInfo(new ComponentName(pkg, pkg + "." + cls),
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                put(component, "exported", info.exported);
                put(component, "manifest_enabled", info.enabled);
                put(component, "application_enabled", info.applicationInfo.enabled);
                put(component, "enabled_override", getPackageManager().getComponentEnabledSetting(
                        new ComponentName(pkg, pkg + "." + cls)));
                put(component, "permission", info.permission == null ? JSONObject.NULL : info.permission);
                put(component, "uid", info.applicationInfo.uid);
            } catch (Exception e) { put(component, "error", error(e)); }
            components.put(component);
        }
        put(data, "diagnostic_components", components);
        JSONArray permissions = new JSONArray();
        for (String name : new String[]{"android.permission.BYDAUTO_SETTING_GET", "android.permission.BYDAUTO_SETTING_SET",
                "android.permission.READ_LOGS", "android.permission.DUMP", "android.permission.WRITE_SECURE_SETTINGS"}) {
            JSONObject permission = object("permission", name, "granted", checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED);
            try {
                PermissionInfo info = getPackageManager().getPermissionInfo(name, 0);
                put(permission, "protection_level", info.protectionLevel);
            } catch (Exception e) { put(permission, "error", error(e)); }
            permissions.put(permission);
        }
        put(data, "permissions", permissions);
        put(data, "displays", displays());
        put(data, "completed_elapsed_ms", SystemClock.elapsedRealtime());
        AtomicFile saved = new AtomicFile(new File(getFilesDir(), "access-last.json"));
        FileOutputStream out = null;
        try {
            out = saved.startWrite();
            out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            saved.finishWrite(out);
        } catch (Exception e) {
            if (out != null) saved.failWrite(out);
            show("Не удалось сохранить снимок доступа", error(e).toString());
            return;
        }
        exportDocument(data, "fse-access-");
    }

    private JSONObject corpusFile(String path, String expected) {
        JSONObject result = hash(path);
        put(result, "ota_sha256", expected);
        put(result, "matches_ota", result.has("sha256") ? expected.equals(result.optString("sha256")) : JSONObject.NULL);
        return result;
    }

    private void exportDocument(JSONObject document, String prefix) {
        Uri uri = null;
        try {
            String filename = prefix + System.currentTimeMillis() + ".json";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FseHudInspector");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("openOutputStream returned null");
                out.write(document.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            if (getContentResolver().update(uri, done, null, null) != 1)
                throw new IllegalStateException("Could not finalize exported file");
            show("Сохранено: Downloads/FseHudInspector/" + filename, null);
        } catch (Exception e) {
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            show("Ошибка экспорта; внутренний отчёт сохранён", error(e).toString());
        }
    }

    private void stop(String message) {
        generation.incrementAndGet();
        if (ticker != null) { ticker.cancel(false); ticker = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (status != null) status.setText(message);
    }

    private void show(String message, String json) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            status.setText(message);
            if (json != null) preview.setText(json);
        });
    }

    @Override protected void onStart() { super.onStart(); ui.post(hudRefresh); }
    @Override protected void onStop() {
        ui.removeCallbacks(hudRefresh);
        stop("Запись остановлена при уходе с экрана");
        super.onStop();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(result, (String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value == null ? JSONObject.NULL : value); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException(key, e); }
    }

    private static JSONObject error(Throwable error) {
        if (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return object("status", "error", "type", error.getClass().getName(), "message", error.getMessage());
    }
}
