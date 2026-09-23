package dev.denza.apps

import android.content.Context
import android.util.Log
import dev.denza.apps.feature.defaultapps.DefaultAppsCatalogCache
import dev.denza.apps.feature.navigation.NavigationAppPolicy
import dev.denza.apps.feature.navigation.NavigationApplications
import dev.denza.apps.feature.navigation.ProjectablePackages

/** What «Что показывать» offers and what it has chosen, read off the car. */
internal object NavigationAppChoices {
    private const val TAG = "DenzaApps.DriverScreen"

    /**
     * The instruments, then every application the car can open.
     *
     * The applications come from the launcher catalog the default-app roles already keep - cached,
     * and dropped when a package is installed, removed or changed - so opening this page twice
     * reads the car once. See [NavigationApplications] for what is left out and in what order the
     * rest stand.
     */
    fun all(context: Context, selectedPackage: String): List<NavigationAppChoice> {
        val installed = runCatching { DefaultAppsCatalogCache.installed(context) }
            .onFailure { Log.w(TAG, "Launcher catalog unavailable for the driver's screen", it) }
            .getOrDefault(emptyList())
        val home = ProjectablePackages.homePackage(context.packageManager)
        val instruments = instruments(selected = NavigationAppPolicy.isDashboard(selectedPackage))
        return listOf(instruments) + NavigationApplications.of(installed, home).map { app ->
            NavigationAppChoice(
                packageName = app.packageName,
                label = app.label,
                icon = app.icon,
                selected = app.packageName == selectedPackage,
            )
        }
    }

    /**
     * What is chosen, for the tile and the panel's row, without reading the whole catalog: one
     * label and one icon from the package manager.
     */
    fun chosen(context: Context, selectedPackage: String): NavigationAppChoice {
        if (NavigationAppPolicy.isDashboard(selectedPackage)) return instruments(selected = true)
        val packageManager = context.packageManager
        val info = runCatching { packageManager.getApplicationInfo(selectedPackage, 0) }.getOrNull()
        return NavigationAppChoice(
            packageName = selectedPackage,
            label = info?.let { packageManager.getApplicationLabel(it).toString() }
                ?.takeIf(String::isNotBlank)
                ?: selectedPackage,
            icon = info?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() },
            selected = true,
        )
    }

    fun instruments(selected: Boolean) = NavigationAppChoice(
        packageName = NavigationAppPolicy.DASHBOARD_PACKAGE,
        label = NavigationAppPolicy.DASHBOARD_LABEL,
        icon = null,
        selected = selected,
        instruments = true,
    )
}
