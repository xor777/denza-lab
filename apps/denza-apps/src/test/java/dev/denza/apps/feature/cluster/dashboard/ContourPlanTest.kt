package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's own geometry: that it fits, and that no data can move it.
 *
 * `ContourBoardContractTest` holds these numbers against the drawing. This holds them against the
 * two things a drawing cannot check - the apertures the vehicle leaves us, and the invariant that
 * gaining a digit moves nothing.
 */
class ContourPlanTest {

    private fun plan(type: ContourType = ContourType.BOARD) =
        ContourPlan(ClusterDashboardLayout(2560, 720, ClusterMapPlacement.FULL), type)

    /** A face whose digits and words are all a different width, to prove nothing is hard-coded. */
    private fun stretched(factor: Float) = ContourType { text, face ->
        ContourType.BOARD.width(text, face) * factor
    }

    // ---- the two guards, which are the same number

    @Test
    fun everythingAtTheTopHangsOffOneGuardAndEverythingAtTheBottomOffTheOther() {
        val plan = plan()
        assertEquals("the guard is three rhythm steps", 24f, plan.clearance, 1e-4f)
        assertEquals(plan.stockTop + 24f, plan.guardTop, 1e-4f)
        assertEquals(plan.stockBottom - 24f, plan.guardBottom, 1e-4f)

        // The hero's cap, both shelves' caps, and the engine box's top edge are all that one line.
        assertEquals(plan.guardTop, plan.heroCapTop, 1e-3f)
        assertEquals(
            plan.guardTop,
            plan.shelfFigureBaseline - InstrumentFace.READING.capHeight,
            1e-3f,
        )
        assertEquals(plan.guardTop, plan.engineBoxTop, 1e-4f)

        // And the band's body clears the lower one, which is what CRITIQUE B2 asked for.
        assertTrue(
            "the band reaches ${plan.bandY + plan.bandBody / 2f} against ${plan.guardBottom}",
            plan.bandY + plan.bandBody / 2f < plan.guardBottom,
        )
    }

    // ---- it fits

    @Test
    fun theCornersFitTheAperturesTheyAreDrawnIn() {
        val plan = plan()
        // The engine's longest heading, at the baseline it is actually set on.
        val room = plan.rightEdge - (plan.width - plan.apertureReach(plan.cornerTitleBaseline, right = true))
        val heading = ContourType.BOARD.width(ContourReadout.TITLE_ENGINE_MINUTES, InstrumentFace.HEADING)
        assertTrue("«ДВС · мин за поездку» is $heading in $room", heading < room)

        // And the four-digit revolutions under it.
        val figureRoom =
            plan.rightEdge - (plan.width - plan.apertureReach(plan.cornerFigureBaseline, right = true))
        val field = 4 * ContourType.BOARD.width("0", InstrumentFace.FIGURE)
        assertTrue("four digits are $field in $figureRoom", field < figureRoom)
    }

    @Test
    fun theHistoryBoxFitsThePetalsCutOutAtItsLowestCorner() {
        val plan = plan()
        // A cut-out narrows downward, so the lower left corner is the one that escapes first.
        val guard = 8f
        assertTrue(
            "the box starts at ${plan.petalBoxLeft} against a cut-out at " +
                "${plan.petalRoom(plan.petalBoxBottom)}",
            plan.petalBoxLeft - plan.petalRoom(plan.petalBoxBottom) > guard,
        )
        assertTrue(
            "and the unit ends at ${plan.petalUnitX + plan.petalUnitWidth}",
            plan.petalEdge(plan.petalBaseline) - plan.petalUnitX - plan.petalUnitWidth > guard,
        )
        assertTrue("nothing goes below the floor", plan.petalBoxBottom < plan.petalFloor)
    }

    @Test
    fun theTwoShelvesStayClearOfTheHeroTheyStandBeside() {
        val plan = plan()
        val jury = 24f
        assertTrue(
            "the left shelf ends at ${plan.leftShelfRight}, the hero's field starts at " +
                "${plan.heroFieldLeft}",
            plan.heroFieldLeft - plan.leftShelfRight > jury,
        )
        assertTrue(
            "the right shelf starts at ${plan.rightShelfLeft}, the hero's unit ends at " +
                "${plan.heroUnitX + plan.heroUnitWidth}",
            plan.rightShelfLeft - (plan.heroUnitX + plan.heroUnitWidth) > jury,
        )
    }

