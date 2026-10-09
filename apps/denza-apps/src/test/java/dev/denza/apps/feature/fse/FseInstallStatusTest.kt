package dev.denza.apps.feature.fse

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FseInstallStatusTest {

    /** While an install runs the press opens nothing; once it has ended, the chooser again. */
    @Test
    fun onlyARunningInstallKeepsTheChooserShut() {
        assertTrue(FseInstallStatus.installing(FseInstallStatus.progress(FseInstallStep.copying(40))))
        assertFalse(FseInstallStatus.installing(FseInstallStatus.of(FseInstallResult.Failed(FseInstallFailure.NO_ANSWER))))
        val app = FseInstallApp("ru.app", "Приложение", "1", 1L, installable = true)
        assertFalse(FseInstallStatus.installing(FseInstallStatus.of(FseInstallResult.Installed(app))))
    }

    @Test
    fun aChannelFailureWaitsOnThePressAndAnyOtherEndingIsBroken() {
        val channel = FseInstallStatus.of(FseInstallResult.Failed(FseInstallFailure.NO_ACCESS, "x"))
        assertEquals(FeatureStatus.NEEDS_ACTION, channel.status)
        assertEquals(FeatureResolution.CHECK_ACCESS, channel.resolution)
        assertEquals("x", channel.details)

        val declined = FseInstallStatus.of(FseInstallResult.Failed(FseInstallFailure.DECLINED))
        assertEquals(FeatureStatus.ERROR, declined.status)
        assertEquals("Экран отклонил", declined.message)
        assertNull(declined.resolution)
    }
}
