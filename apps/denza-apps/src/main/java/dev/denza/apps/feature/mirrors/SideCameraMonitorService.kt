package dev.denza.apps.feature.mirrors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.denza.apps.MainActivity
import dev.denza.apps.R
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SideCameraMonitorService : Service() {
    private var executor: ScheduledExecutorService? = null
    private lateinit var adb: LocalAdbClient
    @Volatile private var running = false
    private var transitionState = MirrorTransitionState()
    private var lastPublishedStatus: Pair<MirrorSide?, String>? = null
    private var clusterDisplayId: Int? = null
    private var lastDisplayResolveMs = 0L

    override fun onCreate() {
        super.onCreate()
        adb = LocalAdbClient(this, "denza-apps@denza")
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitor(disableDesired = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Mirrors are ready"))
        startMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitor(disableDesired = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitor() {
        MirrorsSettings.setEnabled(this, true)
        if (running) return
        running = true
        executor = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.execute(::grantOverlayPermission)
            scheduler.scheduleWithFixedDelay(::poll, 0L, POLL_MS, TimeUnit.MILLISECONDS)
        }
        setStatus(null, "monitor running")
    }

    private fun stopMonitor(disableDesired: Boolean) {
        if (disableDesired) MirrorsSettings.setEnabled(this, false)
        running = false
        executor?.shutdownNow()
        executor = null
        ClusterSceneService.hideCameraSync(FINISH_SYNC_TIMEOUT_MS)
        transitionState = MirrorTransitionState(details = "monitor stopped")
        setStatus(null, "monitor stopped")
    }

    private fun grantOverlayPermission() {
        try {
            adb.shell("cmd appops set '${packageName}' SYSTEM_ALERT_WINDOW allow")
        } catch (error: Exception) {
            setStatus(null, "overlay access pending: ${shortError(error)}")
        }
    }

    private fun poll() {
        if (!running) return
        val now = System.currentTimeMillis()
        val displayId = resolveClusterDisplay(now) ?: return

        try {
            val windows = adb.shell("dumpsys window visible")
            val detection = SideCameraWindowDetector.analyze(windows, displayId)
            MirrorWindowDiagnostics.record(detection)
            val ambiguous = detection.avcCandidateBlocks > 0 &&
                (
                    detection.recognizedSide == null ||
                        detection.unrecognizedCandidates > 0
                )
            applyTransition(detection.recognizedSide, now, ambiguous)
        } catch (error: Exception) {
            setStatus(observedSide(), "ADB monitor error: ${shortError(error)}")
            updateNotification("ADB access needs attention")
        }
    }

    private fun resolveClusterDisplay(now: Long): Int? {
        clusterDisplayId?.let { return it }
        if (now - lastDisplayResolveMs < DISPLAY_RETRY_MS) return null
        lastDisplayResolveMs = now
        return when (val selection = ClusterDisplayResolver.resolveCameraOverlay(this)) {
            is ClusterDisplaySelection.Selected -> selection.display.id.also {
                clusterDisplayId = it
            }
            is ClusterDisplaySelection.NeedsVerification -> {
                setStatus(null, "camera overlay display is ambiguous")
                updateNotification("Camera display needs verification")
                null
            }
            ClusterDisplaySelection.Missing -> {
                setStatus(null, "camera overlay display not found")
                updateNotification("Camera display not found")
                null
            }
        }
    }

    private fun applyTransition(
        requested: MirrorSide?,
        now: Long,
        runtimeWindowAmbiguous: Boolean,
    ) {
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val result = MirrorTransitionReducer.reduce(
            transitionState,
            MirrorTransitionObservation(
                requestedSide = requested,
                runtime = runtime,
                nowMs = now,
                runtimeWindowAmbiguous = runtimeWindowAmbiguous,
            ),
        )
        if (result.state.phase != transitionState.phase) {
            Log.i(
                TAG,
                "transition ${transitionState.phase} -> ${result.state.phase}" +
                    " (${result.state.details}); requested=$requested" +
                    " ambiguous=$runtimeWindowAmbiguous runtime=${runtime.phase}",
            )
        }
        transitionState = result.state
        when (val command = result.command) {
            is MirrorTransitionCommand.Show -> {
                Log.i(TAG, "command: show ${command.side}")
                startOverlay(command.side, now, runtime)
            }
            MirrorTransitionCommand.Hide -> {
                Log.i(TAG, "command: hide")
                if (!ClusterSceneService.hideCameraSync(FINISH_SYNC_TIMEOUT_MS)) {
                    Log.w(TAG, "hide did not finish within ${FINISH_SYNC_TIMEOUT_MS}ms")
                    transitionState = MirrorTransitionReducer.quarantine(
                        transitionState,
                        ClusterSceneService.cameraRuntimeSnapshot(),
                        now,
                        "camera hide timed out",
                    )
                } else {
                    Log.i(TAG, "command: hide finished")
                }
            }
            MirrorTransitionCommand.None -> Unit
        }
        publishTransition()
    }

    private fun startOverlay(
        side: MirrorSide,
        now: Long,
        runtime: CameraRuntimeSnapshot,
    ) {
        val config = MirrorCameraConfig(
            side = side,
            position = MirrorsSettings.position(this),
            processingEnabled = MirrorsSettings.processingEnabled(this),
        )
        try {
            ClusterSceneService.showCamera(this, config)
        } catch (error: RuntimeException) {
            transitionState = MirrorTransitionReducer.quarantine(
                transitionState,
                runtime,
                now,
                "camera dispatch failed: ${shortError(error)}",
            )
        }
    }

    private fun publishTransition() {
        val side = observedSide()
        val details = transitionState.details.ifBlank {
            transitionState.phase.name.lowercase()
        }
        val status = side to details
        if (lastPublishedStatus == status) return
        lastPublishedStatus = status
        setStatus(side, details)
        updateNotification(
            when (transitionState.phase) {
                MirrorTransitionPhase.STARTING -> "Starting ${transitionState.side?.name?.lowercase()} mirror"
                MirrorTransitionPhase.SHOWING -> "Showing ${transitionState.side?.name?.lowercase()} mirror"
                MirrorTransitionPhase.QUARANTINED -> "Mirror waiting for neutral"
                MirrorTransitionPhase.IDLE -> "Mirrors are ready"
            },
        )
    }

    private fun observedSide(): MirrorSide? =
        transitionState.side.takeIf { transitionState.phase == MirrorTransitionPhase.SHOWING }

    private fun setStatus(side: MirrorSide?, details: String) {
        MirrorsSettings.setObserved(this, side, details)
        DenzaAppRepository.refresh()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mirrors", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_denza_apps)
            .setContentTitle("Denza Apps · Mirrors")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DenzaMirrorMonitor"
        private const val CHANNEL_ID = "denza_mirrors"
        private const val NOTIFICATION_ID = 4203
        private const val ACTION_START = "dev.denza.apps.mirrors.START"
        private const val ACTION_STOP = "dev.denza.apps.mirrors.STOP"
        private const val POLL_MS = 100L
        private const val DISPLAY_RETRY_MS = 2_000L
        private const val FINISH_SYNC_TIMEOUT_MS = 250L

        fun start(context: Context) {
            MirrorsSettings.setEnabled(context, true)
            context.startForegroundService(
                Intent(context, SideCameraMonitorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            MirrorsSettings.setEnabled(context, false)
            context.startService(
                Intent(context, SideCameraMonitorService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun shortError(error: Throwable): String =
            error::class.java.simpleName + error.message?.let { " $it" }.orEmpty()
    }
}
