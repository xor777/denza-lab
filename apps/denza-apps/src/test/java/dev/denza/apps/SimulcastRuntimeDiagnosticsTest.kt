package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulcastRuntimeDiagnosticsTest {
    /**
     * The counters are the process's, so the test reads what it added rather than resetting them,
     * and each event happens a different number of times: with every count at one, two counters
     * swapped in [SimulcastRuntimeDiagnostics.snapshot] read the same as two counters in place.
     */
    @Test
    fun `each event moves its own counter and no other`() {
        val before = SimulcastRuntimeDiagnostics.snapshot()

        SimulcastRuntimeDiagnostics.recordRoot(true)
        repeat(2) { SimulcastRuntimeDiagnostics.recordRoot(false) }
        repeat(3) { SimulcastRuntimeDiagnostics.recordGeometryParseMiss() }
        repeat(4) { SimulcastRuntimeDiagnostics.recordUnstableSample() }
        SimulcastRuntimeDiagnostics.recordRelayouts(5)
        SimulcastRuntimeDiagnostics.recordRelayouts(0)
        repeat(6) { SimulcastRuntimeDiagnostics.recordSemanticRebuild() }

        val after = SimulcastRuntimeDiagnostics.snapshot()
        assertEquals(
            SimulcastRuntimeSnapshot(
                rootsFound = 1,
                rootsMissing = 2,
                geometryParseMisses = 3,
                unstableSamples = 4,
                appliedRelayouts = 5,
                semanticWindowRebuilds = 6,
            ),
            SimulcastRuntimeSnapshot(
                rootsFound = after.rootsFound - before.rootsFound,
                rootsMissing = after.rootsMissing - before.rootsMissing,
                geometryParseMisses = after.geometryParseMisses - before.geometryParseMisses,
                unstableSamples = after.unstableSamples - before.unstableSamples,
                appliedRelayouts = after.appliedRelayouts - before.appliedRelayouts,
                semanticWindowRebuilds = after.semanticWindowRebuilds - before.semanticWindowRebuilds,
            ),
        )
    }
}
