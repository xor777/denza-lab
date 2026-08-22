package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `each press dispatches immediately without double-press recognition`() {
        val interceptor = SteeringWheelKeyInterceptor()
        var actions = 0
        val dispatch = SteeringWheelNavigationAction {
            actions += 1
            true
        }

        assertTrue(interceptor.onKeyEvent(true, 321, 0, 0, dispatch))
        assertTrue(interceptor.onKeyEvent(true, 321, 1, 0, dispatch))
        assertTrue(interceptor.onKeyEvent(true, 321, 0, 0, dispatch))
        assertTrue(interceptor.onKeyEvent(true, 321, 1, 0, dispatch))
        assertEquals(2, actions)
    }

    @Test
    fun `rejected navigation leaves the complete press to the stock handler`() {
        val interceptor = SteeringWheelKeyInterceptor()
        val reject = SteeringWheelNavigationAction { false }

        assertFalse(interceptor.onKeyEvent(true, 321, 0, 0, reject))
        assertFalse(interceptor.onKeyEvent(true, 321, 0, 1, reject))
        assertFalse(interceptor.onKeyEvent(true, 321, 1, 0, reject))
    }

    @Test
    fun `accepted navigation owns repeats and release without redispatching`() {
        val interceptor = SteeringWheelKeyInterceptor()
        var actions = 0
        val accept = SteeringWheelNavigationAction {
            actions += 1
            true
        }

        assertTrue(interceptor.onKeyEvent(true, 321, 0, 0, accept))
        assertTrue(interceptor.onKeyEvent(true, 321, 0, 1, accept))
        assertTrue(interceptor.onKeyEvent(true, 321, 1, 0, accept))
        assertEquals(1, actions)
    }

    @Test
    fun `disabling interception mid-press still owns the matching release`() {
        val interceptor = SteeringWheelKeyInterceptor()
        val accept = SteeringWheelNavigationAction { true }

        assertTrue(interceptor.onKeyEvent(true, 321, 0, 0, accept))
        assertTrue(interceptor.onKeyEvent(false, 321, 1, 0, accept))
        assertFalse(interceptor.onKeyEvent(false, 321, 1, 0, accept))
    }
}
