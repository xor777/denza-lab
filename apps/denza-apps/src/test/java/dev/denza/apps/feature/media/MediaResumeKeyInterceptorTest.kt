package dev.denza.apps.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResumeKeyInterceptorTest {
    @Test
    fun `supported media keys map to direct commands`() {
        assertEquals(
            MediaResumeCommand.TOGGLE,
            MediaResumeKeyInterceptor.commandFor(85),
        )
        assertEquals(MediaResumeCommand.PLAY, MediaResumeKeyInterceptor.commandFor(126))
        assertEquals(MediaResumeCommand.PAUSE, MediaResumeKeyInterceptor.commandFor(127))
        assertEquals(
            MediaResumeCommand.TOGGLE,
            MediaResumeKeyInterceptor.commandFor(386),
        )
        assertEquals(null, MediaResumeKeyInterceptor.commandFor(334))
        assertEquals(null, MediaResumeKeyInterceptor.commandFor(335))
        assertEquals(null, MediaResumeKeyInterceptor.commandFor(79))
    }

    @Test
    fun `accepted press executes once and owns repeats and release`() {
        val interceptor = MediaResumeKeyInterceptor()
        var calls = 0
        val perform: (MediaResumeCommand) -> Boolean = {
            calls += 1
            true
        }

        assertTrue(interceptor.onKeyEvent(386, 0, 0, true, perform))
        assertTrue(interceptor.onKeyEvent(386, 0, 1, true, perform))
        assertTrue(interceptor.onKeyEvent(386, 0, 0, true, perform))
        assertTrue(interceptor.onKeyEvent(386, 1, 0, true, perform))
        assertEquals(1, calls)
    }

    @Test
    fun `owned release remains consumed after guard changes`() {
        val interceptor = MediaResumeKeyInterceptor()
        val accept: (MediaResumeCommand) -> Boolean = { true }

        assertTrue(interceptor.onKeyEvent(85, 0, 0, true, accept))
        assertTrue(interceptor.onKeyEvent(85, 1, 0, false, accept))
    }

    @Test
    fun `blocked or rejected press stays with stock handler`() {
        val interceptor = MediaResumeKeyInterceptor()
        var calls = 0
        val reject: (MediaResumeCommand) -> Boolean = {
            calls += 1
            false
        }

        assertFalse(interceptor.onKeyEvent(85, 0, 0, false, reject))
        assertFalse(interceptor.onKeyEvent(85, 1, 0, true, reject))
        assertFalse(interceptor.onKeyEvent(85, 0, 0, true, reject))
        assertFalse(interceptor.onKeyEvent(85, 0, 1, true, reject))
        assertFalse(interceptor.onKeyEvent(85, 1, 0, true, reject))
        assertEquals(1, calls)
    }
}
