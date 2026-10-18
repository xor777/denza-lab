package dev.denza.hudframes.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Bounded replay of owner-recorded RoadInfo packets; no location injection or vehicle setters. */
public final class HudReplayActivity extends Activity {
    private static final String TAG = "DenzaHudReplay";
    private static final byte[] CLEAR = {10,14,16,2,48,(byte)255,1,(byte)128,1,1,
            (byte)242,1,0,(byte)250,1,0};
    private volatile boolean cancelled;
    private Thread worker;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48,48,48,48);
        status = new TextView(this);
        status.setTextSize(30);
        layout.addView(status);
        Button stop = new Button(this);
        stop.setText("Остановить воспроизведение и очистить HUD");
        stop.setOnClickListener(view -> { cancelled = true; if (worker != null) worker.interrupt(); });
        layout.addView(stop);
        setContentView(layout);
        String label = getIntent().getStringExtra("label");
        worker = new Thread(() -> replay(label == null ? "HUD replay" : label), "hud-replay");
        worker.start();
    }

    private void replay(String label) {
        HudSomeIpSender sender = new HudSomeIpSender(this);
        int sent = 0;
        try {
            File input = new File(getFilesDir(), "hud-replay.jsonl");
            if (input.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("replay too large");
            List<Frame> frames = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(input))) {
                String line;
                long previous = -1;
                while ((line = reader.readLine()) != null) {
                    JSONObject row = new JSONObject(line);
                    long at = row.getLong("atMs");
                    byte[] payload = Base64.decode(row.getString("payloadBase64"), Base64.DEFAULT);
                    if (at <= previous || at < 0 || at > 60_000 || frames.size() >= 2000
                            || payload.length < 4 || payload.length > 131072 || payload[0] != 10) {
                        throw new IllegalArgumentException("invalid replay frame");
                    }
                    frames.add(new Frame(at, payload));
                    previous = at;
                }
            }
            if (frames.isEmpty() || frames.get(0).atMs != 0) throw new IllegalArgumentException("empty replay");
            int open = sender.open(5000);
            if (!HudSomeIpSender.offerStarted(open)) throw new IllegalStateException("open=" + open);
            sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, CLEAR);
            Thread.sleep(800);
            long start = SystemClock.elapsedRealtime();
            for (int i = 0; i < frames.size() && !cancelled; i++) {
                Frame frame = frames.get(i);
                long wait = start + frame.atMs - SystemClock.elapsedRealtime();
                if (wait > 0) Thread.sleep(wait);
                if (cancelled) break;
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (elapsed > 65_000) throw new IllegalStateException("replay deadline");
                // Do not burst obsolete frames if the device briefly stalls.
                if (i + 1 < frames.size() && frames.get(i + 1).atMs <= elapsed) continue;
                int result = sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, frame.payload);
                if (result != 0) throw new IllegalStateException("send=" + result);
                sent++;
                if (sent == 1 || sent % 20 == 0) update(label + " — " + elapsed / 1000 + " с");
            }
            Log.i(TAG, "complete label=" + label + " sent=" + sent + " cancelled=" + cancelled);
        } catch (InterruptedException stop) {
            Log.i(TAG, "cancelled sent=" + sent);
        } catch (Exception error) {
            Log.e(TAG, "failed", error);
        } finally {
            Thread.interrupted();
            for (int i = 0; i < 3; i++) {
                Log.i(TAG, "clear result=" + sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, CLEAR));
                try { Thread.sleep(120); } catch (InterruptedException ignored) { Thread.interrupted(); }
            }
            sender.close(false, null);
            update(label + " — завершено, HUD очищен");
        }
    }

    private void update(String message) { runOnUiThread(() -> status.setText(message)); }

    @Override public void onDestroy() {
        cancelled = true;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private static final class Frame {
        final long atMs;
        final byte[] payload;
        Frame(long atMs, byte[] payload) { this.atMs = atMs; this.payload = payload; }
    }
}
