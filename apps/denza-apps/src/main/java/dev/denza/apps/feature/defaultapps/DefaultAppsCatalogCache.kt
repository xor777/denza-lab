package dev.denza.apps.feature.defaultapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Process-wide launcher catalog, invalidated by package changes and explicit refreshes. */
internal object DefaultAppsCatalogCache {
    private val cacheLock = Any()
    private val watcherLock = Any()

    private var generation = 0L
    private var cachedLaunchable: Set<String>? = null
    private var cachedInstalled: List<InstalledDefaultApp>? = null
    private var watching = false
    private var packageReceiver: BroadcastReceiver? = null

    fun launchablePackages(context: Context): Set<String> {
        val started = synchronized(cacheLock) {
            cachedLaunchable?.let { return it }
            generation
        }
        val loaded = DefaultAppsCatalog.launchablePackages(context.applicationContext)
        synchronized(cacheLock) {
            if (generation == started) cachedLaunchable = loaded
        }
        return loaded
    }

    fun installed(context: Context): List<InstalledDefaultApp> {
        val started = synchronized(cacheLock) {
            cachedInstalled?.let { return it }
            generation
        }
        val loaded = DefaultAppsCatalog.discover(context.applicationContext)
        synchronized(cacheLock) {
            if (generation == started) {
                cachedInstalled = loaded
                cachedLaunchable = loaded.mapTo(linkedSetOf(), InstalledDefaultApp::packageName)
            }
        }
        return loaded
    }

    fun installedIfCached(): List<InstalledDefaultApp>? = synchronized(cacheLock) {
        cachedInstalled
    }

    fun invalidate() {
        synchronized(cacheLock) {
            generation += 1L
            cachedLaunchable = null
            cachedInstalled = null
        }
    }

    fun ensureWatching(context: Context, onChanged: () -> Unit) {
        synchronized(watcherLock) {
            if (watching) return
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action !in PACKAGE_CHANGE_ACTIONS) return
                    invalidate()
                    onChanged()
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter().apply {
                    PACKAGE_CHANGE_ACTIONS.forEach(::addAction)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            packageReceiver = receiver
            watching = true
        }
    }

    private val PACKAGE_CHANGE_ACTIONS = setOf(
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REMOVED,
        Intent.ACTION_PACKAGE_REPLACED,
        Intent.ACTION_PACKAGE_CHANGED,
    )
}
