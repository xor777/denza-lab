package com.byd.cluster.projection.mapdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SideCameraOverlayMonitorService extends Service {
    static final String ACTION_START =
            "com.byd.cluster.projection.mapdemo.START_SIDE_CAMERA_MONITOR";
    static final String ACTION_STOP =
            "com.byd.cluster.projection.mapdemo.STOP_SIDE_CAMERA_MONITOR";

    private static final String TAG = "DenzaProjectionProbe";
    private static final String CHANNEL_ID = "dash_projection_monitor";
    private static final String PREFS_NAME = "side_camera_monitor";
    private static final String KEY_ENABLED = "enabled";
    private static final int NOTIFICATION_ID = 77;
    private static final long POLL_MS = 150L;
    private static final int DISPLAY_ID = 4;
    private static final long OVERLAY_DURATION_MS = 300000L;
    private static final int CENTER_EXTEND_PERCENT = 20;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private LocalAdbClient adbClient;
    private volatile boolean running;
    private String currentSide = "";
    private boolean lastLeftVisible;
    private boolean lastRightVisible;
    private long lastErrorLogMs;

    @Override
    public void onCreate() {
        super.onCreate();
        adbClient = new LocalAdbClient(this);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitor(true);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        moveToForeground("Starting");
        startMonitor();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMonitor(false);
        super.onDestroy();
    }

    private void startMonitor() {
        setMonitorEnabled(true);
        if (running) {
            setStatus("monitor already running");
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.execute(this::grantOverlayPermission);
        executor.scheduleWithFixedDelay(this::pollWindows, 0L, POLL_MS, TimeUnit.MILLISECONDS);
        setStatus("monitor running");
        moveToForeground("Running");
    }

    private void stopMonitor(boolean disableAutostart) {
        if (disableAutostart) {
            setMonitorEnabled(false);
        }
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        mainHandler.post(() -> stopOverlay("monitor stopped"));
        setStatus("monitor stopped");
    }

    private void grantOverlayPermission() {
        try {
            adbClient.shell("cmd appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        } catch (Exception e) {
            setStatus("overlay appop not granted: " + shortError(e));
        }
    }

    private void pollWindows() {
        if (!running) {
            return;
        }
        try {
            String windows = adbClient.shell("dumpsys window visible");
            boolean leftVisible = isLeftPipVisible(windows);
            boolean rightVisible = isRightCompactAlertVisible(windows);
            if (leftVisible != lastLeftVisible || rightVisible != lastRightVisible) {
                lastLeftVisible = leftVisible;
                lastRightVisible = rightVisible;
                setStatus("windows left=" + leftVisible + " right=" + rightVisible
                        + " current=" + currentSide);
            }

            if (leftVisible) {
                mainHandler.post(() -> startOverlay("left"));
            } else if (rightVisible) {
                mainHandler.post(() -> startOverlay("right"));
            } else if (!currentSide.isEmpty()) {
                mainHandler.post(() -> stopOverlay("window hidden"));
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLogMs > 3000L) {
                lastErrorLogMs = now;
                setStatus("ADB monitor error: " + shortError(e));
                moveToForeground("ADB auth needed");
            }
        }
    }

    private void startOverlay(String side) {
        if (side.equals(currentSide)) {
            return;
        }
        if (!currentSide.isEmpty()) {
            stopOverlay("switch to " + side);
        }
        Intent intent = new Intent(this, AvcAidlDashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("display_id", DISPLAY_ID);
        intent.putExtra("duration_ms", OVERLAY_DURATION_MS);
        intent.putExtra("center_extend_percent", CENTER_EXTEND_PERCENT);
        intent.putExtra("overlay_window", true);
        if ("left".equals(side)) {
            intent.putExtra("viewpoint", 3205);
            intent.putExtra("slot", "left");
            intent.putExtra("crop_source", "left");
        } else {
            intent.putExtra("viewpoint", 3204);
            intent.putExtra("slot", "right");
            intent.putExtra("crop_source", "none");
        }
        startActivity(intent);
        currentSide = side;
        setStatus("overlay start " + side);
        moveToForeground("Showing " + side);
    }

    private void stopOverlay(String reason) {
        if (currentSide.isEmpty()) {
            return;
        }
        String stoppedSide = currentSide;
        currentSide = "";
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
        setStatus("overlay stop " + stoppedSide + " reason=" + reason);
        moveToForeground("Running");
    }

    private static boolean isLeftPipVisible(String windows) {
        for (String block : windows.split("\\n(?=  Window #[0-9]+ Window\\{)")) {
            if (block.contains("com.byd.avc/com.byd.avc.PIP2MeterActivity")
                    && block.contains("mDisplayId=4")
                    && block.contains("package=com.byd.avc")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRightCompactAlertVisible(String windows) {
        for (String block : windows.split("\\n(?=  Window #[0-9]+ Window\\{)")) {
            if (block.contains("com.byd.avc")
                    && block.contains("mDisplayId=0")
                    && block.contains("package=com.byd.avc")
                    && block.contains("ty=SYSTEM_ALERT")
                    && block.contains("(720x450)")) {
                return true;
            }
        }
        return false;
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        File statusFile = new File(getFilesDir(), "side_camera_monitor_status.txt");
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(status.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (IOException e) {
            Log.i(TAG, "monitor status write failed", e);
        }
    }

    private void moveToForeground(String text) {
        Notification notification = buildNotification(text);
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, ProjectionProbeActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 2,
                new Intent(this, SideCameraOverlayMonitorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Dash Projection")
                .setContentText(text)
                .setOngoing(running)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Dash Projection",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Side camera overlay monitor");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    static void start(Context context) {
        setMonitorEnabled(context, true);
        Intent intent = new Intent(context, SideCameraOverlayMonitorService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        setMonitorEnabled(context, false);
        context.startService(new Intent(context, SideCameraOverlayMonitorService.class)
                .setAction(ACTION_STOP));
    }

    static boolean isMonitorEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private void setMonitorEnabled(boolean enabled) {
        setMonitorEnabled(this, enabled);
    }

    private static void setMonitorEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }
}
