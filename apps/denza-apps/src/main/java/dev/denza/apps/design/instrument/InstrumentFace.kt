package dev.denza.apps.design.instrument

/**
 * The six ways the cluster sets type, and there is no seventh.
 *
 * A face is a size off [InstrumentDensity.RAMP], a weight and a tracking, and nothing else. It is an
 * enum rather than three parameters at every call site because that is how the design boards ended
 * up with the same heading set two different ways on adjacent artboards: each caller chose.
 *
 * ### Roboto with tabular figures, not Roboto Mono
 *
 * «12 , 4». «2 : 15». A monospaced face gives a comma, a colon and a degree the same 0.6 em cell it
 * gives a digit, so every number on the third drawing fell apart into groups. Measured in headless
 * Chrome at the sizes these boards set: a Roboto digit advances 0.5620 of its size at Regular and
 * 0.5547 at Light, a comma 0.1969 and a colon 0.2422.
 *
 * The measurement worth keeping is that **`0`, `1` and `4` all advance identically in Roboto without
 * the feature**: its figures are already tabular, so `tnum` buys nothing in this face. It is set
 * anyway, because a `Paint` on the car may resolve something else, and a reserve field sized by a
 * digit count is a contract rather than a hope.
 *
 * The rule that face was there to serve survives intact and is the panel's own: **no coordinate
 * depends on data.** Every number lives in a field sized by its maximum digit count times a measured
 * advance, and its unit hangs off the field rather than off the string, so gaining a digit moves
 * nothing.
 */
enum class InstrumentFace(
    val size: Float,
    val weight: InstrumentWeight,
    val tracking: Float,
) {
    /** The one figure read on the move, and the only Light thing on the panel. */
    HERO(InstrumentDensity.WIDE.hero, InstrumentWeight.LIGHT, 0f),

    /** A corner's figure and the petal's. Light at this size puts a 1:11 stem on black (M3). */
    FIGURE(InstrumentDensity.WIDE.figure, InstrumentWeight.REGULAR, 0f),

    /** A shelf's figure, and the hero's own unit - the one unit that has to be read on the move. */
    READING(InstrumentDensity.WIDE.reading, InstrumentWeight.REGULAR, 0f),

    /**
     * A unit, and the odometer's own figure inside «42 км · ЗА ПОЕЗДКУ».
     *
     * Units are case-sensitive and are therefore not headings: «кВт·ч», «об/мин», «км» are set as
     * themselves, because a tracked capital does not get to rewrite ГОСТ 8.417 (m1). The odometer's
     * figure takes this face for the same reason - it is a number living in a phrase rather than a
     * reading of its own.
     */
    UNIT(InstrumentDensity.WIDE.body, InstrumentWeight.REGULAR, 0f),

    /** A corner's heading: capitals, tracked, one weight up so it is not its own caption. */
    HEADING(InstrumentDensity.WIDE.title, InstrumentWeight.MEDIUM, InstrumentDensity.WIDE.titleTracking),

    /** A shelf's caption and the engine legend's words: capitals, tracked, Regular. */
    CAPTION(InstrumentDensity.WIDE.title, InstrumentWeight.REGULAR, InstrumentDensity.WIDE.titleTracking),
    ;

    /** Cap height, in the same units as [size]: what the ink of a digit actually occupies. */
    val capHeight: Float get() = size * CAP_HEIGHT

    companion object {
        /**
         * A Roboto cap as a share of its type size.
         *
         * Not the type size itself: a font's size includes the room above the caps and below the
         * baseline that accents and descenders live in, so a shape asked to be "as tall as this
         * number" and given the type size stands visibly taller than the digits beside it. Every
         * baseline on the Contour is derived from this, and `ContourBoardContractTest` holds it
         * against the boards, which are drawn from the same 0.71.
         */
        const val CAP_HEIGHT = 0.71f
    }
}

/** The three weights the cluster has, named rather than numbered. */
enum class InstrumentWeight { LIGHT, REGULAR, MEDIUM }
