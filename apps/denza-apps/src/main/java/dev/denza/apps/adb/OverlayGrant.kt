package dev.denza.apps.adb

import android.content.Context
import android.provider.Settings
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice
import dev.denza.apps.platform.shell.ShellGrants

/**
 * The app's own right to draw over other windows - the scene on the driver's screen, the
 * projection's row over the stock dialog - granted over the local ADB channel when it is missing.
 *
 * The driver's screen used to send `cmd appops set … SYSTEM_ALERT_WINDOW allow` on every press,
 * granted or not, so showing this app's own instruments needed a working ADB channel each time,
 * and any hiccup in it put «Нет доступа» on the tile in place of the scene. Android answers whether
 * the right is held without the channel; the shell is for when it is not.
 */
object OverlayGrant {

    fun held(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Grants it if it is not held; throws what the shell throws. */
    fun ensure(context: Context) {
        if (held(context)) return
        DenzaLocalAdb.client(context).shell(command(context.packageName))
        // The projection reads the same grant.
        StateMarks.mark(StateSlice.SIMULCAST, "overlay granted")
    }

    internal fun command(packageName: String): String =
        ShellGrants.appop(packageName, "SYSTEM_ALERT_WINDOW", "allow")
}
