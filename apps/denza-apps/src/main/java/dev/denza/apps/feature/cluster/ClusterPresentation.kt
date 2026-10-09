package dev.denza.apps.feature.cluster

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
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
import dev.denza.apps.feature.cluster.dashboard.ClusterDashboardLayout
import dev.denza.apps.feature.cluster.dashboard.ClusterDashboardView
import dev.denza.apps.feature.mirrors.AvcCameraRenderer
import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * One window of the scene on a driver's display: the views [SceneView] stacks, laid out and shown
 * as its [SceneLayer] asks. The layer, not this class, decides when AVC is started and let go.
 *
 * What stays here is glue, held by ClusterPresentationGlueTest: a dismiss from anyone - the
 * platform's own on display removal included - goes through [layer], and only the layer's
 * [removeWindow] reaches Dialog.dismiss; [buildViews] stacks this layer kind's views; the two
 * adapters route each renderer call and event to its own counterpart. [displayId] is the id
 * DisplayManager was asked for, the one [display] carries.
 */
internal class ClusterPresentation(
    context: Context,
    display: Display,
    displayId: Int,
    events: AvcEvents,
    private val cameraLayer: Boolean,
    teardownThread: TeardownThread,
    clock: SceneClock,
    log: SceneLog,
) : Presentation(context, display), SceneLayerViews {
    /** This presentation's lifecycle, without Android: what the scene holds. */
    val layer = SceneLayer(displayId, this, events, teardownThread, clock, log)
    lateinit var mapSurface: SurfaceView
        private set
    private lateinit var mapShade: ProjectionEdgeShadeView
    private lateinit var dashboardLayer: FrameLayout
    private lateinit var cameraTexture: TextureView
    private lateinit var cameraFrame: FrameLayout
    private lateinit var cameraEdgeShade: EdgeShadeView
    private lateinit var diagnosticLayer: FrameLayout
    private var dashboardPlacement: ClusterMapPlacement? = null
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

    // Presentation window setup mirrors the verified platform API sequence. The window type is
    // the platform's to choose: Presentation builds a TYPE_PRESENTATION (2037) window context,
    // and setting TYPE_APPLICATION_OVERLAY (2038) here makes every show() throw a window-type
    // mismatch out of WindowManagerImpl. Set flags only.
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
        }

        val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        // Base and camera already have separate presentations/displays. Do not construct or
        // attach an unused map SurfaceView, shade and dashboard container for every turn.
        // The camera still creates a fresh window/texture only on Show; no idle prewarming.
        buildViews { view -> addSceneView(root, view) }
        setContentView(root)

        val avc = AvcCameraRenderer(context, cameraTexture, layer.asAvcListener())
        layer.attach(cameraRendererOf(avc::start, avc::stop, avc::hasLocalSurfaceHandle))
    }

    /** Hands [add] this layer's views bottom first, as [SceneView.stackFor] stacks its kind. */
    internal fun buildViews(add: (SceneView) -> Unit) {
        SceneView.stackFor(cameraLayer).forEach(add)
    }

    /** Builds [view] and adds it on top of what [root] holds; [SceneView] says the order. */
    private fun addSceneView(root: FrameLayout, view: SceneView) {
        when (view) {
            SceneView.MAP_SURFACE -> {
                mapSurface = SurfaceView(context).apply {
                    setZOrderOnTop(false)
                    visibility = View.INVISIBLE
                    holder.addCallback(mapSurfaceCallback)
                }
                root.addView(mapSurface, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            }
            SceneView.MAP_SHADE -> {
                mapShade = ProjectionEdgeShadeView(context).apply { visibility = View.INVISIBLE }
                root.addView(mapShade, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            }
            SceneView.DASHBOARD -> {
                dashboardLayer = FrameLayout(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    visibility = View.GONE
                }
                root.addView(
                    dashboardLayer,
                    FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START),
                )
            }
            SceneView.CAMERA -> {
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
            }
            SceneView.DIAGNOSTIC -> {
                diagnosticLayer = FrameLayout(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    visibility = View.GONE
                }
                root.addView(diagnosticLayer, matchParent())
            }
        }
    }

    override fun dismiss() {
        layer.dismiss()
    }

    override fun removeWindow() {
        super.dismiss()
    }

    override fun layoutCamera(config: MirrorCameraConfig) {
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
    }

    override fun showMap(placement: ClusterMapPlacement, consumer: MapSurfaceConsumer) {
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

    override fun hideMap() {
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
    override fun showDashboard(placement: ClusterMapPlacement) {
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

    override fun hideDashboard() {
        dashboardPlacement = null
        dashboardLayer.visibility = View.GONE
        // Removing the view is what releases the telemetry poll: the dashboard holds it open
        // for as long as it is attached, precisely because the Activity is not running then.
        dashboardLayer.removeAllViews()
    }

    override fun hideCameraFrame() {
        cameraFrame.visibility = View.GONE
    }

    override fun drawDiagnostic(position: MirrorsPosition, visible: Boolean) {
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

    override fun hideDiagnostic() {
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

/** The renderer seam over AvcCameraRenderer's own three calls, which onCreate hands it. */
internal fun cameraRendererOf(
    startRenderer: (viewpoint: Int, processingEnabled: Boolean) -> Unit,
    stopRenderer: () -> Unit,
    holdsLocalSurface: () -> Boolean,
): CameraRenderer = object : CameraRenderer {
    override fun start(viewpoint: Int, processingEnabled: Boolean) =
        startRenderer(viewpoint, processingEnabled)

    override fun stop() = stopRenderer()

    override fun hasLocalSurfaceHandle(): Boolean = holdsLocalSurface()
}

/** AvcCameraRenderer's listener, each event passed to its own counterpart in [this]. */
internal fun CameraRendererEvents.asAvcListener(): AvcCameraRenderer.Listener {
    val events = this
    return object : AvcCameraRenderer.Listener {
        override fun onReady(details: String) = events.onReady(details)
        override fun onFailure(details: String) = events.onFailure(details)
        override fun onLocalSurfaceReleased() = events.onLocalSurfaceReleased()
        override fun onFirstFrame(details: String) = events.onFirstFrame(details)
    }
}
