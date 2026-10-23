package dev.denza.adasstatus.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StatusService extends Service implements PassiveClient.Sink {
    static final String STOP = "dev.denza.adasstatus.probe.STOP", OVERLAY = "dev.denza.adasstatus.probe.OVERLAY", FIXTURE = "dev.denza.adasstatus.probe.FIXTURE";
    static volatile String currentText = "Observer stopped · availability UNKNOWN";
    private static volatile StatusModel snapshot = new StatusModel();
    private static volatile PassiveClient currentClient;
    private static volatile boolean running;
    private static volatile long currentDropped;
    static String textNow() {
        if (!running) return currentText;
        StatusModel view = snapshot;
        PassiveClient source = currentClient;
        if (!view.demo) {
            view = view.copy(); view.connected = source != null && source.binderAlive();
            Boolean hal = source == null ? null : source.halState(); view.halKnown = hal != null; view.halAvailable = Boolean.TRUE.equals(hal);
            if (source != null && view.pilotAt <= source.invalidatedAt()) view.pilotForConnection = false;
        }
        return view.render(SystemClock.elapsedRealtime(), currentDropped);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean uiPending = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> new Thread(task, "AdasStatusWorker"), new ThreadPoolExecutor.AbortPolicy());
    private final StatusModel model = new StatusModel();
    private final StatusTransitions transitions = new StatusTransitions();
    private PassiveClient client;
    private TextView overlay;
    private boolean showOverlay, stopping;
    private File journal;
    private long journalBytes;
    private File transitionJournal;
    private long transitionBytes;
    private static final long MAX_JOURNAL = 4L * 1024 * 1024;
    private static final long MAX_TRANSITIONS = 512L * 1024;

    @Override public void onCreate() {
        super.onCreate();
        running = true; snapshot = new StatusModel();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("adas-status", "Статус ADAS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, StatusActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "adas-status")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Статус ADAS")
                .setContentText("Наблюдение включено").setContentIntent(open).setOngoing(true).build();
        startForeground(21013, notification);
        journal = new File(getFilesDir(), "adas-status.jsonl"); journalBytes = journal.length();
        transitionJournal = new File(getFilesDir(), "adas-status-changes.jsonl"); transitionBytes = transitionJournal.length();
        client = new PassiveClient(this, this); currentClient = client; client.start(); main.post(tick);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (OVERLAY.equals(action)) { showOverlay = intent.getBooleanExtra("enabled", true); updateOverlay(); }
        if (FIXTURE.equals(action)) {
            final String fixture = intent.getStringExtra("fixture");
            enqueue(() -> fixture(fixture));
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public boolean enqueue(Runnable task) {
        if (stopping) return false;
        try { worker.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException full) { currentDropped = dropped.incrementAndGet(); return false; }
    }
    @Override public void state(String event, String detail) {
        model.control(event, detail);
        record(json("event", event, "detail", detail, "source", "native_client_control")); publish();
    }
    @Override public void event(long topic, long timestamp, long receivedAt, byte[] payload) { accept(topic, timestamp, receivedAt, payload, false); }
    private void accept(long topic, long timestamp, long receivedAt, byte[] payload, boolean fixture) {
        JSONObject entry = json("event", "someip", "source", fixture ? "emulator_fixture" : "native_someip",
                "topic", StatusProtocol.hex(topic), "timestamp", timestamp, "declaredLength", payload.length,
                "payloadHex", StatusProtocol.hex(payload), "receivedAtUptimeMs", receivedAt,
                "processedAtUptimeMs", SystemClock.elapsedRealtime());
        try {
            Map<String, Object> decoded = StatusProtocol.decode(topic, payload);
            entry.put("decoded", new JSONObject(decoded)); model.apply(decoded, receivedAt, fixture);
            Map<String, Object> change = transitions.accept(fixture ? "emulator_fixture" : "native_someip", decoded);
            if (change != null) recordTransition(change, topic, receivedAt, fixture);
        } catch (Exception invalid) { try { entry.put("decodeError", invalid.getClass().getSimpleName() + ":" + invalid.getMessage()); } catch (Exception ignored) { } }
        record(entry); publish();
    }
    private void recordTransition(Map<String, Object> change, long topic, long receivedAt, boolean fixture) {
        try {
            JSONObject entry = new JSONObject(change);
            entry.put("schema", "denza-adas-passive-transition-v1");
            entry.put("source", fixture ? "emulator_fixture" : "native_someip");
            entry.put("topic", StatusProtocol.hex(topic)); entry.put("receivedAtUptimeMs", receivedAt);
            entry.put("capturedAtMs", System.currentTimeMillis());
            byte[] bytes = (entry.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            if (transitionBytes+bytes.length > MAX_TRANSITIONS) {
                File previous = new File(getFilesDir(), "adas-status-changes.previous.jsonl");
                if (previous.exists() && !previous.delete()) throw new IllegalStateException("Changes rotation delete");
                if (transitionJournal.exists() && !transitionJournal.renameTo(previous)) throw new IllegalStateException("Changes rotation move");
                transitionBytes = 0;
            }
            try (FileOutputStream out = new FileOutputStream(transitionJournal, true)) { out.write(bytes); }
            transitionBytes += bytes.length;
            Log.i("DenzaAdasChange", entry.toString());
        } catch (Exception failure) { Log.e("DenzaAdasStatus", "Transition journal failure " + failure.getClass().getSimpleName()); }
    }
    private void record(JSONObject entry) {
        try {
            entry.put("schema", "denza-adas-passive-event-v1"); entry.put("capturedAtMs", System.currentTimeMillis());
            entry.put("capturedUptimeMs", SystemClock.elapsedRealtime()); entry.put("droppedEvents", dropped.get());
            byte[] bytes = (entry.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            if (journalBytes+bytes.length > MAX_JOURNAL) {
                File previous = new File(getFilesDir(), "adas-status.previous.jsonl");
                if (previous.exists() && !previous.delete()) throw new IllegalStateException("Journal rotation delete");
                if (journal.exists() && !journal.renameTo(previous)) throw new IllegalStateException("Journal rotation move");
                journalBytes = 0;
            }
            try (FileOutputStream out = new FileOutputStream(journal, true)) { out.write(bytes); }
            journalBytes += bytes.length;
            JSONObject summary = new JSONObject(entry.toString()); summary.remove("payloadHex");
            String text = summary.toString(); Log.i("DenzaAdasStatus", text.length() > 3500 ? text.substring(0, 3500) : text);
        } catch (Exception failure) { Log.e("DenzaAdasStatus", "Journal failure " + failure.getClass().getSimpleName()); }
    }
    private void publish() {
        snapshot = model.copy(); currentDropped = dropped.get(); currentText = textNow();
        if (uiPending.compareAndSet(false, true)) main.post(() -> { uiPending.set(false); updateOverlay(); });
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            updateOverlay(); main.postDelayed(this, 1000);
        }
    };
    private void updateOverlay() {
        WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (stopping || !showOverlay || !Settings.canDrawOverlays(this)) {
            if (overlay != null) { try { windows.removeView(overlay); } catch (RuntimeException ignored) { } overlay = null; }
            return;
        }
        if (overlay == null) {
            overlay = new TextView(this); overlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40); overlay.setTextColor(Color.WHITE);
            overlay.setBackgroundColor(0xDD101820); overlay.setPadding(24, 20, 24, 20);
            overlay.setLineSpacing(6, 1);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(720, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.RIGHT; params.x = 20; params.y = 50;
            try { windows.addView(overlay, params); }
            catch (RuntimeException failure) { overlay = null; Log.e("DenzaAdasStatus", "Overlay denied"); return; }
        }
        StatusModel view = snapshot.copy();
        if (!view.demo) {
            view.connected = client != null && client.binderAlive(); Boolean hal = client == null ? null : client.halState();
            view.halKnown = hal != null; view.halAvailable = Boolean.TRUE.equals(hal);
            if (client != null && view.pilotAt <= client.invalidatedAt()) view.pilotForConnection = false;
        }
        overlay.setText(view.compact(SystemClock.elapsedRealtime()));
    }
    private void fixture(String name) {
        boolean emulator = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
        if (!emulator) { record(json("event", "fixture_rejected", "source", "guard", "reason", "not_emulator")); return; }
        if (name == null) return;
        model.demo = true; model.demoDisconnected = false;
        if (name.equals("active")) accept(StatusProtocol.PILOT, 0, SystemClock.elapsedRealtime(), new byte[]{10,4,32,1,40,1}, true);
        else if (name.equals("unavailable")) accept(StatusProtocol.PILOT, 0, SystemClock.elapsedRealtime(), new byte[]{10,0}, true);
        else {
            accept(StatusProtocol.PILOT, 0, SystemClock.elapsedRealtime(), new byte[]{10,4,32,2,40,2}, true);
            if (name.equals("city")) accept(StatusProtocol.MODE, 0, SystemClock.elapsedRealtime(), new byte[]{10,2,24,2}, true);
            else if (name.equals("highway")) accept(StatusProtocol.MODE, 0, SystemClock.elapsedRealtime(), new byte[]{10,2,24,1}, true);
            if (name.equals("stale")) model.pilotAt = SystemClock.elapsedRealtime()-60000;
            if (name.equals("disconnected")) model.demoDisconnected = true;
        }
        record(json("event", "EMULATOR_DEMO", "source", "emulator_fixture", "fixture", name)); publish();
    }
    static JSONObject json(Object... values) {
        JSONObject out = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) out.put(String.valueOf(values[i]), values[i+1]); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        return out;
    }
    @Override public void onDestroy() {
        stopping = true; main.removeCallbacksAndMessages(null); showOverlay = false; updateOverlay();
        running = false; currentClient = null; currentText = "Наблюдение остановлено · NOA: нет свежих данных";
        client.stopAdmission(); worker.getQueue().clear();
        try { worker.execute(() -> { client.close(); currentText = "Наблюдение остановлено · NOA: нет свежих данных"; }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
        worker.shutdown(); stopForeground(true); super.onDestroy();
    }
}
