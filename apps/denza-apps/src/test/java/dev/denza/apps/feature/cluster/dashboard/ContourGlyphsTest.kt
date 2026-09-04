package dev.denza.apps.feature.cluster.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the five marks actually draw, read back off a recording surface.
 *
 * `ContourBoardContractTest` holds the family's arithmetic against the board, and that is a
 * different question from this one: the board proves the *numbers* agree, while every decision the
 * drawing makes with them - which axle a block sits on, whether a wheel is filled, which of the two
 * colours a part is given - used to live inside a `Canvas` call and was therefore invisible. A
 * mutation run put the front motor's block on the rear axle, filled all four wheels and painted the
 * block in the case's own colour, and 1092 tests stayed green through all three.
 *
 * So the family draws into [GlyphSurface] and this reads back what it drew. The four rules under
 * test are the owner's four, in his order.
 */
class ContourGlyphsTest {

    private val outline = 0x11111111
    private val component = 0x22222222

    /** One shape as the family stated it, in panel units. */
    private data class Shape(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        val colour: Int,
        /** Zero means filled: a plate is a frame with no stroke to speak of. */
        val stroke: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val filled: Boolean get() = stroke <= 0f
    }

    /** A surface that draws nothing and remembers everything. */
    private class Recorder : GlyphSurface {
        val shapes = mutableListOf<Shape>()
        var runs = 0
            private set
        var runColour = 0
            private set
        var runPoints = 0
            private set

        override fun frame(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
            stroke: Float,
        ) {
            shapes += Shape(left, top, right, bottom, radius, colour, stroke)
        }

        override fun plate(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
        ) {
            shapes += Shape(left, top, right, bottom, radius, colour, 0f)
        }

        override fun polyline(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
            colour: Int,
            stroke: Float,
        ) {
            runs++
            runColour = colour
            runPoints = count
        }
    }

    private val baseline = 200f
    private val x = 100f

    private fun record(glyph: ContourGlyphs.Glyph): Recorder {
        val recorder = Recorder()
        ContourGlyphs().draw(recorder, glyph, x, baseline, outline, component)
        return recorder
    }

    private fun Recorder.lit(): List<Shape> = shapes.filter { it.colour == component }

    /** The four marks of a wheel, found by their size rather than by their place in the list. */
    private fun Recorder.wheels(): List<Shape> = shapes.filter {
        kotlin.math.abs(it.width - ContourGlyphs.WHEEL_WIDTH) < 1e-4f &&
            kotlin.math.abs(it.height - ContourGlyphs.WHEEL_HEIGHT) < 1e-4f
    }

    // ---- «точно мотор с колёсами не путаешь?»

    @Test
    fun theFrontCarsBlockIsOnTheFrontAxleAndTheRearPairsAreOnTheRear() {
        val front = record(ContourGlyphs.Glyph.MOTOR_FRONT).lit().single()
        assertEquals(
            "the front block stands on the front axle",
            ContourGlyphs.motorTop(baseline, rear = false),
            front.top,
            1e-4f,
        )

        listOf(
            ContourGlyphs.Glyph.MOTOR_REAR_LEFT,
            ContourGlyphs.Glyph.MOTOR_REAR_RIGHT,
        ).forEach { glyph ->
            val block = record(glyph).lit().single()
            assertEquals(
                "$glyph stands on the rear axle",
                ContourGlyphs.motorTop(baseline, rear = true),
                block.top,
                1e-4f,
            )
        }

        // Which is the whole of what tells the three cells apart, so the two heights must differ.
        assertTrue(
            "the axles are two different lines",
            ContourGlyphs.motorTop(baseline, rear = true) >
                ContourGlyphs.motorTop(baseline, rear = false),
        )
    }

    @Test
    fun theRearPairAreHalfBarsOnOppositeSidesAndTheFrontIsOneBarAcross() {
        val front = record(ContourGlyphs.Glyph.MOTOR_FRONT).lit().single()
        val left = record(ContourGlyphs.Glyph.MOTOR_REAR_LEFT).lit().single()
        val right = record(ContourGlyphs.Glyph.MOTOR_REAR_RIGHT).lit().single()

        assertTrue("the front bar crosses the axle", front.width > left.width + right.width)
        assertTrue("and the rear pair sit either side of the middle", right.left > left.left)
        assertEquals("at the same width", left.width, right.width, 1e-4f)
    }

    // ---- «а колесо никогда не залито»

