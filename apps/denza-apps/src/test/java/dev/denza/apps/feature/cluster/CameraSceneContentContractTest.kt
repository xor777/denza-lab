package dev.denza.apps.feature.cluster

import dev.denza.apps.between
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source wiring checks, not an Android window simulation. The private presentation has no host
 * runtime: these checks keep base-only allocations off its camera path and preserve the base
 * callers. Actual layout, first-frame timing and display behavior require the car.
 */
class CameraSceneContentContractTest {
    private val service = File("src/main/java/dev/denza/apps/feature/cluster/ClusterSceneService.kt").readText()
    private val controller = File("src/main/java/dev/denza/apps/feature/cluster/CameraSceneController.kt").readText()
    private val layer = File("src/main/java/dev/denza/apps/feature/cluster/SceneLayer.kt").readText()

    @Test fun presentationReceivesTheSelectedLayerInsteadOfAssumingBase() {
        assertTrue(controller.block("fun prepareBaseScene():").contains("cameraLayer = false"))
        assertTrue(controller.block("private fun prepareCameraScene():").contains("cameraLayer = true"))
        assertTrue(
            "the scene opens the layer it selected",
            controller.block("private fun prepareScene(").contains("layers.open(selection.display.id, cameraLayer, this)"),
        )
        val creation = service.between("val shown = ClusterPresentation(", ".also { it.show() }")
        assertTrue("pass the actual selected layer", creation.contains("cameraLayer = cameraLayer"))
        assertTrue(service.contains("private val cameraLayer: Boolean"))
    }

    @Test fun cameraCreationDoesNotAllocateTheUnusedMapAndDashboardLayers() {
        val create = service.block("override fun onCreate(savedInstanceState:")
        assertTrue("base setup must be skipped for cameras", create.contains("if (!cameraLayer) createBaseLayers(root)"))
        assertFalse(create.contains("SurfaceView(context)"))
        assertFalse(create.contains("ProjectionEdgeShadeView(context)"))
        assertFalse(create.contains("dashboardLayer ="))
        // The camera and diagnostic tree remain initialized for both kinds of presentation.
        assertTrue(create.contains("cameraTexture = TextureView(context)"))
        assertTrue(create.contains("cameraEdgeShade = EdgeShadeView(context)"))
        assertTrue(create.contains("diagnosticLayer = FrameLayout(context)"))
        assertTrue(create.contains("AvcCameraRenderer(context, cameraTexture"))
    }

    @Test fun baseCreationKeepsMapCallbackAndTheOriginalLayerOrder() {
        val create = service.block("private fun createBaseLayers(")
        val surface = create.indexOf("root.addView(mapSurface,")
        val shade = create.indexOf("root.addView(mapShade,")
        val dashboard = create.indexOf("dashboardLayer,", shade)
        assertTrue("map, its shade, then dashboard", surface >= 0 && shade > surface && dashboard > shade)
        assertTrue(create.contains("holder.addCallback(mapSurfaceCallback)"))
        assertTrue(create.contains("mapSurface = SurfaceView(context)"))
        assertTrue(create.contains("mapShade = ProjectionEdgeShadeView(context)"))
        assertTrue(create.contains("dashboardLayer = FrameLayout(context)"))
    }

    @Test fun onlyTheBasePresentationCanReachBaseOnlyFields() {
        assertTrue(service.block("private fun showMap(placement:").contains("prepareBaseScene()"))
        assertTrue(controller.block("fun showDashboard(placement:").contains("prepareBaseScene()"))
        assertTrue(controller.block("fun hideMap()").contains("basePresentation?.hideMap()"))
        assertTrue(controller.block("fun hideDashboard()").contains("basePresentation?.hideDashboard()"))
        assertFalse(layer.block("fun showCamera(config:").contains("mapSurface"))
        assertFalse(layer.block("fun showDiagnostic(position:").contains("mapSurface"))
    }

    /** Balanced braces for these fixed method bodies; deliberately not a general Kotlin parser. */
    private fun String.block(marker: String): String {
        val start = indexOf(marker)
        assertTrue("missing method: $marker", start >= 0)
        val open = indexOf('{', start)
        var depth = 0
        for (index in open until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return substring(open + 1, index)
            }
        }
        error("unterminated method: $marker")
    }
}
