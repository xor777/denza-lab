package dev.denza.hudframes.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;

import static dev.denza.hudframes.probe.HudSomeIpSender.*;

/** Parked, bounded test of stock nextRoadName (field 10). Never sets vehicle properties. */
public final class HudStreetActivity extends Activity {
    private static final String TAG = "DenzaHudStreet";
    private static final String[] NAMES = {"TEST", "МОСКВА", "北京路"};
    private HandlerThread thread;
    private Handler worker;
    private TextView status;
    private Trial trial;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 32);
        layout.setBackgroundColor(Color.BLACK);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(32);
        layout.addView(status);
        Button stop = new Button(this);
        stop.setText("Остановить тест и очистить HUD");
        stop.setOnClickListener(v -> cancel());
        layout.addView(stop);
        setContentView(layout);
        thread = new HandlerThread("hud-street");
        thread.start();
        worker = new Handler(thread.getLooper());
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void cancel() { if (trial != null) trial.cancelled = true; }

    @Override protected void onDestroy() {
        cancel();
        worker.post(thread::quitSafely);
        super.onDestroy();
    }

    private void handle(Intent intent) {
        cancel();
        String mode = intent.getStringExtra("mode");
        if ("stop".equals(mode)) return;
        boolean pictureControl = "picture-control".equals(mode);
        boolean imageText = "image".equals(mode) || pictureControl || "street-card".equals(mode);
        int first = "latin".equals(mode) ? 0 : ("cyrillic".equals(mode) || imageText) ? 1 : "chinese".equals(mode) ? 2 : -1;
        if (first < 0 && !"sweep".equals(mode)) {
            show("Выбери latin / cyrillic / chinese / sweep / image / picture-control / street-card");
            return;
        }
        int roadClass = intent.getIntExtra("roadClass", 7);
        int counter = intent.getIntExtra("counter", 2);
        if (roadClass < 0 || roadClass > 10 || (counter != 1 && counter != 2)) {
            show("Недопустимые roadClass / counter");
            return;
        }
        trial = new Trial(first, Math.max(1, Math.min(20, intent.getIntExtra("seconds", 12))), roadClass, counter, mode);
        worker.post(trial);
    }

    private void show(String text) {
        Log.i(TAG, text);
        runOnUiThread(() -> status.setText(text));
    }

    private final class Trial implements Runnable {
        final int first, seconds, roadClass, counter;
        final String mode;
        volatile boolean cancelled;
        Trial(int first, int seconds, int roadClass, int counter, String mode) {
            this.first = first; this.seconds = seconds; this.roadClass = roadClass; this.counter = counter;
            this.mode = mode;
        }
        @Override public void run() {
            HudSomeIpSender sender = new HudSomeIpSender(HudStreetActivity.this);
            boolean offered = false;
            int sent = 0;
            try {
                if (cancelled) return;
                int start = sender.open(3000);
                show("start=" + start + " roadClass=" + roadClass + " counter=" + counter);
                if (!offerStarted(start)) return;
                offered = true;
                sender.fire(TOPIC_HUD_ROAD, HudLanePacket.clear());
                SystemClock.sleep(800);
                boolean pictureControl = "picture-control".equals(mode);
                boolean imageText = "image".equals(mode) || pictureControl || "street-card".equals(mode);
                byte[] marker = imageText ? streetImage(mode) : FrameArt.marker(80);
                for (int index = first < 0 ? 0 : first; index < (first < 0 ? NAMES.length : first + 1) && !cancelled; index++) {
                    int metres = (index + 1) * 100;
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    intField(body, 2, counter);
                    intField(body, 3, 2000);
                    intField(body, 4, 600);
                    intField(body, 6, roadClass);
                    bytesField(body, 8, marker);
                    intField(body, 9, metres);
                    stringField(body, 10, NAMES[index]);
                    intField(body, 16, 2);
                    stringField(body, 26, "12:34");
                    stringField(body, 27, "10 min");
                    intField(body, 28, pictureControl ? 1 : 11);
                    byte[] packet = embed(1, body.toByteArray());
                    show(metres + " м → «" + NAMES[index] + "» — " + seconds + " с; imageText=" + imageText + "; pictureControl=" + pictureControl);
                    long startTime = SystemClock.elapsedRealtime();
                    while (!cancelled && SystemClock.elapsedRealtime() - startTime < seconds * 1000L) {
                        int result = sender.fire(TOPIC_HUD_ROAD, packet);
                        if (result != 0) throw new IllegalStateException("fire=" + result);
                        sent++;
                        SystemClock.sleep(500);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "interrupted", error);
            } catch (RuntimeException error) {
                Log.e(TAG, "trial failed", error);
            } finally {
                if (offered) for (int i = 0; i < 3; i++) {
                    Log.i(TAG, "clear=" + sender.fire(TOPIC_HUD_ROAD, HudLanePacket.clear()));
                    SystemClock.sleep(120);
                }
                sender.close(false, null);
                show("Тест завершён, sent=" + sent + ", cancelled=" + cancelled + "; HUD очищен, offer withdrawn");
            }
        }
    }

    /** Positive control: letters are already pixels in field 8, not the native street field. */
    private static byte[] streetImage(String mode) {
        Bitmap image = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff42d9ff);
        paint.setStrokeWidth(6);
        canvas.drawLine(40, 46, 40, 8, paint);
        canvas.drawLine(40, 8, 25, 23, paint);
        canvas.drawLine(40, 8, 55, 23, paint);
        paint.setTextSize(16);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("МОСКВА", 40, 70, paint);
        if ("picture-control".equals(mode)) {
            canvas.drawColor(0xffff00ff);
            paint.setColor(Color.WHITE);
            paint.setTextSize(60);
            paint.setFakeBoldText(true);
            canvas.drawText("А", 40, 62, paint);
        }
        if ("street-card".equals(mode)) {
            canvas.drawColor(Color.BLACK);
            paint.setColor(Color.WHITE);
            paint.setTextSize(30);
            paint.setFakeBoldText(true);
            canvas.drawText("МОС", 40, 32, paint);
            canvas.drawText("КВА", 40, 64, paint);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, output);
        image.recycle();
        return output.toByteArray();
    }
}
