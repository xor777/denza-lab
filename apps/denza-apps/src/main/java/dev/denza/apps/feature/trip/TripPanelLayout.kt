package dev.denza.apps.feature.trip

/**
 * Which composition the trip strip draws, one per window width the car gives this app.
 *
 * This used to be a `narrowLayout: Boolean`, which said there were two. There were three: the
 * two-thirds pane took the wide composition and had it squeezed into 62 per cent of the space it
 * was laid out for, so every type size in it arrived on the screen at six tenths of itself. A
 * boolean cannot carry a third answer, so it was never asked for one.
 *
 * The Luminofor board draws each of the three at the window's own dp, one unit to one dp, because
 * a strip whose text is legible at arm's length in a car has a bottom rung and scaling is exactly
 * the operation that walks off it. Each has its own strip box and its own numbers in
 * `tools/design-canvas/luminofor/spec.json` (`head.full`, `head.two`, `head.one`).
 */
enum class TripPanelLayout {
    /** 1280 dp: the readings right-aligned beside the track, 36 columns, the car's row centred. */
    WIDE,

    /** 828 dp: the track over the readings in a row, 24 columns; the car's cells in two rows. */
    MEDIUM,

    /** 416 dp: the readings as rows, 12 columns; the car's cells in three rows, no trip cell. */
    NARROW,
}
