package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.design.instrument.InstrumentWeight
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contour's boards, read at test time, against the constants the panel is drawn from.
 *
 * `tools/design-canvas/gen_contour.py` and [ContourPlan] are two records of one composition, and for
 * a while on the head unit nothing compared the equivalent pair. It did not hold: the first cut of
 * that screen carried every number off `Main.dc.html` and looked nothing like it, because the board
 * hangs a tile's words off the bottom edge and the code stacked them from the top. The numbers
 * agreed and the composition did not, and nothing anywhere could tell.
 *
 * So this is the join, and it **fails in both directions on purpose**: editing a board without this
 * file breaks it, and so does editing this file without the board. Neither record can move alone,
 * which means a design change that never reached the app cannot pass CI looking finished.
 *
 * ### What it cannot check
 *
 * It reads coordinates and declarations out of two SVG documents; it cannot run a `Canvas`, so it
 * cannot prove the Kotlin paints the result the board draws. Proving that still takes
 * `python3 shot.py ClusterContour --scale 2` beside a photograph of the car, which is the step that
 * was skipped on the head unit and the reason this file exists.
 *
 * ### The one tolerance
 *
 * The board's numbers are written to a tenth, so every coordinate is compared to 0.05. The measured
 * *advances* are compared to two per cent instead: Chrome's Roboto and the car's Roboto are two
 * fonts with one name, and a cell that is as wide as its own caption should follow the face it is
 * actually set in. What must not drift is the arithmetic between them, and that is everything else
 * on this page.
 */
class ContourBoardContractTest {

    private val plan = ContourPlan(
        ClusterDashboardLayout(2560, 720, ClusterMapPlacement.FULL),
        ContourType.BOARD,
    )

    // ---- the ramp and the faces

    @Test
    fun theBoardSetsTheRungsTheRampDeclares() {
        val density = InstrumentDensity.WIDE
        assertEquals("hero", density.hero, cssSize("hero"), TOLERANCE)
        assertEquals("figure", density.figure, cssSize("fig"), TOLERANCE)
        assertEquals("reading", density.reading, cssSize("rd"), TOLERANCE)
        assertEquals("the hero's unit", density.reading, cssSize("un34"), TOLERANCE)
        assertEquals("heading", density.title, cssSize("ttl"), TOLERANCE)
        assertEquals("caption", density.title, cssSize("cl"), TOLERANCE)
        assertEquals("unit", density.body, cssSize("un"), TOLERANCE)
    }

    @Test
    fun theBoardSetsTheWeightsAndTheTrackingTheFacesDeclare() {
        val board = board()
        assertTrue(
            "the hero is Light on the board",
            rule(board, ".hero").contains("font-weight:300"),
        )
        assertEquals(InstrumentWeight.LIGHT, InstrumentFace.HERO.weight)
        assertTrue(rule(board, ".ttl").contains("font-weight:500"))
        assertEquals(InstrumentWeight.MEDIUM, InstrumentFace.HEADING.weight)
        assertTrue(rule(board, ".cl").contains("font-weight:400"))

        val tracking = InstrumentDensity.WIDE.titleTracking
        assertEquals(tracking, number(rule(board, ".ttl"), "letter-spacing"), 1e-4f)
        assertEquals(tracking, number(rule(board, ".cl"), "letter-spacing"), 1e-4f)
    }

    @Test
    fun theBoardAsksForTabularFiguresEverywhereANumberIsSet() {
        val board = board()
        listOf(".hero", ".fig", ".rd", ".un").forEach {
            assertTrue("$it is not tabular on the board", rule(board, it).contains("'tnum'"))
        }
    }

