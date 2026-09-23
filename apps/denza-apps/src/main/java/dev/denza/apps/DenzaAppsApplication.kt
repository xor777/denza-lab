package dev.denza.apps

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.denza.apps.core.DenzaRuntimeCoordinator
import dev.denza.apps.core.RuntimeStartCause
import dev.denza.apps.feature.split.SplitDiagnostics
import dev.denza.apps.feature.vehicle.VehicleSession
import java.util.concurrent.atomic.AtomicBoolean

/** Starts the product runtime even when BYD autoload opens no activity. */
class DenzaAppsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SplitDiagnostics.attach(this, Application.getProcessName())
        if (!DenzaProcessPolicy.shouldBootstrap(packageName, Application.getProcessName())) return
        ScreenOnRuntimeRecovery.register(this)
        // The road is recorded whether or not anyone looks (the energy display contract, §2.7).
        // The claim lasts for the life of the process and there is nothing to release; while no
        // screen holds one of its own the hub sweeps once a second instead of ten times.
        VehicleSession.record(this)
        DenzaRuntimeCoordinator.bootstrap(this, RuntimeStartCause.PROCESS_START)
    }
}

internal object DenzaProcessPolicy {
    fun shouldBootstrap(packageName: String, processName: String?): Boolean =
        processName == packageName
}

/**
 * A live-process wake hint, not a resurrection mechanism.
 *
 * The receiver is deliberately dynamic: SCREEN_ON cannot create a process that quickboot killed.
 * When the process did outlive a dark screen, the signal gives all enabled features one
 * coalesced reconcile pass without adding a second manifest autostart owner.
 */
internal object ScreenOnRuntimeRecovery {
    private const val TAG = "DenzaScreenOn"
    private val registered = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (ScreenOnRecoveryPolicy.shouldRecover(intent?.action)) {
                DenzaRuntimeCoordinator.bootstrap(context, RuntimeStartCause.SCREEN_ON)
            }
        }
    }

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { error ->
            registered.set(false)
            Log.w(TAG, "SCREEN_ON recovery receiver registration failed", error)
        }
    }
}

internal object ScreenOnRecoveryPolicy {
    fun shouldRecover(action: String?): Boolean = action == Intent.ACTION_SCREEN_ON
}
