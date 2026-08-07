package dev.denza.apps.feature.trip

/**
 * Compile-time on/off switch for the whole trip panel.
 *
 * This is intentionally NOT a runtime preference. Flip [ENABLED] to `false` and
 * rebuild to cleanly remove the panel and everything it starts: the renderer,
 * the IMU/GNSS sensor hub, the location self-heal, and any permission requests.
 * When `false`, the free space below the feature cards is simply empty and
 * nothing is scheduled. There is deliberately no in-app toggle, and the panel
 * persists nothing at all — no trip data, no preferences.
 */
object TripPanelFlag {
    const val ENABLED = true
}
