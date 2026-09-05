package dev.denza.apps.feature.trip

import android.annotation.SuppressLint
import android.content.Context

/**
 * The two things the field on the left of the strip can be.
 *
 * Two pages, and not the pager that was here before. `BottomPanelPager` carried four vehicle pages
 * with no indicator and no affordance and was deleted on 2026-08-27 for exactly that: pages nobody
 * could know were there. What is here now is one gesture, two pages, and two dots under the field
 * that are on the screen whether or not anybody swipes.
 *
 * The three trip figures on the right of the strip are not a page. They are true on both and never
 * move: a swipe that took away how long, how high and when the sun goes down would cost the one
 * thing the strip is always good for.
 */
enum class StripPage {
    /** The analyser, which is what the strip has been since the pager went. */
    SOUND,

    /** What the pack is doing, has been doing for two minutes, and how warm five components are. */
    VEHICLE;

    fun next(forward: Boolean): StripPage = when {
        forward && this == SOUND -> VEHICLE
        !forward && this == VEHICLE -> SOUND
        else -> this
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
