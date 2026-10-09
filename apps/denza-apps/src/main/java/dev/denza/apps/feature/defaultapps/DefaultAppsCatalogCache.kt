package dev.denza.apps.feature.defaultapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.denza.apps.AppIcons
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice

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
                // The pictures go where the screen draws them from, so no chooser reads them
                // again - and only from a read no package change has overtaken, or a picture the
                // change has just dropped would come back.
                loaded.forEach { app -> AppIcons.put(app.packageName, app.icon) }
                cachedInstalled = loaded
                cachedLaunchable = loaded.mapTo(linkedSetOf(), InstalledDefaultApp::packageName)
            }
        }
        return loaded
    }

    fun installedIfCached(): List<InstalledDefaultApp>? = synchronized(cacheLock) {
        cachedInstalled
    }

    /**
     * The car's applications may have changed. Everything on the dashboard that names or needs an
     * installed application is marked to be read again ([StateSlice.PACKAGES]): DiShare, the
     * navigator, the projection's row, the driver's-screen choice and the split's launcher icon.
     */
    fun invalidate() {
        synchronized(cacheLock) {
            generation += 1L
            cachedLaunchable = null
            cachedInstalled = null
        }
        StateMarks.mark(StateSlice.PACKAGES, "package changed")
    }

    /**
     * Watches package changes for as long as the process lives.
     *
     * [onPackage] sees each broadcast before [onChanged] is told, so it reads what the app knew of
     * the roles before the refresh that [onChanged] starts can read them again.
     */
    fun ensureWatching(
        context: Context,
        onPackage: (Intent) -> Unit = {},
        onChanged: () -> Unit,
    ) {
        synchronized(watcherLock) {
            if (watching) return
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action !in PACKAGE_CHANGE_ACTIONS) return
                    intent.data?.schemeSpecificPart?.let(AppIcons::forget)
                    onPackage(intent)
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
