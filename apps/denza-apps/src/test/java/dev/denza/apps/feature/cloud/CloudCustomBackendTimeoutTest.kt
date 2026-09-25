package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudCustomBackendTimeoutTest {
    @Test fun startAllowsGuardianToFinishItsBoundedLaunchAndCleanup() {
        assertEquals(40_000, CloudCustomBackend.timeoutFor("START"))
        for (operation in listOf("PROBE", "ATTACH", "RENEW", "STATUS", "STOP")) {
            assertEquals(10_000, CloudCustomBackend.timeoutFor(operation))
        }
    }
}
