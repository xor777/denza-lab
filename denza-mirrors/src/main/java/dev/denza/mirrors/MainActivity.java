package dev.denza.mirrors;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String SERVICE_DESCRIPTOR =
            "com.byd.cluster.projectionmanager.service.IContentProjectionManager";
    private static final ComponentName PROJECTION_SERVICE = new ComponentName(
            "com.example.amapservice",
            "com.byd.cluster.projectionmanager.service.BydProjectionService"
    );

    private static final int TRANSACTION_START = 3;
    private static final int TRANSACTION_STOP = 4;

    private static final int CLUSTER_FULL = 0;
    private static final int CLUSTER_LEFT = 1;

    private static final int MAP_VIEW = 8;
    private static final int MINI_MAP_CARD = 9;
    private static final int TBT_CARD = 15;
    private static final int DASH_DISPLAY_ID = 4;
    private static final int PREVIEW_CENTER_EXTEND_PERCENT = 20;
    private static final long VISIBLE_DIAGNOSTIC_PREVIEW_DURATION_MS = 2200L;
    private static final long INVISIBLE_DIAGNOSTIC_PREVIEW_DURATION_MS = 1000L;
    private static final int STATE_STOPPED = 0;
    private static final int STATE_CHECKING = 1;
    private static final int STATE_READY = 2;
    private static final int STATE_ERROR = 3;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatusFromService();
            uiHandler.postDelayed(this, 1000L);
        }
    };
    private TextView statusBadge;
    private TextView statusMessageView;
    private TextView statusDetailView;
    private TextView logView;
    private ScrollView logScrollView;
    private Button monitorButton;
    private boolean diagnosticsRunning;
    private IBinder projectionBinder;
    private String pendingCommand;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            projectionBinder = service;
            appendLog("connected " + name.flattenToShortString());
            runPendingCommand();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            projectionBinder = null;
            appendLog("disconnected " + name.flattenToShortString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        requestNotificationPermission();
        appendLog("package=" + getPackageName());
        setPendingCommand(getIntent());
        if (pendingCommand == null) {
            startMonitorFromUi("monitor auto-start requested");
            appendLog("ready");
        } else if (isMonitorCommand(pendingCommand)) {
            runPendingCommand();
        } else {
            bindProjectionService();
        }
        uiHandler.post(statusRefreshRunnable);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        setPendingCommand(intent);
        if (pendingCommand == null) {
            startMonitorFromUi("monitor auto-start requested");
        } else {
            runPendingCommand();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (projectionBinder != null) {
            unbindService(connection);
            projectionBinder = null;
        }
        super.onDestroy();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        TextView title = new TextView(this);
        title.setText("Denza Mirrors");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        contentParams.setMargins(0, dp(16), 0, 0);
        root.addView(content, contentParams);

        LinearLayout controls = panel();
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.44f
        );
        controlsParams.setMargins(0, 0, dp(14), 0);
        content.addView(controls, controlsParams);

        statusBadge = new TextView(this);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setTextSize(32);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setTextColor(Color.WHITE);
        statusBadge.setAllCaps(false);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88)
        );
        badgeParams.setMargins(0, 0, 0, dp(12));
        controls.addView(statusBadge, badgeParams);

        statusMessageView = new TextView(this);
        statusMessageView.setTextColor(Color.rgb(224, 237, 242));
        statusMessageView.setTextSize(17);
        statusMessageView.setGravity(Gravity.CENTER);
        controls.addView(statusMessageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        statusDetailView = new TextView(this);
        statusDetailView.setTextColor(Color.rgb(130, 150, 158));
        statusDetailView.setTextSize(13);
        statusDetailView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailParams.setMargins(0, dp(6), 0, 0);
        controls.addView(statusDetailView, detailParams);

        monitorButton = primaryButton("Stop", v -> toggleMonitor());
        LinearLayout.LayoutParams monitorButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
        );
        monitorButtonParams.setMargins(0, dp(22), 0, dp(16));
        controls.addView(monitorButton, monitorButtonParams);

        Button testButton = secondaryButton("Проверить", v -> runDiagnostics(true));
        LinearLayout.LayoutParams testButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        testButtonParams.setMargins(0, 0, 0, dp(18));
        controls.addView(testButton, testButtonParams);

        controls.addView(sectionTitle("Положение камеры"));
        RadioGroup positionGroup = new RadioGroup(this);
        positionGroup.setOrientation(RadioGroup.HORIZONTAL);
        positionGroup.setGravity(Gravity.CENTER_VERTICAL);
        RadioButton sidesRadio = radioButton("По сторонам");
        RadioButton centerRadio = radioButton("По центру");
        sidesRadio.setId(View.generateViewId());
        centerRadio.setId(View.generateViewId());
        positionGroup.addView(sidesRadio, new RadioGroup.LayoutParams(
                0,
                RadioGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        positionGroup.addView(centerRadio, new RadioGroup.LayoutParams(
                0,
                RadioGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        String mode = SideCameraOverlayMonitorService.getCameraPositionMode(this);
        positionGroup.check(SideCameraOverlayMonitorService.CAMERA_POSITION_CENTER.equals(mode)
                ? centerRadio.getId()
                : sidesRadio.getId());
        positionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selectedMode = checkedId == centerRadio.getId()
                    ? SideCameraOverlayMonitorService.CAMERA_POSITION_CENTER
                    : SideCameraOverlayMonitorService.CAMERA_POSITION_SIDES;
            SideCameraOverlayMonitorService.setCameraPositionMode(this, selectedMode);
            appendLog("camera position mode=" + selectedMode);
        });
        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        radioParams.setMargins(0, dp(8), 0, dp(16));
        controls.addView(positionGroup, radioParams);

        controls.addView(sectionTitle("Обработка изображения"));
        TextView imageValue = smallLabel(
                SideCameraOverlayMonitorService.getImageEnhancementStrength(this) + "%");
        imageValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams imageValueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        imageValueParams.setMargins(0, dp(4), 0, 0);
        controls.addView(imageValue, imageValueParams);

        SeekBar imageSeekBar = new SeekBar(this);
        imageSeekBar.setMax(100);
        imageSeekBar.setProgress(SideCameraOverlayMonitorService.getImageEnhancementStrength(this));
        imageSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                imageValue.setText(progress + "%");
                if (fromUser) {
                    SideCameraOverlayMonitorService.setImageEnhancementStrength(
                            MainActivity.this, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                SideCameraOverlayMonitorService.setImageEnhancementStrength(
                        MainActivity.this, progress);
                appendLog("image processing=" + progress + "%");
            }
        });
        LinearLayout.LayoutParams imageSliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        imageSliderParams.setMargins(0, dp(4), 0, 0);
        controls.addView(imageSeekBar, imageSliderParams);

        LinearLayout imageScale = new LinearLayout(this);
        imageScale.setOrientation(LinearLayout.HORIZONTAL);
        TextView imageScaleStart = smallLabel("0");
        TextView imageScaleEnd = smallLabel("100");
        imageScaleEnd.setGravity(Gravity.END);
        imageScale.addView(imageScaleStart, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        imageScale.addView(imageScaleEnd, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        LinearLayout.LayoutParams imageScaleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        imageScaleParams.setMargins(0, 0, 0, dp(12));
        controls.addView(imageScale, imageScaleParams);

        controls.addView(spacer(0, 1f));

        LinearLayout guide = panel();
        content.addView(guide, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.56f
        ));
        guide.addView(sectionTitle("Как это работает"));
        guide.addView(bodyText("Когда штатная система показывает камеру поворотника, Denza Mirrors открывает большое зеркало на экране перед водителем."));
        guide.addView(bodyText("Ready означает, что внутренний ADB отвечает, экран перед водителем найден, а монитор включён."));
        guide.addView(bodyText("«По сторонам» показывает камеры у краёв экрана. «По центру» открывает любую камеру в центре экрана."));
        guide.addView(bodyText("«Обработка изображения»: 0 - штатная картинка, 100 - полный пресет."));
        guide.addView(bodyText("Если статус красный, посмотри короткое сообщение слева и нажми «Проверить» после загрузки системы."));

        TextView diagnosticsTitle = sectionTitle("Диагностика");
        LinearLayout.LayoutParams diagnosticsTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        diagnosticsTitleParams.setMargins(0, dp(22), 0, 0);
        guide.addView(diagnosticsTitle, diagnosticsTitleParams);

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(168, 194, 204));
        logView.setTextSize(12);
        logView.setLineSpacing(0, 1.08f);

        logScrollView = new ScrollView(this);
        logScrollView.setBackground(rounded(Color.rgb(9, 16, 20), dp(8)));
        logScrollView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logScrollView.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        logParams.setMargins(0, dp(10), 0, 0);
        guide.addView(logScrollView, logParams);

        applyUiState(STATE_CHECKING, "Checking", "Проверяю монитор камер...", "");
        return root;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(rounded(Color.rgb(11, 18, 23), dp(8)));
        return panel;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        return title;
    }

    private TextView bodyText(String text) {
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(Color.rgb(203, 220, 226));
        body.setTextSize(16);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, 0);
        body.setLayoutParams(params);
        return body;
    }

    private TextView smallLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(151, 173, 181));
        label.setTextSize(13);
        return label;
    }

    private View spacer(int heightDp, float weight) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp),
                weight
        ));
        return spacer;
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = button(text, listener);
        button.setTextSize(20);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = button(text, listener);
        button.setTextSize(15);
        button.setTextColor(Color.rgb(213, 238, 241));
        button.setBackground(rounded(Color.rgb(21, 35, 42), dp(8)));
        return button;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(Color.rgb(18, 133, 95), dp(8)));
        button.setOnClickListener(listener);
        return button;
    }

    private RadioButton radioButton(String text) {
        RadioButton radioButton = new RadioButton(this);
        radioButton.setText(text);
        radioButton.setTextColor(Color.rgb(220, 234, 238));
        radioButton.setTextSize(15);
        radioButton.setGravity(Gravity.CENTER_VERTICAL);
        return radioButton;
    }

    private GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindProjectionService() {
        Intent intent = new Intent();
        intent.setComponent(PROJECTION_SERVICE);
        boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        appendLog("bind result=" + bound);
    }

    private void setPendingCommand(Intent intent) {
        if (intent != null) {
            pendingCommand = intent.getStringExtra("command");
        }
    }

    private static boolean isMonitorCommand(String command) {
        return "start_monitor".equals(command) || "stop_monitor".equals(command);
    }

    private void startMonitorFromUi(String logMessage) {
        applyUiState(STATE_CHECKING, "Checking", "Проверяю монитор камер...", "");
        SideCameraOverlayMonitorService.start(this);
        appendLog(logMessage);
        runDiagnostics(false);
    }

    private void toggleMonitor() {
        if (SideCameraOverlayMonitorService.isMonitorEnabled(this)) {
            SideCameraOverlayMonitorService.stop(this);
            diagnosticsRunning = false;
            applyUiState(STATE_STOPPED, "Stopped",
                    "Монитор выключен. Камеры поворотников не будут увеличиваться.",
                    "");
            appendLog("monitor stop requested");
            return;
        }
        startMonitorFromUi("monitor start requested");
    }

    private void runDiagnostics(boolean visiblePreview) {
        if (!SideCameraOverlayMonitorService.isMonitorEnabled(this)) {
            applyUiState(STATE_STOPPED, "Stopped",
                    "Монитор выключен. Нажмите Start, чтобы снова включить камеры.",
                    "");
            return;
        }
        if (diagnosticsRunning) {
            appendLog("check skipped: already running");
            return;
        }
        diagnosticsRunning = true;
        appendLog("check started preview=" + (visiblePreview ? "visible" : "hidden"));
        applyUiState(STATE_CHECKING, "Checking",
                visiblePreview
                        ? "Проверяю ADB, экран и тестовый вывод..."
                        : "Проверяю ADB и экран...", "");
        new Thread(() -> {
            String errorMessage = null;
            String detail = "";
            try {
                Display display = AvcAidlDashActivity.findClusterDisplay(this, DASH_DISPLAY_ID);
                if (display == null) {
                    appendLogOnUi("check display: not found id=" + DASH_DISPLAY_ID);
                    errorMessage = "Не найден экран перед водителем.";
                    detail = "Подождите загрузки системы автомобиля и нажмите Start.";
                } else {
                    appendLogOnUi("check display: id=" + display.getDisplayId()
                            + " name=" + display.getName());
                    LocalAdbClient adbClient = new LocalAdbClient(this);
                    appendLogOnUi("check adb: echo");
                    String output = adbClient.shell("echo denza-mirrors-ready");
                    if (!output.contains("denza-mirrors-ready")) {
                        errorMessage = "Внутренний ADB ответил неожиданно.";
                        detail = output.trim();
                        appendLogOnUi("check adb: unexpected output=" + compact(output));
                    } else {
                        appendLogOnUi("check adb: ok");
                        String mode = SideCameraOverlayMonitorService.getCameraPositionMode(this);
                        appendLogOnUi("check preview: mode=" + mode
                                + " visible=" + visiblePreview);
                        AvcAidlDashActivity.writeStatus(this,
                                "diagnostic preview pending visible=" + visiblePreview);
                        String previewOutput = adbClient.shell(buildDiagnosticPreviewCommand(
                                display.getDisplayId(), mode, visiblePreview));
                        appendLogOnUi("check preview start: "
                                + summarizeAmStartOutput(previewOutput));
                        if (hasAmStartFailure(previewOutput)) {
                            errorMessage = visiblePreview
                                    ? "Не удалось открыть тестовое окно на приборке."
                                    : "Не удалось проверить экран перед водителем.";
                            detail = previewOutput.trim();
                        } else {
                            Thread.sleep(650L);
                            String previewStatus = AvcAidlDashActivity.readStatus(this);
                            appendLogOnUi("check preview status: " + compact(previewStatus));
                            if (!previewStatus.contains("diagnostic preview shown")) {
                                errorMessage = visiblePreview
                                        ? "Тестовое окно не подтвердило запуск."
                                        : "Экран не подтвердил скрытую проверку.";
                                detail = previewStatus.isEmpty() ? "no preview status" : previewStatus;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errorMessage = friendlyDiagnosticError(e);
                detail = e.getClass().getSimpleName() + " " + nullToEmpty(e.getMessage());
                appendLogOnUi("check failed: " + detail);
            }

            String finalErrorMessage = errorMessage;
            String finalDetail = detail;
            runOnUiThread(() -> {
                diagnosticsRunning = false;
                if (finalErrorMessage == null) {
                    appendLog("check finished: ok");
                    applyUiState(STATE_READY, "Ready",
                            "Готово. Это окно можно закрыть, приложение работает в фоне.",
                            "");
                } else {
                    appendLog("diagnostics failed: " + finalDetail);
                    applyUiState(STATE_ERROR, "Not ready", finalErrorMessage, "");
                }
            });
        }, "denza-mirrors-diagnostics").start();
    }

    private void refreshStatusFromService() {
        if (diagnosticsRunning) {
            return;
        }
        if (!SideCameraOverlayMonitorService.isMonitorEnabled(this)) {
            applyUiState(STATE_STOPPED, "Stopped",
                    "Монитор выключен. Нажмите Start, чтобы снова включить камеры.",
                    "");
            return;
        }

        String status = SideCameraOverlayMonitorService.readStatus(this);
        if (isErrorStatus(status)) {
            applyUiState(STATE_ERROR, "Not ready", friendlyStatusMessage(status), "");
            return;
        }
        applyUiState(STATE_READY, "Ready",
                "Готово. Это окно можно закрыть, приложение работает в фоне.",
                "");
    }

    private void applyUiState(int state, String badge, String message, String detail) {
        if (statusBadge == null || statusMessageView == null
                || statusDetailView == null || monitorButton == null) {
            return;
        }
        int color;
        if (state == STATE_READY) {
            color = Color.rgb(23, 142, 94);
        } else if (state == STATE_ERROR) {
            color = Color.rgb(176, 56, 49);
        } else if (state == STATE_CHECKING) {
            color = Color.rgb(171, 123, 35);
        } else {
            color = Color.rgb(82, 92, 98);
        }
        statusBadge.setText(badge);
        statusBadge.setBackground(rounded(color, dp(12)));
        statusMessageView.setText(message);
        String cleanDetail = detail == null ? "" : detail;
        statusDetailView.setText(cleanDetail);
        statusDetailView.setVisibility(cleanDetail.isEmpty() ? View.GONE : View.VISIBLE);
        boolean enabled = SideCameraOverlayMonitorService.isMonitorEnabled(this);
        monitorButton.setText(enabled ? "Stop" : "Start");
        monitorButton.setBackground(rounded(
                enabled ? Color.rgb(122, 43, 48) : Color.rgb(18, 133, 95),
                dp(8)));
    }

    private boolean isErrorStatus(String status) {
        if (status == null) {
            return false;
        }
        return status.contains("ADB monitor error")
                || status.contains("ADB auth")
                || status.contains("authorization pending")
                || status.contains("overlay display not found")
                || status.contains("overlay activity failed")
                || status.contains("overlay appop not granted")
                || status.contains("diagnostic preview")
                || status.contains("Overlay failed");
    }

    private String friendlyStatusMessage(String status) {
        if (status == null || status.isEmpty()) {
            return "Монитор включён, но статус ещё не получен.";
        }
        if (status.contains("authorization pending") || status.contains("ADB auth")) {
            return "Нужно разрешить ADB-доступ на экране автомобиля.";
        }
        if (status.contains("overlay display not found")) {
            return "Не найден экран перед водителем.";
        }
        if (status.contains("overlay appop not granted")) {
            return "Не удалось выдать разрешение на показ поверх окон.";
        }
        if (status.contains("overlay activity failed")) {
            return "Не удалось открыть большое окно камеры.";
        }
        if (status.contains("diagnostic preview display not found")) {
            return "Не найден экран перед водителем.";
        }
        if (status.contains("diagnostic preview") && status.contains("failed")) {
            return "Не удалось открыть тестовое окно на приборке.";
        }
        if (status.contains("ADB monitor error")) {
            return "Внутренний ADB сейчас не отвечает.";
        }
        return "Монитор включён, но есть ошибка.";
    }

    private String friendlyDiagnosticError(Exception e) {
        String message = nullToEmpty(e.getMessage());
        if (message.contains("authorization pending")) {
            return "Разрешите ADB-доступ на экране автомобиля.";
        }
        if (message.contains("Connection refused") || message.contains("ECONNREFUSED")) {
            return "Внутренний ADB пока не запущен.";
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return "Внутренний ADB не ответил вовремя.";
        }
        return "Диагностика не прошла. Нажмите Start ещё раз.";
    }

    private String buildDiagnosticPreviewCommand(int displayId, String mode, boolean visiblePreview) {
        return "am start -W --display " + displayId
                + " -n " + getPackageName() + "/.AvcAidlDashActivity"
                + " --ei display_id " + displayId
                + " --ei viewpoint 3205"
                + " --es slot center"
                + " --es crop_source none"
                + " --ei center_extend_percent " + PREVIEW_CENTER_EXTEND_PERCENT
                + " --ez overlay_window true"
                + " --ez uturn false"
                + " --ez diagnostic_preview true"
                + " --ez diagnostic_visible " + visiblePreview
                + " --es preview_mode " + mode
                + " --el duration_ms " + (visiblePreview
                        ? VISIBLE_DIAGNOSTIC_PREVIEW_DURATION_MS
                        : INVISIBLE_DIAGNOSTIC_PREVIEW_DURATION_MS);
    }

    private boolean hasAmStartFailure(String output) {
        if (output == null) {
            return true;
        }
        return output.contains("Error:")
                || output.contains("Exception")
                || output.contains("SecurityException")
                || output.contains("Status: timeout")
                || output.contains("Status: failed");
    }

    private String compact(String value) {
        String compact = nullToEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        if (compact.length() > 180) {
            return compact.substring(0, 180) + "...";
        }
        return compact;
    }

    private String summarizeAmStartOutput(String output) {
        String status = lineValue(output, "Status:");
        String launchState = lineValue(output, "LaunchState:");
        if (!status.isEmpty()) {
            return "status=" + status + (launchState.isEmpty() ? "" : " launch=" + launchState);
        }
        return compact(output);
    }

    private String lineValue(String output, String prefix) {
        for (String line : nullToEmpty(output).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private void appendLogOnUi(String message) {
        runOnUiThread(() -> appendLog(message));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void startProjection(int position, int type) {
        int result = transactProjection(TRANSACTION_START, position, type);
        appendLog("start position=" + position + " type=" + type + " result=" + result);
    }

    private void stopAll() {
        appendLog("stop full map result=" + transactProjection(TRANSACTION_STOP, CLUSTER_FULL, MAP_VIEW));
        appendLog("stop mini result=" + transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, MINI_MAP_CARD));
        appendLog("stop tbt result=" + transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, TBT_CARD));
    }

    private void runPendingCommand() {
        if (pendingCommand == null) {
            return;
        }
        String command = pendingCommand;
        pendingCommand = null;
        if ("start_full".equals(command)) {
            startProjection(CLUSTER_FULL, MAP_VIEW);
        } else if ("start_mini".equals(command)) {
            startProjection(CLUSTER_LEFT, MINI_MAP_CARD);
        } else if ("start_tbt".equals(command)) {
            startProjection(CLUSTER_LEFT, TBT_CARD);
        } else if ("stop_all".equals(command)) {
            stopAll();
        } else if ("start_monitor".equals(command)) {
            startMonitorFromUi("monitor start requested");
        } else if ("stop_monitor".equals(command)) {
            SideCameraOverlayMonitorService.stop(this);
            diagnosticsRunning = false;
            applyUiState(STATE_STOPPED, "Stopped",
                    "Монитор выключен. Камеры поворотников не будут увеличиваться.",
                    "");
            appendLog("monitor stop requested");
        } else {
            appendLog("command=" + command);
        }
    }

    private int transactProjection(int code, int position, int type) {
        if (projectionBinder == null) {
            appendLog("not bound");
            return Integer.MIN_VALUE;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(position);
            data.writeInt(type);
            boolean ok = projectionBinder.transact(code, data, reply, 0);
            if (!ok) {
                return Integer.MIN_VALUE + 1;
            }
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            appendLog("binder error: " + e);
            return Integer.MIN_VALUE + 2;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void appendLog(String message) {
        String line = timeFormat.format(new Date()) + " " + message;
        Log.i(TAG, line);
        if (logView != null) {
            logView.append(line + "\n");
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void testLocalAdb() {
        appendLog("adb test started");
        new Thread(() -> {
            try {
                String output = new LocalAdbClient(this).shell("echo dash-projection-ok");
                runOnUiThread(() -> appendLog("adb test: " + output.trim()));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("adb test failed: " + e.getClass().getSimpleName()
                        + " " + e.getMessage()));
            }
        }, "dash-projection-adb-test").start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7);
        }
    }
}
