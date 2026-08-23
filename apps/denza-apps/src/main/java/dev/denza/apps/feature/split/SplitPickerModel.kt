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

internal const val SPLIT_HOST_PACKAGE = "dev.denza.apps"
internal const val SPLIT_PICKER_ACTIVITY = "dev.denza.apps.feature.split.SplitPickerActivity"
// The host Activity itself is gone. Its exact component is still matched so that a stale host
// task left by an older installed version is recognised and cleaned up by identity.
internal const val SPLIT_APP_HOST_ACTIVITY = "dev.denza.apps.feature.split.SplitAppHostActivity"

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

/**
 * What one pane looked like before a divider move or a collapse.
 *
 * These ids are always ephemeral: they come from the live scene the coordinator last verified and
 * every recipe that receives them re-checks each one against a fresh snapshot (invariant 4).
 */
internal data class SplitPickerObservedPane(
    val hostTaskId: Int,
    val appTaskId: Int? = null,
    val packageName: String? = null,
)

/** Persistent ownership for the firmware-global split enable gate. */
internal interface SplitGateLeaseStore {
    fun isOwned(): Boolean
    fun setOwned(owned: Boolean): Boolean
}
