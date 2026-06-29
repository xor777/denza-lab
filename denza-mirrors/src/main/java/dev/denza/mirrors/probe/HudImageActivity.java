package dev.denza.mirrors.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class HudImageActivity extends Activity {
    private static final String TAG = "DenzaHudImage";
    private static final long DEFAULT_DURATION_MS = 60000L;
    private static final long DEFAULT_START_DELAY_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String label = getIntent().getStringExtra("label");
        if (label == null || label.trim().isEmpty()) {
            label = "HUD IMAGE TEST";
        }
        setContentView(new TestImageView(this, label));

        if (getIntent().getBooleanExtra("start_hud", false)) {
            handler.postDelayed(this::startHudShare,
                    getIntent().getLongExtra("start_delay_ms", DEFAULT_START_DELAY_MS));
        }

        long durationMs = getIntent().getLongExtra("duration_ms", DEFAULT_DURATION_MS);
        if (durationMs > 0) {
            handler.postDelayed(this::finishAndRemoveTask, durationMs);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startHudShare() {
        Log.i(TAG, "requesting hud share for " + getPackageName());
        Intent intent = new Intent();
        intent.putExtra("command", "start_hud");
        intent.putExtra("app", getPackageName());
        DiShareProbeReceiver.runProbe(this, intent,
                () -> Log.i(TAG, "hud share request finished"));
    }

    private static final class TestImageView extends View {
        private final String label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TestImageView(Activity activity, String label) {
            super(activity);
            this.label = label;
            setKeepScreenOn(true);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] {
                            Color.rgb(12, 20, 38),
                            Color.rgb(16, 94, 88),
                            Color.rgb(220, 86, 42)
                    },
                    new float[] {0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            drawTarget(canvas, width, height);
            drawGrid(canvas, width, height);
            drawLabels(canvas, width, height);
        }

        private void drawTarget(Canvas canvas, int width, int height) {
            float centerX = width * 0.5f;
            float centerY = height * 0.48f;
            float radius = Math.min(width, height) * 0.24f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 222, 70));
            canvas.drawCircle(centerX, centerY, radius, paint);

            paint.setColor(Color.rgb(14, 42, 63));
            canvas.drawCircle(centerX, centerY, radius * 0.72f, paint);

            paint.setColor(Color.rgb(255, 255, 255));
            canvas.drawCircle(centerX, centerY, radius * 0.42f, paint);

            paint.setColor(Color.rgb(220, 42, 58));
            canvas.drawCircle(centerX, centerY, radius * 0.2f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(6f, width * 0.004f));
            paint.setColor(Color.WHITE);
            canvas.drawLine(centerX - radius * 1.45f, centerY, centerX + radius * 1.45f, centerY,
                    paint);
            canvas.drawLine(centerX, centerY - radius * 1.2f, centerX, centerY + radius * 1.2f,
                    paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawGrid(Canvas canvas, int width, int height) {
            float margin = width * 0.055f;
            float top = height * 0.14f;
            float bottom = height * 0.84f;
            float cell = Math.max(18f, width * 0.018f);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.argb(90, 255, 255, 255));
            for (float x = margin; x < width - margin; x += cell) {
                canvas.drawLine(x, top, x, bottom, paint);
            }
            for (float y = top; y < bottom; y += cell) {
                canvas.drawLine(margin, y, width - margin, y, paint);
            }

            paint.setStrokeWidth(Math.max(5f, width * 0.003f));
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(margin, top, width - margin, bottom),
                    18f, 18f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawLabels(Canvas canvas, int width, int height) {
            textPaint.setTextSize(Math.max(48f, width * 0.045f));
            canvas.drawText(label, width * 0.5f, height * 0.11f, textPaint);

            textPaint.setTextSize(Math.max(24f, width * 0.018f));
            textPaint.setFakeBoldText(false);
            canvas.drawText("source: dev.denza.mirrors",
                    width * 0.5f, height * 0.9f, textPaint);
            canvas.drawText("not bilibili - generated in our APK",
                    width * 0.5f, height * 0.95f, textPaint);
            textPaint.setFakeBoldText(true);
        }
    }
}
