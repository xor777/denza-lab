package dev.denza.hudframes.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;

import java.util.Locale;

/**
 * The video channel's picture. Cast to `screen_hud` through DiShare it lands on BYD-Mirror and
 * is encoded as H.264; on the HUD it shows whether moving video survives the whole path and how
 * smoothly. Black is dark on a HUD, so the pattern is thin white marks on black.
 */
public final class PatternActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new PatternView(this));
        Display display = getDisplay();
        Log.i(HudSomeIpSender.TAG, "pattern on display " + (display == null ? "?"
                : display.getDisplayId() + " " + display.getName()));
    }

    private static final class PatternView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long startedAt = SystemClock.uptimeMillis();
        private long frames;
        private long windowStart = startedAt;
        private long windowFrames;
        private float drawFps;

        PatternView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            big.setColor(Color.WHITE);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            small.setColor(Color.WHITE);
            small.setTypeface(Typeface.MONOSPACE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            long elapsed = now - startedAt;
            frames++;
            windowFrames++;
            if (now - windowStart >= 1000) {
                drawFps = windowFrames * 1000f / (now - windowStart);
                windowStart = now;
                windowFrames = 0;
            }
            float width = getWidth();
            float height = getHeight();
            float unit = Math.min(width, height) / 20f;

            // A bar sweeping at constant speed, one pass every two seconds: judder, dropped
            // frames and freezes show as jumps or stops in its motion.
            stroke.setStrokeWidth(unit / 2f);
            float phase = (elapsed % 2000L) / 2000f;
            float barX = unit + (width - 2 * unit) * phase;
            canvas.drawLine(barX, height * 0.72f, barX, height - unit, stroke);
            canvas.drawLine(unit, height - unit, width - unit, height - unit, stroke);

            // A hand that turns once a second and a frame counter.
            float cx = width * 0.3f;
            float cy = height * 0.38f;
            float radius = height * 0.26f;
            canvas.drawCircle(cx, cy, radius, stroke);
            double angle = Math.toRadians(-90 + 360.0 * (elapsed % 1000L) / 1000.0);
            canvas.drawLine(cx, cy, (float) (cx + radius * 0.9 * Math.cos(angle)),
                    (float) (cy + radius * 0.9 * Math.sin(angle)), stroke);

            big.setTextSize(height * 0.3f);
            Paint.FontMetrics metrics = big.getFontMetrics();
            canvas.drawText(Long.toString(frames % 1000), width * 0.68f,
                    cy - (metrics.ascent + metrics.descent) / 2f, big);

            small.setTextSize(unit * 1.2f);
            canvas.drawText(String.format(Locale.ROOT, "%d.%03d s   draw %.0f fps",
                    elapsed / 1000, elapsed % 1000, drawFps), unit, unit * 2f, small);
            postInvalidateOnAnimation();
        }
    }
}
