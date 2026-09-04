package dev.denza.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

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
        if (RuntimeRecoveryActionPolicy.shouldRecover(action)) {
            try {
                RuntimeRecoveryService.start(context, action);
            } catch (RuntimeException e) {
                Log.i(TAG, "runtime recovery failed", e);
            }
        }
    }
}
