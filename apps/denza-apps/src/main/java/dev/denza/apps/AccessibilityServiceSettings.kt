package dev.denza.apps

import dev.denza.apps.feature.split.SplitNativePickerAccessibilityAccess
import dev.denza.apps.feature.split.SplitNativePickerAccessLeaseStore

/** Single shell boundary for the shared enabled_accessibility_services setting. */
internal class AccessibilityServiceSettings(
    private val shell: (String) -> String,
) {
    fun read(): List<String> = shell(
        "settings get secure enabled_accessibility_services",
    ).trim()
        .takeUnless { it.isEmpty() || it == "null" }
        ?.split(':')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()

    fun write(entries: List<String>, ensureAccessibilityEnabled: Boolean) {
        val value = entries.distinct().joinToString(":")
        val command = buildString {
            append("settings put secure enabled_accessibility_services ${shellQuote(value)}")
            if (ensureAccessibilityEnabled) {
                append("; settings put secure accessibility_enabled 1")
            }
        }
        val output = shell(command)
        check(
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "settings command failed" } }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}

/**
 * Owns recovery of both Denza accessibility services as one ordered transaction.
 *
 * Split feature enable/disable still owns only its dedicated component. Recovery is different:
 * Android must finish the app-wide observer lifecycle before the split observer is added last,
 * otherwise this firmware can bind both components without routing future window events.
 */
internal class DenzaAccessibilityRepairController(
    shell: (String) -> String,
    private val splitLeaseStore: SplitNativePickerAccessLeaseStore,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    private val settings = AccessibilityServiceSettings(shell)

    fun repair(ensureSplit: Boolean) = AccessibilitySettingsMutationLock.withLock {
        val original = settings.read()
        val originalSplitOwned = splitLeaseStore.isOwned()
        val originalSplitVersion = splitLeaseStore.configurationVersion()
        try {
            settings.write(withoutDenzaServices(original), ensureAccessibilityEnabled = false)
            pause(ALL_SERVICES_UNBIND_MS)

            val simulcastOnly = SimulcastAccessibilityAccess.withServiceEntries(
                withoutDenzaServices(settings.read()),
            )
            settings.write(simulcastOnly, ensureAccessibilityEnabled = true)

            if (ensureSplit) {
                pause(APP_WIDE_SERVICE_BIND_MS)
                val both = SplitNativePickerAccessibilityAccess.withService(
                    SimulcastAccessibilityAccess.withServiceEntries(
                        withoutDenzaServices(settings.read()),
                    ),
                )
                settings.write(both, ensureAccessibilityEnabled = true)
                pause(SPLIT_SERVICE_BIND_MS)
            }

            val repaired = settings.read()
            check(SimulcastAccessibilityAccess.isEnabledEntries(repaired)) {
                "Система не включила общее наблюдение Denza Apps"
            }
            check(!ensureSplit || SplitNativePickerAccessibilityAccess.isEnabled(repaired)) {
                "Система не включила наблюдение Split Screen"
            }
            check(splitLeaseStore.setOwned(ensureSplit)) {
                "Не удалось сохранить владение наблюдением Split Screen"
            }
            if (ensureSplit) {
                check(
                    splitLeaseStore.setConfigurationVersion(
                        SplitNativePickerAccessibilityAccess.CONFIGURATION_VERSION,
                    ),
                ) { "Не удалось сохранить версию наблюдения Split Screen" }
            }
        } catch (error: Throwable) {
            runCatching {
                settings.write(original, ensureAccessibilityEnabled = true)
            }
            runCatching { splitLeaseStore.setOwned(originalSplitOwned) }
            runCatching { splitLeaseStore.setConfigurationVersion(originalSplitVersion) }
            throw error
        }
    }

    private fun withoutDenzaServices(entries: List<String>): List<String> =
        SplitNativePickerAccessibilityAccess.withoutService(
            SimulcastAccessibilityAccess.withoutServiceEntries(entries),
        )

    private companion object {
        const val ALL_SERVICES_UNBIND_MS = 1_000L
        const val APP_WIDE_SERVICE_BIND_MS = 2_000L
        const val SPLIT_SERVICE_BIND_MS = 1_000L
    }
}
