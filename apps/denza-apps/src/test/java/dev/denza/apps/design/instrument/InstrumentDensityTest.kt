package dev.denza.apps.design.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the type ramp.
 *
 * An audit of the design boards found twenty-seven distinct type sizes where six were declared, and
 * eleven of them outside the declared set entirely. Nothing had gone wrong at any one step - each
 * board picked a size that looked right on that board. These tests are the only thing that makes
 * "pick from the ramp" a rule rather than an intention.
 */
class InstrumentDensityTest {

    @Test
    fun everySizeTheClusterUsesIsOnTheRamp() {
        InstrumentDensity.WIDE.sizes.forEach { size ->
            assertTrue("$size is not a rung of the ramp", size in InstrumentDensity.RAMP)
        }
    }

    @Test
    fun everyFaceIsSetAtARungAndNotBetweenTwo() {
        InstrumentFace.entries.forEach { face ->
            assertTrue("$face is set at ${face.size}", face.size in InstrumentDensity.RAMP)
        }
    }

    @Test
    fun noTwoRungsAreCloseEnoughToReadAsTheSameSize() {
        InstrumentDensity.RAMP.zipWithNext().forEach { (upper, lower) ->
            assertTrue("$upper and $lower are too close to tell apart", upper / lower >= 1.18f)
        }
    }

    @Test
    fun theHeroIsAVisibleStepAboveTheFigureRatherThanAnOctave() {
        // 104 was chosen against an arithmetic that made 52 look illegal, on a glass width nobody
        // had measured. 88 is 1.69 x 52, which clears this page's own rule with room, and at the
        // measured 320 mm it still reads at 61 arc minutes.
        val density = InstrumentDensity.WIDE
        assertEquals(1.692f, density.hero / density.figure, 1e-3f)
    }

    @Test
    fun aHeadingSharesItsRungWithTheCaptionUnderIt() {
        // It used to be a rung smaller, on the reasoning that capitals read larger. At the measured
        // distance that reasoning ran out: a rung under 18 is 9 arc minutes, which is board
        // furniture. Weight and tracking are what separate them now.
        val density = InstrumentDensity.WIDE
        assertEquals(density.body, density.title, 1e-4f)
        assertTrue(density.titleTracking > 0f)
        assertEquals(InstrumentWeight.MEDIUM, InstrumentFace.HEADING.weight)
        assertEquals(InstrumentWeight.REGULAR, InstrumentFace.CAPTION.weight)
        assertEquals(InstrumentFace.HEADING.size, InstrumentFace.CAPTION.size, 1e-4f)
    }

    @Test
    fun theHeroIsTheOnlyLightThingOnThePanel() {
        // Light at 34 puts a 1:11 stem on black, which is what CRITIQUE M3 found; at 88 it is the
        // one place the weight buys anything.
        assertEquals(InstrumentWeight.LIGHT, InstrumentFace.HERO.weight)
        InstrumentFace.entries.filter { it != InstrumentFace.HERO }.forEach {
            assertTrue("$it is ${it.weight}", it.weight != InstrumentWeight.LIGHT)
        }
    }

    @Test
    fun onlyHeadingsAndCaptionsAreTracked() {
        InstrumentFace.entries.forEach { face ->
            val tracked = face == InstrumentFace.HEADING || face == InstrumentFace.CAPTION
            assertEquals("$face", tracked, face.tracking > 0f)
        }
    }

    @Test
    fun aRungOfTheRampSubtendsTheArcMinutesTheGlassWasMeasuredFor() {
        // One board unit is 320 mm / 1507.56 units, a cap is 0.71 em, and one arc minute at 750 mm
        // is 0.2182 mm. ISO 15008 puts the floor at 20' and comfort at 30'.
        val unitMm = 320.0 / (424.0 * 2560.0 / 720.0)
        val arcMinuteMm = 750.0 * Math.tan(Math.toRadians(1.0 / 60.0))
        fun minutes(size: Float) = size * InstrumentFace.CAP_HEIGHT * unitMm / arcMinuteMm

        assertEquals(60.8, minutes(InstrumentDensity.WIDE.hero), 0.2)
        assertEquals(35.9, minutes(InstrumentDensity.WIDE.figure), 0.2)
        assertEquals(23.5, minutes(InstrumentDensity.WIDE.reading), 0.2)
        assertEquals(12.4, minutes(InstrumentDensity.WIDE.body), 0.2)
    }

    @Test
    fun theRhythmIsWholeStepsAndNothingElse() {
        val density = InstrumentDensity.WIDE
        assertEquals(density.step, density.rhythm(1f), 1e-4f)
        assertEquals(density.step * 3f, density.rhythm(3f), 1e-4f)
    }
}
