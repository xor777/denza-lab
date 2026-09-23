package dev.denza.apps.feature.navigation;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import dev.denza.apps.BuildConfig;

/**
 * Which applications may be put on the driver's display as a picture.
 *
 * Any application the car can open, which is the platform's own question - a launch intent for the
 * package - rather than a list. There was a list: six navigators, written out twice, once for the
 * picker and once for the shell-UID proxy, and every map, player or browser the owner installed was
 * missing from the driver's screen until somebody added a line. The projection never depended on
 * any of them being a navigator; it moves a task.
 *
 * Two packages are left out, and neither for being unfamiliar. This app, because its own
 * instruments are drawn into the scene directly and are never a task to move - offered as an
 * application, it would move the very screen doing the choosing onto the cluster. And the home
 * screen, because its task is the one every other returns to; moving it to a virtual
 * display is not something this firmware has been asked to survive.
 *
 * Both sides of the shell boundary read this class: the picker, to offer only what the proxy will
 * move, and {@link ClusterProxyMain}, to check again inside the shell UID before every task
 * mutation. One rule, so the picker can never offer a tile that the proxy refuses.
 */
public final class ProjectablePackages {
    private ProjectablePackages() {
    }

    public static boolean isProjectable(PackageManager packageManager, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (isExcluded(packageName, homePackage(packageManager))) return false;
        return packageManager.getLaunchIntentForPackage(packageName) != null;
    }

    /** The two exclusions alone, for a list of launchable packages the car has already given. */
    public static boolean isExcluded(String packageName, String homePackage) {
        return BuildConfig.APPLICATION_ID.equals(packageName) || packageName.equals(homePackage);
    }

    /**
     * The package that answers HOME, or {@code null}.
     *
     * With several homes and no default the platform answers with its own chooser, whose package is
     * {@code android}; that is never launchable, so leaving it out costs nothing.
     */
    public static String homePackage(PackageManager packageManager) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved =
                packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null) return null;
        return resolved.activityInfo.packageName;
    }
}
