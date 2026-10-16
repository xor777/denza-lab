package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Foreground, push-to-talk only. No audio leaves memory and no model is downloaded. */
public final class VoiceInput implements AutoCloseable {
    public interface Listener {
        void onStatus(String status);
        void onResult(JSONObject result);
        void onError(String error);
    }

    private static final String TAG = "DenzaVoice";
    private static final int SAMPLE_RATE = 16_000;
    private static final long LISTEN_MS = 8_000;
    private final Context context;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "DenzaVoice"));
    private final Object lifecycle = new Object();
    private volatile long generation;
    private volatile boolean closed;
    private Session active;
    private Model model; // Only the worker may access the native model.

    private static final class Session {
        final long generation;
        final Listener listener;
        final Object resources = new Object();
        volatile boolean cancelled;
        volatile String abortReason;
        AudioRecord record;
        AudioFocusRequest focus;
        long started;
        long samples;
        long squares;
        int peak;
        Session(long generation, Listener listener) { this.generation = generation; this.listener = listener; }
        String levels() {
            double rms = samples == 0 ? 0 : Math.sqrt((double) squares / samples);
            return String.format(Locale.ROOT, "пик %d / RMS %.0f из 32768", peak, rms);
        }
    }

    public VoiceInput(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Permission must already have been granted by the foreground Activity. Never queues recordings. */
    public void start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        synchronized (lifecycle) {
            if (closed) return;
            if (active != null) {
                long current = generation;
                main.post(() -> { if (!closed && generation == current) listener.onError("Предыдущая запись ещё завершается"); });
                return;
            }
            Session session = new Session(++generation, listener);
            active = session;
            worker.execute(() -> run(session));
        }
    }

    /** Invalidates even callbacks which have already been posted to the main thread. */
    public void cancel() {
        synchronized (lifecycle) {
            generation++;
            if (active != null) { active.cancelled = true; stopRecording(active); }
        }
    }

    /** Bounded diagnostics: only the latest text/score result and latest decision, never PCM. */
    public void recordDecision(JSONObject decision) {
        String json=decision.toString();
        synchronized(lifecycle){if(!closed)worker.execute(()->writeDiagnostic("last-decision.json",json));}
    }

    private void writeDiagnostic(String name,String json) {
        File directory=new File(context.getFilesDir(),"voice");
        try{
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("voice diagnostic directory unavailable");
            AtomicFile target=new AtomicFile(new File(directory,name));
            FileOutputStream output=target.startWrite();
            try{output.write(json.getBytes(StandardCharsets.UTF_8));target.finishWrite(output);}
            catch(Exception failure){target.failWrite(output);throw failure;}
        }catch(Exception failure){Log.w(TAG,"Could not save last voice diagnostic",failure);}
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            generation++;
            if (active != null) { active.cancelled = true; stopRecording(active); }
            worker.execute(() -> { if (model != null) { model.close(); model = null; } });
            worker.shutdown();
        }
    }

    private boolean live(Session session) {
        return !closed && !session.cancelled && generation == session.generation;
    }

    private void check(Session session) throws IOException {
        if (!live(session)) throw new IOException("Запись отменена");
        if (session.abortReason != null) throw new IOException(session.abortReason);
    }

    private void status(Session session, String message) {
        main.post(() -> { if (live(session) && session.abortReason == null) session.listener.onStatus(message); });
    }

    private void run(Session session) {
        JSONObject result = null;
        String error = null;
        try {
            check(session);
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new IOException("Нет разрешения на микрофон");
            if (model == null) {
                status(session, "Подготовка офлайн-модели…");
                long loadStarted = SystemClock.elapsedRealtime();
                File installed = installModel(session);
                check(session);
                model = new Model(installed.getAbsolutePath());
                Log.i(TAG, "model loaded in " + (SystemClock.elapsedRealtime() - loadStarted) + " ms");
            }
            check(session);
            try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE, VoiceCommands.grammarJson())) {
                recognizer.setWords(true);
                acquireMicrophone(session);
                result = recognize(session, recognizer);
            }
        } catch (Exception | LinkageError failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        } finally {
            release(session);
            synchronized (lifecycle) { if (active == session) active = null; }
            if (session.started != 0) Log.i(TAG, "capture " + (SystemClock.elapsedRealtime() - session.started)
                    + " ms, " + session.levels() + ", samples=" + session.samples
                    + ", text=" + (result == null ? "<none>" : result.optString("text")));
        }
        final JSONObject completed = result;
        final String failed = error;
        if(completed!=null)writeDiagnostic("last-result.json",completed.toString());
        main.post(() -> {
            if (!live(session)) return;
            if (session.abortReason != null) session.listener.onError(session.abortReason + " · " + session.levels());
            else if (completed != null) session.listener.onResult(completed);
            else session.listener.onError((failed == null ? "Нет завершённой команды" : failed)
                    + (session.started == 0 ? "" : " · " + session.levels()));
        });
    }

    private void acquireMicrophone(Session session) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Нет разрешения на микрофон");
        if (audioManager == null) throw new IOException("Аудиосервис недоступен");
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    // A loss queued before the endpoint must still veto its pending result after cleanup.
                    if (change <= 0 && live(session)) {
                        session.abortReason = "Аудиофокус потерян, команда отменена";
                        stopRecording(session);
                    }
                }, main).build();
        synchronized (session.resources) { session.focus = request; }
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("Микрофон: эксклюзивный аудиофокус не получен");
        check(session);
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Микрофон не поддерживает PCM16 / 16 кГц");
        synchronized (session.resources) {
            check(session);
            // MIC is intentional: the stock assistant already owns VOICE_RECOGNITION on this firmware.
            session.record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minimum, 6_400)).build();
            if (session.record.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Микрофон не инициализирован");
            session.record.startRecording();
            if (session.record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Микрофон не начал запись");
            session.started = SystemClock.elapsedRealtime();
        }
        status(session, "Говорите · до 8 с · пик 0 из 32768");
    }

    private JSONObject recognize(Session session, Recognizer recognizer) throws Exception {
        short[] pcm = new short[1_600];
        long lastStatus = session.started;
        while (SystemClock.elapsedRealtime() - session.started < LISTEN_MS) {
            check(session);
            // Nonblocking reads keep the eight-second deadline and cancellation bounded even during silence.
            int count = session.record.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
            if (count < 0) throw new IOException("Ошибка чтения микрофона: " + count);
            if (count == 0) { Thread.sleep(10); continue; }
            for (int i = 0; i < count; i++) {
                int magnitude = Math.abs((int) pcm[i]);
                session.peak = Math.max(session.peak, magnitude);
                session.squares += (long) pcm[i] * pcm[i];
            }
            session.samples += count;
            boolean endpoint = recognizer.acceptWaveForm(pcm, count);
            check(session);
            if (SystemClock.elapsedRealtime() - session.started >= LISTEN_MS) break;
            if (endpoint) {
                JSONObject completed = new JSONObject(recognizer.getResult());
                if (!completed.optString("text").trim().isEmpty()) return completed;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastStatus >= 1_000) {
                lastStatus = now;
                status(session, "Слушаю · " + ((now - session.started) / 1_000) + " с · " + session.levels());
            }
        }
        // getFinalResult would turn a truncated phrase into an actionable command. Only endpoints may execute.
        throw new IOException("За 8 с нет завершённой команды; действие не выполнено");
    }

    private static void stopRecording(Session session) {
        synchronized (session.resources) {
            if (session.record != null) try { session.record.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    private void release(Session session) {
        synchronized (session.resources) {
            if (session.record != null) {
                try { session.record.stop(); } catch (IllegalStateException ignored) { }
                try { session.record.release(); } catch (RuntimeException failure) { Log.w(TAG, "Microphone release failed", failure); }
                session.record = null;
            }
            if (session.focus != null && audioManager != null) {
                try { audioManager.abandonAudioFocusRequest(session.focus); }
                catch (RuntimeException failure) { Log.w(TAG, "Audio focus release failed", failure); }
                session.focus = null;
            }
        }
    }

    private File installModel(Session session) throws Exception {
        return VoiceModelFiles.install(context, () -> check(session));
    }
}
