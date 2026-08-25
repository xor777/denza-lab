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
    fun everySizeADensityUsesIsOnTheRamp() {
        listOf(InstrumentDensity.WIDE, InstrumentDensity.COMPACT).forEach { density ->
            density.sizes.forEach { size ->
                assertTrue("$size is not a rung of the ramp", size in InstrumentDensity.RAMP)
            }
        }
    }

    @Test
    fun noTwoRungsAreCloseEnoughToReadAsTheSameSize() {
        InstrumentDensity.RAMP.zipWithNext().forEach { (upper, lower) ->
            assertTrue("$upper and $lower are too close to tell apart", upper / lower >= 1.18f)
        }
    }

    @Test
    fun theNarrowDensityIsTheSameLadderLowerDownRatherThanASecondSet() {
        val wide = InstrumentDensity.WIDE
        val compact = InstrumentDensity.COMPACT
        wide.sizes.zip(compact.sizes).forEach { (big, small) ->
            assertTrue("$small should sit below $big on the ramp", small < big)
            assertTrue(
                "$small should be within two rungs of $big",
                InstrumentDensity.RAMP.indexOf(small) - InstrumentDensity.RAMP.indexOf(big) <= 2,
            )
        }
    }

    @Test
    fun aSectionTitleSitsBelowBodyTextBecauseItIsSetInCapitals() {
        listOf(InstrumentDensity.WIDE, InstrumentDensity.COMPACT).forEach { density ->
            assertTrue(density.title < density.body)
        }
    }

    @Test
    fun theRhythmIsWholeStepsAndNothingElse() {
        val density = InstrumentDensity.WIDE
        assertEquals(density.step, density.rhythm(1f), 1e-4f)
        assertEquals(density.step * 3f, density.rhythm(3f), 1e-4f)
    }
}
