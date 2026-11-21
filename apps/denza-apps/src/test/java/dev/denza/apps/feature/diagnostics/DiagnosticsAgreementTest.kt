package dev.denza.apps.feature.diagnostics

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window in front of «Диагностика»: the stored agreement, the door every way in asks, and the
 * box that lets «ОК» answer. The file and key are spelled out: a renamed one would ask every driver
 * again after an update.
 */
class DiagnosticsAgreementTest {

    @Test
    fun aFreshInstallHasNotAgreed() {
        val context = FakeContext()

        assertFalse(DiagnosticsAgreement.isAgreed(context))
        assertTrue("a read writes nothing", context.files.values.all { it.values.isEmpty() })
    }

    @Test
    fun agreeingIsStoredUnderItsOwnFileAndKey() {
        val context = FakeContext()

        DiagnosticsAgreement.agree(context)

        assertTrue(DiagnosticsAgreement.isAgreed(context))
        assertEquals(mapOf("agreed" to true), context.files.getValue("denza_diagnostics").values)
    }

    /** A restarted process - or an updated APK - is a new context over the same file. */
    @Test
    fun theAgreementOutlivesTheProcess() {
        val files = HashMap<String, Prefs>()
        DiagnosticsAgreement.agree(FakeContext(files))

        assertTrue(DiagnosticsAgreement.isAgreed(FakeContext(files)))
    }

    @Test
    fun onlyAnAgreementOpensTheSection() {
        assertEquals(DiagnosticsDoor.AGREEMENT, DiagnosticsDoor.of(agreed = false))
        assertEquals(DiagnosticsDoor.SECTION, DiagnosticsDoor.of(agreed = true))
    }

    @Test
    fun theWindowOpensUntickedWithOkInactive() {
        val box = AgreementBox()

        assertFalse(box.ticked)
        assertFalse(box.okEnabled)
        assertFalse("a press on the inactive button agrees to nothing", box.confirm())
    }

    @Test
    fun aTickLetsOkAgreeAndUntickingTakesItBack() {
        val ticked = AgreementBox().toggled()
        assertTrue(ticked.okEnabled)
        assertTrue(ticked.confirm())

        val unticked = ticked.toggled()
        assertFalse(unticked.okEnabled)
        assertFalse(unticked.confirm())
    }

    private class FakeContext(val files: HashMap<String, Prefs> = HashMap()) : ContextWrapper(null) {
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
