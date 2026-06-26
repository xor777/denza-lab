package com.byd.cluster.projection.mapdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SideCameraBootReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaProjectionProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && SideCameraOverlayMonitorService.isMonitorEnabled(context)) {
            try {
                SideCameraOverlayMonitorService.start(context);
            } catch (RuntimeException e) {
                Log.i(TAG, "boot monitor start failed", e);
            }
        }
    }
}
