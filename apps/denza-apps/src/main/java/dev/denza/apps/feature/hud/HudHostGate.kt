package dev.denza.apps.feature.hud

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.denza.hudlink.HudSource
import java.security.MessageDigest

/**
 * Who may publish to this app's HUD from another app (docs/hud-output-architecture.md, 5): the
 * YandexHUD module, inside the Navigator package, signed with the owner's local experiment key.
 * Decided from the caller's uid on every attach, so a caller can neither publish as another source
 * nor pass for YandexHUD with Yandex's own package and another key - an official Navigator never
 * attaches.
 */
object HudHostGate {
    private const val TAG = "DenzaHudHost"

    /**
     * SHA-256 of the certificate `tools/yandex_hud_build/build.py` signs YandexHUD with
     * (`CN=Denza HUD local experiment`, the git-ignored `captures/yandex-hud-signing/experiment.p12`),
     * as `apksigner verify --print-certs` prints it for every build handed out so far.
     */
    val YANDEX_HUD_CERTIFICATES: Set<String> =
        setOf("df4100bda2c4f581c6bb5251746f1b2bdee2f7662ead31a46ef01ae25f9d39c6")

    /**
     * The source a caller with [packages] publishes as, or null: the Navigator package with a
     * YandexHUD certificate is the module. [certificates] gives a package's signer digests.
     */
    fun admit(packages: List<String>, certificates: (String) -> Set<String>): HudSource? =
        if (packages.any { it == HudGuidanceSettings.NAVIGATOR_PACKAGE &&
                certificates(it).any(YANDEX_HUD_CERTIFICATES::contains) }) {
            HudSource.MODULE
        } else {
            null
        }

    /** [admit] for a Binder caller's [uid], from PackageManager. */
    fun admit(context: Context, uid: Int): HudSource? {
        val manager = context.packageManager
        val packages = manager.getPackagesForUid(uid)?.toList().orEmpty()
        return admit(packages) { name -> signers(manager, name) }
    }

    private fun signers(manager: PackageManager, name: String): Set<String> = try {
        val info = manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val signatures = when {
            info == null -> emptyArray()
            info.hasMultipleSigners() -> info.apkContentsSigners
            else -> info.signingCertificateHistory
        }
        signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    } catch (error: PackageManager.NameNotFoundException) {
        Log.w(TAG, "no package $name", error)
        emptySet()
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
