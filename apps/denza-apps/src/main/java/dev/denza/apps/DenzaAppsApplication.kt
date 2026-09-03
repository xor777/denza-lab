package dev.denza.apps

import android.app.Application
import dev.denza.apps.core.DenzaRuntimeCoordinator

/** Starts the product runtime even when BYD autoload opens no activity. */
class DenzaAppsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!DenzaProcessPolicy.shouldBootstrap(packageName, Application.getProcessName())) return
        DenzaRuntimeCoordinator.bootstrap(this)
    }
}

internal object DenzaProcessPolicy {
    fun shouldBootstrap(packageName: String, processName: String?): Boolean =
        processName == packageName
}
