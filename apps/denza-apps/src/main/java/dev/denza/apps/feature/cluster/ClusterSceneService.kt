package dev.denza.apps.feature.cluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.denza.apps.MainActivity
import dev.denza.apps.R
import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * One instrument-display scene: positioned map surface, camera overlay, diagnostics on top.
 *
 * Android glue around [CameraSceneController]: this service turns intents into the scene's calls,
 * keeps the foreground notification, finds displays and opens presentations on them. Which Show
 * may start a camera, when a camera layer is torn down and in what order AVC is let go are the
 * controller's and [SceneLayer]'s.
 */
class ClusterSceneService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scene = camera.Scene(
        layers = object : SceneLayers {
            override fun resolve(cameraLayer: Boolean): ClusterDisplaySelection =
                if (cameraLayer) {
                    ClusterDisplayResolver.resolveCameraOverlay(this@ClusterSceneService)
                } else {
                    ClusterDisplayResolver.resolve(this@ClusterSceneService)
                }

            override fun open(displayId: Int, cameraLayer: Boolean, events: AvcEvents): SceneLayer? =
                openPresentation(displayId, cameraLayer, events)
        },
        handler = object : SceneHandler {
            override fun postDelayed(task: Runnable, delayMs: Long) {
                handler.postDelayed(task, delayMs)
            }

            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)

            override fun removeAll() = handler.removeCallbacksAndMessages(null)
        },
    )

    override fun onCreate() {
        super.onCreate()
        scene.created()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scene.onStopAction()
                stopSelf()
            }
            ACTION_HIDE_CAMERA -> scene.onHideCameraAction()
            ACTION_SHOW_CAMERA -> scene.onShowCameraAction(
                config = intent.cameraConfig(),
                generation = intent.getLongExtra(EXTRA_CAMERA_COMMAND_GENERATION, -1L),
            )
            ACTION_SHOW_MAP -> showMap(intent.mapPlacement())
            ACTION_HIDE_MAP -> scene.hideMap()
            ACTION_SHOW_DASHBOARD -> scene.showDashboard(intent.mapPlacement())
            ACTION_HIDE_DASHBOARD -> scene.hideDashboard()
            ACTION_PREVIEW -> scene.showPreview(
                position = intent.position(),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 1_000L),
                cameraOverlay = true,
            )
            ACTION_PREVIEW_BASE -> scene.showPreview(
                position = intent.position(),
                visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 1_000L),
                cameraOverlay = false,
            )
            else -> scene.prepareBaseScene()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scene.destroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openPresentation(
        displayId: Int,
        cameraLayer: Boolean,
        events: AvcEvents,
    ): SceneLayer? {
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = manager?.getDisplay(displayId)
        if (display == null || !display.isValid) {
            sceneLog.w("${if (cameraLayer) "camera" else "instrument"} display $displayId disappeared")
            return null
        }
        return try {
            val shown = ClusterPresentation(
                this,
                display,
                displayId,
                events,
                cameraLayer = cameraLayer,
                teardownThread = vendorTeardown,
                clock = sceneClock,
                log = sceneLog,
            ).also { it.show() }
            shown.layer
        } catch (error: RuntimeException) {
            sceneLog.e("Unable to show ${if (cameraLayer) "camera" else "instrument"} presentation", error)
            null
        }
    }

    private fun showMap(placement: ClusterMapPlacement) {
        val consumer = pendingMapConsumer ?: return
        val base = scene.prepareBaseScene() ?: return
        pendingMapConsumer = null
        base.showMap(placement, consumer)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Экран водителя", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /**
     * The foreground service's notification: one steady line while the scene is up.
     *
     * It used to be rewritten at every step - «Preparing instrument display», «Showing left
     * mirror» on every turn signal, «Camera stopped safely» - in English, and for a missing display
     * «Choose the instrument display in Support», a screen the app does not have. What went wrong
     * is the tile's to say and the log's to keep.
     */
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_denza_apps)
            .setContentTitle("Denza Apps")
            .setContentText("Экран водителя")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
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

        @Volatile private var pendingMapConsumer: MapSurfaceConsumer? = null

        // Vendor binder calls (freeDisplay) run here so a crash-dump zombie
        // com.byd.avc cannot freeze the app main thread or the guard.
        private val vendorTeardownHandler by lazy {
            val thread = android.os.HandlerThread("denza-avc-teardown")
            thread.start()
            Handler(thread.looper)
        }
        private val vendorTeardown = TeardownThread { task -> vendorTeardownHandler.post(task) }
        private val mainLooper = object : MainLooper {
            override fun isCurrent(): Boolean = Looper.myLooper() == Looper.getMainLooper()

            override fun post(task: Runnable) {
                Handler(Looper.getMainLooper()).post(task)
            }
        }
        private val sceneClock = SceneClock { SystemClock.elapsedRealtime() }
        private val sceneLog = object : SceneLog {
            override fun i(message: String) {
                Log.i(TAG, message)
            }

            override fun w(message: String) {
                Log.w(TAG, message)
            }

            override fun e(message: String, error: Throwable) {
                Log.e(TAG, message, error)
            }
        }

        /** The process's one camera scene: it outlives any service instance. */
        private val camera = CameraSceneController(mainLooper, sceneClock, sceneLog)

        fun prepare(context: Context) = start(context, ACTION_PREPARE)

        fun showCamera(context: Context, config: MirrorCameraConfig) {
            val generation = camera.requestShow(config)
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

        fun hideCameraSync(timeoutMs: Long): Boolean = camera.hideCameraSync(timeoutMs)

        /**
         * Invalidates every older Show before asynchronously releasing the active AVC session.
         * Callbacks expose local-window removal separately from vendor freeDisplay completion.
         */
        fun preemptCamera(
            onLocalSurfaceDetached: () -> Unit = {},
            onVendorFreeCompleted: () -> Unit = {},
        ): Long = camera.preemptCamera(onLocalSurfaceDetached, onVendorFreeCompleted)

        fun stop(context: Context) {
            context.startService(serviceIntent(context, ACTION_STOP))
        }

        fun cameraRuntimeSnapshot(): CameraRuntimeSnapshot = camera.runtimeSnapshot()

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
