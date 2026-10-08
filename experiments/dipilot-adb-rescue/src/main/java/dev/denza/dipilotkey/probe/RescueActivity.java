package dev.denza.dipilotkey.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen. It connects as BydDipilot and approves the ADB prompts whose windows are hidden.
 */
public final class RescueActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView report;
    private Button free;
    private Button service;
    private Button copy;
    private boolean busy;
    private boolean autoRan;
    private boolean sawServiceOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = PromptClicker.enabled(this);
        if (!autoRan) {
            autoRan = true;
            sawServiceOff = !enabled;
            runRescue();
        } else if (sawServiceOff && enabled && !busy) {
            sawServiceOff = false;
            runRescue();
        }
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

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        free = addButton(buttons, "Освободить порт", v -> runRescue());
        service = addButton(buttons, "Включить нажатия", v -> openAccessibilitySettings());
        copy = addButton(buttons, "Скопировать", v -> copyReport());
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextColor(Color.WHITE);
        report.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(12), 0, 0);
        report.setText("Ключ Dipilot. Освобождаю порт…");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button addButton(LinearLayout row, String label, android.view.View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(6));
        row.addView(button, params);
        return button;
    }

    private void runRescue() {
        setBusy(true);
        report.setText("Подключаюсь ключом Dipilot…");
        worker.execute(() -> {
            String text;
            try {
                text = new PortRescue(RescueActivity.this).rescue(snapshot -> main.post(() -> {
                    if (!isDestroyed()) {
                        report.setText(snapshot);
                    }
                }));
            } catch (Throwable error) {
                PromptClicker.disarm();
                String message = error.getMessage();
                text = "Shell: НЕ ПРОВЕРЕН — подготовка клиента не удалась.\nСбой: "
                        + error.getClass().getSimpleName()
                        + (message == null ? "" : " " + QueueDrain.redact(message));
            }
            String result = text;
            main.post(() -> {
                if (!isDestroyed()) {
                    report.setText(result);
                    setBusy(false);
                }
            });
        });
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception unavailable) {
            report.setText("Экран специальных возможностей не открылся ("
                    + unavailable.getClass().getSimpleName() + ").\n\n" + report.getText());
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Ключ Dipilot", report.getText()));
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        free.setEnabled(!busy);
        service.setEnabled(!busy);
        copy.setEnabled(!busy);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
