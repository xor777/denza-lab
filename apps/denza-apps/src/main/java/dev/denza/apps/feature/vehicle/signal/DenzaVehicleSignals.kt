package dev.denza.apps.feature.vehicle.signal

import android.app.Application
import android.content.Context
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb

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
        val classpath = TurnSignalProxyClasspath(
            jar = { app.assets.open(TurnSignalProxyClasspath.ASSET).use { it.readBytes() } },
            log = { Log.i(TAG, it) },
        )
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { nonce, requestedKeys ->
                AdbTurnSignalEventChannel(
                    session = client.openResidentSession(nonce),
                    bootstrap = client::openPersistentShell,
                    nonce = nonce,
                    classpath = classpath,
                    requestedKeys = requestedKeys,
                )
            },
        )
        return VehicleSignalHub(listOf(source))
    }

    private const val TAG = "DenzaVehicleSignals"
}
