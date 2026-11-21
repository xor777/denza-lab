package dev.denza.apps.feature.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PatternMatcher
import android.util.Log
import androidx.core.content.ContextCompat
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** Which Yandex Navigator the car has, as the HUD panel tells it apart. */
enum class HudNavigatorInstall {
    /** No Yandex Navigator: nothing to read the hints from. */
    NONE,

    /** The ordinary Navigator: this app reads its hints and map off the screen. */
    YANDEX,

    /** YandexHUD that knows this app's host: it publishes through it. */
    YANDEX_HUD,

    /** YandexHUD built before the host: it writes the HUD itself and leaves no room beside it. */
    YANDEX_HUD_ALONE,
}

/**
 * The installed navigator kept between reads ([HudInstalls.installed]): read once, then only when
 * asked to [refresh]. [read] is the package manager's answer; plain Kotlin so a test holds the
 * host's per-packet question to no read at all.
 */
internal class HudNavigatorCache<C>(private val read: (C) -> HudNavigatorInstall) {
    @Volatile
    private var known: HudNavigatorInstall? = null

    /** What was last read, or a first read. */
    fun current(context: C): HudNavigatorInstall = known ?: refresh(context) {}

    /** Reads again; [changed] runs when the answer differs from one read before. */
    @Synchronized
    fun refresh(context: C, changed: () -> Unit): HudNavigatorInstall {
        val was = known
        val now = read(context)
        known = now
        if (was != null && was != now) changed()
        return now
    }
}

/** Which HUD Speed the car has. */
enum class HudRadarInstall {
    NONE,

    /** From the store: its warnings stay on its own screen. */
    OFFICIAL,

    /** Rebuilt with the adapter, signed with its own key: its warnings reach this app's host. */
    ADAPTED,
}

/**
 * What is installed, for the HUD panel (docs/hud-output-architecture.md, 12).
 *
 * The two YandexHUD builds share the package, the key and - its number stays 1 - the version name,
 * so the name cannot tell them apart. What can is the manifest: the build that knows the host asks
 * to see this app (`<queries>`, `tools/yandex_hud_build/manifest.py` `HOST_PACKAGE`), and every
 * build before it never names it. One zip entry of ~170 KB, read once per installed build.
 */
object HudInstalls {
    private const val TAG = "DenzaHudGuidance"

    /** The package the build tool writes into YandexHUD's `<queries>`; `manifest.py` `HOST_PACKAGE`. */
    const val HOST_PACKAGE = "dev.denza.apps"

    private var knownBuild: String? = null
    private var knownHost = false

    private val cache = HudNavigatorCache<Context> { app -> navigator(app) }
    private val watchLock = Any()
    private var watching = false
    private val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "denza-hud-installs").apply { isDaemon = true }
    }

    /**
     * Which navigator is installed, as last read: what the host's filter asks on every packet
     * ([HudHostOutput]) and the map's default ([HudGuidanceSettings.isMapEnabled]). No package
     * manager call but the first; it is read again on a change of Yandex's package, a switch of the
     * panel ([HudHostRuntime.settingsChanged]) and every reading of the panel's slice ([refresh]).
     */
    fun installed(context: Context): HudNavigatorInstall {
        val app = context.applicationContext
        watch(app)
        return cache.current(app)
    }

    /**
     * Reads the navigator again and keeps it for [installed]. When it changed, the host applies the
     * panel again at once: a YandexHUD installed or removed changes what it lets through.
     */
    fun refresh(context: Context): HudNavigatorInstall {
        val app = context.applicationContext
        watch(app)
        return cache.refresh(app) { HudHostRuntime.navigatorChanged(app) }
    }

    /**
     * Yandex's package installed, replaced or removed, for as long as the process lives: the host
     * learns it here, not from the dashboard, which may not be open. Read off the main thread - a
     * new build's manifest is read once - and the panel's slice is marked to be read again.
     */
    private fun watch(app: Context) {
        synchronized(watchLock) {
            if (watching) return
            watching = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.schemeSpecificPart != HudGuidanceSettings.NAVIGATOR_PACKAGE) return
                reader.execute {
                    refresh(app)
                    StateMarks.mark(StateSlice.HUD_GUIDANCE, "navigator package changed")
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
                addDataSchemeSpecificPart(HudGuidanceSettings.NAVIGATOR_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun navigator(context: Context): HudNavigatorInstall {
        val info = try {
            context.packageManager.getPackageInfo(HudGuidanceSettings.NAVIGATOR_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return HudNavigatorInstall.NONE
        }
        if (!HudNavigatorBuild.drawsHud(info.versionName)) return HudNavigatorInstall.YANDEX
        val apk = info.applicationInfo?.sourceDir ?: return HudNavigatorInstall.YANDEX_HUD
        return if (knowsHost(apk, info.lastUpdateTime)) {
            HudNavigatorInstall.YANDEX_HUD
        } else {
            HudNavigatorInstall.YANDEX_HUD_ALONE
        }
    }

    fun radar(context: Context): HudRadarInstall {
        val manager = context.packageManager
        try {
            manager.getPackageInfo(HudHostGate.HUD_SPEED_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return HudRadarInstall.NONE
        }
        return if (HudHostGate.signers(manager, HudHostGate.HUD_SPEED_PACKAGE) == HudHostGate.HUD_SPEED_CERTIFICATES) {
            HudRadarInstall.ADAPTED
        } else {
            HudRadarInstall.OFFICIAL
        }
    }

    @Synchronized
    private fun knowsHost(apk: String, updated: Long): Boolean {
        val build = "$apk@$updated"
        if (build != knownBuild) {
            knownHost = try {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("AndroidManifest.xml")
                    entry != null && namesHost(zip.getInputStream(entry).use { it.readBytes() })
                }
            } catch (error: Exception) {
                Log.w(TAG, "YandexHUD manifest unreadable", error)
                // Unread is not «old»: the build handed out now knows the host.
                true
            }
            knownBuild = build
        }
        return knownHost
    }

    /**
     * Whether a binary manifest names this app: its string pool holds the name in UTF-16 or, with
     * the UTF-8 flag, in UTF-8.
     */
    internal fun namesHost(manifest: ByteArray): Boolean =
        contains(manifest, HOST_PACKAGE.toByteArray(Charsets.UTF_16LE)) ||
            contains(manifest, HOST_PACKAGE.toByteArray(Charsets.UTF_8))

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }
}
