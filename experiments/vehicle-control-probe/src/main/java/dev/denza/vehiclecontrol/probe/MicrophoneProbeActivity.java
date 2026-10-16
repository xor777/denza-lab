package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Shell-only diagnostic entry. Does not construct the vehicle controller or ASR. */
public final class MicrophoneProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private TextView message;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        message = new TextView(this);
        message.setTextSize(28);
        message.setPadding(40, 40, 40, 40);
        message.setText("Проверка 10 каналов микрофона\nТолько уровни сигнала, без распознавания и команд.\n"
                + "Экран закроется через 7 секунд; запись продолжится в фоне до 30 секунд.");
        setContentView(message);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || started) return;
        started = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.setText("Разрешение RECORD_AUDIO отсутствует. Проверка не запускалась.");
            return;
        }
        Intent capture = new Intent(this, MicrophoneProbeService.class);
        capture.putExtra("run", getIntent().getStringExtra("run"));
        startForegroundService(capture);
        main.postDelayed(this::finish, 7_000);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
