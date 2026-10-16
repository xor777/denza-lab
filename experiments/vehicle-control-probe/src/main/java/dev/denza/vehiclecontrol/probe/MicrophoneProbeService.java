package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import dev.denza.disharebridge.LocalAdbClient;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import org.json.JSONObject;

/** Bounded ordinary-UID capture. ADB is used ONLY to read dumpsys audio. No raw audio retained. */
public final class MicrophoneProbeService extends Service {
    private static final String TAG = "DenzaMicProbe";
    private static final int CHANNELS = 10, SAMPLE_RATE = 16_000, INDEX_MASK = 0x3ff;
    private static final long DURATION_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private boolean started;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        started = true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("mic-diagnostic", "Проверка микрофона", NotificationManager.IMPORTANCE_LOW));
        Notification note = new Notification.Builder(this, "mic-diagnostic")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Проверка микрофона · 30 секунд")
                .setContentText("10 каналов · без распознавания и команд").setOngoing(true).build();
        startForeground(72, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        String run = intent == null ? null : intent.getStringExtra("run");
        if (run == null || !run.matches("[a-z0-9_-]{1,40}")) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        main.postDelayed(() -> { running = false; stopRecorder(); stopSelf(); }, 45_000);
        new Thread(() -> capture(run), "microphone-diagnostic").start();
        return START_NOT_STICKY;
    }

    private void capture(String run) {
        File dir = new File(getFilesDir(), "microphone-probe/" + run);
        MicrophoneProbeStats stats = new MicrophoneProbeStats(CHANNELS);
        String outcome = "failed", error = "", baseline = "";
        long captureStarted = 0;
        AudioRecord record = null;
        BufferedWriter log = null;
        LocalAdbClient adb = null;
        try {
            if (!dir.mkdirs()) throw new IllegalStateException("Run directory exists or cannot be created");
            log = new BufferedWriter(new FileWriter(new File(dir, "events.jsonl")));
            event(log, "start", new JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("source", "VOICE_RECOGNITION").put("index_mask", INDEX_MASK)
                    .put("sample_rate", SAMPLE_RATE).put("channels", CHANNELS)
                    .put("audio_focus_requested", false).put("raw_audio_saved", false));
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("RECORD_AUDIO not granted");
            if (!new File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile())
                throw new IllegalStateException("Existing ADB identity missing; no authorization requested");
            adb = LocalAdbClient.loopbackOnly(this);
            baseline = MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio", 4_000));
            event(log, "stock_baseline", new JSONObject().put("recording", baseline));
            if (!running) throw new IllegalStateException("Stopped before capture");
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE).setChannelIndexMask(INDEX_MASK).build())
                    .setBufferSizeInBytes(320_000).build();
            recorder = record;
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialized");
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not start");
            if (record.getChannelCount() != CHANNELS || record.getSampleRate() != SAMPLE_RATE
                    || record.getFormat().getChannelIndexMask() != INDEX_MASK)
                throw new IllegalStateException("Requested capture format was not granted");
            captureStarted = SystemClock.elapsedRealtime();
            event(log, "recording", configuration(record));
            short[] pcm = new short[16_000];
            long nextReport = captureStarted + 1_000, nextStock = captureStarted;
            while (running && SystemClock.elapsedRealtime() - captureStarted < DURATION_MS) {
                int count = record.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("AudioRecord read error " + count);
                if (count > 0) stats.add(pcm, count); else SystemClock.sleep(10);
                long now = SystemClock.elapsedRealtime();
                if (now >= nextReport) {
                    JSONObject cfg = configuration(record).put("capture_elapsed_ms", now - captureStarted)
                            .put("total_samples", stats.totalSamples).put("levels", stats.report());
                    event(log, "sample", cfg);
                    nextReport = now + 1_000;
                    if (cfg.optBoolean("config_unavailable", false)) throw new IllegalStateException("Active recording configuration unavailable");
                    if (cfg.optBoolean("silenced", false)) throw new IllegalStateException("Probe capture silenced");
                }
                if (now >= nextStock) {
                    String stock = MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio", 4_000));
                    event(log, "stock", new JSONObject().put("recording", stock));
                    MicrophoneProbeStats.requireSameStock(baseline, stock);
                    nextStock = SystemClock.elapsedRealtime() + 2_000;
                }
            }
            outcome = running ? "completed" : "stopped";
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.w(TAG, error);
        } finally {
            running = false;
            stopRecorder();
            if (record != null) record.release();
            recorder = null;
            try {
                JSONObject end = new JSONObject().put("outcome", outcome).put("error", error)
                        .put("uid", Process.myUid()).put("total_samples", stats.totalSamples)
                        .put("frames_per_channel", stats.totalSamples / CHANNELS)
                        .put("elapsed_ms", captureStarted == 0 ? 0 : SystemClock.elapsedRealtime() - captureStarted)
                        .put("levels", stats.report());
                if (adb != null && !baseline.isEmpty()) {
                    try {
                        String after = MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio", 4_000));
                        MicrophoneProbeStats.requireSameStock(baseline, after);
                        end.put("stock_restored_same_recording", true).put("stock_after", after);
                    } catch (Exception failure) {
                        end.put("stock_restored_same_recording", false).put("stock_after_error", failure.toString());
                    }
                }
                if (log != null) { event(log, "end", end); log.close(); }
                if (dir.isDirectory()) {
                    try (FileWriter summary = new FileWriter(new File(dir, "summary.json"))) { summary.write(end.toString(2)); }
                }
                Log.w(TAG, "END " + end);
            } catch (Exception failure) { Log.w(TAG, "Cannot save diagnostic", failure); }
            main.post(this::stopSelf);
        }
    }

    private static JSONObject configuration(AudioRecord record) throws Exception {
        JSONObject out = new JSONObject().put("session", record.getAudioSessionId())
                .put("channel_count", record.getChannelCount()).put("index_mask", record.getFormat().getChannelIndexMask())
                .put("sample_rate", record.getSampleRate()).put("recording_state", record.getRecordingState());
        AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
        if (config != null) {
            out.put("silenced", config.isClientSilenced()).put("client_format", config.getClientFormat().toString())
                    .put("device_format", config.getFormat().toString())
                    .put("device", String.valueOf(config.getAudioDevice()));
        } else out.put("config_unavailable", true);
        AudioTimestamp timestamp = new AudioTimestamp();
        int result = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
        out.put("timestamp_result", result);
        if (result == AudioRecord.SUCCESS) out.put("frame_position", timestamp.framePosition).put("nano_time", timestamp.nanoTime);
        return out;
    }

    private static void event(BufferedWriter log, String type, JSONObject data) throws Exception {
        data.put("type", type).put("elapsed_realtime_ms", SystemClock.elapsedRealtime());
        log.write(data.toString()); log.newLine(); log.flush();
    }

    private void stopRecorder() {
        AudioRecord record = recorder;
        if (record != null) try { record.stop(); } catch (IllegalStateException ignored) { /* already released */ }
    }

    @Override public void onDestroy() {
        running = false; main.removeCallbacksAndMessages(null); stopRecorder(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
