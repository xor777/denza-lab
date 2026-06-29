package dev.denza.apps;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import dev.denza.disharebridge.DiShareProjectionBridge;

/**
 * Headless controller for the casting session. It launches the selected app to a
 * receiver screen through {@link DiShareProjectionBridge}, stops it, and shows the
 * floating exit control (native DiShare exit glyph) over the casting app. The
 * Simulcast picker UI itself is drawn by {@link SimulcastAccessibilityService}.
 */
public class SimulcastOverlayService extends Service {
    private static final String TAG = "DenzaSimulcastOverlay";
    private static final String ACTION_MONITOR = "dev.denza.apps.MONITOR_SIMULCAST";
    private static final String ACTION_SHOW_ACTIVE_EXIT = "dev.denza.apps.SHOW_ACTIVE_EXIT";
    private static final String ACTION_HIDE_ACTIVE_EXIT = "dev.denza.apps.HIDE_ACTIVE_EXIT";
    static final String ACTION_START_TARGET = "dev.denza.apps.START_SIMULCAST_TARGET";
    static final String ACTION_STOP_CURRENT = "dev.denza.apps.STOP_SIMULCAST_TARGET";
    static final String EXTRA_TARGET_PACKAGE = "targetPackage";
    static final String EXTRA_RECEIVER = "receiver";
    private static final String EXTRA_VIDEO_WIDTH = "videoWidth";
    private static final String EXTRA_VIDEO_HEIGHT = "videoHeight";
    static final String ACTION_DISHARE_DIALOG_HOME = "action.byd.dishare.DIALOG_HOME";
    static final String ACTION_DISHARE_DIALOG_LAUNCHER = "action.byd.dishare.DIALOG_LAUNCHER";
    static final String ACTION_DISHARE_DIALOG_CLOSE = "action.byd.dishare.DIALOG_CLOSE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private SimulcastExitButtonView activeShareExitView;
    private BroadcastReceiver dialogReceiver;
    private DiShareProjectionBridge activeBridge;

    public static void startMonitor(Context context) {
        startAction(context, ACTION_MONITOR);
    }

    public static void stopCurrent(Context context) {
        startAction(context, ACTION_STOP_CURRENT);
    }

    public static void showActiveExit(Context context) {
        startAction(context, ACTION_SHOW_ACTIVE_EXIT);
    }

    public static void hideActiveExit(Context context) {
        startAction(context, ACTION_HIDE_ACTIVE_EXIT);
    }

    /** Kept for callers; the picker overlay is owned by the accessibility service. */
    public static void hide(Context context) {
    }

