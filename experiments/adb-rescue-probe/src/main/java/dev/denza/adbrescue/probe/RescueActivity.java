package dev.denza.adbrescue.probe;

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
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen, four buttons, and a report that can be photographed or copied.
 *
 * <p>The whole point of the probe is that it runs on a car nobody can reach, in front of an owner
 * who can only send back what is on the screen. So everything it learns goes into one block of
 * text, in the order someone would need to read it out, and nothing is hidden behind a gesture.
 */
public final class RescueActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RescueRunner runner;
    private TextView report;
    private Button check;
    private Button request;
    private Button rescue;
    private Button wifi;
    private Button copy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runner = new RescueRunner(this);
        setContentView(buildLayout());
        runCheck();
    }

    @Override
    protected void onDestroy() {
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
        check = addButton(buttons, "Проверить доступ", v -> runCheck());
        request = addButton(buttons, "Запросить доступ", v -> runRequest());
        rescue = addButton(buttons, "Спасти Denza Apps", v -> runRescue());
        wifi = addButton(buttons, "Отладка по Wi-Fi", v -> openDeveloperOptions());
        copy = addButton(buttons, "Скопировать", v -> copyReport());
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextColor(Color.WHITE);
        report.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(12), 0, 0);

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

    // --- actions --------------------------------------------------------------------------

    private void runCheck() {
        work("Проверяю доступ (публичный ключ не отправляется)…", () -> fullReport(null));
    }

    private void runRequest() {
        work("Отправляю запрос…", () -> {
            String outcome = runner.requestOnce();
            return fullReport("Результат запроса: " + outcome);
        });
    }

    private void runRescue() {
        work("Чиню Denza Apps…", () -> {
            RescueRunner.Access access = runner.check();
            if (access != RescueRunner.Access.TRUSTED) {
                return fullReport("Починка невозможна: доступа к shell нет.");
            }
            return "== ПОЧИНКА DENZA APPS ==\n\n" + runner.rescueDenzaApps()
                    + "\n\n" + fullReport(null);
        });
    }

    /**
     * Opens the developer options screen, where pairing by code lives.
     *
     * <p>The button exists because finding that screen on this firmware is its own problem, and
     * because whether it opens at all is itself the answer: a car that has no developer options
     * has no pairing path either, and that closes the last different mechanism without the owner
     * hunting through menus to find out.
     */
    private void openDeveloperOptions() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception unavailable) {
            report.setText("На этой машине нет экрана «Для разработчиков»"
                    + " (" + unavailable.getClass().getSimpleName() + ").\n\n"
                    + "Значит, сопряжение по коду отсюда не включить -"
                    + " это был последний отличающийся путь.\n\n"
                    + report.getText());
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Спасатель", report.getText()));
            toast("Отчёт скопирован");
        }
    }

    /**
     * Runs one action off the main thread with every button disabled.
     *
     * <p>Serialising the buttons is not politeness: two of them put a public key or a rejection
     * into the vehicle's prompt queue, and a double press would spend two slots for one intent.
     */
    private void work(String progress, Job job) {
        setBusy(true);
        report.setText(progress);
        worker.execute(() -> {
            String text;
            try {
                text = job.run();
            } catch (Throwable error) {
                text = "Сбой: " + error.getClass().getSimpleName() + " " + error.getMessage();
            }
            String result = text;
            main.post(() -> {
                report.setText(result);
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        check.setEnabled(!busy);
        request.setEnabled(!busy);
        rescue.setEnabled(!busy);
        wifi.setEnabled(!busy);
        copy.setEnabled(!busy);
    }

    private interface Job {
        String run() throws Exception;
    }

    // --- the report -----------------------------------------------------------------------

    private String fullReport(String note) {
        RescueRunner.Access access = runner.check();
        String switchText = runner.systemSwitch();
        boolean readable = !switchText.contains("не удалось");
        boolean on = switchText.startsWith("включена");

        Map<String, String> properties = runner.systemProperties();
        String factoryFlag = properties.get("persist.sys.factory.version.flag.config");

        StringBuilder text = new StringBuilder();
        text.append(RescueRunner.verdict(access, on, readable, factoryFlag)).append("\n\n");
        if (note != null) {
            text.append(note).append("\n\n");
        }
        text.append("Отладка по ADB в системе: ").append(switchText).append('\n');
        text.append("Локальный ADB: ").append(runner.checkDetail(access)).append('\n');
        text.append("Отладка по Wi-Fi: ").append(runner.wifiDebugging()).append('\n');
        text.append("Адреса машины: ").append(runner.localAddresses()).append('\n');
        text.append("Denza Apps: ").append(runner.denzaAppsDescription()).append('\n');
        text.append("Отпечаток ключа спасателя:\n  ").append(runner.fingerprint()).append('\n');
        text.append("Запросов отправлено этим приложением: ")
                .append(runner.requestsSent()).append('\n');

        text.append("Заводской режим (авто-разрешение ADB): ")
                .append("1".equals(factoryFlag) ? "ВКЛЮЧЁН"
                        : "0".equals(factoryFlag) ? "выключен" : "не прочитан")
                .append('\n');

        text.append("\nСвойства системы:\n");
        for (Map.Entry<String, String> property : properties.entrySet()) {
            text.append("  ").append(property.getKey()).append(" = ")
                    .append(property.getValue()).append('\n');
        }

        text.append("\nЧто делать:\n");
        switch (access) {
            case TRUSTED:
                text.append("  Нажмите «Спасти Denza Apps».\n");
                break;
            case AUTHORIZATION_REQUIRED:
                if (readable && !on) {
                    text.append("  Отладку по ADB включают в машине через сервис. "
                            + "Запрос отсюда ничего не изменит.\n");
                } else if ("0".equals(factoryFlag)) {
                    text.append("  Заводской флаг выключен. На этой прошивке ADB выдаётся "
                            + "именно им - диалога тут не бывает.\n");
                    text.append("  В сервисе просят выставить "
                            + "persist.sys.factory.version.flag.config = 1.\n");
                } else {
                    text.append("  Нажмите «Запросить доступ» и смотрите на экран машины: "
                            + "должен появиться диалог с отпечатком выше.\n");
                    text.append("  Если диалога нет - сфотографируйте этот экран и пришлите.\n");
                }
                break;
            case UNAVAILABLE:
                text.append("  Локальный adbd молчит. Это чинится только в машине, "
                        + "приложением не лечится.\n");
                break;
            default:
                text.append("  Сфотографируйте этот экран и пришлите.\n");
                break;
        }
        return text.toString();
    }

    private void toast(String message) {
        android.widget.Toast toast =
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
