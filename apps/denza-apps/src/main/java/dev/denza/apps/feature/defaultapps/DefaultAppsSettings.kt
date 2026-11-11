package dev.denza.apps.feature.defaultapps

import android.annotation.SuppressLint
import android.content.Context

/** The label is worked out from the package when this is read, so it is in today's language. */
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
 * - the last confirmed package, used only until the next provider read completes.
 *
 * No display text is stored. Installs from before 2026-11-06 also hold `confirmed_label_<role>`,
 * possibly a word of ours in the language of that day. Nothing reads it, and the next confirmation
 * removes it in the same edit that writes the package, so a run that never gets that far just
 * leaves an unread value behind.
 */
object DefaultAppsSettings {
    private const val PREFS = "default_apps"

    fun confirmedPackage(context: Context, role: DefaultAppRole): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(confirmedPackageKey(role), null)
            ?.takeIf(String::isNotBlank)

    /**
     * What hydrates the panel, read from preferences alone.
     *
     * `DefaultAppsRuntime.refresh` calls this inside a state update, on the main thread before the
     * first frame, so it must not ask PackageManager. The label is [DefaultAppsCatalog.fallbackLabel]:
     * an unknown third-party app shows its package name until the first provider read replaces it
     * with what `discover` found.
     */
    fun confirmedSelection(context: Context, role: DefaultAppRole): ConfirmedSelection? =
        confirmedPackage(context, role)?.let { packageName ->
            ConfirmedSelection(packageName, DefaultAppsCatalog.fallbackLabel(role, packageName))
        }

    fun rememberConfirmedSelection(
        context: Context,
        role: DefaultAppRole,
        selectedPackageName: String,
    ) {
        if (selectedPackageName.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(confirmedPackageKey(role), selectedPackageName)
            .remove(retiredLabelKey(role))
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

    private fun retiredLabelKey(role: DefaultAppRole): String =
        "confirmed_label_${role.name.lowercase()}"
}
