package dev.denza.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.denza.apps.feature.defaultapps.DefaultAppsSettings;

/** Starts the bounded runtime recovery service after trusted system lifecycle broadcasts. */
public class RuntimeRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaRuntimeBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        Log.i(TAG, "action=" + action);
        boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (packageReplaced) {
            try {
                DefaultAppsSettings.INSTANCE.requestNavigationProxyRepair(context);
            } catch (RuntimeException e) {
                Log.i(TAG, "navigation proxy repair request failed", e);
            }
        }
        if (RuntimeRecoveryActionPolicy.shouldRecover(action)) {
            try {
                RuntimeRecoveryService.start(context, action);
                if (packageReplaced) {
                    // Ignore the normal 30-second freshness window: AutoVoice may have reset the
                    // row milliseconds after the last successful read.
                    DenzaAppRepository.INSTANCE.refreshDefaultApps(true);
                }
            } catch (RuntimeException e) {
                Log.i(TAG, "runtime recovery failed", e);
            }
        }
    }
}
