package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorsPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The driver's-display scene's decisions, without Android: which Show may start a camera, when a
 * camera layer is torn down, and that no two teardowns overlap.
 *
 * One controller lives as long as the process, because what it owns has to outlive a service
 * instance destroyed mid-teardown: the command fence (a Show dispatched before a later Hide never
 * starts), the camera runtime the monitor reads, and the teardown barrier. Each
 * `ClusterSceneService` instance gets its own [Scene], which holds that instance's presentations.
 * The monitor's calls ([preemptCamera], [hideCameraSync]) reach whichever scene is alive.
 */
internal class CameraSceneController(
    private val mainLooper: MainLooper,
    private val clock: SceneClock,
    private val log: SceneLog,
    private val cameraRuntime: CameraRuntimeTracker = CameraRuntimeTracker(),
) {
    private val cameraCommandFence = CameraCommandFence()
    private val cameraTeardownBarrier = CameraTeardownBarrier()

    @Volatile private var active: Scene? = null

    @Volatile var lastCameraDetails: String = ""
        private set

    fun runtimeSnapshot(): CameraRuntimeSnapshot = cameraRuntime.snapshot()

    /** The generation a Show intent carries; any later Hide or Show makes it stale. */
    fun requestShow(config: MirrorCameraConfig): Long {
        val generation = cameraCommandFence.issueShow()
        log.i("camera request; generation=$generation side=${config.side} at_ms=${clock.elapsedRealtime()}")
        return generation
    }

    fun hideCameraSync(timeoutMs: Long): Boolean {
        cameraCommandFence.invalidate()
        val service = active ?: return true
        if (mainLooper.isCurrent()) {
            service.hideCamera()
            return true
        }
        val latch = CountDownLatch(1)
        mainLooper.post {
            service.hideCamera(latch::countDown)
        }
        return latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    /**
     * Invalidates every older Show before asynchronously releasing the active AVC session.
     * Callbacks expose local-window removal separately from vendor freeDisplay completion.
     */
    fun preemptCamera(
        onLocalSurfaceDetached: () -> Unit = {},
        onVendorFreeCompleted: () -> Unit = {},
    ): Long {
        val generation = cameraCommandFence.invalidate()
        val service = active
        if (service == null) {
            if (cameraTeardownBarrier.isClear) {
                onLocalSurfaceDetached()
                onVendorFreeCompleted()
            } else {
                cameraTeardownBarrier.whenClear {
                    onLocalSurfaceDetached()
                    onVendorFreeCompleted()
                }
            }
            return generation
        }
        mainLooper.post {
            service.hideCamera(onLocalSurfaceDetached, onVendorFreeCompleted)
        }
        return generation
    }

    /** One ClusterSceneService instance's scene: its base and camera presentations. */
    inner class Scene(
        private val layers: SceneLayers,
        private val handler: SceneHandler,
    ) : AvcEvents {
        private val diagnosticHides = DiagnosticHideTimers(
            postDelayed = { task, delayMs -> handler.postDelayed(task, delayMs) },
            remove = { task -> handler.removeCallbacks(task) },
        )
        private var basePresentation: SceneLayer? = null
        private var cameraPresentation: SceneLayer? = null
        private var cameraTeardownPresentation: SceneLayer? = null

        /** The service's onCreate. */
        fun created() {
            active = this
        }

        /** The service's onDestroy. */
        fun destroyed() {
            cameraCommandFence.invalidate()
            handler.removeAll()
            stopScene()
            if (active === this) active = null
        }

        /** ACTION_STOP, before the service stops itself. */
        fun onStopAction() {
            cameraCommandFence.invalidate()
            stopScene()
        }

        fun onHideCameraAction() {
            cameraCommandFence.invalidate()
            hideCamera()
        }

        fun onShowCameraAction(config: MirrorCameraConfig, generation: Long) {
            if (cameraCommandFence.isCurrent(generation)) {
                showCamera(config, generation)
            } else {
                log.i("stale showCamera rejected; generation=$generation")
            }
        }

        fun prepareBaseScene(): SceneLayer? {
            val selection = layers.resolve(cameraLayer = false)
            return prepareScene(selection, cameraLayer = false)
        }

        private fun prepareCameraScene(): SceneLayer? {
            val selection = layers.resolve(cameraLayer = true)
            return prepareScene(selection, cameraLayer = true)
        }

        private fun prepareScene(
            selection: ClusterDisplaySelection,
            cameraLayer: Boolean,
        ): SceneLayer? {
            if (selection !is ClusterDisplaySelection.Selected) {
                // The tile says it («Экран не найден», «Экран не выбран»); the notification is not
                // a second place to tell the driver, and it used to send them to a «Support» screen.
                log.w("no ${if (cameraLayer) "camera" else "instrument"} display: $selection")
                return null
            }
            val currentPresentation = if (cameraLayer) cameraPresentation else basePresentation
            currentPresentation?.let { current ->
                if (current.displayId == selection.display.id) return current
                if (cameraLayer) {
                    beginCameraTeardown(current, "camera display changed")
                    return null
                } else {
                    current.dismiss()
                    basePresentation = null
                }
            }
            val shown = layers.open(selection.display.id, cameraLayer, this) ?: return null
            if (cameraLayer) cameraPresentation = shown else basePresentation = shown
            return shown
        }

        private fun showCamera(config: MirrorCameraConfig, commandGeneration: Long) {
            if (
                cameraRuntime.snapshot().phase == CameraRuntimePhase.STOPPING ||
                !cameraTeardownBarrier.isClear
            ) {
                log.i("showCamera ${config.side} rejected: cleanup in progress")
                return
            }
            log.i("showCamera ${config.side}; generation=$commandGeneration at_ms=${clock.elapsedRealtime()}")
            cameraRuntime.starting(config.side)
            val scene = prepareCameraScene()
            if (scene == null) {
                if (cameraRuntime.snapshot().phase != CameraRuntimePhase.STOPPING) {
                    cameraRuntime.failed("camera presentation unavailable")
                }
                return
            }
            try {
                // The camera replaces this layer's diagnostic (SceneLayer.showCamera hides it), so
                // this layer's pending hide goes with it. Nothing else on the handler is the
                // camera's to drop: the base layer's hide is still due over the instruments.
                diagnosticHides.cancel(cameraLayer = true)
                scene.showCamera(config, commandGeneration)
            } catch (error: RuntimeException) {
                log.e("Unable to start camera renderer", error)
                val failure = "camera start failed: ${error::class.java.simpleName}"
                val presentation = cameraPresentation
                if (presentation == null) {
                    cameraRuntime.failed(failure)
                } else {
                    beginCameraTeardown(presentation, failure)
                }
            }
        }

        fun hideCamera(
            onLocalSurfaceDetached: (() -> Unit)? = null,
            onComplete: (() -> Unit)? = null,
        ) {
            val presentation = cameraPresentation
            cameraPresentation = null
            if (presentation == null) {
                val teardown = cameraTeardownPresentation
                if (teardown != null) {
                    teardown.dismissAfterSurfaceRelease(onLocalSurfaceDetached, onComplete)
                } else if (!cameraTeardownBarrier.isClear) {
                    // A previous service instance still owns the teardown. Vendor completion
                    // implies its local surface has also been released, but this instance cannot
                    // observe the earlier local milestone separately.
                    cameraTeardownBarrier.whenClear {
                        onLocalSurfaceDetached?.invoke()
                        onComplete?.invoke()
                    }
                } else {
                    cameraRuntime.idle("camera hidden")
                    onLocalSurfaceDetached?.invoke()
                    onComplete?.invoke()
                }
                return
            }

            log.i("hideCamera: releasing surface")
            beginCameraTeardown(
                presentation = presentation,
                onLocalSurfaceDetached = onLocalSurfaceDetached,
                onComplete = onComplete,
            )
        }

        /** The only path that may retire a camera-layer presentation. */
        private fun beginCameraTeardown(
            presentation: SceneLayer,
            finalFailure: String? = null,
            onLocalSurfaceDetached: (() -> Unit)? = null,
            onComplete: (() -> Unit)? = null,
        ) {
            if (cameraTeardownPresentation === presentation) {
                presentation.dismissAfterSurfaceRelease(onLocalSurfaceDetached, onComplete)
                return
            }
            check(cameraTeardownPresentation == null) {
                "cannot overlap AVC presentation teardowns"
            }
            if (cameraPresentation === presentation) cameraPresentation = null
            cameraTeardownPresentation = presentation
            val teardownToken = cameraTeardownBarrier.begin()
            val stopping = cameraRuntime.stopping("closing camera surface")
            presentation.dismissAfterSurfaceRelease(
                onLocalSurfaceDetached,
                {
                    log.i("hideCamera: surface released, display freed")
                    if (cameraTeardownPresentation === presentation) {
                        cameraTeardownPresentation = null
                    }
                    val runtime = cameraRuntime.snapshot()
                    if (
                        runtime.phase == CameraRuntimePhase.STOPPING &&
                        runtime.generation == stopping.generation
                    ) {
                        if (finalFailure == null) {
                            cameraRuntime.idle("camera hidden")
                        } else {
                            cameraRuntime.failed(finalFailure)
                        }
                    }
                    if (!cameraTeardownBarrier.complete(teardownToken)) {
                        log.i("ignored stale AVC presentation teardown completion")
                    }
                    onComplete?.invoke()
                },
            )
        }

        fun showDashboard(placement: ClusterMapPlacement) {
            val scene = prepareBaseScene() ?: return
            scene.showDashboard(placement)
        }

        fun hideDashboard() {
            basePresentation?.hideDashboard()
        }

        fun hideMap() {
            basePresentation?.hideMap()
        }

        fun showPreview(
            position: MirrorsPosition,
            visible: Boolean,
            durationMs: Long,
            cameraOverlay: Boolean,
        ) {
            if (
                cameraOverlay &&
                (!cameraTeardownBarrier.isClear ||
                    cameraRuntime.snapshot().phase !in
                    setOf(CameraRuntimePhase.IDLE, CameraRuntimePhase.FAILED))
            ) {
                log.i("camera preview rejected: camera lifecycle is active")
                return
            }
            val scene = if (cameraOverlay) prepareCameraScene() else prepareBaseScene()
            scene ?: return
            scene.showDiagnostic(position, visible)
            diagnosticHides.schedule(cameraOverlay, durationMs.coerceIn(250L, 5_000L)) {
                scene.hideDiagnostic()
            }
        }

        override fun onAvcReady(commandGeneration: Long, details: String) {
            if (!cameraCommandFence.isCurrent(commandGeneration)) {
                log.i("stale AVC ready ignored; generation=$commandGeneration")
                return
            }
            val runtime = cameraRuntime.snapshot()
            if (runtime.phase != CameraRuntimePhase.STARTING) return
            lastCameraDetails = details
            log.i(
                "AVC ready; generation=$commandGeneration side=${runtime.side} details=$details",
            )
            runtime.side?.let { cameraRuntime.ready(it, details) }
        }

        override fun onAvcFirstFrame(commandGeneration: Long, details: String) {
            if (!cameraCommandFence.isCurrent(commandGeneration)) return
            log.i("AVC first texture update; generation=$commandGeneration $details")
        }

        override fun onAvcFailure(commandGeneration: Long, details: String) {
            if (!cameraCommandFence.isCurrent(commandGeneration)) {
                log.i("stale AVC failure ignored; generation=$commandGeneration")
                return
            }
            val runtime = cameraRuntime.snapshot()
            if (
                runtime.phase != CameraRuntimePhase.STARTING &&
                runtime.phase != CameraRuntimePhase.READY
            ) return
            lastCameraDetails = details
            log.w(
                "AVC failure; generation=$commandGeneration side=${runtime.side} details=$details",
            )
            val presentation = cameraPresentation
            if (presentation == null) {
                cameraRuntime.failed(details)
            } else {
                beginCameraTeardown(presentation, details)
            }
        }

        private fun stopScene() {
            hideCamera()
            basePresentation?.dismiss()
            basePresentation = null
        }
    }
}
