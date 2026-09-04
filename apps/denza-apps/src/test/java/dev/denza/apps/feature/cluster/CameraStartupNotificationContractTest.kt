package dev.denza.apps.feature.cluster

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural integration check: successful camera setup has no intermediate notify Binder calls. */
class CameraStartupNotificationContractTest {
    @Test fun noIntermediateNotificationOnTheSuccessfulCameraStartupPath() {
        val source = File("src/main/java/dev/denza/apps/feature/cluster/ClusterSceneService.kt").readText()
        assertFalse(source.contains("\"Camera display is ready\""))
        val show = source.substringAfter("private fun showCamera(config:").substringBefore("private fun hideCamera(")
        val success = show.substringAfter("try {").substringBefore("} catch")
        assertFalse(success.contains("updateNotification("))
        assertTrue("foreground-service obligation stays synchronous", source.contains("startForeground(NOTIFICATION_ID"))
    }
}
