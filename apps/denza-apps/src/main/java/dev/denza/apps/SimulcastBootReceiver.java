package dev.denza.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.denza.apps.core.DenzaRuntimeCoordinator;

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
        if (SimulcastBootActionPolicy.shouldRecover(action)) {
            try {
                DenzaRuntimeCoordinator.INSTANCE.bootstrap(context);
            } catch (RuntimeException e) {
                Log.i(TAG, "runtime recovery failed", e);
            }
        }
    }
}
