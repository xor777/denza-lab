package dev.denza.apps

internal object SimulcastAccessibilityAccess {
    const val COMPONENT = "dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService"

    private val aliases = setOf(
        COMPONENT,
        "dev.denza.apps/.SimulcastAccessibilityService",
    )

    // Retired 2026-07-25 fast-switch guard. Kept in the strip list so an
    // installation that still carries the component in its accessibility
    // setting does not keep a dangling entry after an update.
    private val retiredAliases = setOf(
        "dev.denza.apps/dev.denza.apps.feature.mirrors.MirrorGuardAccessibilityService",
        "dev.denza.apps/.feature.mirrors.MirrorGuardAccessibilityService",
    )

    private val ownedAliases = aliases + retiredAliases

    fun isEnabled(setting: String?): Boolean = entries(setting).any(aliases::contains)

    fun isEnabledEntries(entries: List<String>): Boolean = entries.any(aliases::contains)

    fun withoutService(setting: String?): String = withoutServiceEntries(entries(setting))
        .joinToString(":")

    fun withoutServiceEntries(entries: List<String>): List<String> = entries
        .filterNot(ownedAliases::contains)
        .distinct()

    fun withService(setting: String?): String = withServiceEntries(entries(setting))
        .joinToString(":")

    fun withServiceEntries(entries: List<String>): List<String> = buildList {
        addAll(withoutServiceEntries(entries))
        add(COMPONENT)
    }

    private fun entries(setting: String?): List<String> = setting
        ?.trim()
        ?.takeUnless { it.isEmpty() || it == "null" }
        ?.split(':')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()
}
