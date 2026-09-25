package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigPolicyTest {
    @Test fun legacyEnabledInstallKeepsFactoryMode() {
        assertEquals(CloudSimMode.FACTORY, CloudLinkSettings.resolveMode(null, enabled = true))
    }

    @Test fun freshInstallHasNoModeAndNoAutomaticEnable() {
        assertNull(CloudLinkSettings.resolveMode(null, enabled = false))
    }

    @Test fun chosenModeSurvivesOffAndBackOn() {
        assertEquals(CloudSimMode.FACTORY, CloudLinkSettings.resolveMode("FACTORY", enabled = false))
        assertEquals(CloudSimMode.CUSTOM, CloudLinkSettings.resolveMode("CUSTOM", enabled = false))
    }

    @Test fun editsWaitForDisableAndEveryQueuedExplicitOperation() {
        assertTrue(CloudLinkSettings.canConfigure(false, false, false))
        assertFalse(CloudLinkSettings.canConfigure(true, false, false))
        assertFalse(CloudLinkSettings.canConfigure(false, true, false))
        assertFalse(CloudLinkSettings.canConfigure(false, false, true))
    }
}
