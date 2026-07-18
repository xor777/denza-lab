package dev.denza.apps.feature.navigation

import android.content.Context

object NavigationSettings {
    private const val PREFS = "denza_navigation"
    private const val SELECTED_PACKAGE = "selected_package"

    fun selectedPackage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_PACKAGE, null)
        return saved
            ?.takeIf(NavigationAppPolicy::isAllowed)
            ?.takeIf { isInstalled(context, it) }
            ?: installedApps(context).firstOrNull()?.packageName
            ?: NavigationAppPolicy.DEFAULT_PACKAGE
    }

    fun setSelectedPackage(context: Context, packageName: String) {
        require(NavigationAppPolicy.isAllowed(packageName)) { "unsupported navigation package" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_PACKAGE, packageName)
            .apply()
    }

    fun installedApps(context: Context): List<NavigationAppDefinition> =
        NavigationAppPolicy.supported.filter { isInstalled(context, it.packageName) }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}
