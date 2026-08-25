package dev.denza.apps.design.instrument

/**
 * The one type ramp and rhythm every instrument in this app is measured against.
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
 * The two densities are not two designs. The cluster's wide space (1506x424) and its narrow one
 * (602x308) both land on the panel at the same 1.70 scale, so a size means the same number of
 * millimetres in either - [COMPACT] is smaller only because it has less room, and each of its sizes
 * is a lower rung of the same ladder rather than a second, invented set.
 *
 * That is also why nothing here names a virtual space. A density is a set of sizes; the space they
 * are drawn into belongs to the layout, which is the only thing that knows how big its panel is. The
 * two were briefly conflated and it cost a real bug: a corner block, which takes [COMPACT] sizes
 * inside the *wide* space, had its box measured against the narrow one and came out a quarter too
 * small.
 */
data class InstrumentDensity(
    /** The hero number of a block: kilowatts, volts, revolutions. */
    val figure: Float,
    /** A number that supports the hero rather than competing with it. */
    val reading: Float,
    /** Units, status lines, the sentence under a chart. */
    val body: Float,
    /** A section name, set in capitals - which is why it sits below [body] on the ramp. */
    val title: Float,
    /** A mark's label on a dial. */
    val tick: Float,
    /** The rhythm. Every gap in an instrument is a whole number of these. */
    val step: Float,
    val arcWidth: Float,
    val tickLength: Float,
    val trackHeight: Float,
    val barWidth: Float,
    val dotRadius: Float,
    val hairline: Float,
    val lampStep: Float,
    val lampRadius: Float,
) {

    /** [steps] of the rhythm, for a gap that has to be stated in the layout rather than here. */
    fun rhythm(steps: Float): Float = step * steps

    /** Every type size this density uses, for the test that holds it to [RAMP]. */
    val sizes: List<Float> get() = listOf(figure, reading, body, title, tick)

    companion object {

        /**
         * The ladder. A size not on it is not available to an instrument.
         *
         * Six rungs is more than a page needs and fewer than a page can drift into: adjacent rungs
         * are far enough apart - never closer than 1.18x - that two of them can never read as the
         * same size, which is the failure the audit actually found. A seventh rung at 16 was tried
         * and removed for exactly that reason: against 18 it was a 1.13x step, which is a
         * difference you can measure and cannot see.
         */
        val RAMP: List<Float> = listOf(52f, 34f, 24f, 18f, 13f, 11f)

        /** The full-width cluster, and later the two-thirds panel. */
        val WIDE = InstrumentDensity(
            figure = 52f,
            reading = 24f,
            body = 18f,
            title = 13f,
            tick = 13f,
            step = 8f,
            arcWidth = 10f,
            tickLength = 9f,
            trackHeight = 7f,
            barWidth = 9f,
            dotRadius = 7.8f,
            hairline = 1.2f,
            lampStep = 21f,
            lampRadius = 5.5f,
        )

        /** The right third of the cluster, and later the one-third panel. */
        val COMPACT = InstrumentDensity(
            figure = 34f,
            reading = 18f,
            body = 13f,
            title = 11f,
            tick = 11f,
            step = 6f,
            arcWidth = 7f,
            tickLength = 7f,
            trackHeight = 5f,
            barWidth = 6f,
            dotRadius = 5.5f,
            hairline = 1.2f,
            lampStep = 16f,
            lampRadius = 4f,
        )
    }
}
