package dev.denza.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.denza.apps.feature.navigation.NavigationCoordinator;

/**
 * Debug-only ADB bridge. The manifest requires {@code android.permission.DUMP};
 * firmware builds whose shell user lacks that permission must use the normal UI.
 */
public final class SimulcastDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaSimulcastDebug";
    private static final String ACTION_NAVIGATION_PRIMARY =
            "dev.denza.apps.NAVIGATION_PRIMARY";

    @Override
    public void onReceive(Context context, Intent source) {
        String action = source == null ? null : source.getAction();
        if (ACTION_NAVIGATION_PRIMARY.equals(action)) {
            NavigationCoordinator.INSTANCE.performPrimaryAction();
            return;
        }
        if (!SimulcastOverlayService.ACTION_START_TARGET.equals(action)
                && !SimulcastOverlayService.ACTION_STOP_CURRENT.equals(action)) {
            return;
        }
        Intent command = new Intent(context, SimulcastOverlayService.class).setAction(action);
        if (source.getExtras() != null) {
            command.putExtras(source.getExtras());
        }
        try {
            context.startService(command);
        } catch (RuntimeException error) {
            Log.w(TAG, "debug command failed action=" + action, error);
        }
    }
}