    @Test
    fun everyWheelIsHollowAndLighterThanTheCaseItStandsOff() {
        listOf(
            ContourGlyphs.Glyph.MOTOR_FRONT,
            ContourGlyphs.Glyph.MOTOR_REAR_LEFT,
            ContourGlyphs.Glyph.MOTOR_REAR_RIGHT,
        ).forEach { glyph ->
            val recorder = record(glyph)
            val wheels = recorder.wheels()
            assertEquals("$glyph has four wheels", 4, wheels.size)
            wheels.forEach { wheel ->
                assertTrue("a wheel of $glyph was filled", !wheel.filled)
                assertEquals(
                    "and drawn at the furniture weight",
                    ContourGlyphs.WHEEL_STROKE,
                    wheel.stroke,
                    1e-4f,
                )
                assertEquals("in the outline's colour", outline, wheel.colour)
            }
            // Four corners: two sides and two axles, and no two wheels in the same place.
            assertEquals(4, wheels.map { it.left to it.top }.toSet().size)
            assertTrue(
                "the wheels are lighter than the body they stand off",
                ContourGlyphs.WHEEL_STROKE < ContourGlyphs.STROKE,
            )
        }
    }

    // ---- «одна обводка, один компонент»

    @Test
    fun exactlyOnePartOfEveryMarkCarriesTheReadingsColour() {
        ContourGlyphs.Glyph.entries.forEach { glyph ->
            val recorder = record(glyph)
            val lit = recorder.lit().size +
                if (recorder.runs > 0 && recorder.runColour == component) 1 else 0
            assertEquals("$glyph lights exactly one part", 1, lit)
            recorder.shapes.forEach { shape ->
                assertTrue(
                    "$glyph drew a shape in neither colour",
                    shape.colour == outline || shape.colour == component,
                )
            }
        }
    }

    @Test
    fun theLitPartOfACarIsItsBlockAndTheCaseAndWheelsAreNot() {
        val recorder = record(ContourGlyphs.Glyph.MOTOR_FRONT)
        val block = recorder.lit().single()

        assertTrue("the block is filled, not outlined", block.filled)
        assertEquals(
            ContourGlyphs.motorWidth(ContourGlyphs.Glyph.MOTOR_FRONT),
            block.width,
            1e-4f,
        )
        assertEquals(ContourGlyphs.MOTOR_HEIGHT, block.height, 1e-4f)
        // And it is inside the body it belongs to, rather than beside it.
        val body = recorder.shapes.first { kotlin.math.abs(it.width - ContourGlyphs.BODY_WIDTH) < 1e-4f }
        assertTrue("the block is inside the body", block.left >= body.left && block.right <= body.right)
        assertEquals("the body is the case weight", ContourGlyphs.STROKE, body.stroke, 1e-4f)
        assertEquals(outline, body.colour)
    }

    @Test
    fun thePacksLitCellIsInsideItsCaseAndClearOfItsTerminal() {
        val recorder = record(ContourGlyphs.Glyph.PACK)
        val case = recorder.shapes.first { !it.filled }
        val cell = recorder.lit().single()

        assertEquals("the case is an outline at the data weight", ContourGlyphs.STROKE, case.stroke, 1e-4f)
        assertTrue("the cell is inside the case", cell.left > case.left && cell.top > case.top)
        assertTrue(cell.right < case.right && cell.bottom < case.bottom)

        // The terminal is the outline's own, because it is part of what makes it a battery.
        val terminal = recorder.shapes.single { it.filled && it.colour == outline }
        assertTrue("the terminal stands off the case", terminal.left >= case.right - 1e-4f)
    }

    @Test
    fun theInvertersCurrentIsTheLitPartAndItsCaseIsNot() {
        val recorder = record(ContourGlyphs.Glyph.INVERTER)

        assertEquals("one run of points", 1, recorder.runs)
        assertEquals("and it is the part that carries the reading", component, recorder.runColour)
        assertEquals(ContourGlyphs.WAVE_SAMPLES + 1, recorder.runPoints)
        assertTrue("nothing else in the mark is lit", recorder.lit().isEmpty())

        val case = recorder.shapes.single()
        assertEquals(outline, case.colour)
        assertEquals(ContourGlyphs.INVERTER_SIZE, case.width, 1e-4f)
        assertEquals(ContourGlyphs.INVERTER_SIZE, case.height, 1e-4f)
    }

    // ---- and the family scales with one number

    @Test
    fun everyMarkStandsOnItsBaselineAndInsideItsOwnHeight() {
        ContourGlyphs.Glyph.entries.forEach { glyph ->
            val recorder = record(glyph)
            assertNotNull("$glyph drew nothing", recorder.shapes.firstOrNull())
            val top = recorder.shapes.minOf { it.top }
            val bottom = recorder.shapes.maxOf { it.bottom }
            assertTrue(
                "$glyph reaches above its own box: $top against ${baseline - ContourGlyphs.HEIGHT}",
                top >= baseline - ContourGlyphs.HEIGHT - 1e-3f,
            )
            assertTrue("$glyph hangs below its baseline: $bottom", bottom <= baseline + 1e-3f)
        }
    }
}
