package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterDisplayResolverTest {
    @Test
    fun exactKnownDisplayWinsOverRearAndOtherVirtualDisplays() {
        val cluster = candidate(4, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY, 1920, 720)
        val result = ClusterDisplayResolver.select(
            listOf(
                candidate(0, "Built-in Screen", 1920, 1080),
                candidate(2, "left_rse_screen", 1920, 1080),
                candidate(3, "OpenBYD virtual fission", 1920, 720, own = true),
                cluster,
                candidate(5, "overhead_screen", 1920, 1080),
            ),
        )

        assertEquals(cluster, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun manualOverrideWinsWithoutHardcodedFallback() {
        val selected = candidate(8, "Driver instrument panel", 1560, 540)
        val result = ClusterDisplayResolver.select(listOf(selected), manualOverrideId = 8)
        assertEquals(selected, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun equalPlausibleDisplaysRequireVerification() {
        val result = ClusterDisplayResolver.select(
            listOf(
                candidate(6, "vendor_cluster_left", 1920, 720),
                candidate(7, "vendor_cluster_right", 1920, 720),
            ),
        )
        assertTrue(result is ClusterDisplaySelection.NeedsVerification)
    }

    @Test
    fun unknownWideDisplayIsNotGuessed() {
        val result = ClusterDisplayResolver.select(listOf(candidate(9, "HDMI", 1920, 720)))
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayUsesSecondNamedFissionDisplay() {
        val base = candidate(3, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY, 2560, 720)
        val overlay = candidate(
            4,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2560,
            720,
        )

        val result = ClusterDisplayResolver.selectCameraOverlay(listOf(base, overlay))

        assertEquals(overlay, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun cameraOverlayDoesNotGuessAnotherWideDisplay() {
        val result = ClusterDisplayResolver.selectCameraOverlay(
            listOf(candidate(7, "vendor_cluster_overlay", 2560, 720)),
        )

        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayDescribesOnlyTheExactLiveCandidate() {
        val overlay = candidate(
            4,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2_560,
            720,
        ).copy(
            densityDpi = 280,
            type = ClusterDisplayResolver.DISPLAY_TYPE_VIRTUAL,
            flags = 37,
        )
        val displays = listOf(
            liveDisplay(0, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY),
            liveDisplay(3, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY),
            liveDisplay(9, "vendor_cluster_overlay"),
            liveDisplay(4, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY, overlay),
        )
        val describedIds = mutableListOf<Int>()

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = displays,
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = { display ->
                describedIds += display.id
                requireNotNull(display.descriptor)
            },
        )

        assertEquals(listOf(4), describedIds)
        assertEquals(overlay, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun cameraOverlayKeepsDuplicateMatchesAmbiguousAndOrdered() {
        val first = candidate(
            6,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2_560,
            720,
        )
        val second = candidate(
            4,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            1_920,
            540,
        )
        val displays = listOf(
            liveDisplay(3, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY),
            liveDisplay(6, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY, first),
            liveDisplay(4, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY, second),
        )
        val describedIds = mutableListOf<Int>()

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = displays,
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = { display ->
                describedIds += display.id
                requireNotNull(display.descriptor)
            },
        )

        assertEquals(listOf(6, 4), describedIds)
        assertEquals(
            listOf(first, second),
            (result as ClusterDisplaySelection.NeedsVerification).candidates,
        )
    }

    @Test
    fun cameraOverlayDoesNotDescribeOrGuessNearNamedDisplays() {
        val displays = listOf(
            liveDisplay(2, null),
            liveDisplay(3, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY),
            liveDisplay(4, "${ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY}_copy"),
            liveDisplay(5, "SHARED_FISSION_BG_XDJASCREENPROJECTION_1"),
        )
        val describedIds = mutableListOf<Int>()

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = displays,
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = { display ->
                describedIds += display.id
                requireNotNull(display.descriptor)
            },
        )

        assertEquals(emptyList<Int>(), describedIds)
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayReturnsMissingWithoutDescribingAnEmptyLiveList() {
        var describeCalls = 0

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = emptyList<LiveDisplay>(),
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = {
                describeCalls += 1
                requireNotNull(it.descriptor)
            },
        )

        assertEquals(0, describeCalls)
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayRechecksTheDescriptorAfterLiveIdentityMatch() {
        val changedDescriptor = candidate(4, "display_name_changed", 2_560, 720)
        val display = liveDisplay(
            4,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            changedDescriptor,
        )
        val describedIds = mutableListOf<Int>()

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = listOf(display),
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = {
                describedIds += it.id
                requireNotNull(it.descriptor)
            },
        )

        assertEquals(listOf(4), describedIds)
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayRejectsDefaultAndOwnDisplays() {
        val defaultOverlay = candidate(
            0,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2_560,
            720,
        )
        val ownOverlay = candidate(
            8,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2_560,
            720,
            own = true,
        )
        val displays = listOf(
            liveDisplay(0, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY, defaultOverlay),
            liveDisplay(8, ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY, ownOverlay),
        )
        val describedIds = mutableListOf<Int>()

        val result = ClusterDisplayResolver.selectCameraOverlayFromLive(
            displays = displays,
            displayId = LiveDisplay::id,
            displayName = LiveDisplay::name,
            describe = { display ->
                describedIds += display.id
                requireNotNull(display.descriptor)
            },
        )

        assertEquals(listOf(8), describedIds)
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    private fun candidate(
        id: Int,
        name: String,
        width: Int,
        height: Int,
        own: Boolean = false,
    ) = ClusterDisplayDescriptor(
        id = id,
        name = name,
        width = width,
        height = height,
        densityDpi = 240,
        type = ClusterDisplayResolver.DISPLAY_TYPE_VIRTUAL,
        flags = 0,
        isOwnVirtualDisplay = own,
    )

    private fun liveDisplay(
        id: Int,
        name: String?,
        descriptor: ClusterDisplayDescriptor? = null,
    ) = LiveDisplay(id = id, name = name, descriptor = descriptor)

    private data class LiveDisplay(
        val id: Int,
        val name: String?,
        val descriptor: ClusterDisplayDescriptor?,
    )
}
