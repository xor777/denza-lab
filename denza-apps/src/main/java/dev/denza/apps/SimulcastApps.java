package dev.denza.apps;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The user's chosen casting apps for Simulcast. denza-apps can be installed on any
 * car with any app set, so the row is configurable instead of hard-coded. The
 * accessibility overlay erases the native App Change row and paints these over it
 * (in order), so the row looks fully native with no stock icons — no helper APKs or
 * slot registrations required.
 */
final class SimulcastApps {
    private static final String PREFS = "simulcast_apps";
    private static final String KEY_SELECTED = "selected_packages";

    /** Max apps in the row (the native row comfortably fits this many). */
    static final int MAX_SELECTED = 6;

    /** Preferred defaults, in row order. Filtered to whatever is installed. */
    static final String[] DEFAULT_PREFERRED = {
            "com.vk.vkvideo",               // VK Видео
            "ru.rutube.app",                // Rutube
            "ru.kinopoisk",                 // Кинопоиск
            "ru.yandex.yandexnavi",         // Яндекс Навигатор
            "org.videolan.vlc",             // VLC
            "com.google.android.youtube"    // YouTube
    };

    private SimulcastApps() {
    }

    /** Ordered list of selected, currently-installed packages. Seeds defaults on first run. */
    static List<String> getSelected(Context context) {
        String raw = prefs(context).getString(KEY_SELECTED, null);
        if (raw == null) {
            List<String> defaults = defaults(context);
            setSelected(context, defaults);
            return defaults;
        }
        List<String> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String pkg : raw.split("\n")) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty() && isInstalled(pm, trimmed) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    static void setSelected(Context context, List<String> packages) {
        StringBuilder builder = new StringBuilder();
        for (String pkg : packages) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(pkg);
        }
        prefs(context).edit().putString(KEY_SELECTED, builder.toString()).apply();
    }

    static int selectedCount(Context context) {
        return getSelected(context).size();
    }

    private static List<String> defaults(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : DEFAULT_PREFERRED) {
            if (out.size() >= MAX_SELECTED) {
                break;
            }
            if (isInstalled(pm, pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
