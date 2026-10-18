package dev.denza.apps.feature.hud

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
