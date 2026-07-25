package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorSide

enum class CameraRuntimePhase {
    IDLE,
    STARTING,
    READY,
    STOPPING,
    FAILED,
}

data class CameraRuntimeSnapshot(
    val phase: CameraRuntimePhase = CameraRuntimePhase.IDLE,
    val side: MirrorSide? = null,
    val generation: Long = 0L,
    val details: String = "",
)

data class ClusterDisplayDescriptor(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val type: Int,
    val flags: Int,
    val isOwnVirtualDisplay: Boolean = false,
)

sealed interface ClusterDisplaySelection {
    data class Selected(val display: ClusterDisplayDescriptor) : ClusterDisplaySelection
    data class NeedsVerification(val candidates: List<ClusterDisplayDescriptor>) : ClusterDisplaySelection
    data object Missing : ClusterDisplaySelection
}

enum class ClusterCameraPosition {
    LEFT,
    RIGHT,
    CENTER,
}

enum class ClusterMapPlacement {
    FULL,
    LEFT,
    CENTER,
    RIGHT,
}

enum class ClusterShadeCorner {
    TOP_LEFT,
    TOP_RIGHT,
}

data class ClusterBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ClusterLayout(
    val displayWidth: Int,
    val displayHeight: Int,
    val cameraPosition: ClusterCameraPosition,
    val centerExtendPercent: Int = 20,
) {
    val mapBounds: ClusterBounds = ClusterBounds(0, 0, displayWidth, displayHeight)
    val baseSlotWidth: Int = (displayWidth / 3).coerceAtLeast(1)
    val cameraWidth: Int = (
        baseSlotWidth + (baseSlotWidth * centerExtendPercent.coerceIn(0, 100) / 100f).toInt()
    ).coerceAtMost(displayWidth)
    val cameraLeft: Int = when (cameraPosition) {
        ClusterCameraPosition.LEFT -> 0
        ClusterCameraPosition.RIGHT -> displayWidth - cameraWidth
        ClusterCameraPosition.CENTER -> (displayWidth - cameraWidth) / 2
    }
    val cameraBounds: ClusterBounds = ClusterBounds(
        cameraLeft,
        0,
        cameraLeft + cameraWidth,
        displayHeight,
    )
}

data class ClusterSceneState(
    val display: ClusterDisplayDescriptor? = null,
    val layout: ClusterLayout? = null,
    val mapVisible: Boolean = false,
    val cameraVisible: Boolean = false,
    val needsDisplayVerification: Boolean = false,
    val details: String? = null,
)

data class ClusterMapLayout(
    val displayWidth: Int,
    val displayHeight: Int,
    val placement: ClusterMapPlacement,
) {
    private val cameraPosition = when (placement) {
        ClusterMapPlacement.LEFT -> ClusterCameraPosition.LEFT
        ClusterMapPlacement.RIGHT -> ClusterCameraPosition.RIGHT
        else -> ClusterCameraPosition.CENTER
    }
    private val cameraBounds = ClusterLayout(
        displayWidth,
        displayHeight,
        cameraPosition,
    ).cameraBounds
    private val baseSideBottom = (displayHeight * 84 / 100 + displayHeight / 144)
        .coerceAtLeast(1)
        .coerceAtMost(displayHeight)
    private val sideBottom = if (placement == ClusterMapPlacement.RIGHT) {
        (baseSideBottom + 10).coerceAtMost(displayHeight)
    } else {
        baseSideBottom
    }
    private val sideTop = if (placement == ClusterMapPlacement.RIGHT) {
        (displayHeight * 14 / 100 - 5).coerceAtLeast(0)
    } else {
        0
    }
    val surfaceBounds: ClusterBounds = when (placement) {
        ClusterMapPlacement.FULL -> ClusterBounds(0, 0, displayWidth, displayHeight)
        ClusterMapPlacement.CENTER -> cameraBounds
        ClusterMapPlacement.LEFT,
        ClusterMapPlacement.RIGHT,
        -> cameraBounds.copy(top = sideTop, bottom = sideBottom)
    }
    val shadeTop: Boolean = placement == ClusterMapPlacement.CENTER
    val shadeBottom: Boolean = placement == ClusterMapPlacement.FULL
    val shadeHeightDp: Int = when (placement) {
        ClusterMapPlacement.CENTER -> 130
        ClusterMapPlacement.FULL -> 90
        ClusterMapPlacement.LEFT -> 192
        ClusterMapPlacement.RIGHT -> 0
    }
    val shadeTopAlpha: Int = when (placement) {
        ClusterMapPlacement.CENTER -> 250
        ClusterMapPlacement.FULL -> 204
        ClusterMapPlacement.LEFT -> 250
        ClusterMapPlacement.RIGHT -> 0
    }
    val shadeBottomAlpha: Int = if (placement == ClusterMapPlacement.FULL) 242 else 0
    val shadeBottomTopAlpha: Int = if (placement == ClusterMapPlacement.FULL) 242 else 0
    val shadeBottomFadePx: Int = if (placement == ClusterMapPlacement.FULL) 60 else 0
    val shadeBottomSolidPx: Int = if (placement == ClusterMapPlacement.FULL) 90 else 0
    val shadeBottomRevealRadiusPx: Int = if (placement == ClusterMapPlacement.FULL) 600 else 0
    val shadeBottomRevealHeightPercent: Int = if (placement == ClusterMapPlacement.FULL) 55 else 0
    val shadeBottomRevealCenterOffsetPx: Int = if (placement == ClusterMapPlacement.FULL) 120 else 0
    val shadeTopLeftRevealRadiusPx: Int =
        if (placement == ClusterMapPlacement.FULL) displayWidth * 24 / 100 else 0
    val shadeTopRightRevealRadiusPx: Int =
        if (placement == ClusterMapPlacement.FULL) displayWidth * 20 / 100 else 0
    val shadeTopRevealHeightPx: Int =
        shadeTopRightRevealRadiusPx * 40 / 100 * 4 / 3
    val shadeCenterTopFadePx: Int = shadeTopRevealHeightPx
    val shadeCorner: ClusterShadeCorner? = when (placement) {
        ClusterMapPlacement.LEFT -> ClusterShadeCorner.TOP_RIGHT
        else -> null
    }
    val densityScalePercent: Int = when (placement) {
        ClusterMapPlacement.LEFT,
        ClusterMapPlacement.RIGHT,
        ClusterMapPlacement.FULL,
        -> 85
        ClusterMapPlacement.CENTER -> 100
    }
}
