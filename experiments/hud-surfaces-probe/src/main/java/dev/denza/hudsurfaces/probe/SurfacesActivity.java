package dev.denza.hudsurfaces.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The probe's one screen: a button per scenario and «Сбросить HUD». One scenario runs at a time
 * on one worker thread; the screen shows its phase, what to look at on the glass and the time
 * left. Every step is logged under {@link #TAG}: `adb logcat -s DenzaHudSurfaces`.
 */
public final class SurfacesActivity extends Activity {
    static final String TAG = "DenzaHudSurfaces";
    /** `am start ... --es scenario <id>`, the ids of {@link Scenario}. */
    static final String EXTRA_SCENARIO = "scenario";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Scenario, Button> buttons = new EnumMap<>(Scenario.class);
    private final ProbeArt art = new ProbeArt();
    private HandlerThread thread;
    private Handler worker;
    private TextView phase;
    private TextView hint;
    private TextView line;
    /** Bumped by the reset: a scenario queued before it is dropped. */
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile ScenarioRunner current;
    private volatile String phaseTitle = "Готов. Начните со «Сбросить HUD».";
    private volatile long phaseEndsAt;

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            long left = phaseEndsAt - SystemClock.uptimeMillis();
            phase.setText(left > 0
                    ? String.format(Locale.ROOT, "%s · %d с", phaseTitle, (left + 999) / 1000)
                    : phaseTitle);
            ui.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 32);
        layout.setBackgroundColor(Color.BLACK);
        phase = text(32f, Color.WHITE);
        phase.setText(phaseTitle);
        layout.addView(phase);
        hint = text(24f, Color.rgb(0xFF, 0xD5, 0x4F));
        layout.addView(hint);
        line = text(16f, Color.GRAY);
        layout.addView(line);
        for (Scenario scenario : Scenario.values()) {
            Button button = new Button(this);
            button.setText(scenario.title);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
            button.setMinHeight(140);
            button.setOnClickListener(v -> start(scenario));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = scenario == Scenario.RESET ? 40 : 12;
            layout.addView(button, params);
            buttons.put(scenario, button);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(layout);
        setContentView(scroll);
        thread = new HandlerThread("hud-surfaces");
        thread.start();
        worker = new Handler(thread.getLooper());
        ui.post(countdown);
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
        ui.removeCallbacksAndMessages(null);
        ScenarioRunner runner = current;
        if (runner != null) {
            runner.cancel();
        }
        // The running scenario's reset finishes on the worker before the thread quits.
        worker.post(thread::quitSafely);
        super.onDestroy();
    }

    private void handle(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra(EXTRA_SCENARIO);
        if (id == null) {
            return;
        }
        Scenario scenario = Scenario.byId(id);
        if (scenario == null) {
            Log.w(TAG, "unknown scenario " + id);
            return;
        }
        start(scenario);
    }

    /**
     * A scenario goes to the worker after whatever runs now, which is cut short first. The reset
     * also drops anything queued; it cuts the running scenario short - whose own reset then runs -
     * or, with nothing running, opens a session only to clear.
     */
    private void start(Scenario scenario) {
        int ticket = scenario == Scenario.RESET
                ? generation.incrementAndGet()
                : generation.get();
        ScenarioRunner runner = current;
        if (runner != null) {
            runner.cancel();
            if (scenario == Scenario.RESET) {
                return;
            }
        }
        queued.incrementAndGet();
        setRunning(true);
        worker.post(() -> {
            try {
                if (ticket == generation.get()) {
                    run(scenario);
                } else {
                    Log.i(TAG, "scenario " + scenario.id + " dropped by reset");
                }
            } finally {
                if (queued.decrementAndGet() == 0) {
                    ui.post(() -> setRunning(false));
                }
            }
        });
    }

    private void run(Scenario scenario) {
        ScenarioRunner runner = new ScenarioRunner(this, art, new ScenarioRunner.Listener() {
            @Override
            public void onPhase(String title, String text, long millis) {
                phaseTitle = scenario.title + " — " + title;
                phaseEndsAt = SystemClock.uptimeMillis() + millis;
                ui.post(() -> hint.setText(text));
            }

            @Override
            public void onLine(String text) {
                ui.post(() -> line.setText(text));
            }
        });
        current = runner;
        try {
            runner.run(scenario);
        } finally {
            current = null;
            phaseTitle = scenario.title + " — готово";
            phaseEndsAt = 0L;
            ui.post(() -> hint.setText(""));
        }
    }

    private void setRunning(boolean running) {
        for (Map.Entry<Scenario, Button> entry : buttons.entrySet()) {
            entry.getValue().setEnabled(!running || entry.getKey() == Scenario.RESET);
        }
    }

    private TextView text(float sp, int color) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, 8, 0, 8);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }
}
