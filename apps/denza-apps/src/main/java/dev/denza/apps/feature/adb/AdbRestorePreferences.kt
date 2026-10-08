package dev.denza.apps.feature.adb

import android.content.Context
import android.content.SharedPreferences

/** No key material; all values are private to this APK and survive a process/reboot. */
class AdbRestorePreferences(context: Context) : AdbRestoreStore {
    private val prefs = context.applicationContext.getSharedPreferences("adb_restore", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("adb_restore_enabled", true)
        set(value) = save { putBoolean("adb_restore_enabled", value) }
    override var trustedBefore: Boolean
        get() = prefs.getBoolean("trusted_before", false)
        set(value) = save { putBoolean("trusted_before", value) }
    override var lastWriteNetwork: String?
        get() = prefs.getString("last_write_network", null)
        set(value) = save { putString("last_write_network", value) }
    override var lastWriteAtMs: Long
        get() = prefs.getLong("last_write_at", 0)
        set(value) = save { putLong("last_write_at", value) }
    override var lastOutcome: String?
        get() = prefs.getString("last_outcome", null)
        set(value) = save { putString("last_outcome", value) }
    override var lastOutcomeAtMs: Long
        get() = prefs.getLong("last_outcome_at", 0)
        set(value) = save { putLong("last_outcome_at", value) }
    override var autoAllow: String?
        get() = prefs.getString("auto_allow", null)
        set(value) = save { putString("auto_allow", value) }

    override fun recordWrite(network: String, atMs: Long) = save {
        putString("last_write_network", network); putLong("last_write_at", atMs)
    }
    override fun recordOutcome(outcome: String, atMs: Long) = save {
        putString("last_outcome", outcome); putLong("last_outcome_at", atMs)
    }

    private fun save(write: SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(write).commit()) { "ADB restore preference was not saved" }
    }
}
