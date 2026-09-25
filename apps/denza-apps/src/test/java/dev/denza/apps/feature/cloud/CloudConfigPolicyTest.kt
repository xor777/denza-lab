package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigPolicyTest {
    @Test fun legacyEnabledInstallKeepsFactoryMode() {
        assertEquals(CloudSimMode.FACTORY, CloudLinkSettings.resolveMode(null, enabled = true))
    }

    @Test fun freshInstallSelectsFactoryWithoutAutomaticallyEnabling() {
        assertEquals(CloudSimMode.FACTORY, CloudLinkSettings.resolveMode(null, enabled = false))
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

    @Test fun modeSelectionDropsEarlierOnAndQueuedOnUntilTeardownCompletes() {
        val intent = CloudConfigurationIntent()
        assertFalse(CloudConfigurationIntent.modeSelectionNeeded(
            CloudSimMode.FACTORY, CloudSimMode.FACTORY, running = false))
        intent.begin(selectMode = true, enabled = false)
        // The ON request queued before mode selection has now persisted.
        intent.executorStarted(enabled = true)
        assertFalse(intent.resume)
        assertFalse(intent.switchRequested(enabled = true))
        // Returning to the original mode while STOP is pending still completes OFF.
        assertTrue(CloudConfigurationIntent.modeSelectionNeeded(
            CloudSimMode.FACTORY, CloudSimMode.FACTORY, running = true))
        intent.update(selectMode = true)
        assertFalse(intent.resume)
        intent.reset()
        assertTrue(intent.switchRequested(enabled = true)) // A later explicit ON is allowed.
        assertTrue(intent.resume)
    }

    @Test fun modeSelectionDuringPairEditCancelsItsResume() {
        val intent = CloudConfigurationIntent()
        intent.begin(selectMode = false, enabled = true)
        intent.executorStarted(enabled = true)
        assertTrue(intent.resume)
        intent.update(selectMode = true)
        assertFalse(intent.resume)
        assertFalse(intent.switchRequested(enabled = true))
    }

    @Test fun pairEditKeepsItsExistingExplicitSwitchPolicy() {
        val intent = CloudConfigurationIntent()
        intent.begin(selectMode = false, enabled = true)
        assertTrue(intent.switchRequested(enabled = false))
        intent.executorStarted(enabled = true)
        assertFalse(intent.resume)
    }
}
