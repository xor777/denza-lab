package dev.denza.apps.feature.split

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The event-invalidated memo behind both launch catalogs (contract 1.4.2), without Android.
 *
 * Building either list costs one `queryIntentActivities` plus, for every launchable package, a
 * `getPackageInfo` and - for the BYD packages that carry one - a query against that package's own
 * `DynaConfigContentProvider`, which starts the foreign process to answer it. Measured on this car
 * that is 0.9-1.9 s and up to eight processes woken, and the open path used to pay it up to three
 * times for a single tap (`installedPackages` once, `resolve` once per pane).
 *
 * "Каталог актуален" is kept by events rather than by rescanning: an install, a change, a
 * replacement or an uninstall invalidates every list and the next read rebuilds it. Nothing else
 * may invalidate - and nothing is cached at all unless [observe] confirms the events will arrive,
 * because a cache nobody can invalidate is simply a stale catalog (1.4.2, 1.5.6).
 */
internal class SplitCatalogMemo(private val observe: () -> Boolean) {
    private val lock = Any()
    private val cached = mutableMapOf<String, Cached<*>>()
    private var generation = 0L
    private var observed = false

    private class Cached<T>(val generation: Long, val value: T)

    /** One package event, one rebuild of every list on its next read. */
    fun invalidate() {
        synchronized(lock) {
            generation += 1
            cached.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> read(key: String, load: () -> T): T {
        if (!observing()) return load()
        val started = synchronized(lock) {
            val known = cached[key]
            if (known != null && known.generation == generation) return known.value as T
            generation
        }
        // Deliberately outside the lock: the scan wakes foreign processes and must never hold the
        // actor worker or a picker's loader behind another thread doing the same work.
        val value = load()
        synchronized(lock) { if (generation == started) cached[key] = Cached(started, value) }
        return value
    }

    /** The already-scanned list, or `null` while nothing has been scanned yet. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = synchronized(lock) { cached[key]?.value as T? }

    private fun observing(): Boolean {
        synchronized(lock) { if (observed) return true }
        if (!observe()) return false
        synchronized(lock) { observed = true }
        return true
    }
}

/** Process-wide launch catalogs, invalidated by package broadcasts and by nothing else (1.4.2). */
internal object SplitLaunchCatalogCache {
    private const val TAG = "DenzaSplitCatalog"
    private const val TARGETS = "targets"
    private const val APPS = "apps"

    val PACKAGE_CHANGE_ACTIONS = setOf(
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_CHANGED,
        Intent.ACTION_PACKAGE_REMOVED,
        Intent.ACTION_PACKAGE_REPLACED,
    )

    @Volatile
    private var appContext: Context? = null

    private val memo = SplitCatalogMemo(::registerPackageReceiver)

    fun invalidate() = memo.invalidate()

    fun targets(context: Context, load: () -> List<SplitLaunchTarget>): List<SplitLaunchTarget> {
        appContext = context.applicationContext
        return memo.read(TARGETS, load)
    }

    fun apps(context: Context, load: () -> List<SplitPickerApp>): List<SplitPickerApp> {
        appContext = context.applicationContext
        return memo.read(APPS, load)
    }

    /** What a picker can paint on its first frame, or `null` while nothing has been scanned yet. */
    fun cachedApps(): List<SplitPickerApp>? = memo.peek(APPS)

    /** @return whether a package event is now guaranteed to reach [invalidate]. */
    private fun registerPackageReceiver(): Boolean {
        val app = appContext ?: return false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action in PACKAGE_CHANGE_ACTIONS) invalidate()
            }
        }
        return runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter().apply {
                    PACKAGE_CHANGE_ACTIONS.forEach(::addAction)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { error ->
            Log.w(TAG, "package events are unobservable; the catalog stays uncached", error)
        }.isSuccess
    }
}

/** Engine-side launch catalog. The visible picker owns icons and labels in its own package. */
internal object SplitPickerCatalog {
    fun load(context: Context): List<SplitLaunchTarget> =
        SplitLaunchCatalogCache.targets(context) { scan(context) }

    private fun scan(context: Context): List<SplitLaunchTarget> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return context.packageManager
            .queryIntentActivities(launcher, PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                if (
                    !SplitPickerVisibilityPolicy.isLauncherVisible(
                        packageName = packageName,
                        activityName = activityInfo.name,
                        showInAppList = readShowInAppList(context, packageName),
                    ) ||
                    !seen.add(packageName)
                ) {
                    return@mapNotNull null
                }
                SplitLaunchTarget(
                    packageName = packageName,
                    componentName = ComponentName(packageName, activityInfo.name)
                        .flattenToShortString(),
                    launchMode = activityInfo.launchMode,
                )
            }
    }

    /** A hot-path lookup: it reads the cached catalog and never scans on its own account. */
    fun resolve(context: Context, packageName: String): SplitLaunchTarget? =
        load(context).firstOrNull { it.packageName == packageName }

    /**
     * Pays for the catalog before the user asks for anything (U6, 1.13.1).
     *
     * The scan is the single slowest read of the open path and it does not depend on the tap, so it
     * happens once, off the main thread, while the app centre is still starting. It is skipped
     * while the toggle is off: a disabled product does not wake eight foreign processes (U4).
     */
    fun warm(context: Context) {
        val app = context.applicationContext
        if (!SplitScreenSettings.isEnabled(app)) return
        Thread({ runCatching { load(app) } }, "split-catalog-warm").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun readShowInAppList(context: Context, packageName: String): Boolean? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
            )
        }.getOrNull() ?: return null

        packageInfo.applicationInfo?.metaData
            ?.takeIf { it.containsKey(SHOW_IN_APP_LIST) }
            ?.get(SHOW_IN_APP_LIST)
            ?.toString()
            ?.let(SplitPickerVisibilityPolicy::parseShowInAppList)
            ?.let { return it }

        val authorities = packageInfo.providers
            .orEmpty()
            .asSequence()
            .filter { provider ->
                provider.name?.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) == true ||
                    provider.authority
                        ?.split(';')
                        ?.any { it.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) } == true
            }
            .flatMap { it.authority.orEmpty().split(';').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for (authority in authorities) {
            val value = runCatching {
                context.contentResolver.query(
                    Uri.parse("content://$authority"),
                    arrayOf(SHOW_IN_APP_LIST),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(SHOW_IN_APP_LIST)
                    if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) null
                    else SplitPickerVisibilityPolicy.parseShowInAppList(cursor.getString(index))
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private const val SHOW_IN_APP_LIST = "ShowInAppList"
    private const val DYNA_CONFIG_PROVIDER_SUFFIX = "DynaConfigContentProvider"
}

internal object SplitPickerVisibilityPolicy {
    fun isLauncherVisible(
        packageName: String,
        activityName: String,
        showInAppList: Boolean?,
    ): Boolean = activityName !in EXCLUDED_ACTIVITIES && isVisible(packageName, showInAppList)

    fun isVisible(packageName: String, showInAppList: Boolean?): Boolean =
        packageName.isNotBlank() && packageName !in EXCLUDED_PACKAGES && showInAppList != false

    fun parseShowInAppList(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    private val EXCLUDED_PACKAGES = setOf("com.android.launcher3")
    private val EXCLUDED_ACTIVITIES = setOf(
        "android.app.AppDetailsActivity",
        "dev.denza.apps.SplitScreenLauncherAlias",
    )
}
