package dev.denza.apps.feature.split

/**
 * Compile-time on/off switch for the whole split-screen feature.
 *
 * Switched off because the firmware update removed the mechanism this feature
 * rode on, not because the code went wrong. The stock picker
 * (`com.android.launcher3/.SplitScreenListActivity`) still launches, but nothing
 * places the chosen app any more, and every user-space route to a second pane is
 * closed: an ordinary visible task is forced fullscreen, AE Window is gated by a
 * whitelist compiled into `services.jar`, and an app-owned display loses the app
 * as soon as it opens its own next screen. See docs/split-screen-findings.md.
 *
 * Nothing was deleted. [SplitScreenCoordinator], [SplitShellRouter] and their
 * task-routing logic are all still here and still built; with the flag off the
 * card is not shown and [SplitScreenSettings.isEnabled] reports `false`, so no
 * consumer starts anything. Flip this back to `true` if a firmware ever restores
 * the placement.
 */
object SplitScreenFlag {
    const val ENABLED = false
}
