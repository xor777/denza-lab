package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase

/**
 * Decides when a window accessibility event must trigger the emergency camera
 * release. The guard is armed only while our AVC session is live (STARTING or
 * READY): a stock camera window appearing at that moment is the fast-switch
 * signature that crashes stock `com.byd.avc` when its mode transition finds
 * our surface attached (see dishare-api-notes, 2026-07-24/25).
 *
 * Two trigger paths:
 * - a window-state event whose package is the stock camera app - cheap, but
 *   emitted by the stock app's own main thread, so it disappears exactly in
 *   the crash scenario;
 * - a system-emitted windows-changed ADDED event whose new window belongs to
 *   the stock camera app - survives a dying stock main thread. The service
 *   resolves new-window ownership by window title; our own overlay windows
 *   are excluded so showing a mirror does not self-trigger.
 */
object MirrorGuardEvaluator {
    const val STOCK_CAMERA_PACKAGE = "com.byd.avc"

    fun armed(runtimePhase: CameraRuntimePhase, guardEnabled: Boolean): Boolean =
        guardEnabled &&
            (runtimePhase == CameraRuntimePhase.STARTING || runtimePhase == CameraRuntimePhase.READY)

    fun shouldTriggerOnState(
        eventPackage: CharSequence?,
        runtimePhase: CameraRuntimePhase,
        guardEnabled: Boolean,
    ): Boolean = armed(runtimePhase, guardEnabled) &&
        eventPackage != null &&
        STOCK_CAMERA_PACKAGE.contentEquals(eventPackage)

    /** True when any of the newly added windows' titles belongs to the stock camera app. */
    fun shouldTriggerOnNewWindows(
        newWindowTitles: List<CharSequence?>,
        runtimePhase: CameraRuntimePhase,
        guardEnabled: Boolean,
    ): Boolean = armed(runtimePhase, guardEnabled) &&
        newWindowTitles.any { title ->
            title != null && title.toString().contains(STOCK_CAMERA_PACKAGE)
        }
}