    @Test
    fun theEngineBoxTakesExactlyTheRoomTheShelfWouldHave() {
        val plan = plan()
        assertEquals(plan.rightShelfLeft, plan.engineBoxFullLeft, 1e-4f)
        assertEquals(plan.rightEdge, plan.engineBoxRight, 1e-4f)
        // Both rows of the shelf, and a rhythm step above its legend's caps.
        assertEquals(
            plan.engineLegendBaseline - InstrumentFace.CAPTION.capHeight - plan.step,
            plan.engineBoxBottom,
            1e-4f,
        )
        assertTrue("the box has real height", plan.engineBoxBottom - plan.engineBoxTop > 48f)
        // The legend stands on the band's own guard, so the swap moves no neighbour's baseline.
        assertEquals(
            plan.bandY - plan.bandBody / 2f - plan.clearance,
            plan.engineLegendBaseline,
            1e-4f,
        )
    }

    @Test
    fun theSecondSeatAppearingMovesNothing() {
        val plan = plan()
        // Seats are counted right to left from the shelf's own edge, so seat 0 is the same cell in
        // both states and the one that arrives arrives to its left.
        assertEquals(
            plan.tripSeat(0, plan.driveSeats),
            plan.tripSeat(0, plan.parkSeats),
            1e-4f,
        )
        assertEquals(plan.rightEdge - plan.tripCell, plan.tripSeat(0, plan.driveSeats), 1e-4f)
    }

    // ---- no coordinate depends on data

    @Test
    fun aReadingThatGainsADigitMovesNothing() {
        val plan = plan()
        // 34 becomes 128, 9,3 becomes 12,4, 42 km becomes 142 km. Every one of those lives in a
        // reserve field sized by its maximum digit count, and its neighbours are set against the
        // field's edge rather than against the string.
        assertEquals(3 * ContourType.BOARD.width("0", InstrumentFace.HERO), plan.heroFieldWidth, 1e-4f)
        assertEquals(
            3 * ContourType.BOARD.width("0", InstrumentFace.READING) +
                ContourType.BOARD.width(",", InstrumentFace.READING),
            plan.tripField,
            1e-4f,
        )
        assertEquals(3 * ContourType.BOARD.width("0", InstrumentFace.UNIT), plan.odometerField, 1e-4f)
        // The petal's field holds three digits and one mark, so «16,8» and «2:15» share it.
        assertTrue(plan.petalFieldWidth > plan.petalPrintedWidth)
    }

    @Test
    fun thePetalsBoxHangsOffTheWidestTheFieldEverGetsRatherThanOffThePrintedDigits() {
        val plan = plan()
        // So the gap is a floor rather than an average: on P it closes, on the move it opens, and
        // the box does not move between the two.
        assertEquals(
            plan.petalFigureRight - plan.petalFieldWidth - plan.petalBoxGap,
            plan.petalBoxRight,
            1e-4f,
        )
        assertEquals("the printed figure centres on the axis", plan.axis, plan.petalFigureRight - plan.petalPrintedWidth / 2f, 1e-4f)
    }

    @Test
    fun theHeroAndItsUnitCentreOnTheAxisAsOneGroup() {
        val plan = plan()
        // Centring the field alone left «кВт» stranded from a two-digit reading and touching a
        // three-digit one.
        val left = plan.heroFieldLeft
        val right = plan.heroUnitX + plan.heroUnitWidth
        assertEquals(plan.axis, (left + right) / 2f, 1e-3f)
    }

    @Test
    fun aWiderFaceWidensTheCellsAndMovesNoAnchor() {
        // The car's own Roboto may not be Chrome's. A cell is as wide as the wider of its caption
        // and its payload, so it should follow the face - while the hero, the band and the two
        // guards, which are anchors rather than measurements, should not move at all.
        val board = plan()
        val wide = plan(stretched(1.1f))

        assertEquals(board.guardTop, wide.guardTop, 1e-4f)
        assertEquals(board.bandY, wide.bandY, 1e-4f)
        assertEquals(board.axis, wide.axis, 1e-4f)
        assertEquals(board.petalBoxWidth, wide.petalBoxWidth, 1e-4f)
        assertEquals(board.shelfFigureBaseline, wide.shelfFigureBaseline, 1e-4f)
        assertTrue("the cells follow the face", wide.leftShelfRight > board.leftShelfRight)
    }
}
