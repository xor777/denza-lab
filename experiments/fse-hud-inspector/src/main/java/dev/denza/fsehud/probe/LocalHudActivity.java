package dev.denza.fsehud.probe;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Own test pattern only; no DiShare, automotive API, external intents or hidden-display fallback. */
public final class LocalHudActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LocalHudProbe.Session session;
    private Pattern pattern;
    private Window.OnFrameMetricsAvailableListener frames;
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (session == null || !session.active) return;
            if (!correctDisplay()) { session.finish("unexpected_display"); return; }
            session.event("render_sample", "display", LocalHudProbe.describe(getDisplay()),
                    "view_width", pattern.getWidth(), "view_height", pattern.getHeight(),
                    "shown", pattern.isShown(), "window_focus", hasWindowFocus());
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = LocalHudProbe.attach(this, getIntent().getStringExtra("session"));
        if (session == null) { finishAndRemoveTask(); return; }
        session.event("activity_create", "task_id", getTaskId(),
                "actual_display", LocalHudProbe.describe(getDisplay()), "recreated", savedInstanceState != null);
        if (savedInstanceState != null || !correctDisplay()) {
            session.finish(savedInstanceState != null ? "activity_recreated" : "unexpected_display");
            return;
        }
        // FSE PhoneWindow.getInsetsController dereferences mDecor directly. Create it first.
        pattern = new Pattern();
        setContentView(pattern);
        session.event("content_view_installed");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        session.event("window_configured");
        frames = (window, metrics, dropped) -> {
            if (session.active) session.windowFrames++;
        };
        getWindow().addOnFrameMetricsAvailableListener(frames, ui);
        ui.post(sample);
    }

    private boolean correctDisplay() {
        Display d = getDisplay();
        return d != null && d.isValid() && d.getDisplayId() == session.displayId &&
                "arhud".equalsIgnoreCase(d.getName());
    }

    void closeOwnTask() {
        ui.removeCallbacks(sample);
        if (pattern != null) pattern.invalidate();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isFinishing()) finishAndRemoveTask();
    }

    @Override protected void onStart() { super.onStart(); log("activity_start"); }
    @Override protected void onResume() { super.onResume(); log("activity_resume"); }
    @Override protected void onPause() { log("activity_pause"); super.onPause(); }
    @Override protected void onStop() {
        log("activity_stop");
        if (session != null && session.active) session.finish("activity_stopped");
        super.onStop();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (frames != null) getWindow().removeOnFrameMetricsAvailableListener(frames);
        if (session != null) {
            if (session.active) session.finish("activity_destroyed");
            log("activity_destroy");
            session.activity.clear();
        }
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (session != null) session.event("window_focus", "focused", focused);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (session != null) session.event("configuration_changed", "display", LocalHudProbe.describe(getDisplay()));
    }
    private void log(String name) { if (session != null) session.event(name); }

    private final class Pattern extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pattern() { super(LocalHudActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            if (!session.active) return;
            if (!correctDisplay()) { session.finish("unexpected_display"); return; }
            if (SystemClock.elapsedRealtime() >= session.deadline) {
                session.finish("timeout_20_seconds");
                return;
            }
            session.draws++;
            int w = getWidth(), h = getHeight();
            long elapsed = SystemClock.elapsedRealtime() - (session.deadline - LocalHudProbe.DURATION_MS);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(Color.rgb(170, 205, 190));
            canvas.drawRect(2, 2, w - 3, h - 3, paint);
            paint.setColor(Color.rgb(55, 75, 65));
            for (int x = 100; x < w; x += 100) canvas.drawLine(x, 0, x, h, paint);
            for (int y = 100; y < h; y += 100) canvas.drawLine(0, y, w, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(22);
            paint.setColor(Color.LTGRAY);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("0,0", 12, 28, paint);
            for (int x = 100; x < w - 80; x += 100) canvas.drawText(Integer.toString(x), x + 4, 28, paint);
            for (int y = 100; y < h - 30; y += 100) canvas.drawText(Integer.toString(y), 10, y - 6, paint);
            canvas.drawText("0," + (h - 1), 12, h - 16, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText((w - 1) + ",0", w - 12, 56, paint);
            canvas.drawText((w - 1) + "," + (h - 1), w - 12, h - 16, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setTextSize(34);
            canvas.drawText("LOCAL FSE · " + w + "×" + h + " · display " + session.displayId,
                    w / 2f, h / 2f - 80, paint);
            paint.setTextSize(58);
            canvas.drawText(String.format(java.util.Locale.ROOT, "%.1f s", elapsed / 1000f),
                    w / 2f, h / 2f, paint);
            paint.setTextSize(28);
            canvas.drawText("DRAW " + session.draws, w / 2f, h / 2f + 48, paint);
            paint.setColor(Color.rgb(120, 210, 165));
            float x = 20 + (w - 80) * (elapsed % 2000) / 2000f;
            canvas.drawRect(x, h - 90, x + 40, h - 65, paint);
            if (session.draws == 1) session.event("first_draw", "width", w, "height", h);
            postInvalidateDelayed(33);
        }
    }
}
