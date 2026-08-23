package dev.denza.apps.feature.split

/** Owns Android's global resizeability override while Denza split routing is enabled. */
internal class SplitResizeabilityController(
    private val shell: (String) -> String,
    private val leaseStore: SplitResizeabilityLeaseStore,
) {
    /**
     * One round trip: what the setting was, the write, and the proof that it took.
     *
     * The open path may not pay three. Read, write and verify used to be three separate shell
     * commands over the persistent ADB link, measured at 752 ms of an open on this car - for a
     * value that is one character long. The firmware's own compound-statement support is what
     * [SplitSmartMultiController] already reads its four keys with (1.13.3).
     */
    fun enable() {
        val output = shell("$READ_COMMAND; ${writeCommand(SplitGlobalSettingValue.ENABLED)}; $READ_COMMAND")
        validate(output)
        val answers = output.trim().lines().map(String::trim).filter(String::isNotEmpty)
        check(answers.size == 2) { "Прошивка не ответила про resizeability: ${output.trim()}" }
        val before = parse(answers[0])
        // The displaced value is journalled after the write rather than before it, exactly like the
        // firmware gate `ensureGateOpen` takes: one command cannot report and change in two steps.
        if (leaseStore.loadOriginal() == null) {
            check(leaseStore.saveOriginal(before)) {
                "Не удалось сохранить исходную настройку resizeability"
            }
        }
        check(parse(answers[1]) == SplitGlobalSettingValue.ENABLED) {
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

    private fun read(): SplitGlobalSettingValue = parse(shell(READ_COMMAND).trim())

    private fun parse(answer: String): SplitGlobalSettingValue = when (answer) {
        "null" -> SplitGlobalSettingValue.MISSING
        "0" -> SplitGlobalSettingValue.DISABLED
        "1" -> SplitGlobalSettingValue.ENABLED
        else -> error("Неожиданное значение $RESIZEABILITY_SETTING: $answer")
    }

    private fun write(value: SplitGlobalSettingValue) {
        validate(shell(writeCommand(value)))
    }

    private fun writeCommand(value: SplitGlobalSettingValue): String = when (value) {
        SplitGlobalSettingValue.MISSING -> "settings delete global $RESIZEABILITY_SETTING"
        SplitGlobalSettingValue.DISABLED -> "settings put global $RESIZEABILITY_SETTING 0"
        SplitGlobalSettingValue.ENABLED -> "settings put global $RESIZEABILITY_SETTING 1"
    }

    private fun validate(output: String) {
        check(
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "settings command failed" } }
    }

    private companion object {
        const val READ_COMMAND = "settings get global $RESIZEABILITY_SETTING"
    }
}

/** The one firmware-global setting the product borrows; every write of it is logged (1.12). */
internal const val RESIZEABILITY_SETTING = "force_resizable_activities"

internal enum class SplitGlobalSettingValue { MISSING, DISABLED, ENABLED }

internal interface SplitResizeabilityLeaseStore {
    fun loadOriginal(): SplitGlobalSettingValue?
    fun saveOriginal(value: SplitGlobalSettingValue): Boolean
    fun clearOriginal(): Boolean
}
