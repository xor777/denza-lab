package dev.denza.apps.design.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyScaleTest {

    @Test
    fun restingPowerHoldsTheNeedleAtZero() {
        assertEquals(0f, EnergyScale.sweepFraction(0f), 0f)
        assertEquals(0f, EnergyScale.sweepFraction(0.4f), 0f)
        assertEquals(0f, EnergyScale.sweepFraction(-0.4f), 0f)
        assertFalse(EnergyScale.isRegenerating(-0.4f))
    }

    @Test
    fun fullDeflectionIsReachedAtEachSidesOwnSpan() {
        assertEquals(1f, EnergyScale.sweepFraction(EnergyScale.FULL_DISCHARGE_KW), 1e-4f)
        assertEquals(1f, EnergyScale.sweepFraction(-EnergyScale.FULL_REGEN_KW), 1e-4f)
    }

    @Test
    fun beyondFullDeflectionClampsInsteadOfRunningOff() {
        assertEquals(1f, EnergyScale.sweepFraction(900f), 1e-4f)
        assertEquals(1f, EnergyScale.sweepFraction(-400f), 1e-4f)
    }

    @Test
    fun theScaleIsSquareRootSoTheCommonCaseIsReadable() {
        // A quarter of full power sits at half the sweep, not a quarter of it.
        assertEquals(0.5f, EnergyScale.sweepFraction(EnergyScale.FULL_DISCHARGE_KW / 4f), 1e-4f)
        // 34 kW - an ordinary cruise - still clears a third of the dial.
        assertTrue(EnergyScale.sweepFraction(34f) > 0.33f)
    }

    @Test
    fun regenerationUsesItsOwnNarrowerSpan() {
        // Same magnitude, different side: recovery reads much further along its shorter scale.
        val spending = EnergyScale.sweepFraction(20f)
        val recovery = EnergyScale.sweepFraction(-20f)
        assertTrue(recovery > spending)
        assertTrue(EnergyScale.isRegenerating(-20f))
        assertFalse(EnergyScale.isRegenerating(20f))
    }

    @Test
    fun dischargeAndRegenerationLeaveTheTopInOppositeDirections() {
        val top = 90f
        val sweep = 110f
        assertEquals(top, EnergyScale.angleDegrees(0f, top, sweep), 1e-4f)

        val spending = EnergyScale.angleDegrees(300f, top, sweep)
        val recovery = EnergyScale.angleDegrees(-100f, top, sweep)
        assertEquals(top - sweep, spending, 1e-4f)
        assertEquals(top + sweep, recovery, 1e-4f)
    }
}
