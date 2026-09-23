package dev.denza.apps.feature.navigation

import android.annotation.SuppressLint
import android.content.Context
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.defaultapps.InstalledDefaultApp

object NavigationSettings {
    private const val PREFS = "denza_navigation"
    private const val SELECTED_PACKAGE = "selected_package"
    private const val MAP_PLACEMENT = "map_placement"
    private const val STEERING_WHEEL_BUTTON = "steering_wheel_button"

    /**
     * The saved choice while the car can still show it, and the instruments otherwise.
     *
     * The instruments are the fallback because they are the one answer that cannot go missing and
     * that works on the first press. It used to be the first installed navigator, and before that
     * Яндекс Навигатор by name - a preference written into the code for every car it would ever
     * run on. A choice the owner made survives an update untouched: whatever it names is still an
     * application the car can open.
     */
    fun selectedPackage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_PACKAGE, null)
        return saved?.takeIf { isOffered(context, it) } ?: NavigationAppPolicy.DASHBOARD_PACKAGE
    }

    /** Stores a choice its caller has already checked with [isOffered]. */
    @SuppressLint("UseKtx")
    fun setSelectedPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_PACKAGE, packageName)
            .apply()
    }

    fun placement(context: Context): ClusterMapPlacement = runCatching {
        ClusterMapPlacement.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MAP_PLACEMENT, ClusterMapPlacement.FULL.name)
                ?: ClusterMapPlacement.FULL.name,
        )
    }.getOrDefault(ClusterMapPlacement.FULL)

    @SuppressLint("UseKtx")
    fun setPlacement(context: Context, placement: ClusterMapPlacement) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MAP_PLACEMENT, placement.name)
            .apply()
    }

    fun steeringWheelButtonEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(STEERING_WHEEL_BUTTON, false)

    @SuppressLint("UseKtx")
    fun setSteeringWheelButtonEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(STEERING_WHEEL_BUTTON, enabled)
            .apply()
    }

    /** Whether [packageName] is an answer the driver's display can take right now. */
    fun isOffered(context: Context, packageName: String): Boolean =
        NavigationAppPolicy.isDashboard(packageName) ||
            ProjectablePackages.isProjectable(context.packageManager, packageName)
}

/**
 * The chooser's applications: the car's launcher catalog, less what [ProjectablePackages] leaves
 * out, by name.
 *
 * By name alone, the order the projection's chooser uses, so one application sits in the same place
 * on both pages. The chosen one is marked where it stands rather than moved to the front: a list
 * that reorders itself around the last tap loses the tile from under the finger.
 */
internal object NavigationApplications {
    fun of(launchable: List<InstalledDefaultApp>, homePackage: String?): List<InstalledDefaultApp> =
        launchable
            .filterNot { ProjectablePackages.isExcluded(it.packageName, homePackage) }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, InstalledDefaultApp::label)
                    .thenBy(InstalledDefaultApp::packageName),
            )
}
