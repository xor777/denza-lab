package dev.denza.apps.feature.navigation

import android.annotation.SuppressLint
import android.content.Context
import dev.denza.apps.feature.cluster.ClusterMapPlacement

object NavigationSettings {
    private const val PREFS = "denza_navigation"
    private const val SELECTED_PACKAGE = "selected_package"
    private const val MAP_PLACEMENT = "map_placement"
    private const val STEERING_WHEEL_BUTTON = "steering_wheel_button"

    fun selectedPackage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_PACKAGE, null)
        return saved
            ?.takeIf(NavigationAppPolicy::isAllowed)
            ?.takeIf { isInstalled(context, it) }
            ?: installedApps(context).firstOrNull()?.packageName
            ?: NavigationAppPolicy.DEFAULT_PACKAGE
    }

    // Keep validated preference writes explicit at the navigation policy boundary.
    @SuppressLint("UseKtx")
    fun setSelectedPackage(context: Context, packageName: String) {
        require(NavigationAppPolicy.isAllowed(packageName)) { "unsupported navigation package" }
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

    fun installedApps(context: Context): List<NavigationAppDefinition> =
        NavigationAppPolicy.supported.filter { isInstalled(context, it.packageName) }

    /**
     * What the picker offers: every navigator actually installed, and then our own instruments.
     *
     * The dashboard is last on purpose. It is always available - it cannot be uninstalled without
     * uninstalling the picker showing it - so putting it first would move whichever navigator the
     * driver actually uses one tile along for no reason.
     *
     * It is deliberately not part of [installedApps], which is also what the fallback in
     * [selectedPackage] reads. A car with no navigator on it should still say so and ask for one,
     * rather than silently settling on our instruments because they happen to be installable-proof.
     */
    fun choices(context: Context): List<NavigationAppDefinition> =
        installedApps(context) + NavigationAppPolicy.dashboard

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}
