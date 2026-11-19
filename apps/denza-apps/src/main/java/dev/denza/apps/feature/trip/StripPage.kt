package dev.denza.apps.feature.trip

import android.annotation.SuppressLint
import android.content.Context

/**
 * The strip's two pages.
 *
 * Two pages, and not the pager that was here before. `BottomPanelPager` carried four vehicle pages
 * with no indicator and no affordance and was deleted on 2026-08-27 for exactly that: pages nobody
 * could know were there. What is here now is one gesture, two pages, and two dots at the foot of
 * the strip that are on the screen whether or not anybody swipes.
 *
 * **Each page is the whole strip.** The trip's three readings used to stand beside the analyser on
 * both pages, true on both and never moving; on the Luminofor board they belong to the sound page,
 * with the track over them and the analyser under them, and the car's page spends the whole strip
 * on the pack, its temperatures and its ten kilometres.
 */
enum class StripPage {
    /** The track, the trip's readings and the analyser. */
    SOUND,

    /** What the pack is doing, how warm five components are, and what the last ten km cost. */
    VEHICLE;

    fun next(forward: Boolean): StripPage = when {
        forward && this == SOUND -> VEHICLE
        !forward && this == VEHICLE -> SOUND
        else -> this
    }
}

/**
 * What the live strip holds for the page it is on, and nothing for the page it is not.
 *
 * The audio capture is the analyser's, and only the sound page draws the analyser: the car page used
 * to keep the system effect and its FFT running for as long as it was up, for bars nobody saw. The
 * vehicle hub's screen cadence is the car page's, and only while it is up (`VehicleWatcher.STRIP`).
 * The GNSS fixes and the park feed belong to the trip clock and run on either page.
 */
internal data class StripHolds(val spectrum: Boolean, val vehicle: Boolean) {
    companion object {
        fun of(live: Boolean, page: StripPage): StripHolds =
            StripHolds(spectrum = live && page == StripPage.SOUND, vehicle = live && page == StripPage.VEHICLE)
    }
}

/**
 * Which page the strip comes back on.
 *
 * Remembered, because a page is a choice rather than a mood: somebody who swiped to the car's
 * numbers on Monday is looking for them on Tuesday, and a screen that resets itself every ignition
 * teaches nobody where anything is. It is one word in one preferences file, written on the swipe.
 */
object StripPageSettings {
    private const val PREFS = "trip_strip"
    private const val PAGE = "page"

    fun page(context: Context): StripPage {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAGE, null)
        return StripPage.entries.firstOrNull { it.name == name } ?: StripPage.SOUND
    }

    @SuppressLint("UseKtx")
    fun setPage(context: Context, page: StripPage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PAGE, page.name)
            .apply()
    }
}
