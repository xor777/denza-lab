package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

object SplitScreenSettings {
    private const val PREFS = "denza_split_screen"
    private const val POLICY_ENABLED_V2 = "policy_enabled_v2"
    private const val FORCE_RESIZABLE_ORIGINAL = "force_resizable_original"
    private const val ROUTING_PRESENT = "routing_present_v1"
    private const val ROUTING_ANCHOR = "routing_anchor"
    private const val ROUTING_VACANCY_ROOT = "routing_vacancy_root"
    private const val ROUTING_VACANCY_BASELINE = "routing_vacancy_baseline"
    private const val ROUTING_VACANCY_RESTORE = "routing_vacancy_restore"
    private const val ROUTING_TARGET_FIRST = "routing_target_first"
    private const val ROUTING_TARGET_SECOND = "routing_target_second"
    private const val ROUTING_STABLE_FIRST = "routing_stable_first"
    private const val ROUTING_STABLE_SECOND = "routing_stable_second"
    private const val ROUTING_STABLE_FOCUS_ROOT = "routing_stable_focus_root"
    private const val LAST_PRIMARY_PACKAGE = "picker_last_primary_package_v1"
    private const val LAST_SECONDARY_PACKAGE = "picker_last_secondary_package_v1"
    private const val PICKER_GATE_OWNED = "picker_gate_owned_v1"
    private const val PICKER_STATE_PRESENT = "picker_state_present_v1"
    private const val PICKER_STATE_ARMED = "picker_state_armed_v1"
    private const val PICKER_STATE_PHASE = "picker_state_phase_v1"

    fun isEnabled(context: Context): Boolean =
        SplitScreenFlag.ENABLED &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(POLICY_ENABLED_V2, false)

