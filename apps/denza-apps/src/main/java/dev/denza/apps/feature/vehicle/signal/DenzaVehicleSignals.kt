package dev.denza.apps.feature.vehicle.signal

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellProxyJar
import dev.denza.apps.platform.shell.ShellProxyStager

/** Main-process composition root. Secondary Denza Apps processes may not create car transports. */
internal object DenzaVehicleSignals {
    @Volatile private var instance: VehicleSignalHub? = null

    fun hub(context: Context): VehicleSignalHub {
        val app = context.applicationContext
        check(Application.getProcessName() == app.packageName) {
            "vehicle signal hub is available only in the Denza Apps main process"
        }
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(app).also { instance = it }
        }
    }

    private fun create(app: Context): VehicleSignalHub {
        val client = DenzaLocalAdb.client(app)
        // Staging failure disables the listener; it never falls back to loading the whole APK.
        val stager = ShellProxyStager(
            helper = ShellProxyJar.VEHICLE_SIGNAL,
            jar = { app.assets.open(ShellProxyJar.VEHICLE_SIGNAL.asset).use { it.readBytes() } },
            log = { Log.i(TAG, it) },
        )
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { nonce, requestedKeys ->
                AdbTurnSignalEventChannel(
                    session = client.openResidentSession(nonce),
                    bootstrap = client::openPersistentShell,
                    nonce = nonce,
                    stager = stager,
                    requestedKeys = requestedKeys,
                )
            },
        )
        return VehicleSignalHub(listOf(source), clock = SystemClock::elapsedRealtime)
    }

    private const val TAG = "DenzaVehicleSignals"
}
