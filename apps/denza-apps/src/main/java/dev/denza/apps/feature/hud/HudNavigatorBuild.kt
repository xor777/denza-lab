package dev.denza.apps.feature.hud

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether the installed Yandex Navigator is «Яндекс HUD», the owner's build with the HUD module
 * embedded (branch `experiment/yandex-hud-renderer`), which sends the hints, lanes, AR and the map
 * window to the HUD itself.
 *
 * It keeps Yandex's package name, so this app read it as Yandex and sent its own road packet and
 * map beside the module's. The SOME/IP service keeps the two sessions apart, and the HUD shows
 * whichever packet came last: two maps in turn, the manoeuvre and street flickering, the AR guide
 * point jumping between two values and restarting the firmware's AR scene, this app's black frames
 * wiping the module's map. One writer per HUD channel: the module has the route itself, this app
 * reads a screen, so this app gives way and keeps only what the module cannot do - the car's own
 * speed sign, written over ADB.
 *
 * Read from the version name. Both builds share the package, never both installed, so the installed
 * one decides every route; there is nothing to negotiate at run time.
 */
object HudNavigatorBuild {

    /**
     * In the HUD build's version name, `29.8.1-denza-hud-exp.1`, since its first build, so builds
     * already handed out are recognised too. `tools/yandex_hud_build/build.py` on that branch
     * writes and checks it.
     */
    const val MARKER = "-denza-hud"

    fun drawsHud(versionName: String?): Boolean = versionName?.contains(MARKER) == true

    /** One PackageManager call; the monitor asks once per route, the panel on package changes. */
    fun drawsHud(context: Context): Boolean = drawsHud(
        try {
            context.packageManager.getPackageInfo(HudGuidanceSettings.NAVIGATOR_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        },
    )
}
