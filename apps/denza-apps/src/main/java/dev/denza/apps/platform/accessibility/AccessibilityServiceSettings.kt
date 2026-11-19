package dev.denza.apps.platform.accessibility

import android.content.ContentResolver
import android.provider.Settings
import dev.denza.apps.feature.split.SplitNativePickerAccessibilityAccess
import dev.denza.apps.feature.split.SplitNativePickerAccessLeaseStore
import dev.denza.apps.platform.shell.ShellGrants

/**
 * The car's `enabled_accessibility_services` as a list, and `accessibility_enabled` switched on with
 * a write when asked: over the local shell ([AccessibilityServiceSettings]) or in this process with
 * the app's own `WRITE_SECURE_SETTINGS` ([SecureAccessibilityServiceList]).
 */
internal interface AccessibilityServiceList {
    fun read(): List<String>
    fun write(entries: List<String>, ensureAccessibilityEnabled: Boolean)

    companion object {
        /** The setting's text as entries: each once, in its order; empty for an unset or `null` one. */
        fun parse(setting: String?): List<String> = setting
            ?.trim()
            ?.takeUnless { it.isEmpty() || it == "null" }
            ?.split(':')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
    }
}

/**
 * What a repair leaves in the setting, whichever way it writes: every other application's entry
 * as it stood and in its order, then the shared service, then the split's own last - and that only
 * when the split is on ([DenzaAccessibilityRepairController] adds it after the shared one has bound).
 * Each entry once.
 */
internal object DenzaAccessibilityEntries {
    /** Everything that is not one of ours, retired aliases included. */
    fun foreign(entries: List<String>): List<String> =
        SplitNativePickerAccessibilityAccess.withoutService(
            SharedAccessibilityAccess.withoutServiceEntries(entries),
        )

    /** The repair's first listing: the shared service alone of ours. */
    fun sharedOnly(entries: List<String>): List<String> =
        SharedAccessibilityAccess.withServiceEntries(foreign(entries))

    /** The repair's last listing: the split's service after the shared one when [split] is on. */
    fun repaired(entries: List<String>, split: Boolean): List<String> =
        if (split) SplitNativePickerAccessibilityAccess.withService(sharedOnly(entries)) else sharedOnly(entries)
}

/** Single shell boundary for the shared enabled_accessibility_services setting. */
internal class AccessibilityServiceSettings(
    private val shell: (String) -> String,
) : AccessibilityServiceList {
    override fun read(): List<String> = AccessibilityServiceList.parse(
        shell("settings get secure enabled_accessibility_services"),
    )

    override fun write(entries: List<String>, ensureAccessibilityEnabled: Boolean) {
        val value = entries.distinct().joinToString(":")
        val command = buildString {
            append(ShellGrants.settingsPut("secure", "enabled_accessibility_services", value))
            if (ensureAccessibilityEnabled) {
                append("; ")
                append(ShellGrants.settingsPut("secure", "accessibility_enabled", "1"))
            }
        }
        val output = shell(command)
        check(
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "settings command failed" } }
    }
}

/**
 * The same setting written in this process, through `Settings.Secure` with the app's own
 * `WRITE_SECURE_SETTINGS`: for an owner with no shell to write it with - the ADB restore before it
 * raises the network dialog, with port 5555 closed. The values are the shell's, byte for byte.
 */
internal class SecureAccessibilityServiceList(
    private val resolver: ContentResolver,
) : AccessibilityServiceList {
    override fun read(): List<String> = AccessibilityServiceList.parse(
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    )

    override fun write(entries: List<String>, ensureAccessibilityEnabled: Boolean) {
        check(
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                entries.distinct().joinToString(":"),
            ),
        ) { "enabled_accessibility_services was not written" }
        if (ensureAccessibilityEnabled) {
            check(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")) {
                "accessibility_enabled was not written"
            }
        }
    }
}

/**
 * Owns recovery of both Denza accessibility services as one ordered transaction.
 *
 * Split feature enable/disable still owns only its dedicated component. Recovery is different:
 * Android must finish the app-wide observer lifecycle before the split observer is added last,
 * otherwise this firmware can bind both components without routing future window events.
 */
internal class DenzaAccessibilityRepairController(
    private val settings: AccessibilityServiceList,
    private val splitLeaseStore: SplitNativePickerAccessLeaseStore,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    constructor(
        shell: (String) -> String,
        splitLeaseStore: SplitNativePickerAccessLeaseStore,
        pause: (Long) -> Unit = Thread::sleep,
    ) : this(AccessibilityServiceSettings(shell), splitLeaseStore, pause)

    /**
     * Runs the transaction, unless nobody [stillWanted] it or it is no longer [needed] once the lock
     * is held - a repair that held the lock before this one may have brought the service back.
     * True when it ran.
     */
    fun repair(
        ensureSplit: Boolean,
        stillWanted: () -> Boolean = { true },
        needed: () -> Boolean = { true },
    ): Boolean = AccessibilitySettingsMutationLock.withLock(
        ensuresSplitAccess = ensureSplit,
    ) {
        if (!stillWanted() || !needed()) return@withLock false
        val original = settings.read()
        val originalSplitOwned = splitLeaseStore.isOwned()
        val originalSplitVersion = splitLeaseStore.configurationVersion()
        try {
            settings.write(DenzaAccessibilityEntries.foreign(original), ensureAccessibilityEnabled = false)
            pause(ALL_SERVICES_UNBIND_MS)
            check(stillWanted()) { "Accessibility preparation cancelled" }

            settings.write(DenzaAccessibilityEntries.sharedOnly(settings.read()), ensureAccessibilityEnabled = true)

            if (ensureSplit) {
                pause(APP_WIDE_SERVICE_BIND_MS)
                check(stillWanted()) { "Accessibility preparation cancelled" }
                settings.write(
                    DenzaAccessibilityEntries.repaired(settings.read(), split = true),
                    ensureAccessibilityEnabled = true,
                )
                pause(SPLIT_SERVICE_BIND_MS)
            }

            val repaired = settings.read()
            check(SharedAccessibilityAccess.isEnabledEntries(repaired)) {
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
            true
        } catch (error: Throwable) {
            runCatching {
                settings.write(original, ensureAccessibilityEnabled = true)
            }
            runCatching { splitLeaseStore.setOwned(originalSplitOwned) }
            runCatching { splitLeaseStore.setConfigurationVersion(originalSplitVersion) }
            throw error
        }
    }

    private companion object {
        const val ALL_SERVICES_UNBIND_MS = 1_000L
        const val APP_WIDE_SERVICE_BIND_MS = 2_000L
        const val SPLIT_SERVICE_BIND_MS = 1_000L
    }
}
