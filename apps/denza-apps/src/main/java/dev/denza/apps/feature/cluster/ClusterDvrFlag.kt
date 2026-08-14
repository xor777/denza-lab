package dev.denza.apps.feature.cluster

/**
 * Compile-time switch for the front DVR camera on the instrument cluster
 * (double-press of the steering-wheel `*` button).
 *
 * Retired 2026-08-14: the vendor camera stack delivers camera `0` frames in
 * two different in-buffer orientations and flips between them with no
 * externally readable state, so the fixed render transform shows a sideways
 * image on an unpredictable subset of activations. Every metadata channel
 * (sensor orientation, the SurfaceTexture transform matrix, rotate-and-crop)
 * is identical in both states; the recorder, gear and app-lifecycle theories
 * were all falsified live — see docs/instrument-display-findings.md. Nothing
 * was removed: the renderer, the cluster scene plumbing and the
 * steering-wheel trigger are all still built, and [DvrCameraRenderer]'s
 * orientation probe keeps recording evidence whenever the camera runs. Flip
 * [ENABLED] back to `true` to resurrect the feature; the candidate fix on the
 * shelf is a frame-content orientation detector (fisheye vignette position)
 * choosing between the two known geometry presets.
 */
object ClusterDvrFlag {
    const val ENABLED = false
}
