package dev.denza.apps.feature.alice

/** Package identity is prefetched; visible area is checked on the current windows list. */
internal data class NavigatorWindowCandidate(
    val id: Int,
    val displayId: Int = 0,
    val type: Int = 1,
    val navigator: Boolean,
    val visibleArea: Boolean = true,
    val focused: Boolean = false,
)

internal object NavigatorWindowPolicy {
    fun select(windows: List<NavigatorWindowCandidate>): Int? = windows
        .filter { it.displayId == 0 && it.type == 1 && it.navigator && it.visibleArea }
        .singleOrNull()?.id
}
