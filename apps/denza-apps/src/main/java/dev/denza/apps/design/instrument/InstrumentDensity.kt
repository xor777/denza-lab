package dev.denza.apps.design.instrument

/**
 * The one type ramp and rhythm the cluster is measured against.
 *
 * This exists because of what an audit of the design boards found: twenty-seven distinct type sizes
 * where six were declared, eighteen radii, thirteen optical stroke weights for one flat-line icon
 * family, and four sibling boards with four different internal grids. None of that was a decision -
 * it was what happens when each surface picks its own numbers. The same thing was already starting
 * here, with two parallel blocks of size constants inside one renderer and gaps of 6, 7, 8 and 18
 * scattered through its methods.
 *
 * So sizes come from [RAMP] and nowhere else, and every gap is a whole number of [step].
 *
 * ### Where the rungs come from
 *
 * They are not taste, they are arithmetic on a tape measure. The owner measured the cluster on
 * 2026-09-04: the active area of the glass is 320 mm wide and his eyes sit 750 mm from it. The
 * panel is drawn in a virtual space 424 units tall, which makes one unit 0.2123 mm; a Roboto cap is
 * 0.71 em and one arc minute at 750 mm is 0.2182 mm, so a cap of `size` units subtends
 * `size × 0.691` arc minutes. ISO 15008 puts the floor at 20′ and comfort at 30′:
 *
 * | rung | mm | arc min | where |
 * | --- | --- | --- | --- |
 * | 88 | 13.26 | 61 | the hero, read on the move |
 * | 52 | 7.84 | 36 | the corners and the petal - comfortable |
 * | 34 | 5.12 | 23 | both shelves - legal for a deliberate glance |
 * | 18 | 2.71 | 12 | headings, captions, units: furniture |
 *
 * `104` used to head this ladder and is gone. It was chosen when the glass was an estimate that made
 * 52 look illegal and a doubling look necessary; on the measured 320 mm it is a 16 mm numeral. `88`
 * is `1.69 × 52`, which clears this ramp's own 1.2× rule with room, and it still reads at 61′. The
 * rule is a visible step, not an octave.
 *
 * The full ramp keeps 24, 13 and 11 below the four the Contour draws: they are what a future
 * instrument may pick from, and a size not on the ladder is not available to anything.
 */
data class InstrumentDensity(
    /** The one figure read on the move: the kilowatts, on the axis. */
    val hero: Float,
    /** A corner's figure, and the petal's: volts, revolutions, consumption. */
    val figure: Float,
    /** A shelf's figure. Supports the hero rather than competing with it. */
    val reading: Float,
    /** Units, captions, the words under a history. */
    val body: Float,
    /**
     * A heading, set in capitals - which is why it shares a rung with [body] rather than sitting
     * below it.
     *
     * It used to be a rung smaller, on the reasoning that capitals read larger. At 23′ on this
     * glass that reasoning ran out: a heading a rung under the caption beneath it was 9′, which is
     * board furniture. What separates the two now is weight and tracking, which is what separates
     * them typographically anyway.
     */
    val title: Float,
    /**
     * Letter-spacing for that title, in ems.
     *
     * Capitals set solid read as a block rather than as words, so a title carries tracking where
     * nothing else does. It belongs to the ramp because it is part of what makes the smallest rung
     * legible: without it the title would have to be a rung larger and would stop being a title.
     */
    val titleTracking: Float,
    /** The rhythm. Every gap on the panel is a whole number of these. */
    val step: Float,
) {

    /** [steps] of the rhythm, for a gap that has to be stated in the layout rather than here. */
    fun rhythm(steps: Float): Float = step * steps

    /** Every type size this density uses, for the test that holds it to [RAMP]. */
    val sizes: List<Float> get() = listOf(hero, figure, reading, body, title)

    companion object {
        /**
         * The ladder. A size not on it is not available to an instrument.
         *
         * Adjacent rungs are far enough apart - never closer than 1.18× - that two of them can never
         * read as the same size, which is the failure the audit actually found. A rung at 16 was
         * tried and removed for exactly that reason: against 18 it was a 1.13× step, which is a
         * difference you can measure and cannot see.
         */
        val RAMP: List<Float> = listOf(88f, 52f, 34f, 24f, 18f, 13f, 11f)

        /** The full-width cluster, which is the only space this app draws instruments in. */
        val WIDE = InstrumentDensity(
            hero = 88f,
            figure = 52f,
            reading = 34f,
            body = 18f,
            title = 18f,
            titleTracking = 0.12f,
            step = 8f,
        )
    }
}
