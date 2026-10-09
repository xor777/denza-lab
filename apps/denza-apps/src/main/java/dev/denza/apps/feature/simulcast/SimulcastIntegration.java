package dev.denza.apps.feature.simulcast;

import android.content.Context;
import android.content.SharedPreferences;

import dev.denza.apps.StateMarks;
import dev.denza.apps.StateSlice;

import java.util.Objects;

public final class SimulcastIntegration {
    private static final String PREFS = "simulcast_integration";
    private static final String KEY_ENABLED = "enabled";

    /**
     * The app this process is casting, in memory only. DiShare stops a share when the API client
     * that started it dies while it is the mirror client ({@code onClientRemoved} →
     * {@code exitAll}, dishare-api-notes.md "End of a share"), so a target recorded by an earlier
     * process names a share that is already gone. Kept in preferences, as it was, it brought the
     * exit control and a running tile back after every restart of the process.
     */
    private static volatile String lastTargetPackage;

    private SimulcastIntegration() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getLastTargetPackage() {
        return lastTargetPackage;
    }

    /**
     * A share started on [packageName]. The «Трансляция» tile shows a running share, so the write
     * marks its slice; nothing else tells it.
     */
    public static void setLastTargetPackage(String packageName) {
        replaceLastTargetPackage(packageName, "share started");
    }

    /** No share any more: ended by us or by DiShare, or replaced by the next one. */
    public static void clearLastTargetPackage() {
        replaceLastTargetPackage(null, "share over");
    }

    private static synchronized void replaceLastTargetPackage(String packageName, String cause) {
        if (Objects.equals(lastTargetPackage, packageName)) {
            return;
        }
        lastTargetPackage = packageName;
        StateMarks.INSTANCE.mark(StateSlice.SIMULCAST, cause);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
