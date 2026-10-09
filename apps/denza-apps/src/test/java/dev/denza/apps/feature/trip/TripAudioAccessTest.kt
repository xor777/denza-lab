package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class TripAudioAccessTest {
    @Test
    fun theGrantCommandIsRecordAudioForThePackage() {
        assertEquals(
            listOf("pm grant dev.denza.apps android.permission.RECORD_AUDIO"),
            TripAudioAccessPolicy.grantCommands("dev.denza.apps"),
        )
    }
}
