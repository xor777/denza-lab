package dev.denza.apps.feature.vehicle.signal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleSignalManifestContractTest {
    @Test
    fun signalHubAddsNoBydPermissionOrExportedEventComponent() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("BYDAUTO_"))
        assertFalse(manifest.contains("can_msg_event"))
        assertFalse(manifest.contains("GET_EVENT_CENTER_MESSAGE"))
    }
}
