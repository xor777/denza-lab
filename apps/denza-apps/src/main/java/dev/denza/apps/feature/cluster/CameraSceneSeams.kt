package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorsPosition

/*
 * What the camera lifecycle touches, one interface each.
 *
 * The rule that this app never crashes com.byd.avc rests on an order of calls: the window comes
 * down before freeDisplay, freeDisplay runs on its own thread, no two teardowns overlap, a stale
 * Show never starts. The service, its presentations and the renderer are Android classes a unit
 * test cannot create, so that order could only be checked by reading their source. Behind these
 * interfaces the same lifecycle runs in a test against a recording fake, call for call; the
 * Android implementations make exactly the calls the service made before.
 */

/** The AVC renderer one presentation drives: `AvcCameraRenderer` on the car. */
internal interface CameraRenderer {
    /** Binds com.byd.avc; once bound with a surface it calls initDisplay, then setViewpoint. */
    fun start(viewpoint: Int, processingEnabled: Boolean)

    /** freeDisplay (unless AVC took its renderer back), unbind, release the local surface. */
    fun stop()

    /** Whether the renderer still holds the Surface it made from the TextureView. */
    fun hasLocalSurfaceHandle(): Boolean
}

/**
 * What the renderer reports, on the thread it happens on: the binder callbacks and the
 * TextureView's on main, [onLocalSurfaceReleased] also from [CameraRenderer.stop] wherever that runs.
 */
internal interface CameraRendererEvents {
    fun onReady(details: String)

    fun onFailure(details: String)

    fun onLocalSurfaceReleased()

    /** The first TextureView update after READY, not proof of scanout. */
    fun onFirstFrame(details: String)
}

/** What a camera layer's renderer reports, tagged with the Show it belongs to. */
internal interface AvcEvents {
    fun onAvcReady(commandGeneration: Long, details: String)

    fun onAvcFailure(commandGeneration: Long, details: String)

    fun onAvcFirstFrame(commandGeneration: Long, details: String)
}

/** Where the scene finds its displays and opens a presentation on one: the service. */
internal interface SceneLayers {
    /** The camera overlay's resolver for the camera layer, the instruments' for the base. */
    fun resolve(cameraLayer: Boolean): ClusterDisplaySelection

    /**
     * Shows a new presentation on [displayId] and returns its layer, or null when the display is
     * gone or the window could not be shown. [events] hears the layer's renderer.
     */
    fun open(displayId: Int, cameraLayer: Boolean, events: AvcEvents): SceneLayer?
}

/** One presentation's window and views, as its camera and diagnostic lifecycle drives them. */
internal interface SceneLayerViews {
    /** Takes the window down (`Presentation.dismiss` itself); the TextureView goes with it. */
    fun removeWindow()

    /** Sizes and places the camera frame for [config] and makes it visible. */
    fun layoutCamera(config: MirrorCameraConfig)

    fun hideCameraFrame()

    /** Replaces whatever the diagnostic layer held with the panels for [position]. */
    fun drawDiagnostic(position: MirrorsPosition, visible: Boolean)

    fun hideDiagnostic()

    fun showMap(placement: ClusterMapPlacement, consumer: MapSurfaceConsumer)

    fun hideMap()

    fun showDashboard(placement: ClusterMapPlacement)

    fun hideDashboard()
}

/**
 * The service's own main-thread handler. [removeAll] clears what was posted through it and
 * nothing else on the main looper: a Hide posted through [MainLooper] survives it.
 */
internal interface SceneHandler {
    fun postDelayed(task: Runnable, delayMs: Long)

    fun removeCallbacks(task: Runnable)

    fun removeAll()
}

/** The main looper, reached from whichever thread a caller is on. */
internal interface MainLooper {
    fun isCurrent(): Boolean

    fun post(task: Runnable)
}

/**
 * The `denza-avc-teardown` thread vendor freeDisplay runs on, so that a crash-dump zombie
 * com.byd.avc cannot freeze the main thread.
 */
internal fun interface TeardownThread {
    fun post(task: Runnable)
}

internal fun interface SceneClock {
    fun elapsedRealtime(): Long
}

/**
 * The scene's logcat lines, tag `DenzaClusterScene`. `tools/analyze_mirrors_startup.py` parses
 * several of them, so their words are part of what a car run leaves behind.
 */
internal interface SceneLog {
    fun i(message: String)

    fun w(message: String)

    fun e(message: String, error: Throwable)
}
