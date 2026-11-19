package dev.denza.apps.feature.trip

import dev.denza.apps.R
import dev.denza.apps.core.AppText

/**
 * The hint the strip prints where the altitude would be.
 *
 * Kept under its old name. It was the base class of the strip's renderers when they drew on
 * `PanelCanvas`, a virtual space scaled onto whatever box they were given; the Luminofor strip
 * draws in window dp on its own box and has no base class, and the dashboard's layout policy asks
 * for the full screen's box by its shape in `LuminoforSpec.Head` directly. Its clocks went when
 * durations stopped being clocks: every duration is `AppDuration`'s now.
 */
object BaseTripRenderer {

    /**
     * What the strip says where the altitude would be when it has no location access at all.
     *
     * In sentence case, like every caption on the Luminofor strip. `StripReadings` asks for it on
     * every drawn frame of the car page, which [AppText.s] answers with an array read.
     */
    val LOCATION_HINT: String get() = AppText.s(R.string.strip_location_hint)
}