    /** Launch a target share through the proven bridge path at the given source size. */
    public static void startTarget(Context context, String targetPackage, String receiver,
            int videoWidth, int videoHeight) {
        try {
            context.startService(new Intent(context, SimulcastOverlayService.class)
                    .setAction(ACTION_START_TARGET)
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_RECEIVER, receiver)
                    .putExtra(EXTRA_VIDEO_WIDTH, videoWidth)
                    .putExtra(EXTRA_VIDEO_HEIGHT, videoHeight));
        } catch (RuntimeException e) {
            Log.i(TAG, "startTarget failed", e);
        }
    }

    private static void startAction(Context context, String action) {
        try {
            context.startService(new Intent(context, SimulcastOverlayService.class)
                    .setAction(action));
        } catch (RuntimeException e) {
            Log.i(TAG, "start " + action + " failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        ensureDialogReceiver();
        if (ACTION_START_TARGET.equals(action)) {
            String packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            String receiver = intent.getStringExtra(EXTRA_RECEIVER);
            int videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0);
            int videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0);
            if (packageName != null && !packageName.trim().isEmpty()) {
                startTargetByPackage(packageName.trim(), resolveLabel(packageName.trim()),
                        receiver == null || receiver.trim().isEmpty()
                                ? "screen_hud" : receiver.trim(), videoWidth, videoHeight);
            }
            return START_STICKY;
        }
        if (ACTION_STOP_CURRENT.equals(action)) {
            stopCurrentShare();
            hideActiveShareExit();
            sendBroadcast(new Intent(ACTION_DISHARE_DIALOG_CLOSE));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_ACTIVE_EXIT.equals(action)) {
            hideActiveShareExit();
            return START_STICKY;
        }
        if (ACTION_SHOW_ACTIVE_EXIT.equals(action) || ACTION_MONITOR.equals(action)) {
            maybeShowActiveExit();
            return START_STICKY;
        }
        // While the Simulcast dialog is up, hide the floating exit; restore it after.
        if (ACTION_DISHARE_DIALOG_LAUNCHER.equals(action)) {
            hideActiveShareExit();
            return START_STICKY;
        }
        if (ACTION_DISHARE_DIALOG_HOME.equals(action)
                || ACTION_DISHARE_DIALOG_CLOSE.equals(action)) {
            maybeShowActiveExit();
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (dialogReceiver != null) {
            try {
                unregisterReceiver(dialogReceiver);
            } catch (RuntimeException ignored) {
            }
            dialogReceiver = null;
        }
        hideActiveShareExit();
        handler.removeCallbacksAndMessages(null);
        stopBridge();
        super.onDestroy();
    }

    private void ensureDialogReceiver() {
        if (dialogReceiver != null) {
            return;
        }
        dialogReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent received) {
                String action = received == null ? null : received.getAction();
                if (ACTION_DISHARE_DIALOG_LAUNCHER.equals(action)) {
                    hideActiveShareExit();
                } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                        || ACTION_DISHARE_DIALOG_HOME.equals(action)
                        || ACTION_DISHARE_DIALOG_CLOSE.equals(action)) {
                    maybeShowActiveExit();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DISHARE_DIALOG_HOME);
        filter.addAction(ACTION_DISHARE_DIALOG_LAUNCHER);
        filter.addAction(ACTION_DISHARE_DIALOG_CLOSE);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dialogReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dialogReceiver, filter);
        }
    }

    private void startTargetByPackage(final String packageName, final String label,
            final String receiver, final int videoWidth, final int videoHeight) {
        stopBridge();
        SimulcastIntegration.clearLastTargetPackage(this);
        hideActiveShareExit();
        Log.i(TAG, "start target=" + packageName + " receiver=" + receiver
                + " video=" + videoWidth + "x" + videoHeight);
        activeBridge = new DiShareProjectionBridge(
                getApplicationContext(),
                packageName,
                new DiShareProjectionBridge.Callback() {
                    @Override
                    public void onLog(String message) {
                        Log.i(TAG, packageName + " " + message);
                    }

                    @Override
                    public void onStarted(Bundle result) {
                        Log.i(TAG, packageName + " started "
                                + DiShareProjectionBridge.bundleToString(result));
                        SimulcastIntegration.setLastTarget(SimulcastOverlayService.this,
                                packageName, receiver);
                        Toast.makeText(SimulcastOverlayService.this,
                                "Запускаю " + label, Toast.LENGTH_SHORT).show();
                        sendBroadcast(new Intent(ACTION_DISHARE_DIALOG_CLOSE));
                        showActiveShareExit();
                    }

                    @Override
                    public void onFailed(String message) {
                        Log.w(TAG, packageName + " failed " + message);
                        Toast.makeText(SimulcastOverlayService.this,
                                "Simulcast не запустил " + label + ": " + message,
                                Toast.LENGTH_LONG).show();
                        activeBridge = null;
                    }

                    @Override
                    public void onStopped(String message) {
                        Log.i(TAG, packageName + " stopped " + message);
                    }
                });
        if (videoWidth > 0 && videoHeight > 0) {
            activeBridge.startToReceiver(receiver, videoWidth, videoHeight);
        } else {
            activeBridge.startToReceiver(receiver);
        }
    }

    private void stopCurrentShare() {
        if (activeBridge != null) {
            stopBridge();
            SimulcastIntegration.clearLastTargetPackage(this);
            hideActiveShareExit();
            Toast.makeText(this, "Simulcast завершен", Toast.LENGTH_SHORT).show();
            return;
        }
        DiShareProjectionBridge.stopCurrentShare(getApplicationContext(),
                new DiShareProjectionBridge.Callback() {
                    @Override
                    public void onLog(String message) {
                        Log.i(TAG, "stop current " + message);
                    }

                    @Override
                    public void onStarted(Bundle result) {
                    }

                    @Override
                    public void onFailed(String message) {
                        Log.w(TAG, "stop current failed " + message);
                        Toast.makeText(SimulcastOverlayService.this,
                                "Simulcast не завершился: " + message, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onStopped(String message) {
                        SimulcastIntegration.clearLastTargetPackage(SimulcastOverlayService.this);
                        hideActiveShareExit();
                        Toast.makeText(SimulcastOverlayService.this,
                                "Simulcast завершен", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void stopBridge() {
        if (activeBridge != null) {
            activeBridge.stop();
            activeBridge = null;
        }
    }

    private String resolveLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void maybeShowActiveExit() {
        if (SimulcastIntegration.isEnabled(this)
                && SimulcastIntegration.getLastTargetPackage(this) != null) {
            showActiveShareExit();
        } else {
            hideActiveShareExit();
        }
    }

    private void showActiveShareExit() {
        if (SimulcastIntegration.getLastTargetPackage(this) == null) {
            hideActiveShareExit();
            return;
        }
        ensureWindowManager();
        if (activeShareExitView != null) {
            try {
                windowManager.updateViewLayout(activeShareExitView, activeShareExitParams());
            } catch (RuntimeException e) {
                Log.i(TAG, "active share exit update failed", e);
            }
            return;
        }
        activeShareExitView = new SimulcastExitButtonView(this);
        activeShareExitView.setOnClickListener(view -> {
            stopCurrentShare();
            sendBroadcast(new Intent(ACTION_DISHARE_DIALOG_CLOSE));
        });
        try {
            windowManager.addView(activeShareExitView, activeShareExitParams());
            Log.i(TAG, "active share exit shown");
        } catch (RuntimeException e) {
            Log.i(TAG, "active share exit failed", e);
            activeShareExitView = null;
        }
    }

    private void hideActiveShareExit() {
        if (activeShareExitView != null && windowManager != null) {
            try {
                windowManager.removeView(activeShareExitView);
            } catch (RuntimeException ignored) {
            }
        }
        activeShareExitView = null;
    }

    private WindowManager.LayoutParams activeShareExitParams() {
        int size = dp(56);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(24);
        params.y = dp(220);
        return params;
    }

    private void ensureWindowManager() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Floating stop control: native DiShare exit glyph on a dark translucent pill. */
    private final class SimulcastExitButtonView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable exitIcon;

        SimulcastExitButtonView(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Native exit control: red ic_exit glyph (#ff4046) on the stock dark
            // translucent background (color_bg_exit_btn #66000000, 8dp corners).
            float pad = dp(4);
            RectF background = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(0x66, 0, 0, 0));
            canvas.drawRoundRect(background, dp(8), dp(8), paint);

            if (exitIcon == null) {
                exitIcon = getContext().getDrawable(R.drawable.ic_simulcast_exit);
            }
            if (exitIcon != null) {
                float glyph = Math.min(background.width(), background.height()) * 0.56f;
                int left = Math.round(background.centerX() - glyph / 2f);
                int top = Math.round(background.centerY() - glyph / 2f);
                exitIcon.setBounds(left, top, Math.round(left + glyph), Math.round(top + glyph));
                exitIcon.draw(canvas);
            }
        }
    }
}
