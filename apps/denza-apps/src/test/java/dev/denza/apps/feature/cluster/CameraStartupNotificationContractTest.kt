package dev.denza.apps.feature.cluster

import dev.denza.apps.between
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural integration check: successful camera setup has no intermediate notify Binder calls. */
class CameraStartupNotificationContractTest {
    @Test fun noIntermediateNotificationOnTheSuccessfulCameraStartupPath() {
        val service = File("src/main/java/dev/denza/apps/feature/cluster/ClusterSceneService.kt").readText()
        val controller = File("src/main/java/dev/denza/apps/feature/cluster/CameraSceneController.kt").readText()
        assertFalse(service.contains("\"Camera display is ready\""))
        assertFalse(controller.contains("\"Camera display is ready\""))
        val show = controller.between("private fun showCamera(config:", "fun hideCamera(")
        val success = show.between("try {", "} catch")
        assertFalse(success.contains("updateNotification("))
        assertTrue("foreground-service obligation stays synchronous", service.contains("startForeground(NOTIFICATION_ID"))
    }
}
