package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulcastRuntimeDiagnosticsTest {
    @Test
    fun `counts process-only accessibility and window events`() {
        SimulcastRuntimeDiagnostics.resetForTest()

        SimulcastRuntimeDiagnostics.recordRoot(true)
        SimulcastRuntimeDiagnostics.recordRoot(false)
        SimulcastRuntimeDiagnostics.recordGeometryParseMiss()
        SimulcastRuntimeDiagnostics.recordUnstableSample()
        SimulcastRuntimeDiagnostics.recordRelayouts(3)
        SimulcastRuntimeDiagnostics.recordSemanticRebuild()

        assertEquals(
            SimulcastRuntimeSnapshot(
                rootsFound = 1,
                rootsMissing = 1,
                geometryParseMisses = 1,
                unstableSamples = 1,
                appliedRelayouts = 3,
                semanticWindowRebuilds = 1,
            ),
            SimulcastRuntimeDiagnostics.snapshot(),
        )
    }
}
