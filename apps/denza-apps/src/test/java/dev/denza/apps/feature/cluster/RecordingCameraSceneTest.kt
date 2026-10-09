package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording fake is what the camera scenarios are measured against, so the Android behaviour
 * it stands in for is held here: the looper's order, what a handler's removeAll reaches, which
 * thread a call is recorded on, and when the renderer's Surface comes and goes.
 */
class RecordingCameraSceneTest {
    private val rec = SceneRecorder()

    @Test
    fun theLooperRunsWhatIsDueInTheOrderItWasPosted() {
        val main = FakeMain(rec)
        val handler = main.handler()
        handler.postDelayed(Runnable { rec.record("late") }, 100L)
        main.post { rec.record("first") }
        handler.postDelayed(Runnable { rec.record("second") }, 0L)

        main.runDue()
        assertEquals(
            listOf(
                "main: handler.postDelayed 100",
                "main: looper.post",
                "main: handler.postDelayed 0",
                "main: first",
                "main: second",
            ),
            rec.take(),
        )
        main.advance(99L)
        assertEquals(emptyList<String>(), rec.take())
        main.advance(1L)
        assertEquals(listOf("main: late"), rec.take())
        assertEquals(100L, main.now)
    }

    @Test
    fun aHandlersRemoveAllReachesOnlyWhatThatHandlerPosted() {
        val main = FakeMain(rec)
        val service = main.handler("service")
        val other = main.handler("other")
        service.postDelayed(Runnable { rec.record("timer") }, 10L)
        other.postDelayed(Runnable { rec.record("other") }, 10L)
        rec.on("monitor") { main.post { rec.record("hide") } }

        service.removeAll()
        main.advance(10L)

        assertEquals(
            listOf(
                "main: service.postDelayed 10",
                "main: other.postDelayed 10",
                "monitor: looper.post",
                "main: service.removeAll",
                "main: hide",
                "main: other",
            ),
            rec.take(),
        )
    }

    @Test
    fun theTeardownThreadRunsItsQueueAsItself() {
        val teardown = FakeTeardownThread(rec)
        teardown.post { rec.record("free") }
        assertEquals(1, teardown.pending)

        teardown.runAll()

        assertEquals(listOf("main: teardown.post", "teardown: free"), rec.take())
        assertEquals(0, teardown.pending)
    }

    @Test
    fun theRenderersSurfaceFollowsItsTextureOnceItListens() {
        val renderer = FakeRenderer("camera", rec)
        val released = mutableListOf<String>()
        renderer.events = object : CameraRendererEvents {
            override fun onReady(details: String) = Unit
            override fun onFailure(details: String) = Unit
            override fun onLocalSurfaceReleased() {
                released += rec.thread
            }
            override fun onFirstFrame(details: String) = Unit
        }

        renderer.textureAvailable()
        assertFalse("no listener before start", renderer.hasLocalSurfaceHandle())
        renderer.start(3205, true)
        assertTrue("an available texture is taken at once", renderer.hasLocalSurfaceHandle())
        renderer.stop()
        renderer.stop()
        assertEquals("stop reports a surface only once", listOf("main"), released)
        renderer.textureDestroyed()
        assertEquals("the texture's end is always reported", listOf("main", "main"), released)
    }
}
