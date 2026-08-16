package dev.denza.apps.feature.split

enum class SplitPane(val areaId: Int) {
    PRIMARY(areaId = 1),
    SECONDARY(areaId = 2),
    ;

    fun other(): SplitPane = when (this) {
        PRIMARY -> SECONDARY
        SECONDARY -> PRIMARY
    }
}

internal data class SplitLaunchTarget(
    val packageName: String,
    val componentName: String,
)

internal data class SplitPickerPlacement(
    val pane: SplitPane,
    val hostTaskId: Int,
    val appTaskId: Int,
    val packageName: String,
)

internal data class SplitPickerPaneObservation(
    val pane: SplitPane,
    val hostTaskId: Int?,
    val nativeHostVisible: Boolean,
    val pickerVisible: Boolean,
    val observedTaskIds: Set<Int>,
)

internal interface SplitLastPairStore {
    fun load(pane: SplitPane): String?
    fun saveExclusive(pane: SplitPane, packageName: String): Boolean
}

/** Persistent ownership for the firmware-global split enable gate. */
internal interface SplitGateLeaseStore {
    fun isOwned(): Boolean
    fun setOwned(owned: Boolean): Boolean
}

internal interface SplitPickerAutomatonStore {
    fun load(): SplitPickerAutomatonState
    fun save(state: SplitPickerAutomatonState): Boolean
    fun clear(): Boolean
}

internal object SplitPickerSelectionPolicy {
    fun updatedPair(
        primaryPackage: String?,
        secondaryPackage: String?,
        selectedPane: SplitPane,
        selectedPackage: String,
    ): Map<SplitPane, String> {
        require(selectedPackage.isNotBlank())
        return buildMap {
            primaryPackage?.takeIf(String::isNotBlank)?.let { put(SplitPane.PRIMARY, it) }
            secondaryPackage?.takeIf(String::isNotBlank)?.let { put(SplitPane.SECONDARY, it) }
            put(selectedPane, selectedPackage)
            if (get(selectedPane.other()) == selectedPackage) remove(selectedPane.other())
        }
    }

    fun restorablePair(
        primaryPackage: String?,
        secondaryPackage: String?,
        installedPackages: Set<String>,
    ): Map<SplitPane, String> {
        val primary = primaryPackage?.takeIf(installedPackages::contains)
        val secondary = secondaryPackage
            ?.takeIf(installedPackages::contains)
            ?.takeUnless { it == primary }
        return buildMap {
            primary?.let { put(SplitPane.PRIMARY, it) }
            secondary?.let { put(SplitPane.SECONDARY, it) }
        }
    }
}
