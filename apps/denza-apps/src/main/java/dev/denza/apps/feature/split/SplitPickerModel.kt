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
    val launchMode: Int = 0,
)

internal const val SPLIT_HOST_PACKAGE = "dev.denza.split"
internal const val SPLIT_PICKER_ACTIVITY = "dev.denza.split.SplitPickerActivity"
internal const val SPLIT_APP_HOST_ACTIVITY = "dev.denza.split.SplitAppHostActivity"
internal const val SPLIT_APP_HOST_COMPONENT =
    "$SPLIT_HOST_PACKAGE/$SPLIT_APP_HOST_ACTIVITY"
internal const val SPLIT_HOST_TARGET_PACKAGE_EXTRA =
    "dev.denza.split.extra.TARGET_PACKAGE"
internal const val SPLIT_HOST_TARGET_ACTIVITY_EXTRA =
    "dev.denza.split.extra.TARGET_ACTIVITY"

internal data class SplitPickerPlacement(
    val pane: SplitPane,
    val hostTaskId: Int,
    val appTaskId: Int,
    val packageName: String,
)

/** Persisted task identity used only to verify an owned scene hidden by a fullscreen window. */
internal data class SplitPickerExpectedApp(
    val taskId: Int,
    val packageName: String,
)

/**
 * Exact IVI destination selected immediately before a projected navigation task returns.
 *
 * A null [pane] means the navigator did not originate from, and cannot safely join, a live
 * product split root. [fullscreen] means the task is first returned to [rootTaskId] and the
 * native firmware transition then expands that pane.
 */
internal data class SplitNavigationReturnPlan(
    val pane: SplitPane?,
    val rootTaskId: Int,
    val hostTaskId: Int?,
    val fullscreen: Boolean,
    val displacedTasks: List<SplitDisplacedTask> = emptyList(),
)

internal data class SplitDisplacedTask(
    val taskId: Int,
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
    fun replace(packages: Map<SplitPane, String>): Boolean
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
