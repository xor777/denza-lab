package dev.denza.apps.feature.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelNavigationButtonTest {
    @Test
    fun `short custom-key down triggers once when enabled`() {
        assertTrue(
            SteeringWheelNavigationButton.shouldTrigger(
                enabled = true,
                keyCode = SteeringWheelNavigationButton.KEY_CODE,
                action = 0,
                repeatCount = 0,
            ),
        )
        assertFalse(
            SteeringWheelNavigationButton.shouldTrigger(
                enabled = true,
                keyCode = SteeringWheelNavigationButton.KEY_CODE,
                action = 1,
                repeatCount = 0,
            ),
        )
        assertFalse(
            SteeringWheelNavigationButton.shouldTrigger(
                enabled = true,
                keyCode = SteeringWheelNavigationButton.KEY_CODE,
                action = 0,
                repeatCount = 1,
            ),
        )
    }

    @Test
    fun `disabled option leaves the stock short key untouched`() {
        assertFalse(
            SteeringWheelNavigationButton.shouldConsume(
                enabled = false,
                keyCode = SteeringWheelNavigationButton.KEY_CODE,
            ),
        )
    }

    @Test
    fun `long press and unrelated keys are never consumed`() {
        assertFalse(
            SteeringWheelNavigationButton.shouldConsume(
                enabled = true,
                keyCode = 322,
            ),
        )
        assertFalse(
            SteeringWheelNavigationButton.shouldConsume(
                enabled = true,
                keyCode = 24,
            ),
        )
    }

    @Test
    fun `single press becomes one navigation action after timeout`() {
        val sequence = SteeringWheelPressSequence(timeoutMs = 500L)

        assertEquals(SteeringWheelPressResult(), sequence.onPress(nowMs = 1_000L))
        assertEquals(0, sequence.flushNavigationActions(nowMs = 1_499L))
        assertEquals(1, sequence.flushNavigationActions(nowMs = 1_500L))
    }

    @Test
    fun `triple press toggles camera without navigation actions`() {
        val sequence = SteeringWheelPressSequence(timeoutMs = 500L)

        assertFalse(sequence.onPress(nowMs = 1_000L).toggleDvrCamera)
        assertFalse(sequence.onPress(nowMs = 1_250L).toggleDvrCamera)
        val third = sequence.onPress(nowMs = 1_500L)

        assertTrue(third.toggleDvrCamera)
        assertEquals(0, third.navigationActionsBefore)
        assertEquals(0, sequence.flushNavigationActions(nowMs = 2_000L))
    }

    @Test
    fun `two presses retain two navigation actions when sequence expires`() {
        val sequence = SteeringWheelPressSequence(timeoutMs = 500L)

        sequence.onPress(nowMs = 1_000L)
        sequence.onPress(nowMs = 1_300L)

        assertEquals(0, sequence.flushNavigationActions(nowMs = 1_799L))
        assertEquals(2, sequence.flushNavigationActions(nowMs = 1_800L))
    }

    @Test
    fun `late press flushes the prior sequence and starts a new one`() {
        val sequence = SteeringWheelPressSequence(timeoutMs = 500L)

        sequence.onPress(nowMs = 1_000L)
        val late = sequence.onPress(nowMs = 1_700L)

        assertEquals(1, late.navigationActionsBefore)
        assertFalse(late.toggleDvrCamera)
        assertEquals(1, sequence.flushNavigationActions(nowMs = 2_200L))
    }
}
