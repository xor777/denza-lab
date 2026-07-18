package dev.denza.apps.feature.cluster

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
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import dev.denza.apps.feature.mirrors.AvcCameraRenderer
import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One instrument-display scene: full-size map base, camera overlay, diagnostics on top. */
class ClusterSceneService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: ClusterPresentation? = null

    override fun onCreate() {
        super.onCreate()
        active = this
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing instrument display"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopScene()
            ACTION_HIDE_CAMERA -> hideCamera()
            ACTION_SHOW_CAMERA -> showCamera(intent.cameraConfig())
            ACTION_SHOW_MAP -> showMap()
            ACTION_HIDE_MAP -> hideMap()
            ACTION_PREVIEW -> showPreview(
                position = intent.position(),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 1_000L),
            )
            else -> prepareScene()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScene(stopService = false)
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prepareScene(): ClusterPresentation? {
        presentation?.let { return it }
        val selection = ClusterDisplayResolver.resolve(this)
        if (selection !is ClusterDisplaySelection.Selected) {
            updateNotification(
                if (selection is ClusterDisplaySelection.NeedsVerification) {
                    "Choose the instrument display in Support"
                } else {
                    "Instrument display not found"
                },
            )
            stopSelf()
            return null
        }
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = manager?.getDisplay(selection.display.id)
        if (display == null || !display.isValid) {
            updateNotification("Instrument display disappeared")
            stopSelf()
            return null
        }
        return try {
            ClusterPresentation(this, display, ::onAvcReady, ::onAvcFailure).also {
                it.show()
                presentation = it
                updateNotification("Instrument display is ready")
            }
        } catch (_: RuntimeException) {
            updateNotification("Instrument display needs attention")
            stopSelf()
            null
        }
    }

    private fun showCamera(config: MirrorCameraConfig) {
        val scene = prepareScene() ?: return
        handler.removeCallbacksAndMessages(null)
        scene.showCamera(config)
        updateNotification("Showing ${config.side.name.lowercase()} mirror")
    }

    private fun hideCamera() {
        presentation?.hideCamera()
        updateNotification("Mirrors are ready")
    }

    private fun showMap() {
        val consumer = pendingMapConsumer ?: return
        val scene = prepareScene() ?: return
        pendingMapConsumer = null
        scene.showMap(consumer)
        updateNotification("Navigation display is ready")
    }

    private fun hideMap() {
        presentation?.hideMap()
    }

    private fun showPreview(position: MirrorsPosition, visible: Boolean, durationMs: Long) {
        val scene = prepareScene() ?: return
        scene.showDiagnostic(position, visible)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ scene.hideDiagnostic() }, durationMs.coerceIn(250L, 5_000L))
    }

    private fun onAvcReady(details: String) {
        lastCameraDetails = details
    }

    private fun onAvcFailure(details: String) {
        lastCameraDetails = details
        avcFailureGeneration.incrementAndGet()
        presentation?.hideCamera()
        updateNotification("Camera stopped safely")
    }

    private fun stopScene(stopService: Boolean = true) {
        presentation?.dismiss()
        presentation = null
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
        private val ready: (String) -> Unit,
        private val failed: (String) -> Unit,
    ) : Presentation(context, display) {
        lateinit var mapSurface: SurfaceView
            private set
        private lateinit var cameraTexture: TextureView
        private lateinit var cameraFrame: FrameLayout
        private lateinit var diagnosticLayer: FrameLayout
        private lateinit var renderer: AvcCameraRenderer
        private var mapConsumer: MapSurfaceConsumer? = null
        private val mapSurfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = dispatchMapSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                dispatchMapSurface(holder.surface)
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }

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
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }

            val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
            mapSurface = SurfaceView(context).apply {
                setZOrderOnTop(false)
                visibility = View.INVISIBLE
                holder.addCallback(mapSurfaceCallback)
            }
            root.addView(mapSurface, matchParent())

            cameraFrame = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                clipChildren = true
                clipToPadding = true
                visibility = View.GONE
            }
            cameraTexture = TextureView(context).apply { isOpaque = true }
            cameraFrame.addView(cameraTexture, matchParent(Gravity.CENTER))
            cameraFrame.addView(EdgeShadeView(context), matchParent())
            root.addView(cameraFrame, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))

            diagnosticLayer = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
            root.addView(diagnosticLayer, matchParent())
            setContentView(root)

            renderer = AvcCameraRenderer(context, cameraTexture, object : AvcCameraRenderer.Listener {
                override fun onReady(details: String) = ready(details)
                override fun onFailure(details: String) = failed(details)
            })
        }

        override fun dismiss() {
            if (::renderer.isInitialized) renderer.stop()
            super.dismiss()
        }

        fun showCamera(config: MirrorCameraConfig) {
            hideDiagnostic()
            renderer.stop()
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
            cameraFrame.visibility = View.VISIBLE
            renderer.start(
                if (config.side == MirrorSide.LEFT) LEFT_VIEWPOINT else RIGHT_VIEWPOINT,
                config.processingEnabled,
            )
        }

        fun showMap(consumer: MapSurfaceConsumer) {
            mapConsumer = consumer
            mapSurface.visibility = View.VISIBLE
            dispatchMapSurface(mapSurface.holder.surface)
        }

        fun hideMap() {
            mapConsumer = null
            mapSurface.visibility = View.INVISIBLE
        }

        fun hideCamera() {
            if (::renderer.isInitialized) renderer.stop()
            cameraFrame.visibility = View.GONE
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

        private fun dispatchMapSurface(surface: Surface?) {
            if (surface == null || !surface.isValid) return
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            mapConsumer?.onSurface(
                surface,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
            )
        }

        private fun matchParent(gravity: Int = Gravity.TOP or Gravity.START) =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                gravity,
            )
    }

    private class EdgeShadeView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val fadeHeight = (height * 0.20f).toInt().coerceAtLeast(1)
            val dark = Color.argb(179, 0, 0, 0)
            val clear = Color.TRANSPARENT
            paint.shader = LinearGradient(0f, 0f, 0f, fadeHeight.toFloat(), dark, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), fadeHeight.toFloat(), paint)
            paint.shader = LinearGradient(
                0f,
                (height - fadeHeight).toFloat(),
                0f,
                height.toFloat(),
                clear,
                dark,
                Shader.TileMode.CLAMP,
            )
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

    companion object {
        private const val CHANNEL_ID = "denza_cluster_scene"
        private const val NOTIFICATION_ID = 4202
        private const val LEFT_VIEWPOINT = 3205
        private const val RIGHT_VIEWPOINT = 3204
        private const val ACTION_PREPARE = "dev.denza.apps.cluster.PREPARE"
        private const val ACTION_STOP = "dev.denza.apps.cluster.STOP"
        private const val ACTION_SHOW_CAMERA = "dev.denza.apps.cluster.SHOW_CAMERA"
        private const val ACTION_HIDE_CAMERA = "dev.denza.apps.cluster.HIDE_CAMERA"
        private const val ACTION_PREVIEW = "dev.denza.apps.cluster.PREVIEW"
        private const val ACTION_SHOW_MAP = "dev.denza.apps.cluster.SHOW_MAP"
        private const val ACTION_HIDE_MAP = "dev.denza.apps.cluster.HIDE_MAP"
        private const val EXTRA_SIDE = "side"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_PROCESSING = "processing"
        private const val EXTRA_VISIBLE = "visible"
        private const val EXTRA_DURATION = "duration"

        @Volatile private var active: ClusterSceneService? = null
        @Volatile private var pendingMapConsumer: MapSurfaceConsumer? = null
        private val avcFailureGeneration = AtomicLong()
        @Volatile var lastCameraDetails: String = ""
            private set

        fun prepare(context: Context) = start(context, ACTION_PREPARE)

        fun showCamera(context: Context, config: MirrorCameraConfig) {
            val intent = serviceIntent(context, ACTION_SHOW_CAMERA)
                .putExtra(EXTRA_SIDE, config.side.name)
                .putExtra(EXTRA_POSITION, config.position.name)
                .putExtra(EXTRA_PROCESSING, config.processingEnabled)
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

        fun showMap(context: Context, consumer: MapSurfaceConsumer) {
            pendingMapConsumer = consumer
            start(context, ACTION_SHOW_MAP)
        }

        fun hideMap(context: Context) {
            context.startService(serviceIntent(context, ACTION_HIDE_MAP))
        }

        fun hideCameraSync(timeoutMs: Long): Boolean {
            val service = active ?: return true
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideCamera()
                return true
            }
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                service.hideCamera()
                latch.countDown()
            }
            return latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }

        fun stop(context: Context) {
            context.startService(serviceIntent(context, ACTION_STOP))
        }

        fun avcFailureGeneration(): Long = avcFailureGeneration.get()

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
