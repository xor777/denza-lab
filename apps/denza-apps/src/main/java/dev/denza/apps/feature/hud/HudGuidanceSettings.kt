package dev.denza.apps.feature.hud

import android.annotation.SuppressLint
import android.content.Context

object HudGuidanceSettings {
    private const val PREFS = "denza_hud_guidance"
    private const val ENABLED = "enabled"
    private const val MAP = "map"
    private const val SIGN = "sign"
    private const val CAMERAS = "cameras"

    /**
     * The one navigator whose guidance the windscreen repeats.
     *
     * Named here because this feature reads that application's own notifications and screen, which
     * no other navigator writes the same way - not because the driver's display prefers it. That
     * choice has no list any more; see `NavigationAppPolicy`.
     */
    const val NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    // Keep the persistent write boundary explicit for feature recovery.
    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    /**
     * «Карта на проекции»: a crop of Yandex's map in the HUD's map window while a route is held.
     * Its own value beside the hints' switch, off until the driver turns it on; the tile's press
     * moves the hints and never this.
     */
    fun isMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MAP, false)

    /**
     * The map window is this app's to fill: its switch is on and the installed Navigator does not
     * fill it itself ([HudNavigatorBuild]). The switch keeps its value either way, so the map comes
     * back with the ordinary Yandex.
     */
    fun isMapOurs(context: Context): Boolean =
        isMapEnabled(context) && !HudNavigatorBuild.drawsHud(context)

    @SuppressLint("UseKtx")
    fun setMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MAP, enabled)
            .apply()
    }

    /**
     * «Ограничение скорости»: the navigator's limit in field 11 and on the car's own sign. On until
     * the driver turns it off - the sign is what the HUD did first and what the owner asked for.
     * Off, no source's limit passes the host ([HudHostOutput]), so the two go together.
     */
    fun isSignEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SIGN, true)

    @SuppressLint("UseKtx")
    fun setSignEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SIGN, enabled)
            .apply()
    }

    /** «Камеры»: HUD Speed's warnings on the projection. On until turned off. */
    fun areCamerasEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CAMERAS, true)

    @SuppressLint("UseKtx")
    fun setCamerasEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CAMERAS, enabled)
            .apply()
    }
}
