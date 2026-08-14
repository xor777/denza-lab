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
            return SplitRoutingMemory(anchor = anchor, vacancy = vacancy, target = target)
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
            listOf(ROUTING_ANCHOR, ROUTING_TARGET_FIRST, ROUTING_TARGET_SECOND)
                .forEach { prefix ->
                    editor.remove("${prefix}_id")
                    editor.remove("${prefix}_package")
                    editor.remove("${prefix}_activity")
                    editor.remove("${prefix}_root")
                }
        }
    }
}
