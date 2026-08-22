package dev.denza.speakerlift.probe;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fires one one-second stock LOCAL pulse when the foreground window enters
 * Yandex Music. It never opens an Activity and never reads the window tree.
 */
public final class YandexSpeakerLiftAccessibilityService extends AccessibilityService {
    private static final String TAG = "SpeakerLiftYandexProbe";
    private static final String YANDEX_MUSIC_PACKAGE = "ru.yandex.music";
    private static final long REARM_AFTER_AWAY_MS = 1_500L;
    private static final long MIN_TRIGGER_INTERVAL_MS = 10_000L;

    private String lastWindowPackage;
    private boolean yandexForeground;
    private boolean pulseActive;
    private long lastTriggerElapsed = Long.MIN_VALUE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pause = this::pauseSafely;
    private final Runnable rearm = () -> {
        if (!YANDEX_MUSIC_PACKAGE.equals(lastWindowPackage)) {
            yandexForeground = false;
            Log.i(TAG, "rearmed after leaving Yandex Music");
        }
    };

    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "accessibility service connected; waiting for Yandex Music");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }

        String packageName = event.getPackageName().toString();
        lastWindowPackage = packageName;
        if (!YANDEX_MUSIC_PACKAGE.equals(packageName)) {
            if (yandexForeground) {
                handler.removeCallbacks(rearm);
                handler.postDelayed(rearm, REARM_AFTER_AWAY_MS);
            }
            return;
        }

        handler.removeCallbacks(rearm);
        if (yandexForeground) {
            return;
        }
        yandexForeground = true;

        long now = SystemClock.elapsedRealtime();
        if (lastTriggerElapsed != Long.MIN_VALUE
                && now - lastTriggerElapsed < MIN_TRIGGER_INTERVAL_MS) {
            Log.i(TAG, "Yandex entry ignored by cooldown");
            return;
        }
        lastTriggerElapsed = now;
        pulseSafely();
    }

    private void pulseSafely() {
        handler.removeCallbacks(pause);
        try {
            MediaCenterPulse.play(this);
            pulseActive = true;
            handler.postDelayed(pause, MediaCenterPulse.PULSE_DURATION_MS);
            Log.i(TAG, "Yandex entry -> one-second LOCAL pulse");
        } catch (Throwable error) {
            Log.e(TAG, "LOCAL pulse start failed", error);
        }
    }

    private void pauseSafely() {
        handler.removeCallbacks(pause);
        if (!pulseActive) {
            return;
        }
        try {
            MediaCenterPulse.pause(this);
            pulseActive = false;
        } catch (Throwable error) {
            Log.e(TAG, "LOCAL pulse pause failed", error);
        }
    }

    @Override
    public void onInterrupt() {
        pauseSafely();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pauseSafely();
        super.onDestroy();
    }
}
