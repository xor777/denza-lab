package dev.denza.apps.feature.defaultapps

import android.annotation.SuppressLint
import android.content.Context

/**
 * Remembers only whether Denza Apps has handled first-run selection for a role.
 *
 * The selected package deliberately does not live here: AutoVoice's PersonBean row is the source
 * of truth and is read again whenever the Activity resumes. The marker makes first-run discovery a
 * one-time decision: a stock fallback remains stock if a known app is installed later, while an
 * existing non-stock choice remains external and untouched.
 */
object DefaultAppsSettings {
    private const val PREFS = "default_apps"

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

    private fun initializedKey(role: DefaultAppRole): String =
        "initialized_${role.name.lowercase()}"
}
