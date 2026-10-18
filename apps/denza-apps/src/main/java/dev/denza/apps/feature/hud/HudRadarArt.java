package dev.denza.apps.feature.hud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;

/**
 * HUD Speed's own drawables, read from the installed app by the names its adapter sends (the
 * type's tile, the limit's frame, the GPS bars): the warning on the HUD looks as HUD Speed draws
 * it. This app sees the package through QUERY_ALL_PACKAGES. Main thread.
 */
final class HudRadarArt {
    private static final String TAG = "DenzaHudGuidance";
    /** After a failed lookup of the package, how long before trying again. */
    private static final long RETRY_MS = 10_000L;

    private final Context context;
    private Resources resources;
    private long retryAt;

    HudRadarArt(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The drawable {@code name} of HUD Speed, or null when the app or the name is missing. */
    Drawable drawable(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Resources source = resources();
        if (source == null) {
            return null;
        }
        int id = source.getIdentifier(name, "drawable", HudHostGate.HUD_SPEED_PACKAGE);
        if (id == 0) {
            return null;
        }
        try {
            return source.getDrawable(id, null);
        } catch (Resources.NotFoundException missing) {
            return null;
        }
    }

    private Resources resources() {
        if (resources != null) {
            return resources;
        }
        long now = SystemClock.uptimeMillis();
        if (now < retryAt) {
            return null;
        }
        try {
            resources = context.getPackageManager()
                    .getResourcesForApplication(HudHostGate.HUD_SPEED_PACKAGE);
        } catch (PackageManager.NameNotFoundException missing) {
            Log.w(TAG, "HUD Speed is not installed; its warning is drawn without its artwork");
            retryAt = now + RETRY_MS;
        }
        return resources;
    }
}
