package dev.denza.apps.feature.split

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Пилюля», the split panel's second switch (contract 1.15.14): on for a fresh install, stored
 * under its own key in the split's file and nowhere in the automaton's snapshot, and told to the
 * pill's host as it changes. The file and key names are spelled out: a renamed key would forget
 * every driver's choice on update.
 */
class SplitPillSettingTest {

    @Test
    fun thePillIsOnUntilTheDriverTurnsItOff() {
        assertTrue(SplitScreenSettings.isPillEnabled(FakeContext()))
    }

    @Test
    fun theSwitchSurvivesTheProcessUnderItsOwnKey() {
        val context = FakeContext()

        SplitScreenSettings.setPillEnabled(context, false)

        val file = context.files.getValue("denza_split_screen")
        assertEquals(mapOf("pill_enabled" to false), file.values)
        // The next process reads the same file.
        assertFalse(SplitScreenSettings.isPillEnabled(FakeContext(context.files)))
        SplitScreenSettings.setPillEnabled(context, true)
        assertTrue(SplitScreenSettings.isPillEnabled(FakeContext(context.files)))
    }

    @Test
    fun theSnapshotAndTheSwitchNeverWriteEachOther() {
        val context = FakeContext()
        val store = SplitScreenSettings.stateStore(context)
        val before = store.load()

        SplitScreenSettings.setPillEnabled(context, false)
        assertEquals("the switch is not in the snapshot", before, store.load())

        assertTrue(store.commit(before.copy(enabled = !before.enabled)))
        assertFalse("a snapshot commit keeps the switch", SplitScreenSettings.isPillEnabled(context))
    }

    @Test
    fun theHostHearsEachFlipOfItsKeyAndNothingElse() {
        val context = FakeContext()
        val heard = mutableListOf<Boolean>()
        val stop = SplitScreenSettings.observePillEnabled(context) { heard += it }

        SplitScreenSettings.setPillEnabled(context, false)
        SplitScreenSettings.stateStore(context).commit(SplitScreenSettings.stateStore(context).load())
        SplitScreenSettings.setPillEnabled(context, true)
        stop()
        SplitScreenSettings.setPillEnabled(context, false)

        assertEquals(listOf(false, true), heard)
    }

    private class FakeContext(val files: HashMap<String, Prefs> = HashMap()) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            files.getOrPut(name) { Prefs() }
    }

    /** One file of one process: values, an editor that writes them, and change listeners. */
    private class Prefs : SharedPreferences {
        val values = HashMap<String, Any>()
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any>()
            private val removes = LinkedHashSet<String>()

            private fun put(key: String, value: Any?) = apply {
                if (value == null) removes += key else puts[key] = value
            }

            override fun putString(key: String, value: String?) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = throw UnsupportedOperationException("the split never clears its file")

            override fun commit(): Boolean {
                val changed = removes.filter { it in values } + puts.keys
                removes.forEach { values.remove(it) }
                values.putAll(puts)
                changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@Prefs, key) } }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
