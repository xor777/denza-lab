package dev.denza.apps.feature.simulcast

import kotlin.math.floor
import kotlin.math.max

/**
 * Produces centered fit bounds for DiShare's mirror view.
 *
 * This firmware clamps the virtual mirror to an aspect ratio of at least 16:9.
 * The fit rectangle keeps the whole decoded frame visible without distortion
 * and divides any unused target space symmetrically around it.
 */
object SimulcastVideoBoundsResolver {
    private const val FIRMWARE_MIN_ASPECT = 16.0 / 9.0

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    @JvmStatic
    fun resolve(resolution: SimulcastVideoSizeResolver.Resolution): Bounds = resolve(
        videoWidth = resolution.videoWidth,
        videoHeight = resolution.videoHeight,
        viewportWidth = resolution.viewportWidth,
        viewportHeight = resolution.viewportHeight,
    )

    @JvmStatic
    fun resolve(
        videoWidth: Int,
        videoHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Bounds {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return Bounds(0, 0, 0, 0)
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return Bounds(0, 0, videoWidth, videoHeight)
        }

        val viewportAspect = viewportWidth.toDouble() / viewportHeight
        val effectiveVideoAspect = max(
            FIRMWARE_MIN_ASPECT,
            videoWidth.toDouble() / videoHeight,
        )

        val fitWidth: Int
        val fitHeight: Int
        if (effectiveVideoAspect >= viewportAspect) {
            fitWidth = evenFloor(viewportWidth.toDouble())
            fitHeight = evenFloor(fitWidth / effectiveVideoAspect)
        } else {
            fitHeight = evenFloor(viewportHeight.toDouble())
            fitWidth = evenFloor(fitHeight * effectiveVideoAspect)
        }
        val left = (viewportWidth - fitWidth) / 2
        val top = (viewportHeight - fitHeight) / 2
        return Bounds(left, top, left + fitWidth, top + fitHeight)
    }

    /** Keeps encoder dimensions even without letting the fit exceed its viewport. */
    private fun evenFloor(value: Double): Int {
        val floored = floor(value + 1e-6).toInt().coerceAtLeast(2)
        return if (floored % 2 == 0) floored else floored - 1
    }
}
