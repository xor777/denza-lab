package dev.denza.apps.feature.cluster

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
    private val source = File("src/main/java/dev/denza/apps/feature/cluster/ClusterSceneService.kt").readText()

    @Test fun presentationReceivesTheSelectedLayerInsteadOfAssumingBase() {
        assertTrue(block("private fun prepareBaseScene():").contains("cameraLayer = false"))
        assertTrue(block("private fun prepareCameraScene():").contains("cameraLayer = true"))
        val creation = source.substringAfter("val shown = ClusterPresentation(").substringBefore(".also { it.show() }")
        assertTrue("pass the actual selected layer", creation.contains("cameraLayer = cameraLayer"))
        assertTrue(source.contains("private val cameraLayer: Boolean"))
    }

    @Test fun cameraCreationDoesNotAllocateTheUnusedMapAndDashboardLayers() {
        val create = block("override fun onCreate(savedInstanceState:")
        assertTrue("base setup must be skipped for cameras", create.contains("if (!cameraLayer) createBaseLayers(root)"))
        assertFalse(create.contains("SurfaceView(context)"))
        assertFalse(create.contains("ProjectionEdgeShadeView(context)"))
        assertFalse(create.contains("dashboardLayer ="))
        // The camera and diagnostic tree remain initialized for both kinds of presentation.
        assertTrue(create.contains("cameraTexture = TextureView(context)"))
        assertTrue(create.contains("cameraEdgeShade = EdgeShadeView(context)"))
        assertTrue(create.contains("diagnosticLayer = FrameLayout(context)"))
        assertTrue(create.contains("renderer = AvcCameraRenderer("))
    }

    @Test fun baseCreationKeepsMapCallbackAndTheOriginalLayerOrder() {
        val create = block("private fun createBaseLayers(")
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
        assertTrue(block("private fun showMap(placement:").contains("prepareBaseScene()"))
        assertTrue(block("private fun showDashboard(placement:").contains("prepareBaseScene()"))
        assertTrue(block("private fun hideMap()").contains("basePresentation?.hideMap()"))
        assertTrue(block("private fun hideDashboard()").contains("basePresentation?.hideDashboard()"))
        assertFalse(block("        fun showCamera(config:").contains("mapSurface"))
        assertFalse(block("fun showDiagnostic(position:").contains("mapSurface"))
    }

    /** Balanced braces for these fixed method bodies; deliberately not a general Kotlin parser. */
    private fun block(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing method: $marker", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        error("unterminated method: $marker")
    }
}
