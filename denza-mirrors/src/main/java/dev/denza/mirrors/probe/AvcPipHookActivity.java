package dev.denza.mirrors.probe;

import dev.denza.mirrors.AvcAidlDashActivity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class AvcPipHookActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String ACTION_PIP = "com.byd.avc.START_TOP_LEFT_ACTIVITY";
    private static final ComponentName STOCK_PIP_COMPONENT =
            new ComponentName("com.byd.avc", "com.byd.avc.PIP2MeterActivity");
    private static final long DEFAULT_OVERLAY_DELAY_MS = 700L;
    private static final long DEFAULT_OVERLAY_DURATION_MS = 300000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void handleIntent(Intent sourceIntent) {
        if (sourceIntent == null) {
            finishAndRemoveTask();
            return;
        }

        boolean overlayEnabled = sourceIntent.getBooleanExtra("hook_overlay", false);
        long overlayDelayMs = Math.max(0L,
                sourceIntent.getLongExtra("hook_overlay_delay_ms", DEFAULT_OVERLAY_DELAY_MS));
        long overlayDurationMs = Math.max(5000L,
                sourceIntent.getLongExtra("hook_overlay_duration_ms", DEFAULT_OVERLAY_DURATION_MS));
        setStatus("pip hook action=" + sourceIntent.getAction()
                + " overlay=" + overlayEnabled
                + " extras=" + describeExtras(sourceIntent.getExtras()));

        forwardToStockPip(sourceIntent);
        if (overlayEnabled) {
            handler.postDelayed(() -> startOverlay(overlayDurationMs), overlayDelayMs);
            handler.postDelayed(this::finishAndRemoveTask, overlayDelayMs + 1000L);
        } else {
            finishAndRemoveTask();
        }
    }

    private void forwardToStockPip(Intent sourceIntent) {
        Intent stockIntent = new Intent(ACTION_PIP);
        stockIntent.setComponent(STOCK_PIP_COMPONENT);
        stockIntent.addCategory(Intent.CATEGORY_DEFAULT);
        stockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (sourceIntent.getExtras() != null) {
            stockIntent.putExtras(sourceIntent.getExtras());
        }
        try {
            startActivity(stockIntent);
            Log.i(TAG, "pip hook forwarded to stock PIP");
        } catch (RuntimeException e) {
            setStatus("pip hook stock forward failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            Log.i(TAG, "pip hook stock forward failed", e);
        }
    }

    private void startOverlay(long durationMs) {
        Intent intent = new Intent(this, AvcAidlDashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("display_id", 4);
        intent.putExtra("viewpoint", 3205);
        intent.putExtra("slot", "full");
        intent.putExtra("duration_ms", durationMs);
        try {
            startActivity(intent);
            setStatus("pip hook overlay start vp=3205 display=4");
        } catch (RuntimeException e) {
            setStatus("pip hook overlay failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            Log.i(TAG, "pip hook overlay failed", e);
        }
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        File statusFile = new File(getFilesDir(), "avc_pip_hook_status.txt");
        try (FileOutputStream output = new FileOutputStream(statusFile, true)) {
            output.write(status.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (IOException e) {
            Log.i(TAG, "pip hook status write failed", e);
        }
    }

    private static String describeExtras(Bundle extras) {
        if (extras == null || extras.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        Set<String> keys = extras.keySet();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            Object value = extras.get(key);
            builder.append(key).append('=').append(value);
        }
        builder.append('}');
        return builder.toString();
    }
}
