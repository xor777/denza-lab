package dev.denza.apps.feature.hud

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map switch's stored wish: off on a fresh install, in the hints' own file under its own key,
 * and never the hints' value. The file and key names are spelled out: a renamed key would forget
 * every driver's choice on update.
 */
class HudGuidanceSettingsTest {

    @Test
    fun theMapIsOffUntilTheDriverTurnsItOn() {
        val context = FakeContext()

        assertFalse(HudGuidanceSettings.isMapEnabled(context))
        HudGuidanceSettings.setEnabled(context, true)
        assertFalse(HudGuidanceSettings.isMapEnabled(context))
    }

    @Test
    fun theMapIsItsOwnKeyInTheHintsFile() {
        val context = FakeContext()

        HudGuidanceSettings.setMapEnabled(context, true)

        assertEquals(mapOf("map" to true), context.files.getValue("denza_hud_guidance").values)
        assertTrue(HudGuidanceSettings.isMapEnabled(context))
        assertFalse(HudGuidanceSettings.isEnabled(context))
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