    @Test
    fun theMeasuredAdvancesAreTheBoardsOwn() {
        // Roboto's digits are already fixed-width - «00000000» and «44444444» come back at the same
        // 233.7969 at 52 - so `tnum` changes nothing in this face and is set anyway, because a
        // reserve field is a contract rather than a hope.
        val generator = generator()
        assertAdvance("a Regular digit", ContourType.DIGIT_EM, python(generator, "DIGIT"))
        assertAdvance("a Light digit", ContourType.DIGIT_LIGHT_EM, python(generator, "DIGIT_LIGHT"))
        assertAdvance("a comma", ContourType.COMMA_EM, python(generator, "COMMA"))
        assertAdvance("a colon", ContourType.COLON_EM, python(generator, "COLON"))
    }

    @Test
    fun everyCaptionTheShelvesAreMeasuredFromIsTheBoardsCaption() {
        // A caption is a coordinate on this panel: a cell is exactly as wide as the wider of its
        // caption and its payload. So the strings and their measured widths are both contract.
        val generator = generator()
        listOf(
            ContourReadout.CAPTION_PACK,
            ContourReadout.CAPTION_MOTORS,
            ContourReadout.CAPTION_INVERTER,
            ContourReadout.CAPTION_SPREAD,
            ContourReadout.CAPTION_REGEN,
            ContourReadout.CAPTION_ENGINE_GAVE,
            ContourReadout.CAPTION_TRIP,
            ContourReadout.LEGEND_RPM,
            ContourReadout.LEGEND_GENERATION,
        ).forEach { caption ->
            assertAdvance(
                "«$caption»",
                ContourType.BOARD.width(caption, InstrumentFace.CAPTION),
                pythonTable(generator, "W_CAPTION", caption),
            )
        }
        assertAdvance(
            "«${ContourReadout.UNIT_PER_100KM}»",
            ContourType.BOARD.width(ContourReadout.UNIT_PER_100KM, InstrumentFace.UNIT),
            python(generator, "W_PETAL_WINDOW"),
        )
        assertAdvance(
            "«${ContourReadout.UNIT_KWH}»",
            ContourType.BOARD.width(ContourReadout.UNIT_KWH, InstrumentFace.UNIT),
            python(generator, "W_KWH"),
        )
        assertAdvance(
            "«${ContourReadout.UNIT_KW}» at the hero's size",
            ContourType.BOARD.width(ContourReadout.UNIT_KW, InstrumentFace.READING),
            python(generator, "W_KW34"),
        )
    }

    // ---- the panel, and the skeleton that is always on it

    @Test
    fun thePanelIsTheSpaceTheBoardIsDrawnIn() {
        val frame = FRAME.find(board()) ?: error("no artboard on the board")
        assertEquals("width", plan.width, frame.groupValues[1].toFloat(), TOLERANCE)
        assertEquals("height", plan.height, frame.groupValues[2].toFloat(), TOLERANCE)
    }

    @Test
    fun theBandsHairlineAndItsZeroMarkAreWhereTheBoardPutsThem() {
        val board = board()
        val hairline = LINE.findAll(board).first { it.groupValues[5] == "#7C858F" && it.groupValues[2] == it.groupValues[4] }
        assertEquals("the band's y", plan.bandY, hairline.groupValues[2].toFloat(), TOLERANCE)
        assertEquals("its left end", plan.leftEdge, hairline.groupValues[1].toFloat(), TOLERANCE)
        assertEquals("its right end", plan.rightEdge, hairline.groupValues[3].toFloat(), TOLERANCE)
        assertEquals("its weight", plan.bandHairline, hairline.groupValues[6].toFloat(), TOLERANCE)

        val zero = LINE.findAll(board).first { it.groupValues[1] == it.groupValues[3] && it.groupValues[5] == "#7C858F" }
        assertEquals("the zero mark", plan.axis, zero.groupValues[1].toFloat(), TOLERANCE)
        assertEquals(plan.bandY - plan.zeroHalf, zero.groupValues[2].toFloat(), TOLERANCE)
        assertEquals(plan.bandY + plan.zeroHalf, zero.groupValues[4].toFloat(), TOLERANCE)
        assertEquals(plan.zeroWidth, zero.groupValues[6].toFloat(), TOLERANCE)
    }

