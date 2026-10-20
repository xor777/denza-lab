package dev.denza.clusterglow.probe;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts a Contour scene on the driver's display with the glow at a strength, shape and size picked
 * here on the head unit, so the owner can see on the car's own glass how bright the glow has to be.
 *
 * The picture covers Denza Apps' instruments while this screen is in front and goes the moment it
 * is not: the scenes are fixed boards, not the car's readings, and are for a parked car.
 *
 * {@code --ez preview true} draws the picture in this Activity at the cluster's own 2560 x 720,
 * top-left, with {@code --ef strength}, {@code --ez soft}, {@code --ef size} and
 * {@code --ei scene}, for a screenshot beside the board. {@code --es display <name>} picks another
 * display by name, for an emulator's overlay display.
 */
public final class GlowProbeActivity extends Activity {

    private static final String TAG = "ClusterGlowProbe";
    private static final String CLUSTER_DISPLAY = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int CLUSTER_W = 2560;
    private static final int CLUSTER_H = 720;

    private static final float[] STRENGTHS = {0f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 7f};
    private static final float[] SIZES = {1f, 1.3f, 1.6f};
    private static final int DESIGN_STRENGTH = 1;

    private static final int ON = Color.rgb(67, 159, 252);
    private static final int OFF = Color.rgb(44, 43, 51);

    private Scenes scenes;
    private final List<Bitmap> pictures = new ArrayList<>();
    private int strength = DESIGN_STRENGTH;
    private boolean soft;
    private int size;
    private int scene;

