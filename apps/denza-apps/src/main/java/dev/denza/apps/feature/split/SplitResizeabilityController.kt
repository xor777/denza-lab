package dev.denza.apps.feature.split

/** Owns Android's global resizeability override while Denza split routing is enabled. */
internal class SplitResizeabilityController(
    private val shell: (String) -> String,
    private val leaseStore: SplitResizeabilityLeaseStore,
) {
    fun enable() {
        val current = read()
        if (leaseStore.loadOriginal() == null) {
            check(leaseStore.saveOriginal(current)) {
                "Не удалось сохранить исходную настройку resizeability"
            }
        }
        if (current != SplitGlobalSettingValue.ENABLED) {
            write(SplitGlobalSettingValue.ENABLED)
        }
        check(read() == SplitGlobalSettingValue.ENABLED) {
            "Прошивка не включила системную resizeability"
        }
    }

    fun restore() {
        val original = leaseStore.loadOriginal() ?: return
        val current = read()
        // Restore only the value we still own. An external change while split was active wins.
        if (current == SplitGlobalSettingValue.ENABLED && original != current) {
            write(original)
            check(read() == original) {
                "Не удалось восстановить исходную настройку resizeability"
            }
        }
        check(leaseStore.clearOriginal()) {
            "Не удалось завершить восстановление resizeability"
        }
    }

    private fun read(): SplitGlobalSettingValue {
        val output = shell("settings get global $SETTING").trim()
        return when (output) {
            "null" -> SplitGlobalSettingValue.MISSING
            "0" -> SplitGlobalSettingValue.DISABLED
            "1" -> SplitGlobalSettingValue.ENABLED
            else -> error("Неожиданное значение $SETTING: $output")
        }
    }

    private fun write(value: SplitGlobalSettingValue) {
        val command = when (value) {
            SplitGlobalSettingValue.MISSING -> "settings delete global $SETTING"
            SplitGlobalSettingValue.DISABLED -> "settings put global $SETTING 0"
            SplitGlobalSettingValue.ENABLED -> "settings put global $SETTING 1"
        }
        val output = shell(command)
        check(
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "settings command failed" } }
    }

    private companion object {
        const val SETTING = "force_resizable_activities"
    }
}

internal enum class SplitGlobalSettingValue { MISSING, DISABLED, ENABLED }

internal interface SplitResizeabilityLeaseStore {
    fun loadOriginal(): SplitGlobalSettingValue?
    fun saveOriginal(value: SplitGlobalSettingValue): Boolean
    fun clearOriginal(): Boolean
}