    @Test
    fun theBandsBodyIsFourteenUnitsCentredOnItsOwnLine() {
        val body = RECT.findAll(board()).first { it.groupValues[5].startsWith("url(#bandfill") }
        assertEquals(plan.bandY - plan.bandBody / 2f, body.groupValues[2].toFloat(), TOLERANCE)
        assertEquals(plan.bandBody, body.groupValues[4].toFloat(), TOLERANCE)
        assertEquals("it starts at zero", plan.axis, body.groupValues[1].toFloat(), TOLERANCE)
    }

    @Test
    fun theGlowIsCentredOnZeroAndReachesTheLowerEdgeExactly() {
        val glow = ELLIPSE.find(board()) ?: error("no glow on the board")
        assertEquals(plan.glowCentreX, glow.groupValues[1].toFloat(), TOLERANCE)
        assertEquals(plan.glowCentreY, glow.groupValues[2].toFloat(), TOLERANCE)
        assertEquals(plan.glowRadiusX, glow.groupValues[3].toFloat(), TOLERANCE)
        assertEquals(plan.glowRadiusY, glow.groupValues[4].toFloat(), TOLERANCE)

        // 0.18·sqrt(|P| / 120 kW), saturated at 120 kW, and 34 kW is what the calm board draws.
        val stop = GLOW_STOP.find(board()) ?: error("no glow gradient on the board")
        assertEquals(plan.glowAlpha(34f), stop.groupValues[1].toFloat(), 0.005f)
        assertEquals(plan.glowAlpha(500f), plan.glowAlpha(ContourPlan.GLOW_FULL_KW), 1e-6f)
    }

    // ---- the hero

    @Test
    fun theHeroSitsOnTheGuardWithItsUnitBesideIt() {
        val hero = text(board(), "hero", "34")
        assertEquals("baseline", plan.heroBaseline, hero.second, TOLERANCE)
        assertEquals("the field's right edge", plan.heroFieldRight, hero.first, TOLERANCE)

        val unit = text(board(), "un34", ContourReadout.UNIT_KW)
        assertEquals(plan.heroUnitX, unit.first, TOLERANCE)
        assertEquals(plan.heroBaseline, unit.second, TOLERANCE)
    }

    // ---- the corners

    @Test
    fun theCornersHoldOneHeadingAndOneFigureAndThatIsAllTheyHold() {
        val board = board()
        val title = text(board, "ttl", ContourReadout.TITLE_PACK)
        assertEquals(plan.leftEdge, title.first, TOLERANCE)
        assertEquals(plan.cornerTitleBaseline, title.second, TOLERANCE)

        val volts = text(board, "fig", "552")
        assertEquals(plan.voltsFieldRight, volts.first, TOLERANCE)
        assertEquals(plan.cornerFigureBaseline, volts.second, TOLERANCE)
    }

    // ---- the shelves

    @Test
    fun bothShelvesStandOnOnePairOfBaselines() {
        val board = board()
        assertEquals(plan.shelfFigureBaseline, text(board, "rd", "28").second, TOLERANCE)
        assertEquals(
            plan.shelfCaptionBaseline,
            text(board, "cl", ContourReadout.CAPTION_PACK).second,
            TOLERANCE,
        )
        assertEquals(plan.shelfFigureBaseline, text(board, "rd", "9,3").second, TOLERANCE)
    }

    @Test
    fun theTemperatureCellsAreTheBoardsCells() {
        val cells = planCells(BLUE)
        // Four cells at the shelf's own top, in the order the shelf reads.
        val widths = listOf(
            plan.leftCells[0],
            plan.leftCells[1],
            plan.leftCells[2],
            plan.leftCells[3],
        )
        widths.forEachIndexed { index, width ->
            val left = plan.leftCell(index)
            val drawn = cells.firstOrNull { closeTo(it.x, left) && closeTo(it.width, width) }
            assertTrue(
                "cell $index at $left x $width is not on the plan board; it draws " +
                    cells.filter { closeTo(it.y, plan.guardTop) }.joinToString { "${it.x}x${it.width}" },
                drawn != null,
            )
        }
        // The three motors share one caption and one degree sign, and their fields are one pitch.
        val motors = plan.leftCell(1)
        listOf(0, 1, 2).forEach { index ->
            val left = motors + index * plan.motorPitch
            assertTrue(
                "motor field $index at $left",
                cells.any { closeTo(it.x, left) && closeTo(it.width, plan.temperatureField) },
            )
        }
    }