    private Presentation presentation;
    private GlowView clusterView;
    private TextView status;
    private TextView strengthValue;
    private final List<Button> shapeButtons = new ArrayList<>();
    private final List<Button> sizeButtons = new ArrayList<>();
    private final List<Button> sceneButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        try {
            scenes = new Scenes(getAssets());
            for (Scenes.Scene s : scenes.scenes) pictures.add(scenes.picture(s));
        } catch (Exception e) {
            Log.e(TAG, "scenes", e);
            TextView missing = text("Нет сцен в сборке: запустите tools/cluster_glow_scenes.py и соберите заново.", 28);
            missing.setPadding(dp(32), dp(32), dp(32), dp(32));
            setContentView(missing);
            return;
        }
        Intent intent = getIntent();
        if (intent.getBooleanExtra("preview", false)) {
            strength = nearest(STRENGTHS, intent.getFloatExtra("strength", 1f));
            soft = intent.getBooleanExtra("soft", false);
            size = nearest(SIZES, intent.getFloatExtra("size", 1f));
            scene = Math.max(0, Math.min(pictures.size() - 1, intent.getIntExtra("scene", 0)));
            FrameLayout root = new FrameLayout(this);
            clusterView = new GlowView(this, scenes.geometry);
            root.addView(clusterView, new FrameLayout.LayoutParams(CLUSTER_W, CLUSTER_H, Gravity.TOP | Gravity.START));
            setContentView(root);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            render();
            return;
        }
        setContentView(controls());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (scenes == null || getIntent().getBooleanExtra("preview", false)) return;
        String name = getIntent().getStringExtra("display");
        Display display = find(name != null ? name : CLUSTER_DISPLAY);
        if (display == null) {
            status.setText("Экран приборов не найден. Дисплеи: " + displayNames());
            return;
        }
        clusterView = new GlowView(this, scenes.geometry);
        presentation = new ClusterPresentation(this, display, clusterView);
        try {
            presentation.show();
            Log.i(TAG, "shown on display " + display.getDisplayId() + " " + display.getName());
        } catch (RuntimeException e) {
            Log.e(TAG, "show", e);
            presentation = null;
            clusterView = null;
            status.setText("Не удалось показать на экране приборов: " + e.getMessage());
            return;
        }
        render();
    }

    @Override
    protected void onStop() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
            clusterView = null;
            Log.i(TAG, "dismissed");
        }
        super.onStop();
    }

    private void render() {
        Scenes.Scene s = scenes.scenes.get(scene);
        float k = STRENGTHS[strength];
        if (clusterView != null) clusterView.show(pictures.get(scene), s.powerKw, k, soft, SIZES[size]);
        if (status == null) return;
        strengthValue.setText(times(k) + (strength == DESIGN_STRENGTH ? " · сейчас" : ""));
        tint(shapeButtons, soft ? 1 : 0);
        tint(sizeButtons, size);
        tint(sceneButtons, scene);
        float alpha = GlowView.centreAlpha(scenes.geometry, s.powerKw, k);
        int colour = GlowView.into(s.powerKw) ? scenes.geometry.blue : scenes.geometry.ink;
        int brightest = Math.max(Color.red(colour), Math.max(Color.green(colour), Color.blue(colour)));
        String where = clusterView == null ? "Не на приборах. " : "На приборах: ";
        status.setText(String.format(Locale.ROOT, "%sсила %s, форма %s, размер %s, %s. Центр ореола ≈ %d из 255.",
                where, times(k), soft ? "мягкая" : "как в дизайне", times(SIZES[size]),
                sceneName(s), Math.round(alpha * brightest)));
        Log.i(TAG, "strength=" + k + " soft=" + soft + " size=" + SIZES[size] + " scene=" + s.board);
    }

    // ---------------------------------------------------------------- the head unit's controls

    private View controls() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(40), dp(28), dp(40), dp(28));

        column.addView(text("Ореол на приборах", 34));
        TextView note = text("Смотрите на экран приборов. Сцены — картинки доски, не данные машины: только на стоянке.", 20);
        note.setTextColor(Color.rgb(134, 144, 155));
        column.addView(note);

        strengthValue = text("", 30);
        strengthValue.setGravity(Gravity.CENTER);
        LinearLayout strengthRow = row("Сила");
        strengthRow.addView(button("−", v -> step(-1)), weight(1));
        strengthRow.addView(strengthValue, weight(2));
        strengthRow.addView(button("+", v -> step(1)), weight(1));
        strengthRow.addView(button("Как сейчас", v -> { strength = DESIGN_STRENGTH; render(); }), weight(2));
        column.addView(strengthRow);

        LinearLayout shapeRow = row("Форма");
        shapeButtons.add(button("Как в дизайне", v -> { soft = false; render(); }));
        shapeButtons.add(button("Мягкая", v -> { soft = true; render(); }));
        for (Button b : shapeButtons) shapeRow.addView(b, weight(1));
        column.addView(shapeRow);

        LinearLayout sizeRow = row("Размер");
        for (int i = 0; i < SIZES.length; i++) {
            int index = i;
            sizeButtons.add(button(times(SIZES[i]), v -> { size = index; render(); }));
        }
        for (Button b : sizeButtons) sizeRow.addView(b, weight(1));
        column.addView(sizeRow);

        LinearLayout sceneRow = row("Сцена");
        for (int i = 0; i < scenes.scenes.size(); i++) {
            int index = i;
            sceneButtons.add(button(sceneName(scenes.scenes.get(i)), v -> { scene = index; render(); }));
        }
        for (Button b : sceneButtons) sceneRow.addView(b, weight(1));
        column.addView(sceneRow);

        status = text("", 22);
        status.setPadding(0, dp(18), 0, 0);
        column.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    private void step(int by) {
        strength = Math.max(0, Math.min(STRENGTHS.length - 1, strength + by));
        render();
    }

    private LinearLayout row(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, 0);
        TextView name = text(label, 24);
        row.addView(name, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(72));
        b.setBackgroundTintList(ColorStateList.valueOf(OFF));
        b.setOnClickListener(click);
        return b;
    }

    private static void tint(List<Button> buttons, int selected) {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setBackgroundTintList(ColorStateList.valueOf(i == selected ? ON : OFF));
        }
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.setMargins(dp(6), 0, dp(6), 0);
        return p;
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String sceneName(Scenes.Scene s) {
        int kw = Math.round(Math.abs(s.powerKw));
        if (GlowView.into(s.powerKw)) return "Рекуперация " + kw + " кВт";
        return (s.powerKw >= 120f ? "Полный газ " : "Город ") + kw + " кВт";
    }

    private static String times(float k) {
        String s = k == Math.round(k) ? Integer.toString(Math.round(k)) : String.format(Locale.ROOT, "%.1f", k);
        return "×" + s.replace('.', ',');
    }

    private static int nearest(float[] values, float v) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
        }
        return best;
    }

    // ---------------------------------------------------------------- the driver's display

    private Display find(String name) {
        DisplayManager dm = getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays()) {
            if (name.equals(d.getName())) return d;
        }
        return null;
    }

    private String displayNames() {
        StringBuilder sb = new StringBuilder();
        for (Display d : getSystemService(DisplayManager.class).getDisplays()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.getDisplayId()).append(' ').append(d.getName());
        }
        return sb.toString();
    }

    /** Opaque and untouchable, over whatever Denza Apps has on the same display. */
    private static final class ClusterPresentation extends Presentation {
        private final View content;

        ClusterPresentation(Context context, Display display, View content) {
            super(context, display);
            this.content = content;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (getWindow() != null) {
                getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }
            setContentView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
