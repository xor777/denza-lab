package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.EnergyScale
import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.design.instrument.InstrumentWeight
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.VehicleConvention
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
            ContourReadout.CAPTION_SPREAD,
            ContourReadout.CAPTION_REGEN,
            ContourReadout.CAPTION_ENGINE_GAVE,
            ContourReadout.CAPTION_TRIP,
            ContourReadout.LEGEND_INTO_PACK,
            ContourReadout.LEGEND_INTO_PACK_SHORT,
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
        // And the widest that unit ever is, which is the window still filling. It decides no
        // coordinate - the unit is left-aligned and nothing hangs off it - but it is what the
        // clearance to the petal's cut-out is taken against.
        assertAdvance(
            "«${ContourReadout.UNIT_PER_100KM_FILLING}»",
            ContourType.BOARD.width(ContourReadout.UNIT_PER_100KM_FILLING, InstrumentFace.UNIT),
            python(generator, "W_PETAL_FILLING"),
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
            text(board, "cl", ContourReadout.CAPTION_TRIP).second,
            TOLERANCE,
        )
        assertEquals(plan.shelfFigureBaseline, text(board, "rd", "9,3").second, TOLERANCE)
    }

    @Test
    fun theTemperatureCellsAreTheBoardsCells() {
        val cells = planCells(BLUE)
        // Six cells at the shelf's own top: five identical ones for the readings, and the
        // exception's behind them, which is the only one still sized by a caption.
        plan.leftCells.forEachIndexed { index, width ->
            val left = plan.leftCell(index)
            assertTrue(
                "cell $index at $left x $width is not on the plan board; it draws " +
                    cells.filter { closeTo(it.y, plan.guardTop) }.joinToString { "${it.x}x${it.width}" },
                cells.any { closeTo(it.x, left) && closeTo(it.width, width) },
            )
        }
        // And the box each glyph stands in, one unit inside its own cell.
        repeat(ContourGlyphs.Glyph.entries.size) { index ->
            val left = plan.leftCell(index) + plan.glyphInset
            assertTrue(
                "the glyph box of cell $index at $left",
                cells.any {
                    closeTo(it.x, left) &&
                        closeTo(it.y, plan.glyphBaseline - ContourGlyphs.HEIGHT) &&
                        closeTo(it.width, ContourGlyphs.WIDTH) &&
                        closeTo(it.height, ContourGlyphs.HEIGHT)
                },
            )
        }
    }

    // ---- the five glyphs, which are what the temperature row is named by

    @Test
    fun theTemperatureRowIsFiveGlyphsAndOneWordThatMeansTrouble() {
        assertEquals("the family", 5, ContourGlyphs.Glyph.entries.size)
        assertEquals("five readings and the exception", 6, plan.leftCells.size)
        plan.leftCells.take(ContourGlyphs.Glyph.entries.size).forEach {
            assertEquals("every reading's cell is the same width", plan.temperatureCell, it, TOLERANCE)
        }
        assertEquals(plan.spreadCell, plan.leftCells[plan.spreadCellIndex], TOLERANCE)
        // A glyph is narrower than two digits and a degree sign, so the figure sets the cell.
        assertEquals(
            "the cell is the wider of its payload and its glyph",
            plan.temperatureField + plan.degreeWidth,
            plan.temperatureCell,
            TOLERANCE,
        )
        assertTrue(
            "and the glyph is the narrower: ${ContourGlyphs.WIDTH} against ${plan.temperatureCell}",
            ContourGlyphs.WIDTH < plan.temperatureCell,
        )
        // Every cell carries its own «°» now - five of them on the calm board, where three motors
        // used to share one at the end of their run.
        assertEquals("one degree sign per cell", 5, board().split(">°</text>").size - 1)
        // The three words are gone from both records, which is what makes a word in this row mean
        // that the pack is misbehaving.
        val generator = generator()
        listOf("БАТАРЕЯ", "МОТОРЫ", "ИНВЕРТОР").forEach {
            assertTrue("«$it» is still a caption in gen_contour.py", !generator.contains("'$it'"))
        }
    }

    @Test
    fun theGlyphsStandOnTheCaptionBaselineAtTwentyFourUnits() {
        assertEquals("5.1 mm of glass, not the caption's 2.7", 24f, ContourGlyphs.HEIGHT, 1e-4f)
        assertEquals(
            "they stand on the caption baseline rather than hanging from it",
            plan.shelfCaptionBaseline,
            plan.glyphBaseline,
            1e-4f,
        )
        assertTrue(
            "and clear the figures' own baseline: " +
                "${plan.glyphBaseline - ContourGlyphs.HEIGHT - plan.shelfFigureBaseline}",
            plan.glyphBaseline - ContourGlyphs.HEIGHT - plan.shelfFigureBaseline >= 8f,
        )
        // Every proportion is in caption units, so the family scales with its height alone.
        assertEquals(24f / InstrumentFace.CAPTION.size, ContourGlyphs.K, 1e-4f)
        assertEquals(17 * ContourGlyphs.K, ContourGlyphs.WIDTH, 1e-4f)
        assertTrue(generator().contains("GLYPH = STEP * 3"))
        assertTrue(generator().contains("GLYPH_K = GLYPH / CAPTION"))
    }

    @Test
    fun thePackIsACaseWithATerminalAndOneLitCellInside() {
        val boxes = boxes(board())
        val x = plan.leftCell(0) + plan.glyphInset
        val top = ContourGlyphs.packTop(plan.glyphBaseline)
        assertBox(
            "the case", boxes,
            Box(
                x, top, ContourGlyphs.PACK_WIDTH - ContourGlyphs.PACK_NUB, ContourGlyphs.PACK_HEIGHT,
                ContourGlyphs.PACK_RADIUS, MUTED, ContourGlyphs.STROKE,
            ),
        )
        assertBox(
            "the terminal, which is part of what makes it a battery", boxes,
            Box(
                x + ContourGlyphs.PACK_WIDTH - ContourGlyphs.PACK_NUB,
                top + ContourGlyphs.PACK_NUB_INSET,
                ContourGlyphs.PACK_NUB,
                ContourGlyphs.PACK_HEIGHT - 2 * ContourGlyphs.PACK_NUB_INSET,
                0f, MUTED, 0f,
            ),
        )
        assertBox(
            "and the cell inside it, which is the only part that carries colour", boxes,
            Box(
                x + ContourGlyphs.PACK_CELL_INSET,
                top + ContourGlyphs.PACK_CELL_INSET,
                ContourGlyphs.PACK_WIDTH - ContourGlyphs.PACK_CELL_TRIM,
                ContourGlyphs.PACK_HEIGHT - 2 * ContourGlyphs.PACK_CELL_INSET,
                0f, INK, 0f,
            ),
        )
    }

    @Test
    fun aMotorIsABlockOnAnAxleAndEveryWheelIsHollow() {
        val boxes = boxes(board())
        val baseline = plan.glyphBaseline
        MOTORS.forEachIndexed { offset, glyph ->
            val x = plan.leftCell(offset + 1) + plan.glyphInset
            assertBox(
                "the body of $glyph", boxes,
                Box(
                    x + ContourGlyphs.BODY_X, ContourGlyphs.bodyTop(baseline),
                    ContourGlyphs.BODY_WIDTH, ContourGlyphs.BODY_HEIGHT,
                    ContourGlyphs.BODY_RADIUS, MUTED, ContourGlyphs.STROKE,
                ),
            )
            // Four wheels, outlines every one of them and lighter than the case they stand off:
            // the motor is the motor, not the wheel it drives.
            listOf(false, true).forEach { right ->
                listOf(false, true).forEach { rear ->
                    assertBox(
                        "a wheel of $glyph", boxes,
                        Box(
                            ContourGlyphs.wheelX(x, right), ContourGlyphs.wheelTop(baseline, rear),
                            ContourGlyphs.WHEEL_WIDTH, ContourGlyphs.WHEEL_HEIGHT,
                            ContourGlyphs.WHEEL_RADIUS, MUTED, ContourGlyphs.WHEEL_STROKE,
                        ),
                    )
                }
            }
            val rear = glyph != ContourGlyphs.Glyph.MOTOR_FRONT
            assertBox(
                "the block of $glyph", boxes,
                Box(
                    ContourGlyphs.motorLeft(x, glyph), ContourGlyphs.motorTop(baseline, rear),
                    ContourGlyphs.motorWidth(glyph), ContourGlyphs.MOTOR_HEIGHT,
                    ContourGlyphs.MOTOR_RADIUS, INK, 0f,
                ),
            )
        }
        // The front's bar crosses the whole axle; the rear pair are half bars either side of it.
        val front = ContourGlyphs.motorWidth(ContourGlyphs.Glyph.MOTOR_FRONT)
        val half = ContourGlyphs.motorWidth(ContourGlyphs.Glyph.MOTOR_REAR_LEFT)
        assertTrue("the front bar is the wide one: $front against $half", front > 2 * half)
        assertTrue(
            "and the rear pair differ by side",
            ContourGlyphs.motorLeft(0f, ContourGlyphs.Glyph.MOTOR_REAR_RIGHT) >
                ContourGlyphs.motorLeft(0f, ContourGlyphs.Glyph.MOTOR_REAR_LEFT),
        )
        assertTrue(
            "and both rear axles, which is not where the front block sits",
            ContourGlyphs.motorTop(plan.glyphBaseline, rear = true) >
                ContourGlyphs.motorTop(plan.glyphBaseline, rear = false),
        )
    }

    @Test
    fun theInverterIsACaseWithOnePeriodOfItsOwnCurrentInIt() {
        val board = board()
        val x = plan.leftCell(4) + plan.glyphInset
        val top = ContourGlyphs.inverterTop(plan.glyphBaseline)
        assertBox(
            "the case", boxes(board),
            Box(
                x, top, ContourGlyphs.INVERTER_SIZE, ContourGlyphs.INVERTER_SIZE,
                ContourGlyphs.INVERTER_RADIUS, MUTED, ContourGlyphs.STROKE,
            ),
        )
        val wave = WAVE.find(board) ?: error("no wave inside the inverter on the board")
        assertEquals("it starts inside the case", x + ContourGlyphs.WAVE_INSET, wave.groupValues[1].toFloat(), TOLERANCE)
        assertEquals(
            "on the case's own middle",
            top + ContourGlyphs.INVERTER_SIZE / 2f,
            wave.groupValues[2].toFloat(),
            TOLERANCE,
        )
        assertEquals("in the component's colour", INK, wave.groupValues[3])
        assertEquals("at the data weight", ContourGlyphs.STROKE, wave.groupValues[4].toFloat(), TOLERANCE)
        assertEquals("one period in twenty steps", 20, ContourGlyphs.WAVE_SAMPLES)
        assertTrue(generator().contains("WAVE_SAMPLES = 20"))
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
        assertEquals("width", plan.engineBoxWidth, box.width, TOLERANCE)
        assertEquals("height", plan.engineBoxBottom - plan.engineBoxTop, box.height, TOLERANCE)
        assertEquals("120 one-second slots", 120, plan.engineSlots)
        // Drawn as twenty-four steps of five seconds, so a full box is 24 pitches wide rather than
        // 119 gaps between points.
        assertEquals("steps", 24, plan.engineBins)
        assertEquals("seconds in one", 5, plan.engineBinSeconds)
        assertEquals("and its pitch", plan.engineBoxWidth / 24f, plan.enginePitch, 1e-4f)
        assertTrue(generator().contains("ENGINE_BIN_SECONDS = 5"))
        // Generation alone, linear to 30 kW, on the boards and in the readout both.
        assertTrue(generator().contains("ENGINE_GEN_FULL = 30."))
        assertTrue("no revolutions anywhere in the box", !generator().contains("ENGINE_RPM_FULL"))
        assertEquals(30f, plan.engineGenerationFull, 1e-4f)
    }

    @Test
    fun theEngineBoxSpeaksOneSentenceLaidOutFromTheShelfsEdge() {
        val board = states()
        // «● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 1:22»: the window against the edge, the unit and the
        // figure's reserve field to its left, the dot at the head of the whole phrase. The board's
        // generating state is 82 seconds in and the phrase says so - it used to say two minutes,
        // which is the box's capacity rather than its reach.
        assertEquals(
            "the window",
            plan.legendWindowX,
            text(board, "cl", RUNNING_WINDOW).first,
            TOLERANCE,
        )
        assertEquals(
            "its baseline",
            plan.engineLegendBaseline,
            text(board, "cl", RUNNING_WINDOW).second,
            TOLERANCE,
        )
        assertEquals("«кВт»", plan.legendUnitX, text(board, "un", ContourReadout.UNIT_KW).first, TOLERANCE)
        assertEquals("the figure's field", plan.legendFigureRight, text(board, "un", "14").first, TOLERANCE)
        val dot = DOT.findAll(board).map { it.groupValues[1].toFloat() }.toList()
        assertTrue("the marker at ${plan.legendMarkX}: $dot", dot.any { closeTo(it, plan.legendMarkX) })
        assertEquals(
            "the long window fits this face",
            ContourReadout.LEGEND_INTO_PACK,
            plan.legendWindow,
        )
    }

    @Test
    fun theWindowIsTheBoxsOwnReachAndItMovesNoAnchorInFrontOfIt() {
        // The board draws two reaches: 82 seconds under a box seventeen steps wide, and the full
        // two minutes under a box that has filled. Both are laid out right to left off the shelf's
        // edge and both land on the same x, because the figures are tabular and a «м:сс» is four
        // glyphs and a mark - which is what lets one measured template decide every anchor in the
        // phrase while the duration inside it counts up.
        val board = states()
        assertEquals("the board draws the box's own 82 s", "В БАТАРЕЮ · ПОСЛЕДНИЕ 1:22", RUNNING_WINDOW)
        assertEquals("and a filled box's two minutes", "В БАТАРЕЮ · ПОСЛЕДНИЕ 2:00", QUIET_WINDOW)
        listOf(RUNNING_WINDOW, QUIET_WINDOW).forEach { phrase ->
            everyText(board, "cl", phrase).forEach { (x, _) ->
                assertEquals("«$phrase» is on the window's anchor", plan.legendWindowX, x, TOLERANCE)
            }
        }
        assertEquals(
            "and the literal two minutes is off the board",
            0,
            Regex("ПОСЛЕДНИЕ 2 МИН").findAll(board).count(),
        )
    }

    @Test
    fun theSentenceClosesUpOnceTheFigureLeavesWithTheEngine() {
        // The states board draws both: the engine generating at 82 s, and the engine forty seconds
        // dead with its box still up. The words are on the same anchor in both; only the dot moves.
        val dots = DOT.findAll(states()).map { it.groupValues[1].toFloat() }.toList()
        assertTrue(
            "the dot at ${plan.legendMarkX} while the engine runs: $dots",
            dots.any { closeTo(it, plan.legendMarkX) },
        )
        assertTrue(
            "and at ${plan.legendMarkQuietX} once it has stopped: $dots",
            dots.any { closeTo(it, plan.legendMarkQuietX) },
        )
        assertEquals(
            "which is exactly the reserve, its unit and both gaps",
            plan.generationField + plan.smallGap + plan.kilowattWidth + plan.smallGap,
            plan.legendMarkQuietX - plan.legendMarkX,
            TOLERANCE,
        )
        assertEquals(
            "the words do not move between the two",
            plan.legendWindowX,
            text(states(), "cl", QUIET_WINDOW).first,
            TOLERANCE,
        )
    }

    @Test
    fun theEnginesShareIsDrawnTheWayTheConventionSaysItMayBe() {
        // Whether GENERATION_KW is already inside POWER_KW decides whether the engine's share may be
        // a seam behind the band's tip or has to be a separate line under it, and for a while the
        // two records disagreed: the renderer said one thing in a private constant and the generator
        // defaulted the other way, so the board's canonical engine state drew the one picture the app
        // never draws. There is one decision now, and this is the join.
        val generator = generator()
        assertTrue(
            "the board's default is the app's assumption",
            generator.contains("s.get('seam_on_band', False)"),
        )
        assertTrue(
            "and the assumption is that generation is already inside pack power",
            VehicleConvention.GENERATION_INSIDE_PACK_POWER,
        )
        // Both drawings are energy arriving at the pack, so both are measured on the return side's
        // own span: a positive argument to `sweep` picks the 300 kW discharge span and made 14 kW of
        // generation 1.73 times shorter than 14 kW of regeneration on the band above it.
        assertTrue(
            "the line under the band is on the return scale",
            generator.contains("far = AXIS + sweep(-generation) * BAND_HALF"),
        )
        assertTrue(
            "and the two spans really are different, which is what made the drawing wrong",
            EnergyScale.sweepFraction(-14f) > EnergyScale.sweepFraction(14f),
        )
    }

    @Test
    fun thePetalBoxIsTwoHundredAndThirtyTwoByTheFiguresOwnHeight() {
        val box = planCells(ORANGE).first { closeTo(it.y, plan.petalBoxTop) }
        assertEquals("left", plan.petalBoxLeft, box.x, TOLERANCE)
        assertEquals("width", plan.petalBoxWidth, box.width, TOLERANCE)
        assertEquals("height", plan.petalBoxHeight, box.height, TOLERANCE)
        assertEquals(232f, plan.petalBoxWidth, TOLERANCE)
        // The cap of the 52 beside it plus a descender: 36.92 + 13.
        assertEquals(49.92f, plan.petalBoxHeight, TOLERANCE)
        assertEquals("thirty buckets of the log's own hundred metres", 30, plan.petalBuckets)
    }

    @Test
    fun thePetalsZeroIsTheFiguresOwnBaseline() {
        val zero = LINE.findAll(board()).first { closeTo(it.groupValues[1].toFloat(), plan.petalBoxLeft) }
        assertEquals(plan.petalZeroY, zero.groupValues[2].toFloat(), TOLERANCE)
        assertEquals(
            plan.petalBoxLeft + plan.petalBoxWidth,
            zero.groupValues[3].toFloat(),
            TOLERANCE,
        )
        assertEquals("the zero line is where the figure stands", plan.petalBaseline, plan.petalZeroY, 1e-4f)
        assertEquals(30f, plan.petalFull, 1e-4f)
        assertEquals("and the descender holds the return", 10f, plan.petalReturnFull, 1e-4f)
        assertTrue(generator().contains("PETAL_FULL = 30."))
        assertTrue(generator().contains("PETAL_RETURN_FULL = 10."))
        assertTrue("the zero share is gone with the ladder it set", !generator().contains("PETAL_ZERO_SHARE"))
    }

    @Test
    fun thePetalDrawsItsReturnInBlueOnlyWhereItHappened() {
        // The board's calm history has one run of return buckets in it, so the drawing carries
        // exactly one blue patch - filled at 50 % and edged in RETURN_INK - and no blue anywhere
        // along the zero line. The grey field is drawn once, across all thirty buckets.
        val board = board()
        val patches = PATH_FILL.findAll(board).filter { it.groupValues[1] == BLUE }.toList()
        assertEquals("one blue patch for the one run of return buckets", 1, patches.size)
        assertEquals(
            ContourPlan.RETURN_AREA_ALPHA,
            patches[0].groupValues[2].toFloat(),
            1e-4f,
        )
        assertTrue(
            "and its edge is the lighter blue",
            PATH_STROKE.findAll(board).any { it.groupValues[1] == RETURN_INK },
        )
        assertEquals(
            "the spending field is one shape",
            1,
            PATH_FILL.findAll(board).count { it.groupValues[1] == DEEP },
        )
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

    @Test
    fun theUnitNamesTheRoadTheFigureIsTheMeanOfWhileTheLogIsStillFilling() {
        // «за 3 км» is what the log holds when it *has* three kilometres. The states board draws a
        // history twelve buckets long, and under it the unit says 1,2 - a figure read against a
        // road it is not the mean of is the very thing this window was added to stop.
        val board = states()
        assertEquals("кВт·ч/100 км · за 1,2 км", FILLING_UNIT)
        assertEquals(
            "the filling unit is on the full one's anchor",
            plan.petalUnitX,
            text(board, "un", FILLING_UNIT).first,
            TOLERANCE,
        )
        // And the reserve is taken against the widest form, so the wider string still clears the
        // petal's cut-out rather than running into it.
        assertEquals(
            "the plan reserves the widest of the two",
            maxOf(
                ContourType.BOARD.width(ContourReadout.UNIT_PER_100KM, InstrumentFace.UNIT),
                ContourType.BOARD.width(ContourReadout.UNIT_PER_100KM_FILLING, InstrumentFace.UNIT),
            ),
            plan.petalUnitWidth,
            TOLERANCE,
        )
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

    /** One rectangle a glyph is made of, filled or outlined, with everything that decides it. */
    private data class Box(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val radius: Float,
        val color: String,
        val stroke: Float,
    )

    /** Every rectangle on [board], both kinds, in one list - a glyph is drawn from both. */
    private fun boxes(board: String): List<Box> =
        GLYPH_FRAME.findAll(board).map {
            Box(
                it.groupValues[1].toFloat(),
                it.groupValues[2].toFloat(),
                it.groupValues[3].toFloat(),
                it.groupValues[4].toFloat(),
                it.groupValues[5].toFloat(),
                it.groupValues[6],
                it.groupValues[7].toFloat(),
            )
        }.toList() +
            GLYPH_FILL.findAll(board).map {
                Box(
                    it.groupValues[1].toFloat(),
                    it.groupValues[2].toFloat(),
                    it.groupValues[3].toFloat(),
                    it.groupValues[4].toFloat(),
                    it.groupValues[6].ifEmpty { "0" }.toFloat(),
                    it.groupValues[5],
                    0f,
                )
            }.toList()

    private fun assertBox(what: String, boxes: List<Box>, want: Box) {
        assertTrue(
            "$what: $want is not drawn; near it the board has " +
                boxes.filter { kotlin.math.abs(it.x - want.x) < ContourGlyphs.WIDTH },
            boxes.any {
                closeTo(it.x, want.x) && closeTo(it.y, want.y) &&
                    closeTo(it.width, want.width) && closeTo(it.height, want.height) &&
                    closeTo(it.radius, want.radius) && it.color == want.color &&
                    closeTo(it.stroke, want.stroke)
            },
        )
    }

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
    private fun text(board: String, cssClass: String, content: String): Pair<Float, Float> =
        everyText(board, cssClass, content).firstOrNull()
            ?: error("«$content» is not set in .$cssClass on the board")

    /** And every place it is drawn, for a string a board sets in more than one state. */
    private fun everyText(
        board: String,
        cssClass: String,
        content: String,
    ): List<Pair<Float, Float>> =
        Regex(
            """<text class="$cssClass" x="([-\d.]+)" y="([-\d.]+)"[^>]*>${Regex.escape(content)}</text>""",
        ).findAll(board)
            .map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
            .toList()

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

    /** The scenes, which is the only board the engine's box is drawn on. */
    private fun states(): String = read("ClusterContourStates.dc.html")

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

        /**
         * The two windows the states board actually draws under the engine box.
         *
         * The phrase names the box's own reach rather than its capacity, so a board state is a
         * duration and not a literal. 82 seconds is the generating state, two minutes the box that
         * has filled and gone quiet.
         */
        val RUNNING_WINDOW = ContourReadout.intoPack(82, short = false)
        val QUIET_WINDOW = ContourReadout.intoPack(120, short = false)

        /** And the road the petal's figure is the mean of while the log is still filling. */
        val FILLING_UNIT = ContourReadout.perHundredKm(1.2, ConsumptionWindow.KM)

        const val BLUE = "#2D82D7"
        const val ORANGE = "#FF9F19"
        const val RED = "#FF4046"
        const val RETURN_INK = "#4B9BE0"
        const val DEEP = "#7C858F"

        /** A glyph's outline, and the component inside it in the ordinary case. */
        const val MUTED = "#86909B"
        const val INK = "#DAE1EB"

        /** The three cars, in the order the shelf draws them after the pack. */
        val MOTORS = listOf(
            ContourGlyphs.Glyph.MOTOR_FRONT,
            ContourGlyphs.Glyph.MOTOR_REAR_LEFT,
            ContourGlyphs.Glyph.MOTOR_REAR_RIGHT,
        )

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
        val DOT = Regex("""<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)" fill="(#[0-9A-F]{6})"/>""")
        val PATH_FILL = Regex("""<path d="[^"]+" fill="(#[0-9A-F]{6})" opacity="([\d.]+)"/>""")
        val PATH_STROKE = Regex("""<path d="[^"]+" fill="none" stroke="(#[0-9A-F]{6})"""")

        /** The two shapes a glyph is drawn from: an outlined rounded rectangle, and a filled one. */
        val GLYPH_FRAME = Regex(
            """<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)" """ +
                """rx="([\d.]+)" fill="none" stroke="(#[0-9A-F]{6})" stroke-width="([\d.]+)"/>""",
        )
        val GLYPH_FILL = Regex(
            """<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)" """ +
                """fill="(#[0-9A-F]{6})"(?: rx="([\d.]+)")?/>""",
        )

        /** And the one thing on the panel drawn with a round cap: the inverter's own current. */
        val WAVE = Regex(
            """<path d="M ([\d.]+) ([\d.]+) L[^"]*" fill="none" stroke="(#[0-9A-F]{6})" """ +
                """stroke-width="([\d.]+)" stroke-linejoin="round" stroke-linecap="round"/>""",
        )
    }
}
