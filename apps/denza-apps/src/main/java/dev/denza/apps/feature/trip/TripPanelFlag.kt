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

    /**
     * The panel's original instruments: the hanging mirror toy, the compass tape
     * and the journey thread.
     *
     * Switched off in favour of the spectrum analyser, which now owns that space.
     * Nothing was removed — [MirrorToyRenderer], [MirrorToyPhysics],
     * [JourneyThreadDrawer], [CourseTracker] and their tests are all still here
     * and still built, and the engine keeps feeding them. Flip this back to
     * `true` to get them on screen again; the two cannot share the space, so the
     * analyser yields when they are on.
     */
    const val LEGACY_INSTRUMENTS = false
}
