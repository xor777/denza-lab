package dev.denza.mirrors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SideCameraBootReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaProjectionProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && isRestartAction(intent.getAction())
                && SideCameraOverlayMonitorService.isMonitorEnabled(context)) {
            try {
                SideCameraOverlayMonitorService.start(context);
            } catch (RuntimeException e) {
                Log.i(TAG, "boot monitor start failed", e);
            }
        }
    }

    private static boolean isRestartAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
    }
}
