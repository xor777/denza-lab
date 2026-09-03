package dev.denza.apps.feature.defaultapps

import android.annotation.SuppressLint
import android.content.Context

/**
 * Remembers what this product has decided about a role, never what the car is doing with it.
 *
 * What the car is doing is AutoVoice's PersonBean row, read again whenever the Activity resumes;
 * nothing here is allowed to answer that question. Two decisions do live here:
 *
 * - the first-run marker, which makes discovery a one-time decision: a stock fallback remains
 *   stock if a known app is installed later, while an existing non-stock choice remains external
 *   and untouched;
 * - the remembered pick, which is the last non-stock package the car was seen using for this role.
 *   Switching the substitution off writes the car's own application into every role, and without
 *   this there would be nothing left to say what switching it back on should restore;
 * - the navigation proxy target and its confirmed-active marker. PersonBean remains the source of
 *   truth for whether the proxy is selected, while the target is necessarily app-owned because
 *   AutoVoice stores only one package in the role row.
 */
object DefaultAppsSettings {
    private const val PREFS = "default_apps"
    private const val NAVIGATION_PROXY_ACTIVE = "navigation_proxy_active"
    private const val NAVIGATION_PROXY_REPAIR_PENDING = "navigation_proxy_repair_pending"

    fun isInitializationHandled(context: Context, role: DefaultAppRole): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(initializedKey(role), false)

    @SuppressLint("UseKtx")
    fun markInitializationHandled(context: Context, role: DefaultAppRole) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(initializedKey(role), true)
                .commit(),
        ) {
            "AutoVoice readback succeeded, but Denza Apps did not persist ${role.roleKey} " +
                "initialization state"
        }
    }

    /** The last non-stock package this role was seen holding, if any. */
    fun rememberedPick(context: Context, role: DefaultAppRole): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(pickKey(role), null)
            ?.takeIf(String::isNotBlank)

    /**
     * Records a package the car is confirmed to be using for [role].
     *
     * Only a non-stock value is worth remembering: the stock application is what "off" means, and
     * a role that has never held anything else has no pick to restore.
     */
    @SuppressLint("UseKtx")
    fun rememberPick(context: Context, role: DefaultAppRole, packageName: String) {
        if (packageName == role.stockPackageName || packageName.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(pickKey(role), packageName)
            .apply()
    }

    /** Whether Denza Apps last confirmed the proxy package in AutoVoice's navigation row. */
    fun isNavigationProxyActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(NAVIGATION_PROXY_ACTIVE, false)

    /** A proxy replacement asked us to repair the row AutoVoice clears during that replacement. */
    fun isNavigationProxyRepairPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(NAVIGATION_PROXY_REPAIR_PENDING, false)

    fun navigationProxyTarget(context: Context): String? =
        DefaultNavigationProxyStore.read(context)

    /** The target must be durable before PersonBean is allowed to point at the proxy package. */
    @SuppressLint("UseKtx")
    fun setNavigationProxyTarget(context: Context, packageName: String): String {
        require(DefaultNavigationProxyContract.isValidTarget(packageName)) {
            "Invalid navigation proxy target: $packageName"
        }
        return DefaultNavigationProxyStore.write(context, packageName)
    }

    /** Called only when Android reports that this APK itself has been replaced. */
    @SuppressLint("UseKtx")
    fun requestNavigationProxyRepair(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = preferences.getBoolean(NAVIGATION_PROXY_ACTIVE, false)
        check(
            preferences.edit()
                .putBoolean(NAVIGATION_PROXY_REPAIR_PENDING, pending)
                .commit(),
        ) {
            "Could not persist navigation proxy repair request"
        }
    }

    /** Records only an exact PersonBean readback, never an intended write. */
    @SuppressLint("UseKtx")
    fun markNavigationProxyActive(context: Context, active: Boolean) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(NAVIGATION_PROXY_ACTIVE, active)
                .putBoolean(NAVIGATION_PROXY_REPAIR_PENDING, false)
                .commit(),
        ) {
            "AutoVoice readback succeeded, but proxy state was not persisted"
        }
    }

    private fun initializedKey(role: DefaultAppRole): String =
        "initialized_${role.name.lowercase()}"

    private fun pickKey(role: DefaultAppRole): String = "pick_${role.name.lowercase()}"
}
