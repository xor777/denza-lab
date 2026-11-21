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

    /**
     * «Повороты» as the driver stored it: the feature's switch with the ordinary Yandex, whose hints
     * this app reads off the screen, and the capture's switch everywhere. With a YandexHUD that
     * publishes through the host it no longer decides what reaches the glass ([hintsShown]).
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    /**
     * Whether the host lets the hints through: always with a YandexHUD that knows the host - nothing
     * in this app switches its turns off (2026-11-21) - and otherwise as [stored].
     *
     * Until then the stored switch held the module back too, and it is off on a fresh install: the
     * module drew by itself while the host was not up yet, handed over to it and went dark.
     */
    fun hintsShown(stored: Boolean, navigator: HudNavigatorInstall): Boolean =
        stored || navigator == HudNavigatorInstall.YANDEX_HUD

    // Keep the persistent write boundary explicit for feature recovery.
    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    /**
     * «Карта»: the HUD's map window while a route is held - a crop of Yandex's map with the
     * ordinary Yandex, YandexHUD's own map and marks with the module. Its own value beside the
     * hints' switch; the tile's press never moves it. The installed navigator is the cached one
     * ([HudInstalls.installed]), since the host asks on every packet.
     */
    fun isMapEnabled(context: Context): Boolean = isMapEnabled(context, HudInstalls.installed(context))

    /**
     * A value the driver wrote is kept. One never written is [mapByDefault] for [navigator].
     */
    fun isMapEnabled(context: Context, navigator: HudNavigatorInstall): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MAP, mapByDefault(navigator))

    /**
     * On with a YandexHUD that knows the host: its map comes with its hints and asks for nothing.
     * Off with the ordinary Yandex, where the map is a screen capture and on would ask for its grant
     * unasked.
     */
    fun mapByDefault(navigator: HudNavigatorInstall): Boolean = navigator == HudNavigatorInstall.YANDEX_HUD

    /**
     * The map window is this app's to fill: its switch is on and the installed Navigator does not
     * fill it itself ([HudNavigatorBuild]). The switch keeps its value either way, so the map comes
     * back with the ordinary Yandex. Without YandexHUD the navigator is the ordinary Yandex or none,
     * and both read the ordinary default, so the cached install is not asked.
     */
    fun isMapOurs(context: Context): Boolean =
        !HudNavigatorBuild.drawsHud(context) && isMapEnabled(context, HudNavigatorInstall.YANDEX)

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
