package dev.denza.apps.feature.defaultapps

import android.annotation.SuppressLint
import android.content.Context

data class ConfirmedSelection(
    val selectedPackageName: String,
    val selectedLabel: String,
)

/**
 * Persists product decisions and the last exact provider-confirmed selection for each role.
 *
 * What the car is doing is AutoVoice's PersonBean row, read again whenever the Activity resumes;
 * a remembered confirmation hydrates the UI but never replaces that read. The stored values are:
 *
 * - the first-run marker, which makes discovery a one-time decision: a stock fallback remains
 *   stock if a known app is installed later, while an existing non-stock choice remains external
 *   and untouched;
 * - the remembered pick, which is the last non-stock package the car was seen using for this role.
 *   Switching the substitution off writes the car's own application into every role, and without
 *   this there would be nothing left to say what switching it back on should restore;
 * - the last confirmed package and label, used only until the next provider read completes.
 */
object DefaultAppsSettings {
    private const val PREFS = "default_apps"

    fun confirmedSelection(context: Context, role: DefaultAppRole): ConfirmedSelection? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = preferences.getString(confirmedPackageKey(role), null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val label = preferences.getString(confirmedLabelKey(role), null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return ConfirmedSelection(packageName, label)
    }

    fun rememberConfirmedSelection(
        context: Context,
        role: DefaultAppRole,
        selectedPackageName: String,
        selectedLabel: String,
    ) {
        if (selectedPackageName.isBlank() || selectedLabel.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(confirmedPackageKey(role), selectedPackageName)
            .putString(confirmedLabelKey(role), selectedLabel)
            .apply()
    }

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

    private fun initializedKey(role: DefaultAppRole): String =
        "initialized_${role.name.lowercase()}"

    private fun pickKey(role: DefaultAppRole): String = "pick_${role.name.lowercase()}"

    private fun confirmedPackageKey(role: DefaultAppRole): String =
        "confirmed_package_${role.name.lowercase()}"

    private fun confirmedLabelKey(role: DefaultAppRole): String =
        "confirmed_label_${role.name.lowercase()}"
}