    @Test
    fun theTripSeatsAreCountedFromTheShelfsOwnEdge() {
        val cells = planCells(BLUE)
        listOf(0, 1, 2).forEach { index ->
            val left = plan.tripSeat(index, plan.parkSeats)
            assertTrue(
                "seat $index on P at $left x ${plan.parkSeats[index]}",
                cells.any { closeTo(it.x, left) && closeTo(it.width, plan.parkSeats[index]) },
            )
        }
        // And the pair it keeps on the move, whose second seat is somewhere else entirely.
        val onTheMove = plan.tripSeat(1, plan.driveSeats)
        assertTrue(
            "the moving shelf's second seat at $onTheMove",
            cells.any { closeTo(it.x, onTheMove) && closeTo(it.width, plan.driveSeats[1]) },
        )
    }

    @Test
    fun theTripSeatPutsItsKilometresInFrontOfItsWords() {
        val board = board()
        val left = plan.tripSeat(0, plan.driveSeats)
        assertEquals(
            "the odometer's field",
            left + plan.odometerField,
            text(board, "un", "42").first,
            TOLERANCE,
        )
        assertEquals(
            "«км»",
            left + plan.odometerField + plan.smallGap,
            text(board, "un", ContourReadout.UNIT_KM).first,
            TOLERANCE,
        )
        assertEquals(
            "«· ЗА ПОЕЗДКУ»",
            left + plan.odometerField + plan.smallGap + plan.kilometreWidth + plan.smallGap,
            text(board, "cl", ContourReadout.CAPTION_TRIP).first,
            TOLERANCE,
        )
        assertEquals(
            "the figure's own field",
            left + plan.tripField,
            text(board, "rd", "9,3").first,
            TOLERANCE,
        )
        assertEquals(
            "and «кВт·ч» hanging off it",
            left + plan.tripField + plan.smallGap,
            text(board, "un", ContourReadout.UNIT_KWH).first,
            TOLERANCE,
        )
    }

    // ---- the two boxes

    @Test
    fun theEngineBoxIsTheBoardsBox() {
        val box = planCells(ORANGE).first { closeTo(it.y, plan.engineBoxTop) }
        assertEquals("left", plan.engineBoxFullLeft, box.x, TOLERANCE)
        assertEquals("width", plan.engineBoxRight - plan.engineBoxFullLeft, box.width, TOLERANCE)
        assertEquals("height", plan.engineBoxBottom - plan.engineBoxTop, box.height, TOLERANCE)
        assertEquals("120 one-second slots", 120, plan.engineSlots)
        assertEquals(
            "and its pitch",
            (plan.engineBoxRight - plan.engineBoxFullLeft) / 119f,
            plan.enginePitch,
            1e-4f,
        )
        // Revolutions run linearly to 3000 and generation by a root to 100 kW, on the boards and in
        // the readout both.
        assertTrue(generator().contains("ENGINE_RPM_FULL = 3000."))
        assertTrue(generator().contains("ENGINE_GEN_FULL = 100."))
        assertEquals(3000f, plan.engineRpmFull, 1e-4f)
        assertEquals(100f, plan.engineGenerationFull, 1e-4f)
    }

