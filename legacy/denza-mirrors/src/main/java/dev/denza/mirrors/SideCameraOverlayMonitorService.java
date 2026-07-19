package dev.denza.mirrors;

import dev.denza.mirrors.probe.HudDiShareActivity;

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
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SideCameraOverlayMonitorService extends Service {
    static final String ACTION_START =
            "dev.denza.mirrors.START_SIDE_CAMERA_MONITOR";
    static final String ACTION_STOP =
            "dev.denza.mirrors.STOP_SIDE_CAMERA_MONITOR";

    private static final String TAG = "DenzaProjectionProbe";
    private static final String CHANNEL_ID = "dash_projection_monitor";
    private static final String PREFS_NAME = "side_camera_monitor";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CAMERA_POSITION_MODE = "camera_position_mode";
    private static final String KEY_IMAGE_ENHANCEMENT_MODE = "image_enhancement_mode";
    private static final String KEY_IMAGE_ENHANCEMENT_STRENGTH = "image_enhancement_strength";
    static final String CAMERA_POSITION_SIDES = "sides";
    static final String CAMERA_POSITION_CENTER = "center";
    static final String IMAGE_ENHANCEMENT_NORMAL = "normal";
    static final String IMAGE_ENHANCEMENT_CONTRAST = "contrast";
    static final String DEFAULT_IMAGE_ENHANCEMENT = IMAGE_ENHANCEMENT_CONTRAST;
    static final int DEFAULT_IMAGE_ENHANCEMENT_STRENGTH = 100;
    static final String STATUS_FILE_NAME = "side_camera_monitor_status.txt";
    private static final int NOTIFICATION_ID = 77;
    private static final long POLL_MS = 100L;
    private static final long OVERLAY_RETRY_MS = 1500L;
    private static final long FINISH_SYNC_TIMEOUT_MS = 250L;
    private static final int DISPLAY_ID = 4;
    private static final long OVERLAY_DURATION_MS = 300000L;
    private static final int CENTER_EXTEND_PERCENT = 20;
    private static final boolean USE_HUD_DISHARE_STREAM = false;
    private static final String LEFT_CAMERA_ID = "0";
    private static final String RIGHT_CAMERA_ID = "1";
    private static final int HUD_STREAM_WIDTH = 1280;
    private static final int HUD_STREAM_HEIGHT = 720;
    private static final int HUD_CAMERA_WIDTH = 1280;
    private static final int HUD_CAMERA_HEIGHT = 720;
    private static final float HUD_CAMERA_SCALE_X = 0.56f;
    private static final float HUD_CAMERA_SCALE_Y = 1.0f;
    private static final long HUD_START_AFTER_WINDOW_VISIBLE_MS = 350L;
    private static final long HUD_OVERLAY_ACTIVE_MS = 6500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private LocalAdbClient adbClient;
    private volatile boolean running;
    private String currentSide = "";
    private final Runnable overlayTimeoutRunnable = () -> {
        if (!currentSide.isEmpty()) {
            stopOverlay("timeout");
        }
    };
    private long lastOverlayFailureMs;
    private String lastOverlayFailureSide = "";
    private boolean lastLeftVisible;
    private boolean lastRightVisible;
    private long lastErrorLogMs;
    private String pendingHudSide = "";
    private long lastHudWindowVisibleMs = -1L;
    private long hudWindowVisibleSinceMs = -1L;
    private long hudStartedMs = -1L;

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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (isMonitorEnabled(this)) {
            moveToForeground("Running");
            startMonitor();
        }
        super.onTaskRemoved(rootIntent);
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

            if (USE_HUD_DISHARE_STREAM) {
                handleHudWindowState(leftVisible, rightVisible);
                return;
            }

            if (leftVisible) {
                startOverlay("left");
            } else if (rightVisible) {
                startOverlay("right");
            } else if (!currentSide.isEmpty()) {
                stopOverlay("window hidden");
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

    private void handleHudWindowState(boolean leftVisible, boolean rightVisible) {
        long now = System.currentTimeMillis();
        String visibleSide = leftVisible ? "left" : rightVisible ? "right" : "";
        if (!visibleSide.isEmpty()) {
            if (!currentSide.isEmpty() && !currentSide.equals(visibleSide)) {
                mainHandler.post(() -> stopOverlay("switch pending side"));
            }
            if (!visibleSide.equals(pendingHudSide)) {
                hudWindowVisibleSinceMs = now;
            }
            pendingHudSide = visibleSide;
            lastHudWindowVisibleMs = now;
            if (currentSide.isEmpty()
                    && hudWindowVisibleSinceMs > 0L
                    && now - hudWindowVisibleSinceMs >= HUD_START_AFTER_WINDOW_VISIBLE_MS) {
                String side = pendingHudSide;
                pendingHudSide = "";
                mainHandler.post(() -> startOverlay(side));
            }
            return;
        }

        pendingHudSide = "";
        lastHudWindowVisibleMs = -1L;
        hudWindowVisibleSinceMs = -1L;
        if (!currentSide.isEmpty()) {
            mainHandler.post(() -> stopOverlay("window hidden"));
            return;
        }

        if (!currentSide.isEmpty() && hudStartedMs > 0L
                && now - hudStartedMs >= HUD_OVERLAY_ACTIVE_MS) {
            mainHandler.post(() -> stopOverlay("hud timeout"));
        }
    }

    private void startOverlay(String side) {
        if (side.equals(currentSide)) {
            return;
        }
        if (!currentSide.isEmpty()) {
            stopOverlay("switch to " + side);
        }
        if (USE_HUD_DISHARE_STREAM) {
            startHudDiShareOverlay(side);
            currentSide = side;
            hudStartedMs = System.currentTimeMillis();
            setStatus("hud overlay start " + side);
            moveToForeground("Showing HUD " + side);
            return;
        }
        if (!startDashActivity(side)) {
            return;
        }
        currentSide = side;
        setStatus("overlay start " + side);
        moveToForeground("Showing " + side);
    }

    private boolean startDashActivity(String side) {
        long now = System.currentTimeMillis();
        if (side.equals(lastOverlayFailureSide)
                && now - lastOverlayFailureMs < OVERLAY_RETRY_MS) {
            return false;
        }

        Display display = AvcAidlDashActivity.findClusterDisplay(this, DISPLAY_ID);
        if (display == null) {
            setStatus("overlay display not found id=" + DISPLAY_ID);
            lastOverlayFailureMs = now;
            lastOverlayFailureSide = side;
            return false;
        }

        boolean centerMode = CAMERA_POSITION_CENTER.equals(getCameraPositionMode(this));
        int imageEnhancementStrength = getImageEnhancementStrength(this);
        String imageEnhancementMode = imageEnhancementStrength > 0
                ? IMAGE_ENHANCEMENT_CONTRAST
                : IMAGE_ENHANCEMENT_NORMAL;
        int viewpoint = "left".equals(side) ? 3205 : 3204;
        String slot = centerMode ? "center" : "left".equals(side) ? "left" : "right";
        String cropSource = "left".equals(side) ? "left" : "none";
        try {
            String output = adbClient.shell(buildDashActivityCommand(
                    display.getDisplayId(), viewpoint, slot, cropSource,
                    imageEnhancementMode, imageEnhancementStrength, false));
            Log.i(TAG, "dash activity shell start output=" + output.trim());
            if (isShellStartFailure(output)) {
                setStatus("overlay activity failed: " + output.trim());
                lastOverlayFailureMs = System.currentTimeMillis();
                lastOverlayFailureSide = side;
                moveToForeground("Overlay failed");
                return false;
            }
            mainHandler.removeCallbacks(overlayTimeoutRunnable);
            mainHandler.postDelayed(overlayTimeoutRunnable, OVERLAY_DURATION_MS);
            lastOverlayFailureSide = "";
            return true;
        } catch (Exception e) {
            Log.i(TAG, "dash activity start failed", e);
            setStatus("overlay activity failed: " + shortError(e));
            lastOverlayFailureMs = System.currentTimeMillis();
            lastOverlayFailureSide = side;
            moveToForeground("Overlay failed");
            return false;
        }
    }

    private String buildDashActivityCommand(int displayId, int viewpoint, String slot,
            String cropSource, String imageEnhancementMode, int imageEnhancementStrength,
            boolean finish) {
        return "am start --display " + displayId
                + " -n " + getPackageName() + "/.AvcAidlDashActivity"
                + " --ei display_id " + DISPLAY_ID
                + " --ei viewpoint " + viewpoint
                + " --es slot " + slot
                + " --es crop_source " + cropSource
                + " --es image_enhancement " + imageEnhancementMode
                + " --ei image_enhancement_strength " + imageEnhancementStrength
                + " --ei center_extend_percent " + CENTER_EXTEND_PERCENT
                + " --ez overlay_window true"
                + " --ez uturn false"
                + " --el duration_ms " + OVERLAY_DURATION_MS
                + " --ez " + AvcAidlDashActivity.EXTRA_FINISH + " " + finish;
    }

    private static boolean isShellStartFailure(String output) {
        if (output == null) {
            return true;
        }
        return output.contains("Error:")
                || output.contains("Exception")
                || output.contains("SecurityException")
                || output.contains("Status: timeout")
                || output.contains("Status: failed");
    }

    private void startHudDiShareOverlay(String side) {
        Intent intent = new Intent(this, HudDiShareActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("media_stream", true);
        intent.putExtra("camera_stream", true);
        intent.putExtra("camera_gl_stream", true);
        intent.putExtra("pattern", false);
        intent.putExtra("texture_bridge", false);
        intent.putExtra("mirror_source", true);
        intent.putExtra("start_control", true);
        intent.putExtra("show_image", false);
        intent.putExtra("debug_ui", false);
        intent.putExtra("camera_id", "left".equals(side) ? LEFT_CAMERA_ID : RIGHT_CAMERA_ID);
        intent.putExtra("slot", side);
        intent.putExtra("stream_width", HUD_STREAM_WIDTH);
        intent.putExtra("stream_height", HUD_STREAM_HEIGHT);
        intent.putExtra("camera_width", HUD_CAMERA_WIDTH);
        intent.putExtra("camera_height", HUD_CAMERA_HEIGHT);
        intent.putExtra("camera_rotate_degrees", 0);
        intent.putExtra("camera_draw_scale_x", HUD_CAMERA_SCALE_X);
        intent.putExtra("camera_draw_scale_y", HUD_CAMERA_SCALE_Y);
        intent.putExtra("bounds_left", 0);
        intent.putExtra("bounds_top", 0);
        intent.putExtra("bounds_right", HUD_STREAM_WIDTH);
        intent.putExtra("bounds_bottom", HUD_STREAM_HEIGHT);
        intent.putExtra("start_delay_ms", 650L);
        intent.putExtra("auto_stop_ms", HUD_OVERLAY_ACTIVE_MS + 1500L);
        startActivity(intent);
    }

    private void stopOverlay(String reason) {
        if (currentSide.isEmpty()) {
            return;
        }
        String stoppedSide = currentSide;
        currentSide = "";
        pendingHudSide = "";
        lastHudWindowVisibleMs = -1L;
        hudWindowVisibleSinceMs = -1L;
        hudStartedMs = -1L;
        if (USE_HUD_DISHARE_STREAM) {
            boolean finished = HudDiShareActivity.finishActiveInstance();
            if (!finished) {
                Intent intent = new Intent(this, HudDiShareActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra(HudDiShareActivity.EXTRA_FINISH, true);
                startActivity(intent);
            }
            setStatus("hud overlay stop " + stoppedSide + " reason=" + reason);
            moveToForeground("Running");
            return;
        }
        mainHandler.removeCallbacks(overlayTimeoutRunnable);
        if (!AvcAidlDashActivity.finishActiveInstanceSync(FINISH_SYNC_TIMEOUT_MS)) {
            try {
                Display display = AvcAidlDashActivity.findClusterDisplay(this, DISPLAY_ID);
                adbClient.shell(buildDashActivityCommand(
                        display == null ? DISPLAY_ID : display.getDisplayId(),
                        3205, "left", "left", IMAGE_ENHANCEMENT_NORMAL, 0, true));
            } catch (Exception e) {
                Log.i(TAG, "dash activity shell finish failed", e);
            }
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
        File statusFile = new File(getFilesDir(), STATUS_FILE_NAME);
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
        Intent openIntent = new Intent(this, MainActivity.class);
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
                .setContentTitle(getString(R.string.app_name))
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
                getString(R.string.app_name),
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

    static String getCameraPositionMode(Context context) {
        String mode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CAMERA_POSITION_MODE, CAMERA_POSITION_SIDES);
        return CAMERA_POSITION_CENTER.equals(mode) ? CAMERA_POSITION_CENTER : CAMERA_POSITION_SIDES;
    }

    static void setCameraPositionMode(Context context, String mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CAMERA_POSITION_MODE,
                        CAMERA_POSITION_CENTER.equals(mode)
                                ? CAMERA_POSITION_CENTER
                                : CAMERA_POSITION_SIDES)
                .apply();
    }

    static String getImageEnhancementMode(Context context) {
        String mode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_IMAGE_ENHANCEMENT_MODE, DEFAULT_IMAGE_ENHANCEMENT);
        return IMAGE_ENHANCEMENT_CONTRAST.equals(mode)
                ? IMAGE_ENHANCEMENT_CONTRAST
                : IMAGE_ENHANCEMENT_NORMAL;
    }

    static void setImageEnhancementMode(Context context, String mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_IMAGE_ENHANCEMENT_MODE,
                        IMAGE_ENHANCEMENT_CONTRAST.equals(mode)
                                ? IMAGE_ENHANCEMENT_CONTRAST
                                : IMAGE_ENHANCEMENT_NORMAL)
                .apply();
    }

    static int getImageEnhancementStrength(Context context) {
        int strength = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_IMAGE_ENHANCEMENT_STRENGTH, DEFAULT_IMAGE_ENHANCEMENT_STRENGTH);
        return clampPercent(strength);
    }

    static void setImageEnhancementStrength(Context context, int strength) {
        int clamped = clampPercent(strength);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_IMAGE_ENHANCEMENT_STRENGTH, clamped)
                .putString(KEY_IMAGE_ENHANCEMENT_MODE,
                        clamped > 0 ? IMAGE_ENHANCEMENT_CONTRAST : IMAGE_ENHANCEMENT_NORMAL)
                .apply();
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    static String readStatus(Context context) {
        File statusFile = new File(context.getFilesDir(), STATUS_FILE_NAME);
        if (!statusFile.exists()) {
            return "";
        }
        byte[] buffer = new byte[(int) Math.min(statusFile.length(), 4096)];
        try (FileInputStream input = new FileInputStream(statusFile)) {
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
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
