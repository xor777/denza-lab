package dev.denza.apps.feature.trip

/**
 * Which composition the trip strip draws, one per window width the car gives this app.
 *
 * This used to be a `narrowLayout: Boolean`, which said there were two. There were three: the
 * two-thirds pane took the wide composition and had it squeezed into 62 per cent of the space it
 * was laid out for, so every type size in it - the ladder's 46, 24 and 15 - arrived on the screen
 * at 28, 15 and 9. A boolean cannot carry a third answer, so it was never asked for one.
 *
 * The panes are laid out one unit to one dp, which is why they are separate compositions rather
 * than one design at three scales: a strip whose text is legible at arm's length in a car has a
 * bottom rung, and scaling is exactly the operation that walks off it.
 */
enum class TripPanelLayout {
    /** 1280 dp: the analyser on the left, three figures hung apart down a column on the right. */
    WIDE,

    /** 828 dp: the analyser on the left, the same three figures as rows on the right. */
    MEDIUM,

    /** 416 dp: the analyser across the top, the three rows under it. */
    NARROW,
}
