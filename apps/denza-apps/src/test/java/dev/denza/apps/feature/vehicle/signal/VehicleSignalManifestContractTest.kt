package dev.denza.apps.feature.vehicle.signal

import dev.denza.apps.appManifest
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleSignalManifestContractTest {
    @Test
    fun signalHubAddsNoBydPermissionOrExportedEventComponent() {
        val manifest = appManifest()

        assertFalse(manifest.contains("BYDAUTO_"))
        assertFalse(manifest.contains("can_msg_event"))
        assertFalse(manifest.contains("GET_EVENT_CENTER_MESSAGE"))
    }
}
