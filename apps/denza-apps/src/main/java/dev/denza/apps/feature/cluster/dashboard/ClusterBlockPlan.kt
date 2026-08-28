package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.InstrumentDensity

/** One horizontal band inside a block, and the rhythm steps kept clear above it. */
internal sealed interface DashboardRow {

    val lead: Float

    /** A line of type of [sizeV], placed by its baseline. */
    data class Text(val sizeV: Float, override val lead: Float = 0f) : DashboardRow

    /** A linear gauge, placed by its centre line. */
    data class Rule(override val lead: Float) : DashboardRow

    /** [rows] rows of dots [stepV] apart, placed by the centre of the first. */
    data class Dots(val rows: Int, val stepV: Float, override val lead: Float) : DashboardRow
}

/**
 * What each block of the cluster dashboard is made of, before anything is drawn.
 *
 * Separating the plan from the drawing buys two things a canvas cannot give on its own. The block
 * gets *centred* in its box, because its height is known before the first baseline is placed - a
 * block laid out from the top down sits high in a tall box and drags its scrim off with it. And the
 * fit becomes testable: a unit test can add these up against the boxes
 * [ClusterDashboardLayout] hands out and fail when a row stops fitting, which is otherwise something
 * you find out on the car.
 *
 * The wide layout gives a block about 430 units of width and 160 of height; the narrow one gives it
 * 172 by 280. So the wide plans spend width - two numbers on one row - and the narrow plans spend
 * height, and each fact that shares a row in one gets a row of its own in the other.
 */
internal object ClusterBlockPlan {

    /** The leads any block uses, in steps of the density's own rhythm. */
    const val LEAD_TITLE = 2f
    const val LEAD_FIGURE = 3f
    const val LEAD_GROUP = 3f
    const val LEAD_ROW = 2f

    /** How tall a plan comes out, in the density's own units. */
    fun height(rows: List<DashboardRow>, density: InstrumentDensity): Float =
        rows.sumOf { row ->
            val body = when (row) {
                is DashboardRow.Text -> row.sizeV
                is DashboardRow.Rule -> density.trackHeight
                is DashboardRow.Dots -> row.rows * row.stepV
            }
            (density.rhythm(row.lead) + body).toDouble()
        }.toFloat()

    /** Title, pack voltage with its spread beside it, the voltage gauge, one sentence. */
    fun electricWide(d: InstrumentDensity): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Text(d.figure, LEAD_TITLE),
        DashboardRow.Rule(LEAD_FIGURE),
        DashboardRow.Text(d.body, LEAD_GROUP),
    )

    /**
     * The same facts stacked, with the spread and the pack's health each given a row.
     *
     * The last row is a reading's row, not a sentence's, and it is that height whichever of the two
     * it is carrying today: the insulation is a number with a unit and a name like the two above
     * it, and while the car is charging the same row says how long is left instead. Planning it at
     * the smaller of the two would move the whole block up the panel the moment the gun came out.
     */
    fun electricNarrow(d: InstrumentDensity): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Text(d.figure, LEAD_TITLE),
        DashboardRow.Rule(LEAD_FIGURE),
        DashboardRow.Text(d.reading, LEAD_GROUP),
        DashboardRow.Text(d.reading, LEAD_ROW),
        DashboardRow.Text(d.reading, LEAD_GROUP),
    )

    /**
     * Title, revolutions with their own two minutes of history beside them, the gauge, one sentence.
     *
     * The figure row used to carry the tank's percentage on its left. The vehicle's own cluster
     * shows the tank a few centimetres away, so that was a duplicate; the space it leaves goes to a
     * trace of where the revolutions have just been, which is nowhere else. No row is added: the
     * trace is drawn the height of the figure's own digits, on the figure's own baseline.
     *
     * The last row is usually empty and is planned anyway. It carries a sentence only when the
     * engine is doing something the rest of the block cannot show - generating, or not answering -
     * and reserving its height means the block does not jump up the panel the moment it does.
     */
    fun engineWide(d: InstrumentDensity): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Text(d.figure, LEAD_TITLE),
        DashboardRow.Rule(LEAD_FIGURE),
        DashboardRow.Text(d.body, LEAD_GROUP),
    )

    /** The same four rows: the tank's own gauge went with its percentage. */
    fun engineNarrow(d: InstrumentDensity): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Text(d.figure, LEAD_TITLE),
        DashboardRow.Rule(LEAD_FIGURE),
        DashboardRow.Text(d.body, LEAD_GROUP),
    )

    /** Pack and inverter share a row; the three drive motors take the next. */
    fun temperatures(d: InstrumentDensity): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Text(d.reading, LEAD_ROW),
        DashboardRow.Text(d.reading, LEAD_ROW),
    )

    /** A grid of lamps under the title, and the one line that speaks for them. */
    fun lamps(d: InstrumentDensity, lampCount: Int, columns: Int): List<DashboardRow> = listOf(
        DashboardRow.Text(d.title),
        DashboardRow.Dots((lampCount + columns - 1) / columns, d.lampStep, LEAD_TITLE),
        DashboardRow.Text(d.body, LEAD_ROW),
    )
}
