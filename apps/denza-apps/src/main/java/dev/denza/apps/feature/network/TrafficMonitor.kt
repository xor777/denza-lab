package dev.denza.apps.feature.network

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors

/** Created only on opening the service page; the existing passive ADB identity owns every call. */
internal object TrafficMonitor {
    private var controller: TrafficMonitorController? = null

    @Synchronized fun of(context: Context): TrafficMonitorController = controller ?: run {
        val app = context.applicationContext
        TrafficMonitorController(
            Executors.newSingleThreadExecutor { task -> Thread(task, "denza-traffic-monitor").apply { isDaemon = true } },
        ) { command -> DenzaLocalAdb.client(app).shell(command, 8_000) }
            .also { controller = it }
    }
}
