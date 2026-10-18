package dev.denza.overseamap.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/** Static, app-owned comparison. No car APIs, services, network or permissions. */
public final class MainActivity extends Activity {
    private ComparisonView comparison;
    private Button darkButton;
    private Button lightButton;
    private boolean light;
    private boolean enlargedExport = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            light = state.getBoolean("light");
            enlargedExport = state.getBoolean("enlargedExport", true);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(12));
        root.setBackgroundColor(Color.rgb(16, 20, 26));
        root.addView(text("Штатная карта BYD · сравнение масок", 27, Color.WHITE));
        root.addView(text("Сохранённый кадр с машины. В текущем месте картографические детали не видны.", 17, 0xffb5c2cf));

        LinearLayout controls = new LinearLayout(this);
        darkButton = button("Тёмная маска", controls, () -> setLight(false));
        lightButton = button("Светлая маска", controls, () -> setLight(true));
        button("Закрыть", controls, this::finish);
        LinearLayout.LayoutParams toolbar = new LinearLayout.LayoutParams(-1, dp(58));
        toolbar.topMargin = dp(12);
        root.addView(controls, toolbar);

        try {
            comparison = new ComparisonView(this);
            root.addView(comparison, new LinearLayout.LayoutParams(-1, 0, 1));
            updateSelection();
        } catch (IOException error) {
            TextView failure = text("Не удалось открыть подготовленные изображения: " + error.getMessage(), 20, 0xffffa3a3);
            root.addView(failure);
        }
        root.addView(text("Только сравнение оформления приборки на фрагменте карты. Регион автомобиля здесь не переключается.", 16, 0xffb5c2cf));
        setContentView(root);
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("light", light);
        state.putBoolean("enlargedExport", enlargedExport);
        super.onSaveInstanceState(state);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, LinearLayout parent, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(0, 0, dp(12), 0);
        parent.addView(button, params);
        return button;
    }

    private void setLight(boolean value) {
        light = value;
        updateSelection();
    }

    private void updateSelection() {
        darkButton.setText(light ? "Тёмная маска" : "✓ Тёмная маска");
        lightButton.setText(light ? "✓ Светлая маска" : "Светлая маска");
        if (comparison != null) {
            comparison.setContentDescription("Слева oversea false, справа oversea true. "
                    + (light ? "Светлая маска. " : "Тёмная маска. ")
                    + "Увеличено: " + (enlargedExport ? "с масками" : "без масок"));
            comparison.invalidate();
        }
    }

    private final class ComparisonView extends View {
        private final Bitmap source;
        private final Bitmap dark;
        private final Bitmap bright;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private float scale = 1, offsetX, offsetY;
        private final RectF left = new RectF(0, 72, 1240, 420.75f);
        private final RectF right = new RectF(1320, 72, 2560, 420.75f);
        private final RectF enlarged = new RectF(0, 560, 2560, 1280);

        ComparisonView(Context context) throws IOException {
            super(context);
            source = readBitmap("stock-map.png");
            dark = readBitmap("dark-overlay.png");
            bright = readBitmap("light-overlay.png");
            setClickable(true);
            setFocusable(true);
        }

        private Bitmap readBitmap(String name) throws IOException {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            try (InputStream stream = getAssets().open(name)) {
                Bitmap value = BitmapFactory.decodeStream(stream, null, options);
                if (value == null || value.getWidth() != 2560 || value.getHeight() != 720) {
                    throw new IOException("Некорректный ресурс " + name);
                }
                return value;
            }
        }

        private void label(Canvas canvas, String value, float x, float baseline, int color, float size) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            canvas.drawText(value, x, baseline, paint);
        }

        private void map(Canvas canvas, RectF target, boolean withMasks, boolean selected) {
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(source, null, target, paint);
            if (withMasks) canvas.drawBitmap(light ? bright : dark, null, target, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected ? 4 : 2);
            paint.setColor(selected ? 0xff60c8ff : 0xff415264);
            canvas.drawRect(target, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            scale = Math.min(getWidth() / 2560f, getHeight() / 1330f);
            offsetX = (getWidth() - 2560 * scale) / 2;
            offsetY = (getHeight() - 1330 * scale) / 2;
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            label(canvas, "oversea = false · без масок", 0, 47, Color.WHITE, 44);
            label(canvas, "oversea = true · с масками", 1320, 47, Color.WHITE, 44);
            map(canvas, left, false, !enlargedExport);
            map(canvas, right, true, enlargedExport);
            label(canvas, "Нажми на любой вариант, чтобы увеличить его ниже", 0, 479, 0xffb5c2cf, 35);
            label(canvas, "Крупно: " + (enlargedExport ? "с масками" : "без масок"), 0, 540, Color.WHITE, 42);
            map(canvas, enlarged, enlargedExport, false);
            label(canvas, "Один и тот же фрагмент снимка BYD · маски из cluster_hmi.pck · 2560 × 720", 0, 1325, 0xffb5c2cf, 32);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && scale > 0) {
                float x = (event.getX() - offsetX) / scale;
                float y = (event.getY() - offsetY) / scale;
                if (left.contains(x, y) || right.contains(x, y)) {
                    enlargedExport = right.contains(x, y);
                    updateSelection();
                    performClick();
                }
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
