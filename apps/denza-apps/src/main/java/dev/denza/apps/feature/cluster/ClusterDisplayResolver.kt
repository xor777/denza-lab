package dev.denza.apps.feature.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import java.util.Locale

object ClusterDisplayResolver {
    const val KNOWN_DENZA_DISPLAY = "shared_fission_bg_XDJAScreenProjection_0"
    const val KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY = "shared_fission_bg_XDJAScreenProjection_1"
    private const val PREFS = "denza_cluster"
    private const val PREF_OVERRIDE = "display_override"
    private val excludedNameParts = listOf(
        "screen_ivi",
        "ivi_screen",
        "rear",
        "rse",
        "overhead",
        "fse_screen",
        "dishare",
    )

    fun resolve(context: Context): ClusterDisplaySelection {
        val candidates = candidates(context)
        if (candidates.isEmpty()) return ClusterDisplaySelection.Missing
        val override = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(PREF_OVERRIDE, Display.INVALID_DISPLAY)
            .takeIf { it != Display.INVALID_DISPLAY }
        return select(candidates, override)
    }

    /**
     * Denza exposes the stock side-camera composition as a second shared
     * fission display. Mirrors must use that overlay display while navigation
     * remains on [KNOWN_DENZA_DISPLAY]. Fail closed when the named overlay is
     * absent instead of guessing a display id.
     */
    fun resolveCameraOverlay(context: Context): ClusterDisplaySelection =
        selectCameraOverlay(candidates(context))

    fun selectCameraOverlay(
        candidates: List<ClusterDisplayDescriptor>,
    ): ClusterDisplaySelection {
        val matches = candidates.filter {
            it.id != Display.DEFAULT_DISPLAY &&
                !it.isOwnVirtualDisplay &&
                it.name == KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY
        }
        return when (matches.size) {
            0 -> ClusterDisplaySelection.Missing
            1 -> ClusterDisplaySelection.Selected(matches.first())
            else -> ClusterDisplaySelection.NeedsVerification(matches)
        }
    }

    fun candidates(context: Context): List<ClusterDisplayDescriptor> =
        context.getSystemService(DisplayManager::class.java)
            ?.displays
            ?.map(::describe)
            .orEmpty()

    fun saveOverride(context: Context, displayId: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (displayId == null) remove(PREF_OVERRIDE) else putInt(PREF_OVERRIDE, displayId)
        }.apply()
    }

    fun select(
        candidates: List<ClusterDisplayDescriptor>,
        manualOverrideId: Int? = null,
    ): ClusterDisplaySelection {
        manualOverrideId?.let { id ->
            candidates.firstOrNull { it.id == id && it.id != Display.DEFAULT_DISPLAY && !it.isOwnVirtualDisplay }
                ?.let { return ClusterDisplaySelection.Selected(it) }
        }

        val scored = candidates
            .filterNot(::mustExclude)
            .map { it to score(it) }
            .filter { (_, score) -> score >= 250 }
            .sortedWith(compareByDescending<Pair<ClusterDisplayDescriptor, Int>> { it.second }
                .thenBy { it.first.id })
        if (scored.isEmpty()) return ClusterDisplaySelection.Missing

        val bestScore = scored.first().second
        val best = scored.filter { it.second == bestScore }.map { it.first }
        return if (best.size == 1) {
            ClusterDisplaySelection.Selected(best.first())
        } else {
            ClusterDisplaySelection.NeedsVerification(best)
        }
    }

    private fun mustExclude(display: ClusterDisplayDescriptor): Boolean {
        if (display.id == Display.DEFAULT_DISPLAY || display.isOwnVirtualDisplay) return true
        val name = display.name.lowercase(Locale.ROOT)
        return excludedNameParts.any(name::contains)
    }

    private fun score(display: ClusterDisplayDescriptor): Int {
        if (display.name == KNOWN_DENZA_DISPLAY) return 10_000
        val name = display.name.lowercase(Locale.ROOT)
        var score = 0
        if ("cluster" in name) score += 400
        if ("fission" in name) score += 350
        if (display.width > display.height && display.width >= 1_200) score += 40
        if (display.width >= display.height * 2) score += 20
        if (display.type == DISPLAY_TYPE_VIRTUAL) score += 10
        return score
    }

    private fun describe(display: Display): ClusterDisplayDescriptor {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val normalizedName = display.name.orEmpty()
        val lowerName = normalizedName.lowercase(Locale.ROOT)
        return ClusterDisplayDescriptor(
            id = display.displayId,
            name = normalizedName,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            // Android's public Display API does not expose the backing type on
            // all platform SDKs. Keep it unknown unless a platform adapter can
            // supply a trusted value; names and real metrics remain authoritative.
            type = displayType(display),
            flags = display.flags,
            isOwnVirtualDisplay = lowerName.startsWith("denza apps") ||
                lowerName.startsWith("denza_cluster") ||
                lowerName.startsWith("denza navigation"),
        )
    }

    private fun displayType(display: Display): Int = runCatching {
        val method = Display::class.java.getDeclaredMethod("getType")
        method.isAccessible = true
        method.invoke(display) as Int
    }.getOrDefault(DISPLAY_TYPE_UNKNOWN)

    const val DISPLAY_TYPE_UNKNOWN = 0
    const val DISPLAY_TYPE_VIRTUAL = 5
}
