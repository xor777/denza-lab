package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * The views a presentation stacks, bottom first; `ClusterPresentation.onCreate` builds them in
 * this order and nothing else.
 *
 * The dashboard comes after the shade on purpose. The shade darkens whatever is beneath it so a
 * projected map cannot cover instrument data; the dashboard needs no such protection because it
 * places its own blocks off the stock graphics to begin with, and darkening it twice would only
 * cost contrast. The camera covers the instruments and the diagnostic panels cover everything.
 */
internal enum class SceneView {
    MAP_SURFACE,
    MAP_SHADE,
    DASHBOARD,
    CAMERA,
    DIAGNOSTIC,
    ;

    companion object {
        private val CAMERA_LAYER = listOf(CAMERA, DIAGNOSTIC)
        private val BASE_LAYER = listOf(MAP_SURFACE, MAP_SHADE, DASHBOARD, CAMERA, DIAGNOSTIC)

        /**
         * A camera layer builds no map surface, shade or dashboard: it never shows them, and
         * building them was paid on every turn signal (44f02df5). The base layer still builds a
         * camera frame and renderer it never starts; that is left as it is.
         */
        fun stackFor(cameraLayer: Boolean): List<SceneView> =
            if (cameraLayer) CAMERA_LAYER else BASE_LAYER
    }
}

/**
 * One presentation of the driver's-display scene - the base layer or the camera layer - without
 * Android: when its AVC camera starts and stops, and the order its window, the renderer's Surface
 * and vendor freeDisplay are let go in.
 *
 * `ClusterPresentation` owns the window and the views and hands them over as [views]; its onCreate
 * makes the renderer and [attach]es it. [CameraSceneController] holds layers, never presentations.
 */
internal class SceneLayer(
    val displayId: Int,
    private val views: SceneLayerViews,
    private val events: AvcEvents,
    private val teardownThread: TeardownThread,
    private val clock: SceneClock,
    private val log: SceneLog,
) : CameraRendererEvents {
    private enum class CameraSource {
        NONE,
        AVC,
    }

    private lateinit var renderer: CameraRenderer
    private var cameraSource = CameraSource.NONE
    private var teardownScheduled = false
    private var localSurfaceDetached = false
    private var teardownFinished = false
    private val localDetachCallbacks = mutableListOf<() -> Unit>()
    private val teardownCallbacks = mutableListOf<() -> Unit>()
    private var cameraCommandGeneration = 0L

    /** The presentation's onCreate made [renderer]. */
    fun attach(renderer: CameraRenderer) {
        this.renderer = renderer
    }

    override fun onReady(details: String) = events.onAvcReady(cameraCommandGeneration, details)

    override fun onFailure(details: String) = events.onAvcFailure(cameraCommandGeneration, details)

    override fun onLocalSurfaceReleased() = markLocalSurfaceDetached()

    override fun onFirstFrame(details: String) = events.onAvcFirstFrame(cameraCommandGeneration, details)

    /** What the presentation's own dismiss does, whoever calls it - the platform included. */
    fun dismiss() {
        dismissAfterSurfaceRelease()
    }

    fun dismissAfterSurfaceRelease(
        onLocalSurfaceDetached: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        var shouldSchedule = false
        synchronized(teardownCallbacks) {
            onLocalSurfaceDetached?.let(localDetachCallbacks::add)
            onComplete?.let(teardownCallbacks::add)
            if (!teardownFinished && !teardownScheduled) {
                teardownScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) scheduleVendorRelease()
        completeLocalDetachCallbacks()
        completeTeardownCallbacks()
    }

    // Remove the window first so the last camera buffer cannot remain
    // visible while the vendor freeDisplay binder call is running. The
    // binder call then runs on the dedicated teardown thread: it keeps the
    // verified surface-before-freeDisplay order (the post executes strictly
    // after dismiss returned) and cannot freeze the main thread when the
    // vendor process is a crash-dump zombie - live trials measured 4-5 s
    // blocked binder calls into com.byd.avc right after its SIGSEGV.
    private fun scheduleVendorRelease() {
        try {
            views.removeWindow()
        } finally {
            if (!renderer.hasLocalSurfaceHandle()) markLocalSurfaceDetached()
            teardownThread.post {
                val startedAt = clock.elapsedRealtime()
                try {
                    stopActiveCamera()
                } finally {
                    synchronized(teardownCallbacks) { teardownFinished = true }
                    log.i(
                        "vendor display freed in " +
                            "${clock.elapsedRealtime() - startedAt}ms",
                    )
                    completeTeardownCallbacks()
                }
            }
        }
    }

    private fun markLocalSurfaceDetached() {
        synchronized(teardownCallbacks) {
            if (!teardownScheduled) return
            localSurfaceDetached = true
        }
        completeLocalDetachCallbacks()
    }

    private fun completeLocalDetachCallbacks() {
        val callbacks = synchronized(teardownCallbacks) {
            if (!localSurfaceDetached) return
            val drained = localDetachCallbacks.toList()
            localDetachCallbacks.clear()
            drained
        }
        callbacks.forEach { it() }
    }

    private fun completeTeardownCallbacks() {
        val callbacks = synchronized(teardownCallbacks) {
            if (!teardownFinished) return
            val drained = teardownCallbacks.toList()
            teardownCallbacks.clear()
            drained
        }
        callbacks.forEach { it() }
    }

    fun showCamera(config: MirrorCameraConfig, commandGeneration: Long) {
        stopActiveCamera()
        cameraCommandGeneration = commandGeneration
        hideDiagnostic()
        views.layoutCamera(config)
        cameraSource = CameraSource.AVC
        renderer.start(
            if (config.side == MirrorSide.LEFT) LEFT_VIEWPOINT else RIGHT_VIEWPOINT,
            config.processingEnabled,
        )
    }

    fun showMap(placement: ClusterMapPlacement, consumer: MapSurfaceConsumer) =
        views.showMap(placement, consumer)

    fun hideMap() = views.hideMap()

    fun showDashboard(placement: ClusterMapPlacement) = views.showDashboard(placement)

    fun hideDashboard() = views.hideDashboard()

    fun hideCamera() {
        stopActiveCamera()
        views.hideCameraFrame()
    }

    private fun stopActiveCamera() {
        when (cameraSource) {
            CameraSource.AVC -> if (::renderer.isInitialized) renderer.stop()
            CameraSource.NONE -> Unit
        }
        cameraSource = CameraSource.NONE
    }

    fun showDiagnostic(position: MirrorsPosition, visible: Boolean) {
        hideCamera()
        views.drawDiagnostic(position, visible)
    }

    fun hideDiagnostic() = views.hideDiagnostic()

    private companion object {
        // AVC's global viewpoint: 3205 (VIEW_PIP_DOUBLE) for the left camera, where the stock
        // left PIP uses 3203, and 3204 for the right (instrument-display-findings.md, "Who owns
        // the renderer, precisely").
        const val LEFT_VIEWPOINT = 3205
        const val RIGHT_VIEWPOINT = 3204
    }
}
