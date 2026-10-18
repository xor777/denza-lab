package dev.denza.apps.feature.hud

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether the installed Yandex Navigator is «YandexHUD», the owner's build with the HUD module
 * embedded (`experiments/yandex-hud-module`, built by `tools/yandex_hud_build`), which sends the
 * hints, lanes, AR and the map window to the HUD itself.
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
 * one decides every route; there is nothing to negotiate at run time. The build has carried two
 * names, and both are recognised: a build already handed out is never reinstalled to match.
 */
object HudNavigatorBuild {

    /**
     * The first name, `29.8.1-denza-hud-exp.1`, written by the first build tool
     * (`tools/yandex_hud_build/build.py` and `manifest.py` on that branch, from `d45901e9` until
     * `53dd3d7f`). The builds handed out on 14-15.10 carry it.
     */
    const val FIRST_MARKER = "-denza-hud"

    /**
     * The name since `53dd3d7f` (15.10): `tools/yandex_hud_build/identity.py` writes
     * `{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}` with the label `YandexHUD`, so
     * `29.8.1-YandexHUD.1`, the number growing with each distributed build and the base following
     * Yandex's APK. Matched as the label, a dot and the number closing the name, case and all; the
     * rename alone, unmatched, put this app back on the HUD beside the module. Since the module
     * came to main `HudNavigatorBuildTest` reads that file, so the next rename fails this app's
     * tests instead of reaching the car.
     */
    private val NUMBERED = Regex("""-YandexHUD\.[0-9]+$""")

    fun drawsHud(versionName: String?): Boolean =
        versionName != null && (FIRST_MARKER in versionName || NUMBERED.containsMatchIn(versionName))

    /** One PackageManager call; the monitor asks once per route, the panel on package changes. */
    fun drawsHud(context: Context): Boolean = drawsHud(
        try {
            context.packageManager.getPackageInfo(HudGuidanceSettings.NAVIGATOR_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        },
    )
}
