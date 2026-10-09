package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorSide.LEFT
import dev.denza.apps.feature.mirrors.MirrorsPosition.SIDES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which layer is which: the camera layer is opened and built as a camera layer and never carries
 * the map or the instruments, and the base layer keeps its stack.
 *
 * These replace the source-wiring checks of 44f02df5 (CameraSceneContentContractTest) and the
 * startup-notification check of bcaee0cd, which read ClusterSceneService's text. A camera start
 * cannot post a notification any more: the scene reaches Android only through
 * `CameraSceneSeams.kt`, none of which notifies, and CameraSceneControllerTest pins a Show's every
 * call.
 */
class CameraSceneLayersTest {
    private val h = CameraSceneHarness()

    @Test
    fun theCameraLayerBuildsNoMapShadeOrDashboard() {
        val camera = SceneView.stackFor(cameraLayer = true)

        assertFalse(SceneView.MAP_SURFACE in camera)
        assertFalse(SceneView.MAP_SHADE in camera)
        assertFalse(SceneView.DASHBOARD in camera)
        assertEquals(listOf(SceneView.CAMERA, SceneView.DIAGNOSTIC), camera)
    }

    /**
     * Map, its shade, the dashboard, then the camera frame and the diagnostic panels on top. The
     * base layer's camera frame is never shown, but onCreate makes the renderer from its
     * TextureView: without it the base presentation cannot open, and the instruments and the map
     * go with it.
     */
    @Test
    fun theBaseLayerStacksTheMapItsShadeAndTheDashboardUnderTheDiagnostic() {
        assertEquals(
            listOf(
                SceneView.MAP_SURFACE,
                SceneView.MAP_SHADE,
                SceneView.DASHBOARD,
                SceneView.CAMERA,
                SceneView.DIAGNOSTIC,
            ),
            SceneView.stackFor(cameraLayer = false),
        )
    }

    @Test
    fun eachLayerIsFoundAndOpenedAsItself() {
        h.show(LEFT)
        h.service.showPreview(SIDES, visible = true, durationMs = 2_200L, cameraOverlay = false)
        h.service.showDashboard(ClusterMapPlacement.FULL)

        val opened = h.rec.take().filter { it.contains("layers.") }
        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: layers.resolve base -> 3",
                "main: layers.open base display=3",
                "main: layers.resolve base -> 3",
            ),
            opened,
        )
    }

    @Test
    fun mapsAndDashboardsReachOnlyTheBaseLayer() {
        h.showReady(LEFT)
        h.service.hideMap()
        h.service.hideDashboard()
        assertTrue("no base layer is opened to hide something", h.layers.bases.isEmpty())
        h.rec.take()

        h.service.showDashboard(ClusterMapPlacement.FULL)
        h.service.prepareBaseScene()?.showMap(ClusterMapPlacement.RIGHT) { _, _, _, _ -> }
        h.service.hideMap()
        h.service.hideDashboard()

        val toLayers = h.rec.take().filter { "#" in it }
        assertEquals(
            listOf(
                "main: base#1.show",
                "main: base#1.showDashboard FULL",
                "main: base#1.showMap RIGHT",
                "main: base#1.hideMap",
                "main: base#1.hideDashboard",
            ),
            toLayers,
        )
    }
}
