package dev.denza.mirrors.probe;

import dev.denza.mirrors.AvcAidlDashActivity;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class AvcTurnSignalMonitorService extends Service {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String ACTION_STOP =
            "dev.denza.mirrors.STOP_TURN_SIGNAL_MONITOR";

    private static final int DEFAULT_DISPLAY_ID = 4;
    private static final int DEFAULT_VIEWPOINT = 3205;
    private static final long DEFAULT_POLL_MS = 150L;
    private static final long DEFAULT_START_DELAY_MS = 650L;
    private static final long DEFAULT_STOP_DELAY_MS = 500L;
    private static final long DEFAULT_OVERLAY_DURATION_MS = 300000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LightStateReader lightStateReader;
    private boolean running;
    private boolean overlayRunning;
    private int displayId = DEFAULT_DISPLAY_ID;
    private int viewpoint = DEFAULT_VIEWPOINT;
    private long pollMs = DEFAULT_POLL_MS;
    private long startDelayMs = DEFAULT_START_DELAY_MS;
    private long stopDelayMs = DEFAULT_STOP_DELAY_MS;
    private long overlayDurationMs = DEFAULT_OVERLAY_DURATION_MS;
    private long activeSinceMs = -1L;
    private long inactiveSinceMs = -1L;
    private int lastTurnState = Integer.MIN_VALUE;
    private int lastFlashState = Integer.MIN_VALUE;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTurnSignal();
            if (running) {
                handler.postDelayed(this, pollMs);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitor();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            displayId = intent.getIntExtra("display_id", DEFAULT_DISPLAY_ID);
            viewpoint = intent.getIntExtra("viewpoint", DEFAULT_VIEWPOINT);
            pollMs = Math.max(50L, intent.getLongExtra("poll_ms", DEFAULT_POLL_MS));
            startDelayMs = Math.max(0L, intent.getLongExtra("start_delay_ms", DEFAULT_START_DELAY_MS));
            stopDelayMs = Math.max(0L, intent.getLongExtra("stop_delay_ms", DEFAULT_STOP_DELAY_MS));
            overlayDurationMs = Math.max(5000L,
                    intent.getLongExtra("overlay_duration_ms", DEFAULT_OVERLAY_DURATION_MS));
        }

        if (!running) {
            try {
                lightStateReader = LightStateReader.create(this);
                running = true;
                setStatus("turn monitor started display=" + displayId
                        + " vp=" + viewpoint
                        + " delay=" + startDelayMs + "/" + stopDelayMs);
                handler.post(pollRunnable);
            } catch (ReflectiveOperationException | RuntimeException e) {
                setStatus("turn monitor light reader failed: " + shortError(e));
                Log.i(TAG, "turn monitor light reader failed", e);
                stopSelf();
            }
        } else {
            setStatus("turn monitor updated display=" + displayId
                    + " vp=" + viewpoint
                    + " delay=" + startDelayMs + "/" + stopDelayMs);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitor();
        super.onDestroy();
    }

    private void stopMonitor() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopOverlay("monitor stopped");
    }

    private void pollTurnSignal() {
        if (lightStateReader == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int turnState;
        int flashState;
        try {
            turnState = lightStateReader.readTurnState();
            flashState = lightStateReader.readFlashState();
        } catch (ReflectiveOperationException | RuntimeException e) {
            setStatus("turn monitor poll failed: " + shortError(e));
            Log.i(TAG, "turn monitor poll failed", e);
            stopMonitor();
            stopSelf();
            return;
        }

        boolean active = isTurnSignalActive(turnState, flashState);
        if (turnState != lastTurnState || flashState != lastFlashState) {
            lastTurnState = turnState;
            lastFlashState = flashState;
            setStatus("turn state=" + turnState
                    + " flash=" + flashState
                    + " active=" + active
                    + " overlay=" + overlayRunning);
        }

        if (active) {
            inactiveSinceMs = -1L;
            if (activeSinceMs < 0L) {
                activeSinceMs = now;
            }
            if (!overlayRunning && now - activeSinceMs >= startDelayMs) {
                startOverlay();
            }
        } else {
            activeSinceMs = -1L;
            if (inactiveSinceMs < 0L) {
                inactiveSinceMs = now;
            }
            if (overlayRunning && now - inactiveSinceMs >= stopDelayMs) {
                stopOverlay("turn inactive state=" + turnState + " flash=" + flashState);
            }
        }
    }

    private static boolean isTurnSignalActive(int turnState, int flashState) {
        if (turnState == 2 || turnState == 3 || turnState == 4 || turnState == 5) {
            return true;
        }
        if (turnState == 1 || turnState == 6) {
            return true;
        }
        return turnState < 0 && flashState > 0;
    }

    private void startOverlay() {
        Intent intent = new Intent(this, AvcAidlDashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("display_id", displayId);
        intent.putExtra("viewpoint", viewpoint);
        intent.putExtra("slot", "full");
        intent.putExtra("duration_ms", overlayDurationMs);
        startActivity(intent);
        overlayRunning = true;
        setStatus("overlay start display=" + displayId + " vp=" + viewpoint);
    }

    private void stopOverlay(String reason) {
        if (!overlayRunning) {
            return;
        }
        boolean finished = AvcAidlDashActivity.finishActiveInstance();
        if (!finished) {
            Intent intent = new Intent(this, AvcAidlDashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(AvcAidlDashActivity.EXTRA_FINISH, true);
            startActivity(intent);
        }
        overlayRunning = false;
        setStatus("overlay stop " + reason);
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        File statusFile = new File(getFilesDir(), "avc_turn_signal_status.txt");
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(status.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (IOException e) {
            Log.i(TAG, "turn status file write failed", e);
        }
    }

    private static String shortError(Throwable throwable) {
        Throwable root = throwable;
        if (throwable instanceof InvocationTargetException
                && ((InvocationTargetException) throwable).getTargetException() != null) {
            root = ((InvocationTargetException) throwable).getTargetException();
        }
        String message = root.getMessage();
        if (message == null || message.isEmpty()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + " " + message;
    }

    private static final class LightStateReader {
        private final Object lightDevice;
        private final Method getTurnLightState;
        private final Method getTurnLightFlashState;

        private LightStateReader(Object lightDevice, Method getTurnLightState,
                Method getTurnLightFlashState) {
            this.lightDevice = lightDevice;
            this.getTurnLightState = getTurnLightState;
            this.getTurnLightFlashState = getTurnLightFlashState;
        }

        static LightStateReader create(Context context) throws ReflectiveOperationException {
            Class<?> lightDeviceClass =
                    Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
            Method getInstance = lightDeviceClass.getMethod("getInstance", Context.class);
            Object lightDevice = getInstance.invoke(null, context.getApplicationContext());
            Method getTurnLightState = findNoArgMethod(lightDeviceClass, "getTurnLightState");
            Method getTurnLightFlashState =
                    findNoArgMethod(lightDeviceClass, "getTurnLightFlashState");
            return new LightStateReader(lightDevice, getTurnLightState, getTurnLightFlashState);
        }

        int readTurnState() throws ReflectiveOperationException {
            if (getTurnLightState == null) {
                return -1;
            }
            return ((Number) getTurnLightState.invoke(lightDevice)).intValue();
        }

        int readFlashState() throws ReflectiveOperationException {
            if (getTurnLightFlashState == null) {
                return -1;
            }
            return ((Number) getTurnLightFlashState.invoke(lightDevice)).intValue();
        }

        private static Method findNoArgMethod(Class<?> clazz, String name) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }
}
