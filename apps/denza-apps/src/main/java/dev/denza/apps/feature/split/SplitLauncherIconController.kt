package dev.denza.apps.feature.split

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Owns only the launcher visibility of the user-facing Split Screen entry. */
internal object SplitLauncherIconController {
    fun isVisible(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setVisible(context: Context, visible: Boolean) {
        if (isVisible(context) == visible) return
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS,
        )
        check(isVisible(context) == visible) {
            "Launcher did not ${if (visible) "show" else "hide"} Split Screen"
        }
    }

    private fun component(context: Context): ComponentName = ComponentName(
        context.packageName,
        "${context.packageName}.SplitScreenLauncherAlias",
    )
}
