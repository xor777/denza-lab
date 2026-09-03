package dev.denza.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.denza.apps.core.DenzaRuntimeCoordinator;
import dev.denza.apps.feature.defaultapps.DefaultAppsSettings;

/**
 * Restores desired runtimes after trusted system lifecycle broadcasts. Simulcast dialog
 * visibility is observed through accessibility instead of accepting spoofable vendor broadcasts.
 */
public class SimulcastBootReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaSimulcastBoot";

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
        if (SimulcastBootActionPolicy.shouldRecover(action)) {
            try {
                DenzaRuntimeCoordinator.INSTANCE.bootstrap(context);
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
