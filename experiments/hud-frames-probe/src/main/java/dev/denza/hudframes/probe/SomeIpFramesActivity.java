package dev.denza.hudframes.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays pictures into the HUD's SOME/IP picture slots and logs what the stock service accepted.
 * Whether the HUD actually showed each frame is for the person looking at the windshield; the log
 * only proves what left the IVI.
 *
 * <pre>
 * am start -n dev.denza.hudframes.probe/.SomeIpFramesActivity \
 *     --es channel icon|map|grid|yandex|stop --es fps 1,2,5,10 --ei seconds 10 \
 *     [--ei map_w 300 --ei map_h 180 --ez marker true]
 *     [yandex: --ei vd_w 960 --ei vd_h 576 --ei vd_dpi 160
 *              --ef cx 0.5 --ef cy 0.55 --ef cw 0.6 --ez invert false --ez road false]
 * </pre>
 *
 * The `yandex` channel creates the "Denza Navigation" virtual display and sends a crop of what is
 * drawn on it to the map window until the host sends `stop`; moving the navigator task onto the
 * display and back is the host's job (tools/hud_frames_probe.sh).
 */
public final class SomeIpFramesActivity extends Activity {
    private static final String TAG = HudSomeIpSender.TAG;
    private static final int MAX_FPS = 30;
    private static final int MAX_STEP_SECONDS = 60;
    private static final int MAX_YANDEX_SECONDS = 600;

