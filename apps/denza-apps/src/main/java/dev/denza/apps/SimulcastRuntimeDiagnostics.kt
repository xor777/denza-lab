package dev.denza.apps

import java.util.concurrent.atomic.AtomicLong

data class SimulcastRuntimeSnapshot(
    val rootsFound: Long = 0,
    val rootsMissing: Long = 0,
    val geometryParseMisses: Long = 0,
    val unstableSamples: Long = 0,
    val appliedRelayouts: Long = 0,
    val semanticWindowRebuilds: Long = 0,
)

/** Process-local counters only; no package names, labels, or other user data. */
object SimulcastRuntimeDiagnostics {
    private val rootsFound = AtomicLong()
    private val rootsMissing = AtomicLong()
    private val geometryParseMisses = AtomicLong()
    private val unstableSamples = AtomicLong()
    private val appliedRelayouts = AtomicLong()
    private val semanticWindowRebuilds = AtomicLong()

    @JvmStatic
    fun recordRoot(found: Boolean) {
        if (found) rootsFound.incrementAndGet() else rootsMissing.incrementAndGet()
    }

    @JvmStatic
    fun recordGeometryParseMiss() {
        geometryParseMisses.incrementAndGet()
    }

    @JvmStatic
    fun recordUnstableSample() {
        unstableSamples.incrementAndGet()
    }

    @JvmStatic
    fun recordRelayouts(count: Int) {
        if (count > 0) appliedRelayouts.addAndGet(count.toLong())
    }

    @JvmStatic
    fun recordSemanticRebuild() {
        semanticWindowRebuilds.incrementAndGet()
    }

    fun snapshot(): SimulcastRuntimeSnapshot = SimulcastRuntimeSnapshot(
        rootsFound = rootsFound.get(),
        rootsMissing = rootsMissing.get(),
        geometryParseMisses = geometryParseMisses.get(),
        unstableSamples = unstableSamples.get(),
        appliedRelayouts = appliedRelayouts.get(),
        semanticWindowRebuilds = semanticWindowRebuilds.get(),
    )

    internal fun resetForTest() {
        rootsFound.set(0)
        rootsMissing.set(0)
        geometryParseMisses.set(0)
        unstableSamples.set(0)
        appliedRelayouts.set(0)
        semanticWindowRebuilds.set(0)
    }
}