    @Test
    fun thePetalBoxIsTwoHundredAndThirtyTwoByFiftySix() {
        val box = planCells(ORANGE).first { closeTo(it.y, plan.petalBoxTop) }
        assertEquals("left", plan.petalBoxLeft, box.x, TOLERANCE)
        assertEquals("width", plan.petalBoxWidth, box.width, TOLERANCE)
        assertEquals("height", plan.petalBoxHeight, box.height, TOLERANCE)
        assertEquals(232f, plan.petalBoxWidth, TOLERANCE)
        assertEquals(56f, plan.petalBoxHeight, TOLERANCE)
        assertEquals("thirty buckets of the log's own hundred metres", 30, plan.petalBuckets)
    }

    @Test
    fun thePetalsScaleIsAFixedLadderWithItsZeroFourFifthsDown() {
        val zero = LINE.findAll(board()).first { closeTo(it.groupValues[1].toFloat(), plan.petalBoxLeft) }
        assertEquals(plan.petalZeroY, zero.groupValues[2].toFloat(), TOLERANCE)
        assertEquals(
            plan.petalBoxLeft + plan.petalBoxWidth,
            zero.groupValues[3].toFloat(),
            TOLERANCE,
        )
        assertEquals(40f, plan.petalFull, 1e-4f)
        assertEquals("the bottom fifth is the return", 10f, plan.petalReturnFull, 1e-4f)
        assertTrue(generator().contains("PETAL_FULL = 40."))
        assertTrue(generator().contains("PETAL_ZERO_SHARE = 0.8"))
    }

    @Test
    fun thePetalsFigureAndItsUnitAreOnTheBoardsAnchors() {
        val board = board()
        assertEquals(plan.petalFigureRight, text(board, "fig", "17").first, TOLERANCE)
        assertEquals(plan.petalBaseline, text(board, "fig", "17").second, TOLERANCE)
        assertEquals(
            plan.petalUnitX,
            text(board, "un", ContourReadout.UNIT_PER_100KM).first,
            TOLERANCE,
        )
        // The accident worth keeping: the petal's figure and the hero's share a right edge, so
        // «кВт» and «кВт·ч/100 км» start on the same x.
        assertEquals(plan.heroFieldRight, plan.petalFigureRight, 0.1f)
        assertEquals(plan.heroUnitX, plan.petalUnitX, 0.1f)
    }

    // ---- the guards, drawn in red on the plan board

    @Test
    fun bothGuardsAreDrawnOnThePlanBoardAtTwentyFour() {
        val guards = LINE.findAll(planBoard()).filter { it.groupValues[5] == RED }
            .map { it.groupValues[2].toFloat() }
            .toList()
        assertTrue("the top guard at ${plan.guardTop} is not drawn: $guards", guards.any { closeTo(it, plan.guardTop) })
        assertTrue("the bottom guard at ${plan.guardBottom}", guards.any { closeTo(it, plan.guardBottom) })
        assertEquals(24f, plan.clearance, 1e-4f)

        val floor = LINE.findAll(planBoard()).first {
            it.groupValues[5] == ORANGE && closeTo(it.groupValues[1].toFloat(), 0f)
        }
        assertEquals("the composition's floor", plan.petalFloor, floor.groupValues[2].toFloat(), TOLERANCE)
    }

    @Test
    fun theHerosFieldIsDrawnOnThePlanBoardAtTheWidthThreeDigitsTake() {
        val field = planCells(BLUE).first { closeTo(it.x, plan.heroFieldLeft) && closeTo(it.y, plan.guardTop) }
        assertEquals(plan.heroFieldWidth, field.width, TOLERANCE)
        assertEquals(
            "and it is exactly the cap of the hero",
            plan.heroBaseline - plan.heroCapTop,
            field.height,
            TOLERANCE,
        )
    }

    // ---- reading the boards

    private data class Cell(val x: Float, val y: Float, val width: Float, val height: Float)

    private fun planCells(color: String): List<Cell> =
        OUTLINE.findAll(planBoard())
            .filter { it.groupValues[5] == color }
            .map {
                Cell(
                    it.groupValues[1].toFloat(),
                    it.groupValues[2].toFloat(),
                    it.groupValues[3].toFloat(),
                    it.groupValues[4].toFloat(),
                )
            }
            .toList()