    private HandlerThread thread;
    private Handler worker;
    private TextView status;
    private ImageView preview;
    private volatile Run run;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(48, 48, 48, 48);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        layout.addView(status);
        layout.addView(preview);
        setContentView(layout);
        thread = new HandlerThread("hud-frames");
        thread.start();
        worker = new Handler(thread.getLooper());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        Run current = run;
        if (current != null) {
            current.cancelled = true;
        }
        // Let the worker blank or clear the HUD and withdraw the offer before it quits.
        worker.post(thread::quitSafely);
        super.onDestroy();
    }

    private void handle(Intent intent) {
        String channel = valueOr(intent.getStringExtra("channel"), "icon");
        Run previous = run;
        if (previous != null) {
            previous.cancelled = true;
        }
        if ("stop".equals(channel)) {
            show("stopped by host");
            return;
        }
        if (!"icon".equals(channel) && !"map".equals(channel) && !"grid".equals(channel)
                && !"yandex".equals(channel)) {
            show("unknown channel " + channel);
            return;
        }
        boolean yandex = "yandex".equals(channel);
        Run next = new Run(
                channel,
                parseRates(valueOr(intent.getStringExtra("fps"), yandex ? "5" : "1,2,5,10")),
                clamp(intent.getIntExtra("seconds", yandex ? 180 : 10), 1,
                        yandex ? MAX_YANDEX_SECONDS : MAX_STEP_SECONDS),
                clamp(intent.getIntExtra("icon_size", 192), 48, 512),
                clamp(intent.getIntExtra("map_w", 300), 64, 1280),
                clamp(intent.getIntExtra("map_h", 180), 64, 1280),
                intent.getBooleanExtra("marker", false));
        if (yandex) {
            next.vdWidth = clamp(intent.getIntExtra("vd_w", 960), 320, 2560);
            next.vdHeight = clamp(intent.getIntExtra("vd_h", 576), 240, 1600);
            next.vdDpi = clamp(intent.getIntExtra("vd_dpi", 160), 120, 480);
            next.cropX = intent.getFloatExtra("cx", 0.5f);
            next.cropY = intent.getFloatExtra("cy", 0.55f);
            next.cropW = intent.getFloatExtra("cw", 0.6f);
            next.invert = intent.getBooleanExtra("invert", false);
            next.road = intent.getBooleanExtra("road", false);
        }
        run = next;
        worker.post(yandex ? next::executeYandex : next::execute);
    }

    private void show(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> status.setText("HUD frames probe\n\n" + line));
    }

    private final class Run {
        final String channel;
        final List<Integer> rates;
        final int seconds;
        final int iconSize;
        final int mapWidth;
        final int mapHeight;
        /** Field-8 picture sent with every map frame, or null for none. */
        final byte[] marker;
        /** The still calibration frame for the grid channel, rendered once. */
        final byte[] grid;
        int vdWidth;
        int vdHeight;
        int vdDpi;
        float cropX;
        float cropY;
        float cropW;
        boolean invert;
        /** Yandex channel: also keep the road event in "navigating" state, for runs without a route. */
        boolean road;
        volatile boolean cancelled;
        int frame;

        Run(String channel, List<Integer> rates, int seconds, int iconSize, int mapWidth,
                int mapHeight, boolean withMarker) {
            this.channel = channel;
            this.rates = rates;
            this.seconds = seconds;
            this.iconSize = iconSize;
            this.mapWidth = mapWidth;
            this.mapHeight = mapHeight;
            this.marker = withMarker ? FrameArt.marker(iconSize) : null;
            this.grid = "grid".equals(channel) ? FrameArt.grid(mapWidth, mapHeight) : null;
        }

        void execute() {
            HudSomeIpSender sender = new HudSomeIpSender(SomeIpFramesActivity.this);
            StringBuilder report = new StringBuilder();
            try {
                int opened = sender.open(3000);
                show("channel=" + channel + " start ret=" + opened);
                if (opened != 0) {
                    return;
                }
                for (int fps : rates) {
                    if (cancelled) {
                        break;
                    }
                    String line = step(sender, fps);
                    report.append(line).append('\n');
                    show(report.toString());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                sender.close();
                show(report + (cancelled ? "cancelled" : "done") + ", offer withdrawn");
            }
        }

        /** One frame rate for `seconds`; returns the stats line that is also logged. */
        String step(HudSomeIpSender sender, int fps) {
            long period = 1000L / fps;
            int total = fps * seconds;
            int accepted = 0;
            int failed = 0;
            long fireSum = 0;
            long fireMax = 0;
            long bytes = 0;
            long started = SystemClock.uptimeMillis();
            long due = started;
            for (int index = 0; index < total && !cancelled; index++) {
                due = pace(due, period);
                frame++;
                String label = String.format(Locale.ROOT, "%d fps #%d", fps, frame);
                long before = SystemClock.elapsedRealtimeNanos();
                int result;
                long size;
                if ("icon".equals(channel)) {
                    byte[] payload = HudSomeIpSender.roadPayload(2, false,
                            FrameArt.icon(frame, iconSize), frame, label,
                            fps + " fps", "#" + frame);
                    size = payload.length;
                    result = sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, payload);
                } else {
                    byte[] map = HudSomeIpSender.mapPayload(grid != null ? grid
                            : FrameArt.map(frame, mapWidth, mapHeight, fps));
                    // The stock keeps the road event alive beside the map; so does the probe,
                    // optionally with a still marker in field 8 to tell the two windows apart.
                    byte[] road = HudSomeIpSender.roadPayload(2, false, marker, frame, label,
                            fps + " fps", "#" + frame);
                    size = map.length + road.length;
                    int mapResult = sender.fire(HudSomeIpSender.TOPIC_HUD_MAP, map);
                    int roadResult = sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD, road);
                    result = mapResult != 0 ? mapResult : roadResult;
                }
                long fireMs = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000L;
                fireSum += fireMs;
                fireMax = Math.max(fireMax, fireMs);
                bytes += size;
                if (result == 0) {
                    accepted++;
                } else {
                    failed++;
                    Log.w(TAG, "frame " + frame + " ret=" + result);
                }
            }
            long elapsed = Math.max(1, SystemClock.uptimeMillis() - started);
            int sent = accepted + failed;
            String line = String.format(Locale.ROOT,
                    "%s %dfps: sent=%d ok=%d fail=%d achieved=%.1ffps build+fire avg=%dms max=%dms"
                            + " payload avg=%dB",
                    channel, fps, sent, accepted, failed, sent * 1000.0 / elapsed,
                    sent == 0 ? 0 : fireSum / sent, fireMax, sent == 0 ? 0 : bytes / sent);
            Log.i(TAG, line);
            return line;
        }

        /**
         * Sends a crop of the "Denza Navigation" display to the map window until `stop`. Only the
         * map event is written: the road event stays with Denza Apps' guidance, so the maneuver
         * window keeps showing real hints. The display is released only after the loop ends,
         * which the host arranges to happen after it has moved the navigator back.
         */
        void executeYandex() {
            HudSomeIpSender sender = new HudSomeIpSender(SomeIpFramesActivity.this);
            YandexMapSource source = null;
            byte[] blank = FrameArt.black(mapWidth, mapHeight);
            int fps = rates.get(0);
            long period = 1000L / fps;
            int accepted = 0;
            int failed = 0;
            int stale = 0;
            long work = 0;
            long bytes = 0;
            try {
                source = new YandexMapSource(SomeIpFramesActivity.this, vdWidth, vdHeight, vdDpi);
                // The host reads this exact line to learn where to move the navigator.
                Log.i(TAG, "yandex display id=" + source.displayId()
                        + " size=" + vdWidth + "x" + vdHeight + " dpi=" + vdDpi);
                int opened = sender.open(3000);
                show("yandex: display " + source.displayId() + " " + vdWidth + "x" + vdHeight
                        + ", someip start ret=" + opened + "\nwaiting for the navigator...");
                if (opened != 0) {
                    return;
                }
                long started = SystemClock.uptimeMillis();
                long due = started;
                long nextPreview = started;
                long nextSave = started;
                Rect crop = new Rect();
                Bitmap previous = null;
                while (!cancelled && SystemClock.uptimeMillis() - started < seconds * 1000L) {
                    due = pace(due, period);
                    long before = SystemClock.elapsedRealtimeNanos();
                    Bitmap frameBitmap = source.latest();
                    if (frameBitmap == null) {
                        continue;
                    }
                    if (frameBitmap == previous) {
                        stale++;
                    }
                    previous = frameBitmap;
                    Bitmap hud = YandexMapSource.crop(frameBitmap, cropX, cropY, cropW,
                            mapWidth, mapHeight, invert, crop);
                    byte[] payload = HudSomeIpSender.mapPayload(FrameArt.pngOf(hud));
                    int result = sender.fire(HudSomeIpSender.TOPIC_HUD_MAP, payload);
                    if (road) {
                        // With no route nobody else says "navigating"; the map-window tests that
                        // showed a picture all sent this beside the map.
                        int roadResult = sender.fire(HudSomeIpSender.TOPIC_HUD_ROAD,
                                HudSomeIpSender.roadPayload(2, false, null, 0, "Yandex HUD map",
                                        "", ""));
                        result = result != 0 ? result : roadResult;
                    }
                    work += (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000L;
                    bytes += payload.length;
                    frame++;
                    if (result == 0) {
                        accepted++;
                    } else {
                        failed++;
                        Log.w(TAG, "yandex frame " + frame + " ret=" + result);
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now >= nextPreview) {
                        nextPreview = now + 500;
                        showPreview(frameBitmap, crop, hud);
                    }
                    if (now >= nextSave) {
                        nextSave = now + 2000;
                        save("yandex-full.png", frameBitmap, crop);
                        save("yandex-hud.png", hud, null);
                    }
                    hud.recycle();
                    if (frame % (fps * 10) == 0) {
                        Log.i(TAG, stats(fps, accepted, failed, stale, work, bytes));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                Log.e(TAG, "yandex channel failed", error);
            } finally {
                // Without a route the probe owned the road event too, so it also clears it.
                sender.close(road, blank);
                if (source != null) {
                    source.close();
                }
                String line = stats(fps, accepted, failed, stale, work, bytes);
                Log.i(TAG, line);
                show(line + "\n" + (cancelled ? "cancelled" : "done") + ", offer withdrawn");
            }
        }

        private String stats(int fps, int accepted, int failed, int stale, long work, long bytes) {
            int sent = accepted + failed;
            return String.format(Locale.ROOT,
                    "yandex %dfps: sent=%d ok=%d fail=%d repeated=%d grab+crop+png+fire avg=%dms"
                            + " payload avg=%dB",
                    fps, sent, accepted, failed, stale, sent == 0 ? 0 : work / sent,
                    sent == 0 ? 0 : bytes / sent);
        }

        private void showPreview(Bitmap frameBitmap, Rect crop, Bitmap hud) {
            int thumbW = 640;
            int thumbH = Math.round(thumbW * frameBitmap.getHeight() / (float) frameBitmap.getWidth());
            Bitmap composed = Bitmap.createBitmap(thumbW + 24 + hud.getWidth() * 2,
                    Math.max(thumbH, hud.getHeight() * 2), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composed);
            canvas.drawColor(Color.BLACK);
            Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(frameBitmap, null, new Rect(0, 0, thumbW, thumbH), filter);
            float scale = thumbW / (float) frameBitmap.getWidth();
            Paint box = new Paint();
            box.setColor(Color.RED);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(4f);
            canvas.drawRect(crop.left * scale, crop.top * scale, crop.right * scale,
                    crop.bottom * scale, box);
            canvas.drawBitmap(hud, null, new Rect(thumbW + 24, 0,
                    thumbW + 24 + hud.getWidth() * 2, hud.getHeight() * 2), filter);
            runOnUiThread(() -> preview.setImageBitmap(composed));
        }

        private void save(String name, Bitmap bitmap, Rect crop) {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            Bitmap toSave = bitmap;
            if (crop != null) {
                toSave = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                Paint box = new Paint();
                box.setColor(Color.RED);
                box.setStyle(Paint.Style.STROKE);
                box.setStrokeWidth(3f);
                new Canvas(toSave).drawRect(crop, box);
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                toSave.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException error) {
                Log.w(TAG, "save " + name + " failed", error);
            } finally {
                if (toSave != bitmap) {
                    toSave.recycle();
                }
            }
        }
    }

    /** Sleeps until `due`, never bursting to catch up; returns the next due time. */
    private static long pace(long due, long period) {
        long now = SystemClock.uptimeMillis();
        if (due > now) {
            SystemClock.sleep(due - now);
        } else if (now - due > period) {
            due = now;
        }
        return due + period;
    }

    private static List<Integer> parseRates(String value) {
        List<Integer> rates = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                rates.add(clamp(Integer.parseInt(part.trim()), 1, MAX_FPS));
            } catch (NumberFormatException ignored) {
                // A malformed entry is skipped rather than guessed.
            }
        }
        if (rates.isEmpty()) {
            rates.add(1);
        }
        return rates;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