    // Keep the persistent write boundary explicit for split-screen recovery.
    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(POLICY_ENABLED_V2, enabled)
                .commit(),
        ) { "Failed to persist split-screen state" }
    }

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

    internal fun routingStateStore(context: Context): SplitRoutingStateStore =
        PreferencesRoutingStateStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    internal fun lastPairStore(context: Context): SplitLastPairStore =
        PreferencesLastPairStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    internal fun gateLeaseStore(context: Context): SplitGateLeaseStore =
        PreferencesGateLeaseStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    internal fun pickerAutomatonStore(context: Context): SplitPickerAutomatonStore =
        PreferencesPickerAutomatonStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )

    private class PreferencesPickerAutomatonStore(
        private val preferences: SharedPreferences,
    ) : SplitPickerAutomatonStore {
        override fun load(): SplitPickerAutomatonState {
            if (!preferences.getBoolean(PICKER_STATE_PRESENT, false)) {
                return SplitPickerAutomatonState()
            }
            val phase = preferences.getString(PICKER_STATE_PHASE, null)
                ?.let { runCatching { SplitPickerPhase.valueOf(it) }.getOrNull() }
                ?: SplitPickerPhase.IDLE
            val slots = SplitPane.entries.associateWith(::readSlot)
            return SplitPickerAutomatonState(
                armed = preferences.getBoolean(PICKER_STATE_ARMED, false),
                phase = phase,
                slots = slots,
            )
        }

        @SuppressLint("UseKtx")
        override fun save(state: SplitPickerAutomatonState): Boolean {
            val editor = preferences.edit()
                .putBoolean(PICKER_STATE_PRESENT, true)
                .putBoolean(PICKER_STATE_ARMED, state.armed)
                .putString(PICKER_STATE_PHASE, state.phase.name)
            SplitPane.entries.forEach { pane -> writeSlot(editor, pane, state.slot(pane)) }
            return editor.commit()
        }

        @SuppressLint("UseKtx")
        override fun clear(): Boolean {
            val editor = preferences.edit()
                .remove(PICKER_STATE_PRESENT)
                .remove(PICKER_STATE_ARMED)
                .remove(PICKER_STATE_PHASE)
            SplitPane.entries.forEach { pane ->
                SLOT_SUFFIXES.forEach { suffix -> editor.remove("${pane.keyPrefix}_$suffix") }
            }
            return editor.commit()
        }

        private fun readSlot(pane: SplitPane): SplitPickerSlotState {
            val prefix = pane.keyPrefix
            val kind = preferences.getString("${prefix}_kind", null)
                ?.let { runCatching { SplitPickerSlotKind.valueOf(it) }.getOrNull() }
                ?: SplitPickerSlotKind.CLOSED
            return SplitPickerSlotState(
                kind = kind,
                hostTaskId = preferences.getInt("${prefix}_host", -1).takeIf { it > 0 },
                appTaskId = preferences.getInt("${prefix}_app", -1).takeIf { it > 0 },
                packageName = preferences.getString("${prefix}_package", null)
                    ?.takeIf(String::isNotBlank),
                userClosedWhileProjected = preferences.getBoolean("${prefix}_projected_closed", false),
                attachAttempts = preferences.getInt("${prefix}_attach_attempts", 0)
                    .coerceIn(0, 3),
            )
        }

        private fun writeSlot(
            editor: SharedPreferences.Editor,
            pane: SplitPane,
            slot: SplitPickerSlotState,
        ) {
            val prefix = pane.keyPrefix
            SLOT_SUFFIXES.forEach { suffix -> editor.remove("${prefix}_$suffix") }
            editor.putString("${prefix}_kind", slot.kind.name)
            slot.hostTaskId?.let { editor.putInt("${prefix}_host", it) }
            slot.appTaskId?.let { editor.putInt("${prefix}_app", it) }
            slot.packageName?.let { editor.putString("${prefix}_package", it) }
            if (slot.userClosedWhileProjected) {
                editor.putBoolean("${prefix}_projected_closed", true)
            }
            if (slot.attachAttempts > 0) {
                editor.putInt("${prefix}_attach_attempts", slot.attachAttempts)
            }
        }

        private val SplitPane.keyPrefix: String
            get() = "picker_state_${name.lowercase()}_v1"

        private companion object {
            val SLOT_SUFFIXES = listOf(
                "kind",
                "host",
                "app",
                "package",
                "projected_closed",
                "attach_attempts",
            )
        }
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

    private class PreferencesLastPairStore(
        private val preferences: SharedPreferences,
    ) : SplitLastPairStore {
        override fun load(pane: SplitPane): String? = preferences
            .getString(pane.preferenceKey, null)
            ?.takeIf(String::isNotBlank)

        @SuppressLint("UseKtx")
        override fun saveExclusive(pane: SplitPane, packageName: String): Boolean {
            val updated = SplitPickerSelectionPolicy.updatedPair(
                primaryPackage = load(SplitPane.PRIMARY),
                secondaryPackage = load(SplitPane.SECONDARY),
                selectedPane = pane,
                selectedPackage = packageName,
            )
            val editor = preferences.edit()
                .remove(LAST_PRIMARY_PACKAGE)
                .remove(LAST_SECONDARY_PACKAGE)
            updated.forEach { (updatedPane, updatedPackage) ->
                editor.putString(updatedPane.preferenceKey, updatedPackage)
            }
            return editor.commit()
        }

        private val SplitPane.preferenceKey: String
            get() = when (this) {
                SplitPane.PRIMARY -> LAST_PRIMARY_PACKAGE
                SplitPane.SECONDARY -> LAST_SECONDARY_PACKAGE
            }
    }

    private class PreferencesRoutingStateStore(
        private val preferences: SharedPreferences,
    ) : SplitRoutingStateStore {
        override fun load(): SplitRoutingMemory {
            if (!preferences.getBoolean(ROUTING_PRESENT, false)) {
                return SplitRoutingMemory()
            }
            val anchor = readTask(ROUTING_ANCHOR)
            val vacancyRoot = preferences.getInt(ROUTING_VACANCY_ROOT, -1)
            val vacancy = vacancyRoot.takeIf { it > 0 }?.let { rootId ->
                SplitVacancy(
                    rootId = rootId,
                    baselineTaskIds = preferences
                        .getString(ROUTING_VACANCY_BASELINE, "")
                        .orEmpty()
                        .split(',')
                        .mapNotNull(String::toIntOrNull)
                        .toSet(),
                    restorePlaceholderAfterRecovery = preferences.getBoolean(
                        ROUTING_VACANCY_RESTORE,
                        false,
                    ),
                )
            }
            val targetFirst = readTask(ROUTING_TARGET_FIRST)
            val targetSecond = readTask(ROUTING_TARGET_SECOND)
            val target = if (targetFirst != null && targetSecond != null) {
                SplitPairTarget(targetFirst, targetSecond)
            } else {
                null
            }
            val stableFirst = readTask(ROUTING_STABLE_FIRST)
            val stableSecond = readTask(ROUTING_STABLE_SECOND)
            val stablePair = if (stableFirst != null && stableSecond != null) {
                SplitStablePair(
                    first = stableFirst,
                    second = stableSecond,
                    lastFocusedRootId = preferences
                        .getInt(ROUTING_STABLE_FOCUS_ROOT, -1)
                        .takeIf { it > 0 },
                )
            } else {
                null
            }
            return SplitRoutingMemory(
                anchor = anchor,
                vacancy = vacancy,
                target = target,
                stablePair = stablePair,
            )
        }

        @SuppressLint("UseKtx")
        override fun save(memory: SplitRoutingMemory) {
            val editor = preferences.edit()
            clearKeys(editor)
            editor.putBoolean(ROUTING_PRESENT, true)
            writeTask(editor, ROUTING_ANCHOR, memory.anchor)
            memory.vacancy?.let { vacancy ->
                editor.putInt(ROUTING_VACANCY_ROOT, vacancy.rootId)
                editor.putString(
                    ROUTING_VACANCY_BASELINE,
                    vacancy.baselineTaskIds.sorted().joinToString(","),
                )
                editor.putBoolean(
                    ROUTING_VACANCY_RESTORE,
                    vacancy.restorePlaceholderAfterRecovery,
                )
            }
            writeTask(editor, ROUTING_TARGET_FIRST, memory.target?.first)
            writeTask(editor, ROUTING_TARGET_SECOND, memory.target?.second)
            writeTask(editor, ROUTING_STABLE_FIRST, memory.stablePair?.first)
            writeTask(editor, ROUTING_STABLE_SECOND, memory.stablePair?.second)
            memory.stablePair?.lastFocusedRootId?.let { rootId ->
                editor.putInt(ROUTING_STABLE_FOCUS_ROOT, rootId)
            }
            check(editor.commit()) { "Failed to persist split routing state" }
        }

        @SuppressLint("UseKtx")
        override fun clear() {
            val editor = preferences.edit()
            clearKeys(editor)
            check(editor.commit()) { "Failed to clear split routing state" }
        }

        private fun readTask(prefix: String): SplitExpectedTask? {
            val packageName = preferences.getString("${prefix}_package", null)
                ?: return null
            val rootId = preferences.getInt("${prefix}_root", -1)
            if (rootId <= 0) return null
            return SplitExpectedTask(
                id = preferences.getInt("${prefix}_id", -1).takeIf { it > 0 },
                packageName = packageName,
                activityName = preferences.getString("${prefix}_activity", null),
                preferredRootId = rootId,
            )
        }

        private fun writeTask(
            editor: SharedPreferences.Editor,
            prefix: String,
            task: SplitExpectedTask?,
        ) {
            if (task == null) return
            task.id?.let { editor.putInt("${prefix}_id", it) }
            editor.putString("${prefix}_package", task.packageName)
            task.activityName?.let { editor.putString("${prefix}_activity", it) }
            editor.putInt("${prefix}_root", task.preferredRootId)
        }

        private fun clearKeys(editor: SharedPreferences.Editor) {
            editor.remove(ROUTING_PRESENT)
            editor.remove(ROUTING_VACANCY_ROOT)
            editor.remove(ROUTING_VACANCY_BASELINE)
            editor.remove(ROUTING_VACANCY_RESTORE)
            editor.remove(ROUTING_STABLE_FOCUS_ROOT)
            listOf(
                ROUTING_ANCHOR,
                ROUTING_TARGET_FIRST,
                ROUTING_TARGET_SECOND,
                ROUTING_STABLE_FIRST,
                ROUTING_STABLE_SECOND,
            )
                .forEach { prefix ->
                    editor.remove("${prefix}_id")
                    editor.remove("${prefix}_package")
                    editor.remove("${prefix}_activity")
                    editor.remove("${prefix}_root")
                }
        }
    }
}
