package dev.denza.apps.feature.cluster

import android.content.ContextWrapper
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClusterPresentation's own code, run on the unit tests' android.jar.
 *
 * That jar builds Android objects but answers every framework method with "Method ... not
 * mocked". A presentation can therefore be constructed and its glue driven, and a call that
 * reaches the framework - Dialog.dismiss, which takes the window down - shows itself as that
 * refusal. onCreate cannot run here (Dialog.onCreate is the framework's), so what it builds is
 * held through [ClusterPresentation.buildViews], which it calls.
 */
class ClusterPresentationGlueTest {
    private val rec = SceneRecorder()
    private val teardown = FakeTeardownThread(rec)

    /**
     * A dismiss nobody in this app asked for - the platform's own, when the display goes away -
     * must still let AVC go: the layer schedules freeDisplay, and only then does the window come
     * down through Dialog.dismiss. A dismiss straight to the framework would leave our Surface in
     * AVC's field with nothing to free it, the state the next stock PIP crashes AVC from.
     */
    @Test
    fun aDismissFromAnyoneGoesThroughTheLayerAndOnlyItRemovesTheWindow() {
        val presentation = presentation(cameraLayer = true)
        presentation.layer.attach(FakeRenderer("camera", rec).also { it.events = presentation.layer })

        val refused = runCatching { presentation.dismiss() }.exceptionOrNull()

        assertTrue(
            "the window is removed by Dialog.dismiss itself: $refused",
            refused?.message.orEmpty().startsWith("Method dismiss in android.app."),
        )
        assertEquals(
            listOf("main: camera.renderer.hasLocalSurfaceHandle -> false", "main: teardown.post"),
            rec.take(),
        )
        teardown.runAll()
        assertEquals(listOf("teardown: I vendor display freed in 0ms"), rec.logs())
    }

    @Test
    fun eachPresentationBuildsItsOwnLayerKindsViews() {
        assertEquals(
            listOf(SceneView.CAMERA, SceneView.DIAGNOSTIC),
            presentation(cameraLayer = true).builtViews(),
        )
        assertEquals(
            SceneView.stackFor(cameraLayer = false),
            presentation(cameraLayer = false).builtViews(),
        )
    }

    @Test
    fun theLayerCarriesTheDisplayItWasOpenedOn() {
        assertEquals(7, presentation(cameraLayer = true).layer.displayId)
    }

    @Test
    fun theRenderersListenerPassesEachEventToItsOwnCounterpart() {
        val listener = object : CameraRendererEvents {
            override fun onReady(details: String) = rec.record("ready $details")
            override fun onFailure(details: String) = rec.record("failure $details")
            override fun onLocalSurfaceReleased() = rec.record("local surface released")
            override fun onFirstFrame(details: String) = rec.record("first frame $details")
        }.asAvcListener()

        listener.onReady("r")
        listener.onFailure("f")
        listener.onLocalSurfaceReleased()
        listener.onFirstFrame("ff")

        assertEquals(
            listOf("main: ready r", "main: failure f", "main: local surface released", "main: first frame ff"),
            rec.take(),
        )
    }

    @Test
    fun theRendererSeamPassesEachCallToItsOwnCounterpart() {
        val renderer = cameraRendererOf(
            startRenderer = { viewpoint, processing -> rec.record("start $viewpoint $processing") },
            stopRenderer = { rec.record("stop") },
            holdsLocalSurface = { rec.record("holds"); true },
        )

        renderer.start(3205, false)
        renderer.stop()
        val holds = renderer.hasLocalSurfaceHandle()

        assertTrue(holds)
        assertEquals(listOf("main: start 3205 false", "main: stop", "main: holds"), rec.take())
    }

    private fun ClusterPresentation.builtViews(): List<SceneView> =
        mutableListOf<SceneView>().also { built -> buildViews { built += it } }

    private fun presentation(cameraLayer: Boolean) = ClusterPresentation(
        context = ContextWrapper(null),
        display = unitTestDisplay(),
        displayId = 7,
        events = NoAvcEvents,
        cameraLayer = cameraLayer,
        teardownThread = teardown,
        clock = SceneClock { 0L },
        log = RecordingLog(rec),
    )

    /** The android.jar's Display keeps its no-argument constructor package-private. */
    private fun unitTestDisplay(): Display =
        Display::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private object NoAvcEvents : AvcEvents {
        override fun onAvcReady(commandGeneration: Long, details: String) = Unit
        override fun onAvcFailure(commandGeneration: Long, details: String) = Unit
        override fun onAvcFirstFrame(commandGeneration: Long, details: String) = Unit
    }
}
