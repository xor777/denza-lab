package dev.denza.apps.feature.cluster

import dev.denza.apps.between
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural integration check: production camera lookup uses the pre-describe live seam. */
class CameraDisplayResolverWiringContractTest {
    @Test fun cameraOverlayContextEntryPointUsesLiveIdentityPrefilter() {
        val source = File(
            "src/main/java/dev/denza/apps/feature/cluster/ClusterDisplayResolver.kt",
        ).readText()
        val wiring = source.between(
            "fun resolveCameraOverlay(context: Context)",
            "internal fun <T> selectCameraOverlayFromLive",
        )

        assertTrue(wiring.contains("selectCameraOverlayFromLive("))
        assertTrue(wiring.contains("getSystemService(DisplayManager::class.java)"))
        assertTrue(wiring.contains("describe = ::describe"))
        assertFalse(wiring.contains("candidates(context)"))
    }
}
