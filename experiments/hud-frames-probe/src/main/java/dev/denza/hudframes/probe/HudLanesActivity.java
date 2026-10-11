package dev.denza.hudframes.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bounded, manually started native lane-slot experiment. A tx result is not glass evidence. */
public final class HudLanesActivity extends Activity {
    private HandlerThread thread;
    private Handler worker;
    private TextView status;
    private volatile Trial trial;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 32);
        layout.setBackgroundColor(Color.BLACK);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        layout.addView(status);
        ImageView image = new ImageView(this);
        byte[] png = FrameArt.lanes();
        image.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.length));
        layout.addView(image, new LinearLayout.LayoutParams(492, 240));
        Button stop = new Button(this);
        stop.setText("Stop and clear HUD");
        stop.setOnClickListener(v -> cancel());
        layout.addView(stop);
        setContentView(layout);
        thread = new HandlerThread("hud-lanes");
        thread.start();
        worker = new Handler(thread.getLooper());
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void cancel() {
        Trial previous = trial;
        if (previous != null) previous.cancelled = true;
    }

    @Override protected void onDestroy() {
        cancel();
        worker.post(thread::quitSafely); // run the finally/clear before leaving
        super.onDestroy();
    }

    private void handle(Intent intent) {
        cancel();
        String mode = intent.getStringExtra("mode");
        if ("stop".equals(mode)) return;
        if (!HudLanePacket.validMode(mode)) {
            show("Required mode: control / ids / image / both / both-city");
            return;
        }
        Trial next = new Trial(mode, Math.max(1, Math.min(30,
                intent.getIntExtra("seconds", 12))));
        trial = next;
        worker.post(next);
    }

    private void show(String text) {
        Log.i(HudSomeIpSender.TAG, text);
        runOnUiThread(() -> status.setText(text));
    }

    private final class Trial implements Runnable {
        final String mode;
        final int seconds;
        volatile boolean cancelled;

        Trial(String mode, int seconds) { this.mode = mode; this.seconds = seconds; }

        @Override public void run() {
            HudSomeIpSender sender = new HudSomeIpSender(HudLanesActivity.this);
            boolean offered = false;
            try {
                if (cancelled) return;
                int start = sender.open(3000);
                show("lanes " + mode + " start ret=" + start);
                if (!HudSomeIpSender.offerStarted(start)) return;
                offered = true;
                byte[] payload = HudLanePacket.build(mode, FrameArt.lanes(), FrameArt.marker(96));
                int sent = 0;
                long began = SystemClock.uptimeMillis();
                while (!cancelled && SystemClock.uptimeMillis() - began < seconds * 1000L) {
                    int ret = sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, payload);
                    if (ret != 0) { show("lanes " + mode + " fire failed ret=" + ret); break; }
                    sent++;
                    if (sent == 1) show("lanes " + mode + ": active for " + seconds
                            + " s; look for LEFT / WHITE AHEAD / RIGHT beside square-cross");
                    SystemClock.sleep(500);
                }
                show("lanes " + mode + " sent=" + sent + " cancelled=" + cancelled);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                show("lanes interrupted");
            } catch (RuntimeException e) {
                Log.e(HudSomeIpSender.TAG, "lanes failed", e);
            } finally {
                if (offered) {
                    for (int i = 0; i < 3; i++) {
                        Log.i(HudSomeIpSender.TAG, "lanes clear " + i + " ret="
                                + sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, HudLanePacket.clear()));
                        if (i < 2) SystemClock.sleep(120);
                    }
                }
                sender.close(false, null);
                show("lanes " + mode + " offer withdrawn");
            }
        }
    }
}