    /** The x and the baseline of one drawn string. */
    private fun text(board: String, cssClass: String, content: String): Pair<Float, Float> {
        val match = Regex(
            """<text class="$cssClass" x="([-\d.]+)" y="([-\d.]+)"[^>]*>${Regex.escape(content)}</text>""",
        ).find(board) ?: error("«$content» is not set in .$cssClass on the board")
        return match.groupValues[1].toFloat() to match.groupValues[2].toFloat()
    }

    private fun cssSize(cssClass: String): Float = number(rule(board(), ".$cssClass"), "font-size")

    private fun rule(board: String, selector: String): String =
        Regex("\\Q$selector\\E\\s*\\{([^}]*)}").find(board)?.groupValues?.get(1)?.replace(" ", "")
            ?: error("the board has no $selector rule")

    private fun number(css: String, property: String): Float =
        Regex("(?<![\\w-])$property:([\\d.]+)").find(css)?.groupValues?.get(1)?.toFloat()
            ?: error("no $property in $css")

    /** One `NAME = 0.1234` out of the generator, which is where the measurement lives. */
    private fun python(source: String, name: String): Float =
        Regex("(?m)^$name = ([\\d.]+)").find(source)?.groupValues?.get(1)?.toFloat()
            ?: error("$name is not a constant in gen_contour.py")

    /** And one `'key': 0.1234` out of a table in it. */
    private fun pythonTable(source: String, table: String, key: String): Float {
        val body = Regex("(?s)$table = \\{(.*?)}").find(source)?.groupValues?.get(1)
            ?: error("$table is not a table in gen_contour.py")
        return Regex("'${Regex.escape(key)}': ([\\d.]+)").find(body)?.groupValues?.get(1)?.toFloat()
            ?: error("«$key» is not in $table")
    }

    private fun assertAdvance(what: String, ours: Float, theirs: Float) {
        assertEquals(
            "$what: we say $ours, the board says $theirs",
            theirs,
            ours,
            theirs * ADVANCE_TOLERANCE,
        )
    }

    private fun closeTo(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) <= TOLERANCE

    private fun board(): String = read("ClusterContour.dc.html")

    private fun planBoard(): String = read("ClusterContourPlan.dc.html")

    private fun generator(): String = read("gen_contour.py")

    private fun read(name: String): String = cached.getOrPut(name) {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })) { it.parentFile }
            .map { File(it, "tools/design-canvas/$name") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$name not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The boards write their numbers to a tenth. */
        const val TOLERANCE = 0.05f

        /**
         * And the advances get two per cent.
         *
         * Chrome's Roboto and the car's Roboto are two fonts with one name. A cell that is as wide
         * as its own caption should follow the face it is actually set in; what must not drift is
         * the arithmetic between the two, which is everything else on this page.
         */
        const val ADVANCE_TOLERANCE = 0.02f

        const val BLUE = "#2D82D7"
        const val ORANGE = "#FF9F19"
        const val RED = "#FF4046"

        val cached = mutableMapOf<String, String>()

        val FRAME = Regex("""<svg width="([\d.]+)" height="([\d.]+)" viewBox=""")
        val LINE = Regex(
            """<line x1="([-\d.]+)" y1="([-\d.]+)" x2="([-\d.]+)" y2="([-\d.]+)" """ +
                """stroke="(#[0-9A-F]{6})" stroke-width="([\d.]+)"""",
        )
        val RECT = Regex(
            """<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="([^"]+)"""",
        )
        val OUTLINE = Regex(
            """<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="none" """ +
                """stroke="(#[0-9A-F]{6})"""",
        )
        val ELLIPSE = Regex(
            """<ellipse cx="([\d.]+)" cy="([\d.]+)" rx="([\d.]+)" ry="([\d.]+)" fill="url\(#bandglow\)"""",
        )
        val GLOW_STOP = Regex("""<stop offset="0" stop-color="#DAE1EB" stop-opacity="([\d.]+)"""")
    }
}
