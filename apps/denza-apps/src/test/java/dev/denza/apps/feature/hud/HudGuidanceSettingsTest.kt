package dev.denza.apps.feature.hud

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map switch's stored wish: in the hints' own file under its own key, and never the hints'
 * value; never written, it is off with the ordinary Yandex and on with a YandexHUD through the host.
 * The file and key names are spelled out: a renamed key would forget every driver's choice on
 * update.
 */
class HudGuidanceSettingsTest {

    @Test
    fun withTheOrdinaryYandexTheMapIsOffUntilTheDriverTurnsItOn() {
        val context = FakeContext()

        assertFalse(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX))
        HudGuidanceSettings.setEnabled(context, true)
        assertFalse(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX))
        assertFalse(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.NONE))
        assertFalse(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX_HUD_ALONE))
    }

    /** YandexHUD's map comes with its hints and asks for no grant: on until the driver says no. */
    @Test
    fun withYandexHudTheMapIsOnUntilTheDriverTurnsItOff() {
        val context = FakeContext()

        assertTrue(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX_HUD))
        assertEquals(emptyMap<String, Any>(), context.files.getValue("denza_hud_guidance").values)

        HudGuidanceSettings.setMapEnabled(context, false)
        assertFalse(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX_HUD))
    }

    /** A value the driver wrote is kept whichever navigator comes next. */
    @Test
    fun aWrittenMapIsKeptAcrossNavigators() {
        val context = FakeContext()

        HudGuidanceSettings.setMapEnabled(context, true)
        assertTrue(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX))
        assertTrue(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX_HUD))
    }

    @Test
    fun theMapIsItsOwnKeyInTheHintsFile() {
        val context = FakeContext()

        HudGuidanceSettings.setMapEnabled(context, true)

        assertEquals(mapOf("map" to true), context.files.getValue("denza_hud_guidance").values)
        assertTrue(HudGuidanceSettings.isMapEnabled(context, HudNavigatorInstall.YANDEX))
        assertFalse(HudGuidanceSettings.isEnabled(context))
    }

    @Test
    fun theHintsPassWithYandexHudWhateverIsStored() {
        assertTrue(HudGuidanceSettings.hintsShown(stored = false, navigator = HudNavigatorInstall.YANDEX_HUD))
        assertFalse(HudGuidanceSettings.hintsShown(stored = false, navigator = HudNavigatorInstall.YANDEX))
        assertFalse(HudGuidanceSettings.hintsShown(stored = false, navigator = HudNavigatorInstall.YANDEX_HUD_ALONE))
        assertTrue(HudGuidanceSettings.hintsShown(stored = true, navigator = HudNavigatorInstall.YANDEX))
    }

    private class FakeContext : ContextWrapper(null) {
        val files = HashMap<String, Prefs>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            files.getOrPut(name) { Prefs() }
    }

    /** Booleans only: all this file holds. */
    private class Prefs : SharedPreferences {
        val values = HashMap<String, Any>()

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = HashMap<String, Any>()

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                staged[key] = value
                return this
            }

            override fun apply() {
                values.putAll(staged)
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun putString(key: String, value: String?) = unsupported()
            override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
            override fun putInt(key: String, value: Int) = unsupported()
            override fun putLong(key: String, value: Long) = unsupported()
            override fun putFloat(key: String, value: Float) = unsupported()
            override fun remove(key: String) = unsupported()
            override fun clear() = unsupported()
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = unsupported()
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = unsupported()
        override fun getInt(key: String, defValue: Int) = unsupported()
        override fun getLong(key: String, defValue: Long) = unsupported()
        override fun getFloat(key: String, defValue: Float) = unsupported()
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = unsupported()
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = unsupported()
    }

    private companion object {
        fun unsupported(): Nothing = throw UnsupportedOperationException("not a boolean")
    }
}
