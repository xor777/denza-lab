package dev.denza.apps

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Promotes the already user-started control task to the stable full-IVI root. */
class DenzaControlLaunchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RUNNING.compareAndSet(false, true)) return START_NOT_STICKY
        EXECUTOR.execute {
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(applicationContext).openPersistentShell()
                DenzaControlLaunchShellSession(adb::shell).moveExistingControlFullscreen()
            } catch (error: Throwable) {
                Log.i(TAG, "shell-backed fullscreen launch unavailable", error)
            } finally {
                adb?.close()
                RUNNING.set(false)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "DenzaControlLaunch"
        val RUNNING = AtomicBoolean(false)
        val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "denza-control-launch").apply { isDaemon = true }
        }
    }
}
