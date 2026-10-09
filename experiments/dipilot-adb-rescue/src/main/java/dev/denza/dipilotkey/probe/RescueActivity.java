package dev.denza.dipilotkey.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One explicit action, with a continuation after manual service setup if shell cannot enable it. */
public final class RescueActivity extends Activity {
    private static final String PENDING_SETTINGS = "pending_service_settings";
    // Present in this vehicle's Settings framework, but hidden from the public Android SDK.
    private static final String ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView report;
    private Button restore;
    private SharedPreferences preferences;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("rescue", MODE_PRIVATE);
        setContentView(buildLayout());
        if (savedInstanceState != null) {
            report.setText(savedInstanceState.getCharSequence("report", report.getText()));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy && preferences.getBoolean(PENDING_SETTINGS, false)
                && PromptClicker.enabled(this)) {
            // Consume the continuation before running; a failure must not reopen Settings in a loop.
            runRescue(false);
        } else if (preferences.getBoolean(PENDING_SETTINGS, false)) {
            report.setText("Ожидаю включения службы «Ключ Dipilot». После включения вернитесь "
                    + "сюда — восстановление продолжится автоматически.\n\n" + report.getText());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putCharSequence("report", report.getText());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        PromptClicker.disarm();
        worker.shutdownNow();
        super.onDestroy();
    }

    private ViewGroup buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        restore = new Button(this);
        restore.setText("Восстановить ADB");
        restore.setAllCaps(false);
        restore.setOnClickListener(v -> runRescue(true));
        root.addView(restore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextColor(Color.WHITE);
        report.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(12), 0, 0);
        report.setText("Ключ Dipilot · 0.2.0\nНажмите «Восстановить ADB». Сначала проверю shell, "
                + "затем подготовлю службу нажатий, проверю окна и очередь.\n"
                + "Отчёт можно выделить и скопировать долгим нажатием.");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void runRescue(boolean mayOpenSettings) {
        if (busy) {
            return;
        }
        preferences.edit().remove(PENDING_SETTINGS).apply();
        setBusy(true);
        report.setText("Подключаюсь ключом Dipilot…");
        worker.execute(() -> {
            PortRescue.Result result;
            String[] lastSnapshot = {""};
            try {
                result = new PortRescue(RescueActivity.this).run(snapshot -> {
                    lastSnapshot[0] = snapshot;
                    main.post(() -> {
                        if (!isDestroyed()) {
                            report.setText(snapshot);
                        }
                    });
                });
            } catch (Exception error) {
                PromptClicker.disarm();
                String message = error.getMessage();
                result = new PortRescue.Result(
                        (lastSnapshot[0].isEmpty()
                                ? "Shell: НЕ ПРОВЕРЕН — подготовка клиента не удалась.\n"
                                : lastSnapshot[0])
                                + "\nВосстановление прервано. Сбой: "
                                + error.getClass().getSimpleName()
                                + (message == null ? "" : " " + QueueDrain.redact(message)), false);
            }
            PortRescue.Result completed = result;
            main.post(() -> {
                if (!isDestroyed()) {
                    report.setText(completed.report);
                    setBusy(false);
                    if (completed.needsServiceSettings && mayOpenSettings) {
                        // Persist before leaving the app, including a process death in Settings.
                        preferences.edit().putBoolean(PENDING_SETTINGS, true).apply();
                        openAccessibilitySettings();
                    } else if (completed.needsServiceSettings) {
                        report.append("\nСлужба по-прежнему недоступна. Проверьте её настройки "
                                + "и нажмите «Восстановить ADB» ещё раз.\n");
                    }
                }
            });
        });
    }

    private void openAccessibilitySettings() {
        Intent details = new Intent(ACCESSIBILITY_DETAILS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(this, PromptClicker.class));
        try {
            startActivity(details);
        } catch (Exception unavailable) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception fallbackUnavailable) {
                report.append("\nНастройки не открылись ("
                        + fallbackUnavailable.getClass().getSimpleName()
                        + "). Откройте специальные возможности и включите «Ключ Dipilot» вручную.\n");
            }
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        restore.setEnabled(!busy);
        restore.setText(busy ? "Восстанавливаю…" : "Восстановить ADB");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
