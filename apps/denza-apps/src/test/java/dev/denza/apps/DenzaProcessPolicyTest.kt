package dev.denza.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaProcessPolicyTest {
    @Test
    fun `runtime bootstrap belongs only to the main application process`() {
        assertTrue(DenzaProcessPolicy.shouldBootstrap("dev.denza.apps", "dev.denza.apps"))
        assertFalse(DenzaProcessPolicy.shouldBootstrap("dev.denza.apps", "dev.denza.apps:weather"))
        assertFalse(DenzaProcessPolicy.shouldBootstrap("dev.denza.apps", "dev.denza.apps:picker"))
        assertFalse(DenzaProcessPolicy.shouldBootstrap("dev.denza.apps", null))
    }
}
