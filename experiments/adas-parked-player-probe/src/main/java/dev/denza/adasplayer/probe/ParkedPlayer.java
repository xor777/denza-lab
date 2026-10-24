package dev.denza.adasplayer.probe;

import android.content.Context;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Plays one parked scenario on the car and tears its own session down. It refuses unless the
 * preconditions file proves com.byd.launchermap is absent and disabled and no valid live arm is
 * held. It starts a read-only answer listener (services 13/44), binds its own SOME/IP server
 * session, picks a fresh pathId, runs {@link PlayerEngine} (which always ends with the stock stop
 * sequence and releases the session), and is bounded by a hard watchdog that stops the feed and
 * then kills the process even if a vendor Binder stalls - the same shape as ParkedRouteProbe.
 */
public final class ParkedPlayer {
    private static final String TAG = "DenzaParkedPlayer";
    static final String SCENARIO_FILE = "scenario.json";
    static final String PRECONDITIONS_FILE = "preconditions.json";
    static final int HARD_MAX_S = 300;
    static final int GRACE_MS = 15000;

    private ParkedPlayer() {}

    /** Registered-APK entry: reads scenario.json and preconditions.json from the private files dir. */
    public static void startInApplication(Context context, File directory) {
        String scenarioJson = readText(new File(directory, SCENARIO_FILE), 4 * 1024 * 1024);
        String preconditionsJson = readTextOrNull(new File(directory, PRECONDITIONS_FILE), 65536);
        Preconditions.Decision decision = Preconditions.evaluate(preconditionsJson);
        ScenarioPlan plan = ScenarioPlan.parse(scenarioJson);
        Recorder recorder = new FileRecorder(System.out, plan.id);
        if (!decision.safe) {
            recorder.note("refused", String.join("; ", decision.reasons));
            Log.e(TAG, "refused: " + decision.reasons);
            return;
        }
        recorder.note("preconditions_ok", "stock absent/disabled, no live arm");
        play(context.getApplicationContext(), plan, recorder);
    }

    static void play(Context context, ScenarioPlan plan, Recorder recorder) {
        final PlayerEngine engine = new PlayerEngine();
        long durationMs = plan.durationMs;
        long hardMaxMs = Math.min(durationMs, HARD_MAX_S * 1000L) + GRACE_MS;
        AdasAnswerListener listener = new AdasAnswerListener(context, recorder);
        listener.start();
        String session = "dev.denza.adasplayer.probe.server-" + UUID.randomUUID();
        AndroidTransport transport = new AndroidTransport(context, listener, session);
        final Thread worker = new Thread(() -> {
            try {
                if (!transport.bind(5000)) { recorder.note("bind_failed", session); return; }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                long pathId = freshPathId();
                recorder.note("play", "scenario=" + plan.id + " pathId=" + pathId);
                engine.run(plan, transport, new AndroidClock(), recorder, pathId);
            } catch (Throwable error) {
                recorder.note("worker_error", error.getClass().getSimpleName() + ":" + error.getMessage());
            }
        }, "adas-parked-play");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(hardMaxMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            recorder.note("watchdog", "hard deadline reached; stopping the feed");
            engine.requestStop();
            try { worker.join(GRACE_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try { listener.close(); } catch (RuntimeException ignored) { }
        try { transport.close(); } catch (RuntimeException ignored) { }
        recorder.note("finished", "session released");
        if (worker.isAlive()) {
            recorder.note("hard_kill", "worker still alive after grace; killing process");
            Process.killProcess(Process.myPid());
        }
    }

    private static long freshPathId() {
        long id = System.currentTimeMillis() / 1000L;
        if (id < 100002 || id > Integer.MAX_VALUE) id = 100002;
        return id;
    }

    private static String readText(File file, int max) {
        String text = readTextOrNull(file, max);
        if (text == null) throw new IllegalArgumentException("missing/oversize file: " + file);
        return text;
    }

    private static String readTextOrNull(File file, int max) {
        if (!file.isFile()) return null;
        long size = file.length();
        if (size < 1 || size > max) return null;
        byte[] bytes = new byte[(int) size];
        try (FileInputStream in = new FileInputStream(file)) {
            int at = 0, n;
            while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) != -1) at += n;
            if (at != bytes.length) return null;
        } catch (Exception failure) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
