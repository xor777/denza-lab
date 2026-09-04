package dev.denza.apps.feature.cluster

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import dev.denza.apps.MainActivity
import dev.denza.apps.R
import dev.denza.apps.feature.cluster.dashboard.ClusterDashboardLayout
import dev.denza.apps.feature.cluster.dashboard.ClusterDashboardView
import dev.denza.apps.feature.mirrors.AvcCameraRenderer
import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One instrument-display scene: positioned map surface, camera overlay, diagnostics on top. */
class ClusterSceneService : Service() {
    private enum class CameraSource {
        NONE,
        AVC,
    }

    private val handler = Handler(Looper.getMainLooper())
    private var basePresentation: ClusterPresentation? = null
    private var cameraPresentation: ClusterPresentation? = null
    private var cameraTeardownPresentation: ClusterPresentation? = null

    override fun onCreate() {
        super.onCreate()
        active = this
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing instrument display"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cameraCommandFence.invalidate()
                stopScene()
            }
            ACTION_HIDE_CAMERA -> {
                cameraCommandFence.invalidate()
                hideCamera()
            }
            ACTION_SHOW_CAMERA -> {
                val generation = intent.getLongExtra(EXTRA_CAMERA_COMMAND_GENERATION, -1L)
                if (cameraCommandFence.isCurrent(generation)) {
                    showCamera(intent.cameraConfig(), generation)
                } else {
                    Log.i(TAG, "stale showCamera rejected; generation=$generation")
                }
            }
            ACTION_SHOW_MAP -> showMap(intent.mapPlacement())
            ACTION_HIDE_MAP -> hideMap()
            ACTION_SHOW_DASHBOARD -> showDashboard(intent.mapPlacement())
            ACTION_HIDE_DASHBOARD -> hideDashboard()
            ACTION_PREVIEW -> showPreview(
                position = intent.position(),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 1_000L),
                cameraOverlay = true,
            )
            ACTION_PREVIEW_BASE -> showPreview(
                position = intent.position(),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 1_000L),
                cameraOverlay = false,
            )
            else -> prepareBaseScene()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cameraCommandFence.invalidate()
        handler.removeCallbacksAndMessages(null)
        stopScene(stopService = false)
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prepareBaseScene(): ClusterPresentation? {
        val selection = ClusterDisplayResolver.resolve(this)
        return prepareScene(selection, overlayWindow = false, cameraLayer = false)
    }

    private fun prepareCameraScene(): ClusterPresentation? {
        val selection = ClusterDisplayResolver.resolveCameraOverlay(this)
        return prepareScene(selection, overlayWindow = true, cameraLayer = true)
    }

    private fun prepareScene(
        selection: ClusterDisplaySelection,
        overlayWindow: Boolean,
        cameraLayer: Boolean,
    ): ClusterPresentation? {
        if (selection !is ClusterDisplaySelection.Selected) {
            updateNotification(
                if (selection is ClusterDisplaySelection.NeedsVerification) {
                    if (cameraLayer) "Camera display needs verification"
                    else "Choose the instrument display in Support"
                } else {
                    if (cameraLayer) "Camera display not found"
                    else "Instrument display not found"
                },
            )
            return null
        }
        val currentPresentation = if (cameraLayer) cameraPresentation else basePresentation
        currentPresentation?.let { current ->
            if (current.display.displayId == selection.display.id) return current
            if (cameraLayer) {
                beginCameraTeardown(current, "camera display changed")
                return null
            } else {
                current.dismiss()
                basePresentation = null
            }
        }
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = manager?.getDisplay(selection.display.id)
        if (display == null || !display.isValid) {
            updateNotification(if (cameraLayer) "Camera display disappeared" else "Instrument display disappeared")
            return null
        }
        fun show(useOverlayWindow: Boolean): ClusterPresentation =
            ClusterPresentation(
                this,
                display,
                useOverlayWindow,
                ::onAvcReady,
                ::onAvcFailure,
            ).also { it.show() }

        return try {
            val shown = try {
                show(overlayWindow)
            } catch (overlayError: RuntimeException) {
                if (!overlayWindow) throw overlayError
                Log.w(TAG, "Overlay presentation failed; retrying normal camera window", overlayError)
                show(false)
            }
            if (cameraLayer) cameraPresentation = shown else basePresentation = shown
            updateNotification(if (cameraLayer) "Camera display is ready" else "Instrument display is ready")
            shown
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to show ${if (cameraLayer) "camera" else "instrument"} presentation", error)
            updateNotification(if (cameraLayer) "Camera display needs attention" else "Instrument display needs attention")
            null
        }
    }

    private fun showCamera(config: MirrorCameraConfig, commandGeneration: Long) {
        if (
            cameraRuntime.snapshot().phase == CameraRuntimePhase.STOPPING ||
            !cameraTeardownBarrier.isClear
        ) {
            Log.i(TAG, "showCamera ${config.side} rejected: cleanup in progress")
            updateNotification("Waiting for camera cleanup")
            return
        }
        Log.i(TAG, "showCamera ${config.side}")
        cameraRuntime.starting(config.side)
        val scene = prepareCameraScene()
        if (scene == null) {
            if (cameraRuntime.snapshot().phase != CameraRuntimePhase.STOPPING) {
                cameraRuntime.failed("camera presentation unavailable")
            }
            return
        }
        try {
            handler.removeCallbacksAndMessages(null)
            scene.showCamera(config, commandGeneration)
            updateNotification("Starting ${config.side.name.lowercase()} mirror")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start camera renderer", error)
            val failure = "camera start failed: ${error::class.java.simpleName}"
            val presentation = cameraPresentation
            if (presentation == null) {
                cameraRuntime.failed(failure)
            } else {
                beginCameraTeardown(presentation, failure)
            }
            updateNotification("Camera stopped safely")
        }
    }

    private fun hideCamera(
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
                // A previous service instance still owns the teardown. Vendor completion implies
                // its local surface has also been released, but this instance cannot observe the
                // earlier local milestone separately.
                cameraTeardownBarrier.whenClear {
                    onLocalSurfaceDetached?.invoke()
                    onComplete?.invoke()
                }
            } else {
                cameraRuntime.idle("camera hidden")
                updateNotification("Mirrors are ready")
                onLocalSurfaceDetached?.invoke()
                onComplete?.invoke()
            }
            return
        }

        Log.i(TAG, "hideCamera: releasing surface")
        beginCameraTeardown(
            presentation = presentation,
            onLocalSurfaceDetached = onLocalSurfaceDetached,
            onComplete = onComplete,
        )
    }

    /** The only path that may retire a camera-layer presentation. */
    private fun beginCameraTeardown(
        presentation: ClusterPresentation,
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
                Log.i(TAG, "hideCamera: surface released, display freed")
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
                        updateNotification("Mirrors are ready")
                    } else {
                        cameraRuntime.failed(finalFailure)
                        updateNotification("Camera stopped safely")
                    }
                }
                if (!cameraTeardownBarrier.complete(teardownToken)) {
                    Log.e(TAG, "ignored stale AVC presentation teardown completion")
                }
                onComplete?.invoke()
            },
        )
    }

    private fun showMap(placement: ClusterMapPlacement) {
        val consumer = pendingMapConsumer ?: return
        val scene = prepareBaseScene() ?: return
        pendingMapConsumer = null
        scene.showMap(placement, consumer)
        updateNotification("Navigation display is ready")
    }

    private fun showDashboard(placement: ClusterMapPlacement) {
        val scene = prepareBaseScene() ?: return
        scene.showDashboard(placement)
    }

    private fun hideDashboard() {
        basePresentation?.hideDashboard()
    }

    private fun hideMap() {
        basePresentation?.hideMap()
    }

    private fun showPreview(
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
            Log.i(TAG, "camera preview rejected: camera lifecycle is active")
            return
        }
        val scene = if (cameraOverlay) prepareCameraScene() else prepareBaseScene()
        scene ?: return
        scene.showDiagnostic(position, visible)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ scene.hideDiagnostic() }, durationMs.coerceIn(250L, 5_000L))
    }

    private fun onAvcReady(commandGeneration: Long, details: String) {
        if (!cameraCommandFence.isCurrent(commandGeneration)) {
            Log.i(TAG, "stale AVC ready ignored; generation=$commandGeneration")
            return
        }
        val runtime = cameraRuntime.snapshot()
        if (runtime.phase != CameraRuntimePhase.STARTING) return
        lastCameraDetails = details
        Log.i(
            TAG,
            "AVC ready; generation=$commandGeneration side=${runtime.side} details=$details",
        )
        runtime.side?.let { cameraRuntime.ready(it, details) }
    }

    private fun onAvcFailure(commandGeneration: Long, details: String) {
        if (!cameraCommandFence.isCurrent(commandGeneration)) {
            Log.i(TAG, "stale AVC failure ignored; generation=$commandGeneration")
            return
        }
        val runtime = cameraRuntime.snapshot()
        if (
            runtime.phase != CameraRuntimePhase.STARTING &&
            runtime.phase != CameraRuntimePhase.READY
        ) return
        lastCameraDetails = details
        Log.w(
            TAG,
            "AVC failure; generation=$commandGeneration side=${runtime.side} details=$details",
        )
        val presentation = cameraPresentation
        if (presentation == null) {
            cameraRuntime.failed(details)
            updateNotification("Camera stopped safely")
        } else {
            beginCameraTeardown(presentation, details)
        }
    }

    private fun stopScene(stopService: Boolean = true) {
        hideCamera()
        basePresentation?.dismiss()
        basePresentation = null
        if (stopService) stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Instrument display", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_denza_apps)
            .setContentTitle("Denza Apps")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private class ClusterPresentation(
        context: Context,
        display: Display,
        private val overlayWindow: Boolean,
        private val ready: (Long, String) -> Unit,
        private val failed: (Long, String) -> Unit,
    ) : Presentation(context, display) {
        lateinit var mapSurface: SurfaceView
            private set
        private lateinit var mapShade: ProjectionEdgeShadeView
        private lateinit var dashboardLayer: FrameLayout
        private lateinit var cameraTexture: TextureView
        private lateinit var cameraFrame: FrameLayout
        private lateinit var cameraEdgeShade: EdgeShadeView
        private lateinit var diagnosticLayer: FrameLayout
        private lateinit var renderer: AvcCameraRenderer
        private var cameraSource = CameraSource.NONE
        private var dashboardPlacement: ClusterMapPlacement? = null
        private var teardownScheduled = false
        private var localSurfaceDetached = false
        private var teardownFinished = false
        private val localDetachCallbacks = mutableListOf<() -> Unit>()
        private val teardownCallbacks = mutableListOf<() -> Unit>()
        private var cameraCommandGeneration = 0L
        private var mapConsumer: MapSurfaceConsumer? = null
        private var expectedMapWidth = 0
        private var expectedMapHeight = 0
        private var expectedMapDensityDpi = 0
        private val mapSurfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) =
                dispatchMapSurface(holder.surface, mapSurface.width, mapSurface.height)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                dispatchMapSurface(holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }

        // Presentation window setup mirrors the verified platform API sequence.
        @SuppressLint("UseKtx")
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                )
                if (overlayWindow) {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
            }

            val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
            mapSurface = SurfaceView(context).apply {
                setZOrderOnTop(false)
                visibility = View.INVISIBLE
                holder.addCallback(mapSurfaceCallback)
            }
            root.addView(mapSurface, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            mapShade = ProjectionEdgeShadeView(context).apply { visibility = View.INVISIBLE }
            root.addView(mapShade, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))

            // After the shade on purpose. The shade darkens whatever is beneath it so a projected
            // map cannot cover instrument data; the dashboard needs no such protection because it
            // places its own blocks off the stock graphics to begin with, and darkening it twice
            // would only cost contrast.
            dashboardLayer = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
            root.addView(
                dashboardLayer,
                FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START),
            )

            cameraFrame = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                clipChildren = true
                clipToPadding = true
                visibility = View.GONE
            }
            cameraTexture = TextureView(context).apply { isOpaque = true }
            cameraFrame.addView(cameraTexture, matchParent(Gravity.CENTER))
            cameraEdgeShade = EdgeShadeView(context)
            cameraFrame.addView(cameraEdgeShade, matchParent())
            root.addView(cameraFrame, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))

            diagnosticLayer = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
            root.addView(diagnosticLayer, matchParent())
            setContentView(root)

            renderer = AvcCameraRenderer(context, cameraTexture, object : AvcCameraRenderer.Listener {
                override fun onReady(details: String) = ready(cameraCommandGeneration, details)
                override fun onFailure(details: String) = failed(cameraCommandGeneration, details)
                override fun onLocalSurfaceReleased() = markLocalSurfaceDetached()
            })
        }

        override fun dismiss() {
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
                super.dismiss()
            } finally {
                if (!renderer.hasLocalSurfaceHandle()) markLocalSurfaceDetached()
                vendorTeardownHandler.post {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    try {
                        stopActiveCamera()
                    } finally {
                        synchronized(teardownCallbacks) { teardownFinished = true }
                        Log.i(
                            TAG,
                            "vendor display freed in " +
                                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
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
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val cameraPosition = when {
                config.position == MirrorsPosition.CENTER -> ClusterCameraPosition.CENTER
                config.side == MirrorSide.LEFT -> ClusterCameraPosition.LEFT
                else -> ClusterCameraPosition.RIGHT
            }
            val layout = ClusterLayout(metrics.widthPixels, metrics.heightPixels, cameraPosition)
            cameraFrame.layoutParams = FrameLayout.LayoutParams(
                layout.cameraWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                when (cameraPosition) {
                    ClusterCameraPosition.LEFT -> Gravity.START or Gravity.TOP
                    ClusterCameraPosition.RIGHT -> Gravity.END or Gravity.TOP
                    ClusterCameraPosition.CENTER -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
                },
            )
            // Preserve the proven asymmetric crop: only the left camera uses a
            // double-width source surface; the right camera stays uncropped.
            cameraTexture.layoutParams = if (config.side == MirrorSide.LEFT) {
                FrameLayout.LayoutParams(
                    (layout.cameraWidth * 2).coerceAtMost(metrics.widthPixels * 2),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.START or Gravity.TOP,
                )
            } else {
                matchParent(Gravity.CENTER)
            }
            cameraTexture.alpha = 1f
            cameraTexture.rotation = 0f
            cameraTexture.scaleX = 1f
            cameraTexture.scaleY = 1f
            cameraTexture.setTransform(Matrix())
            cameraEdgeShade.visibility = View.VISIBLE
            cameraFrame.visibility = View.VISIBLE
            cameraSource = CameraSource.AVC
            renderer.start(
                if (config.side == MirrorSide.LEFT) LEFT_VIEWPOINT else RIGHT_VIEWPOINT,
                config.processingEnabled,
            )
        }

        fun showMap(placement: ClusterMapPlacement, consumer: MapSurfaceConsumer) {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val layout = ClusterMapLayout(
                metrics.widthPixels,
                metrics.heightPixels,
                placement,
            )
            val bounds = layout.surfaceBounds
            expectedMapWidth = bounds.right - bounds.left
            expectedMapHeight = bounds.bottom - bounds.top
            expectedMapDensityDpi = metrics.densityDpi * layout.densityScalePercent / 100
            mapConsumer = consumer
            mapSurface.layoutParams = mapParams(bounds)
            mapSurface.holder.setFixedSize(expectedMapWidth, expectedMapHeight)
            mapShade.layoutParams = mapParams(bounds)
            mapShade.configure(
                top = layout.shadeTop,
                bottom = layout.shadeBottom,
                heightDp = layout.shadeHeightDp,
                topAlpha = layout.shadeTopAlpha,
                bottomAlpha = layout.shadeBottomAlpha,
                bottomTopAlpha = layout.shadeBottomTopAlpha,
                bottomFadePx = layout.shadeBottomFadePx,
                bottomSolidPx = layout.shadeBottomSolidPx,
                bottomRevealRadiusPx = layout.shadeBottomRevealRadiusPx,
                bottomRevealHeightPercent = layout.shadeBottomRevealHeightPercent,
                bottomRevealCenterOffsetPx = layout.shadeBottomRevealCenterOffsetPx,
                topLeftRevealRadiusPx = layout.shadeTopLeftRevealRadiusPx,
                topRightRevealRadiusPx = layout.shadeTopRightRevealRadiusPx,
                topRevealHeightPx = layout.shadeTopRevealHeightPx,
                centerTopFadePx = layout.shadeCenterTopFadePx,
                corner = layout.shadeCorner,
            )
            mapSurface.visibility = View.VISIBLE
            mapShade.visibility = if (
                layout.shadeTop || layout.shadeBottom || layout.shadeCorner != null
            ) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            mapSurface.requestLayout()
            mapSurface.post {
                dispatchMapSurface(mapSurface.holder.surface, mapSurface.width, mapSurface.height)
            }
        }

        fun hideMap() {
            mapConsumer = null
            expectedMapWidth = 0
            expectedMapHeight = 0
            expectedMapDensityDpi = 0
            mapShade.visibility = View.INVISIBLE
            mapSurface.visibility = View.INVISIBLE
        }

        /**
         * Hosts this app's own instrument dashboard in the chosen placement.
         *
         * Nothing here touches [mapSurface] or [mapConsumer], so no virtual display is created and
         * nothing is projected: the dashboard is a plain view drawing into a window we already own.
         * That is the whole difference from [showMap], and it is what makes this path free of the
         * shell commands, task moves and vendor surfaces the projection path needs.
         */
        fun showDashboard(placement: ClusterMapPlacement) {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val layout = ClusterDashboardLayout(
                metrics.widthPixels,
                metrics.heightPixels,
                placement,
            )
            if (!layout.supported) {
                hideDashboard()
                return
            }
            if (dashboardPlacement != placement || dashboardLayer.childCount == 0) {
                dashboardPlacement = placement
                dashboardLayer.removeAllViews()
                dashboardLayer.addView(ClusterDashboardView(context, layout), matchParent())
            }
            dashboardLayer.layoutParams = mapParams(layout.bounds)
            dashboardLayer.visibility = View.VISIBLE
        }

        fun hideDashboard() {
            dashboardPlacement = null
            dashboardLayer.visibility = View.GONE
            // Removing the view is what releases the telemetry poll: the dashboard holds it open
            // for as long as it is attached, precisely because the Activity is not running then.
            dashboardLayer.removeAllViews()
        }

        fun hideCamera() {
            stopActiveCamera()
            cameraFrame.visibility = View.GONE
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
            diagnosticLayer.removeAllViews()
            if (!visible) {
                diagnosticLayer.addView(View(context).apply { setBackgroundColor(Color.TRANSPARENT) }, matchParent())
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (position == MirrorsPosition.SIDES) {
                    diagnosticLayer.addView(
                        DiagnosticView(context, "LEFT"),
                        diagnosticParams(metrics.widthPixels, ClusterCameraPosition.LEFT),
                    )
                    diagnosticLayer.addView(
                        DiagnosticView(context, "RIGHT"),
                        diagnosticParams(metrics.widthPixels, ClusterCameraPosition.RIGHT),
                    )
                } else {
                    diagnosticLayer.addView(
                        DiagnosticView(context, "CENTER"),
                        diagnosticParams(metrics.widthPixels, ClusterCameraPosition.CENTER),
                    )
                }
            }
            diagnosticLayer.visibility = View.VISIBLE
        }

        fun hideDiagnostic() {
            diagnosticLayer.removeAllViews()
            diagnosticLayer.visibility = View.GONE
        }

        private fun diagnosticParams(width: Int, position: ClusterCameraPosition): FrameLayout.LayoutParams {
            val layout = ClusterLayout(width, 1, position)
            val gravity = when (position) {
                ClusterCameraPosition.LEFT -> Gravity.START
                ClusterCameraPosition.RIGHT -> Gravity.END
                ClusterCameraPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            }
            return FrameLayout.LayoutParams(
                layout.cameraWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                gravity or Gravity.TOP,
            )
        }

        private fun dispatchMapSurface(surface: Surface?, width: Int, height: Int) {
            if (surface == null || !surface.isValid) return
            if (width != expectedMapWidth || height != expectedMapHeight) return
            mapConsumer?.onSurface(
                surface,
                width,
                height,
                expectedMapDensityDpi,
            )
        }

        private fun mapParams(bounds: ClusterBounds) = FrameLayout.LayoutParams(
            bounds.right - bounds.left,
            bounds.bottom - bounds.top,
            Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
        }

        private fun matchParent(gravity: Int = Gravity.TOP or Gravity.START) =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                gravity,
            )
    }

    /** OpenBYD-compatible navigation contrast shades, strengthened for the center panel. */
    // Explicit save/restore calls make the destructive blend scope auditable.
    @SuppressLint("UseKtx")
    private class ProjectionEdgeShadeView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        private var topShader: Shader? = null
        private var bottomShader: Shader? = null
        private var cornerShader: Shader? = null
        private var bottomRevealShader: Shader? = null
        private var topLeftRevealShader: Shader? = null
        private var topRightRevealShader: Shader? = null
        private var fadeHeight = (90f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        private var shadeTopAlpha = 204
        private var shadeBottomAlpha = 204
        private var shadeBottomTopAlpha = 0
        private var shadeBottomFadePx = 0
        private var shadeBottomSolidPx = 0
        private var shadeBottomRevealRadiusPx = 0
        private var shadeBottomRevealHeightPercent = 0
        private var shadeBottomRevealCenterOffsetPx = 0
        private var shadeTopLeftRevealRadiusPx = 0
        private var shadeTopRightRevealRadiusPx = 0
        private var shadeTopRevealHeightPx = 0
        private var shadeCenterTopFadePx = 0
        private var shadeTop = true
        private var shadeBottom = true
        private var shadeCorner: ClusterShadeCorner? = null

        fun configure(
            top: Boolean,
            bottom: Boolean,
            heightDp: Int,
            topAlpha: Int,
            bottomAlpha: Int,
            bottomTopAlpha: Int,
            bottomFadePx: Int,
            bottomSolidPx: Int,
            bottomRevealRadiusPx: Int,
            bottomRevealHeightPercent: Int,
            bottomRevealCenterOffsetPx: Int,
            topLeftRevealRadiusPx: Int,
            topRightRevealRadiusPx: Int,
            topRevealHeightPx: Int,
            centerTopFadePx: Int,
            corner: ClusterShadeCorner?,
        ) {
            shadeTop = top
            shadeBottom = bottom
            shadeCorner = corner
            fadeHeight = (heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            shadeTopAlpha = topAlpha.coerceIn(0, 255)
            shadeBottomAlpha = bottomAlpha.coerceIn(0, 255)
            shadeBottomTopAlpha = bottomTopAlpha.coerceIn(0, 255)
            shadeBottomFadePx = bottomFadePx.coerceAtLeast(0)
            shadeBottomSolidPx = bottomSolidPx.coerceAtLeast(0)
            shadeBottomRevealRadiusPx = bottomRevealRadiusPx.coerceAtLeast(0)
            shadeBottomRevealHeightPercent = bottomRevealHeightPercent.coerceIn(0, 100)
            shadeBottomRevealCenterOffsetPx = bottomRevealCenterOffsetPx.coerceAtLeast(0)
            shadeTopLeftRevealRadiusPx = topLeftRevealRadiusPx.coerceAtLeast(0)
            shadeTopRightRevealRadiusPx = topRightRevealRadiusPx.coerceAtLeast(0)
            shadeTopRevealHeightPx = topRevealHeightPx.coerceAtLeast(0)
            shadeCenterTopFadePx = centerTopFadePx.coerceAtLeast(0)
            rebuildShaders()
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            rebuildShaders()
        }

        private fun rebuildShaders() {
            if (width <= 0 || height <= 0) {
                topShader = null
                bottomShader = null
                cornerShader = null
                bottomRevealShader = null
                topLeftRevealShader = null
                topRightRevealShader = null
                return
            }
            val edge = fadeHeight.coerceAtMost(height).toFloat()
            val clear = Color.TRANSPARENT
            val topDark = Color.argb(shadeTopAlpha, 0, 0, 0)
            topShader = LinearGradient(
                0f,
                0f,
                0f,
                edge,
                topDark,
                clear,
                Shader.TileMode.CLAMP,
            )
            val bottomDark = Color.argb(shadeBottomAlpha, 0, 0, 0)
            val topTint = Color.argb(shadeBottomTopAlpha, 0, 0, 0)
            val solidHeight = shadeBottomSolidPx.coerceAtMost(height)
            val bottomFadeHeight = shadeBottomFadePx.coerceAtMost(height - solidHeight)
            val fadeTop = height - solidHeight - bottomFadeHeight
            val solidTop = height - solidHeight
            val topFade = shadeCenterTopFadePx.coerceAtMost(fadeTop)
            bottomShader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(topTint, clear, clear, bottomDark, bottomDark),
                floatArrayOf(
                    0f,
                    topFade.toFloat() / height,
                    fadeTop.toFloat() / height,
                    solidTop.toFloat() / height,
                    1f,
                ),
                Shader.TileMode.CLAMP,
            )
            cornerShader = shadeCorner?.let { corner ->
                RadialGradient(
                    if (corner == ClusterShadeCorner.TOP_LEFT) 0f else width.toFloat(),
                    0f,
                    edge.coerceAtLeast(1f),
                    topDark,
                    clear,
                    Shader.TileMode.CLAMP,
                )
            }
            val bottomRadius = shadeBottomRevealRadiusPx.toFloat()
            val bottomCenterY = (height - shadeBottomRevealCenterOffsetPx)
                .coerceAtLeast(0)
                .toFloat()
            bottomRevealShader = if (bottomRadius > 0f) {
                RadialGradient(
                    width / 2f,
                    bottomCenterY,
                    bottomRadius,
                    intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.30f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                null
            }
            topLeftRevealShader = revealShader(0f, shadeTopLeftRevealRadiusPx)
            topRightRevealShader = revealShader(width.toFloat(), shadeTopRightRevealRadiusPx)
        }

        private fun revealShader(centerX: Float, radiusPx: Int): Shader? {
            if (radiusPx <= 0) return null
            return RadialGradient(
                centerX,
                0f,
                radiusPx.toFloat(),
                Color.WHITE,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val revealLayer = if (
                shadeBottomRevealRadiusPx > 0 ||
                    shadeTopLeftRevealRadiusPx > 0 ||
                    shadeTopRightRevealRadiusPx > 0
            ) {
                canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            } else {
                null
            }
            val edge = fadeHeight.coerceAtMost(height)
            if (shadeTop) {
                paint.shader = topShader
                canvas.drawRect(0f, 0f, width.toFloat(), edge.toFloat(), paint)
            }
            if (shadeBottom) {
                paint.shader = bottomShader
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            shadeCorner?.let { corner ->
                paint.shader = cornerShader
                val left = if (corner == ClusterShadeCorner.TOP_LEFT) 0f else width - edge.toFloat()
                val right = if (corner == ClusterShadeCorner.TOP_LEFT) edge.toFloat() else width.toFloat()
                canvas.drawRect(left.coerceAtLeast(0f), 0f, right.coerceAtMost(width.toFloat()), edge.toFloat(), paint)
            }
            if (shadeBottomRevealRadiusPx > 0 && shadeBottomRevealHeightPercent > 0) {
                val centerX = width / 2f
                val centerY = (height - shadeBottomRevealCenterOffsetPx)
                    .coerceAtLeast(0)
                    .toFloat()
                val radius = shadeBottomRevealRadiusPx.toFloat()
                val revealSave = canvas.save()
                canvas.scale(
                    1f,
                    shadeBottomRevealHeightPercent / 100f,
                    centerX,
                    centerY,
                )
                paint.shader = bottomRevealShader
                paint.xfermode = eraseMode
                canvas.drawCircle(centerX, centerY, radius, paint)
                paint.xfermode = null
                canvas.restoreToCount(revealSave)
            }
            if (shadeTopRevealHeightPx > 0) {
                drawTopReveal(
                    canvas,
                    0f,
                    shadeTopLeftRevealRadiusPx,
                    topLeftRevealShader,
                )
                drawTopReveal(
                    canvas,
                    width.toFloat(),
                    shadeTopRightRevealRadiusPx,
                    topRightRevealShader,
                )
            }
            paint.shader = null
            revealLayer?.let(canvas::restoreToCount)
        }

        private fun drawTopReveal(
            canvas: Canvas,
            centerX: Float,
            radiusPx: Int,
            shader: Shader?,
        ) {
            if (radiusPx <= 0 || shader == null) return
            val radius = radiusPx.toFloat()
            val revealSave = canvas.save()
            canvas.scale(1f, shadeTopRevealHeightPx / radius, centerX, 0f)
            paint.shader = shader
            paint.xfermode = eraseMode
            canvas.drawCircle(centerX, 0f, radius, paint)
            paint.xfermode = null
            canvas.restoreToCount(revealSave)
        }
    }

    private class EdgeShadeView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var topShader: Shader? = null
        private var bottomShader: Shader? = null
        private var fadeHeight = 1

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            fadeHeight = (height * 0.20f).toInt().coerceAtLeast(1)
            val dark = Color.argb(179, 0, 0, 0)
            val clear = Color.TRANSPARENT
            topShader = LinearGradient(
                0f,
                0f,
                0f,
                fadeHeight.toFloat(),
                dark,
                clear,
                Shader.TileMode.CLAMP,
            )
            bottomShader = LinearGradient(
                0f,
                (height - fadeHeight).toFloat(),
                0f,
                height.toFloat(),
                clear,
                dark,
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = topShader
            canvas.drawRect(0f, 0f, width.toFloat(), fadeHeight.toFloat(), paint)
            paint.shader = bottomShader
            canvas.drawRect(0f, (height - fadeHeight).toFloat(), width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
    }

    private class DiagnosticView(context: Context, private val label: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(10, 24, 28)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val inset = (width * 0.04f).coerceAtLeast(8f)
            paint.color = Color.rgb(20, 156, 132)
            canvas.drawRect(inset, inset, width - inset, height - inset, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = (height * 0.10f).coerceAtLeast(34f)
            canvas.drawText(label, width / 2f, height * 0.50f, paint)
        }
    }

    private fun Intent.cameraConfig() = MirrorCameraConfig(
        side = if (getStringExtra(EXTRA_SIDE) == MirrorSide.RIGHT.name) MirrorSide.RIGHT else MirrorSide.LEFT,
        position = position(),
        processingEnabled = getBooleanExtra(EXTRA_PROCESSING, true),
    )

    private fun Intent.position() = if (getStringExtra(EXTRA_POSITION) == MirrorsPosition.CENTER.name) {
        MirrorsPosition.CENTER
    } else {
        MirrorsPosition.SIDES
    }

    private fun Intent.mapPlacement() = runCatching {
        ClusterMapPlacement.valueOf(
            getStringExtra(EXTRA_MAP_PLACEMENT) ?: ClusterMapPlacement.FULL.name,
        )
    }.getOrDefault(ClusterMapPlacement.FULL)

    companion object {
        private const val TAG = "DenzaClusterScene"
        private const val CHANNEL_ID = "denza_cluster_scene"
        private const val NOTIFICATION_ID = 4202
        private const val LEFT_VIEWPOINT = 3205
        private const val RIGHT_VIEWPOINT = 3204
        private const val ACTION_PREPARE = "dev.denza.apps.cluster.PREPARE"
        private const val ACTION_STOP = "dev.denza.apps.cluster.STOP"
        private const val ACTION_SHOW_CAMERA = "dev.denza.apps.cluster.SHOW_CAMERA"
        private const val ACTION_HIDE_CAMERA = "dev.denza.apps.cluster.HIDE_CAMERA"
        private const val ACTION_PREVIEW = "dev.denza.apps.cluster.PREVIEW"
        private const val ACTION_PREVIEW_BASE = "dev.denza.apps.cluster.PREVIEW_BASE"
        private const val ACTION_SHOW_MAP = "dev.denza.apps.cluster.SHOW_MAP"
        private const val ACTION_HIDE_MAP = "dev.denza.apps.cluster.HIDE_MAP"
        private const val ACTION_SHOW_DASHBOARD = "dev.denza.apps.cluster.SHOW_DASHBOARD"
        private const val ACTION_HIDE_DASHBOARD = "dev.denza.apps.cluster.HIDE_DASHBOARD"
        private const val EXTRA_SIDE = "side"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_PROCESSING = "processing"
        private const val EXTRA_CAMERA_COMMAND_GENERATION = "camera_command_generation"
        private const val EXTRA_VISIBLE = "visible"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_MAP_PLACEMENT = "map_placement"

        @Volatile private var active: ClusterSceneService? = null
        @Volatile private var pendingMapConsumer: MapSurfaceConsumer? = null
        private val cameraRuntime = CameraRuntimeTracker()
        private val cameraCommandFence = CameraCommandFence()
        private val cameraTeardownBarrier = CameraTeardownBarrier()

        // Vendor binder calls (freeDisplay) run here so a crash-dump zombie
        // com.byd.avc cannot freeze the app main thread or the guard.
        private val vendorTeardownHandler by lazy {
            val thread = android.os.HandlerThread("denza-avc-teardown")
            thread.start()
            Handler(thread.looper)
        }
        @Volatile var lastCameraDetails: String = ""
            private set

        fun prepare(context: Context) = start(context, ACTION_PREPARE)

        fun showCamera(context: Context, config: MirrorCameraConfig) {
            val generation = cameraCommandFence.issueShow()
            val intent = serviceIntent(context, ACTION_SHOW_CAMERA)
                .putExtra(EXTRA_SIDE, config.side.name)
                .putExtra(EXTRA_POSITION, config.position.name)
                .putExtra(EXTRA_PROCESSING, config.processingEnabled)
                .putExtra(EXTRA_CAMERA_COMMAND_GENERATION, generation)
            context.startForegroundService(intent)
        }

        fun preview(
            context: Context,
            position: MirrorsPosition,
            visible: Boolean,
            durationMs: Long,
        ) {
            context.startForegroundService(
                serviceIntent(context, ACTION_PREVIEW)
                    .putExtra(EXTRA_POSITION, position.name)
                    .putExtra(EXTRA_VISIBLE, visible)
                    .putExtra(EXTRA_DURATION, durationMs),
            )
        }

        fun previewBase(
            context: Context,
            position: MirrorsPosition,
            visible: Boolean,
            durationMs: Long,
        ) {
            context.startForegroundService(
                serviceIntent(context, ACTION_PREVIEW_BASE)
                    .putExtra(EXTRA_POSITION, position.name)
                    .putExtra(EXTRA_VISIBLE, visible)
                    .putExtra(EXTRA_DURATION, durationMs),
            )
        }

        fun showMap(
            context: Context,
            placement: ClusterMapPlacement,
            consumer: MapSurfaceConsumer,
        ) {
            pendingMapConsumer = consumer
            context.startForegroundService(
                serviceIntent(context, ACTION_SHOW_MAP)
                    .putExtra(EXTRA_MAP_PLACEMENT, placement.name),
            )
        }

        fun hideMap(context: Context) {
            context.startService(serviceIntent(context, ACTION_HIDE_MAP))
        }

        /**
         * Shows this app's own instruments on the driver's display.
         *
         * Unlike [showMap] this stages nothing beforehand: there is no surface to hand out and no
         * consumer to register, so the action carries everything the service needs.
         */
        fun showDashboard(context: Context, placement: ClusterMapPlacement) {
            context.startForegroundService(
                serviceIntent(context, ACTION_SHOW_DASHBOARD)
                    .putExtra(EXTRA_MAP_PLACEMENT, placement.name),
            )
        }

        fun hideDashboard(context: Context) {
            context.startService(serviceIntent(context, ACTION_HIDE_DASHBOARD))
        }

        fun hideCameraSync(timeoutMs: Long): Boolean {
            cameraCommandFence.invalidate()
            val service = active ?: return true
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideCamera()
                return true
            }
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
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
            Handler(Looper.getMainLooper()).post {
                service.hideCamera(onLocalSurfaceDetached, onVendorFreeCompleted)
            }
            return generation
        }

        fun stop(context: Context) {
            context.startService(serviceIntent(context, ACTION_STOP))
        }

        fun cameraRuntimeSnapshot(): CameraRuntimeSnapshot = cameraRuntime.snapshot()

        private fun start(context: Context, action: String) {
            context.startForegroundService(serviceIntent(context, action))
        }

        private fun serviceIntent(context: Context, action: String) =
            Intent(context, ClusterSceneService::class.java).setAction(action)
    }
}

fun interface MapSurfaceConsumer {
    fun onSurface(surface: Surface, width: Int, height: Int, densityDpi: Int)
}
