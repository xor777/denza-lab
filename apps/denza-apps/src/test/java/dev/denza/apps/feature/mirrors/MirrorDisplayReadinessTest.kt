package dev.denza.apps.feature.mirrors

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorDisplayReadinessTest {
    @Test
    fun `camera overlay is required before mirrors report ready`() {
        val overlay = display(
            id = 4,
            name = ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
        )

        val snapshot = MirrorDisplayReadiness.snapshot(
            ClusterDisplaySelection.Selected(overlay),
            active = true,
        )

        assertEquals(FeatureStatus.ACTIVE, snapshot.status)
        assertTrue(snapshot.desiredEnabled)
    }

    @Test
    fun `missing camera overlay offers retry instead of base display picker`() {
        val snapshot = MirrorDisplayReadiness.snapshot(
            ClusterDisplaySelection.Missing,
            active = false,
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, snapshot.status)
        assertEquals(FeatureResolution.RETRY, snapshot.resolution)
        assertEquals("Экран не найден", snapshot.message)
    }

    @Test
    fun `ambiguous camera overlay does not offer base display picker`() {
        val snapshot = MirrorDisplayReadiness.snapshot(
            ClusterDisplaySelection.NeedsVerification(
                listOf(
                    display(4, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY),
                    display(5, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY),
                ),
            ),
            active = false,
        )

        assertEquals(FeatureStatus.NEEDS_ACTION, snapshot.status)
        assertEquals(FeatureResolution.RETRY, snapshot.resolution)
        assertEquals("camera overlay display is ambiguous", snapshot.details)
    }

    private fun display(id: Int, name: String) = ClusterDisplayDescriptor(
        id = id,
        name = name,
        width = 2_560,
        height = 720,
        densityDpi = 240,
        type = ClusterDisplayResolver.DISPLAY_TYPE_VIRTUAL,
        flags = 0,
    )
}
