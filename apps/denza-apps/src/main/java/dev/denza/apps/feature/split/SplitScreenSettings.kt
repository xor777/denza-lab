package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

object SplitScreenSettings {
    private const val PREFS = "denza_split_screen"
    private const val FORCE_RESIZABLE_ORIGINAL = "force_resizable_original"
    private const val PICKER_GATE_OWNED = "picker_gate_owned_v1"
    private const val PICKER_ACCESS_OWNED = "picker_access_owned_v1"
    private const val PICKER_ACCESS_CONFIGURATION_VERSION =
        "picker_access_configuration_version_v1"
    private const val PICKER_NOTICE = "picker_notice_v1"

    /**
     * The toggle, read from the one durable snapshot that owns it (contract section 6).
     *
     * There is deliberately no writer next to this reader any more: the toggle changes only as the
     * durable projection of a `DISABLE`-priority operation, so the value here and the slots it
     * travels with can never disagree - which is exactly how the two old stores diverged (8.1).
     */
    fun isEnabled(context: Context): Boolean = stateStore(context).load().enabled

    internal fun resizeabilityLeaseStore(context: Context): SplitResizeabilityLeaseStore =
        object : SplitResizeabilityLeaseStore {
            private val preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            override fun loadOriginal(): SplitGlobalSettingValue? =
                preferences.getString(FORCE_RESIZABLE_ORIGINAL, null)
                    ?.let(SplitGlobalSettingValue::valueOf)

            override fun saveOriginal(value: SplitGlobalSettingValue): Boolean =
                preferences.edit()
                    .putString(FORCE_RESIZABLE_ORIGINAL, value.name)
                    .commit()

            override fun clearOriginal(): Boolean =
                preferences.edit()
                    .remove(FORCE_RESIZABLE_ORIGINAL)
                    .commit()
        }

    /**
     * The single durable store of the product (contract section 6).
     *
     * It shares the preferences file with the keys it replaces, because its first load is also
     * their one-shot migration: reading and deleting them has to happen in one place.
     */
    internal fun stateStore(context: Context): SplitStateStore =
        PreferencesSplitStateStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    internal fun gateLeaseStore(context: Context): SplitGateLeaseStore =
        PreferencesGateLeaseStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    internal fun loadPickerNotice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PICKER_NOTICE, null)
            .orEmpty()

    @SuppressLint("UseKtx")
    internal fun savePickerNotice(context: Context, message: String) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (message.isBlank()) editor.remove(PICKER_NOTICE)
        else editor.putString(PICKER_NOTICE, message)
        check(editor.commit()) { "Failed to persist split picker notice" }
    }

    internal fun nativePickerAccessLeaseStore(
        context: Context,
    ): SplitNativePickerAccessLeaseStore = object : SplitNativePickerAccessLeaseStore {
        private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun isOwned(): Boolean = preferences.getBoolean(PICKER_ACCESS_OWNED, false)

        @SuppressLint("UseKtx")
        override fun setOwned(owned: Boolean): Boolean = preferences.edit().let { editor ->
            if (owned) editor.putBoolean(PICKER_ACCESS_OWNED, true)
            else editor.remove(PICKER_ACCESS_OWNED)
            editor.commit()
        }

        override fun configurationVersion(): Int =
            preferences.getInt(PICKER_ACCESS_CONFIGURATION_VERSION, 0)

        @SuppressLint("UseKtx")
        override fun setConfigurationVersion(version: Int): Boolean = preferences.edit()
            .putInt(PICKER_ACCESS_CONFIGURATION_VERSION, version)
            .commit()
    }

    private class PreferencesGateLeaseStore(
        private val preferences: SharedPreferences,
    ) : SplitGateLeaseStore {
        override fun isOwned(): Boolean = preferences.getBoolean(PICKER_GATE_OWNED, false)

        @SuppressLint("UseKtx")
        override fun setOwned(owned: Boolean): Boolean = preferences.edit().let { editor ->
            if (owned) editor.putBoolean(PICKER_GATE_OWNED, true)
            else editor.remove(PICKER_GATE_OWNED)
            editor.commit()
        }
    }
}
