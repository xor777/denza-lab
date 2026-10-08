package dev.denza.apps.feature.defaultapps

import android.content.Intent

/**
 * Puts the driver's navigator back into AutoVoice's map role after a Store update took it out.
 *
 * AutoVoice's `AppReceiver` answers `PACKAGE_REMOVED` without reading `EXTRA_REPLACING`: when the
 * removed package is the one `DEFAULT_MAP_SWITCH` holds, it writes the stock map in its place, and
 * what it does on `PACKAGE_ADDED` / `PACKAGE_REPLACED` only refreshes its own catalogue. So an
 * ordinary update of the chosen navigator hands every voice and Shortcuts "open the map" to the
 * stock map, and the panel had nothing to say but «Штатные»
 * (docs/shortcuts-automation-findings.md, "Retired single-package navigation proxy experiment").
 *
 * The repair keeps the rule written there for it - restore only a role whose exact package was seen
 * going into a replacement, and never over a value nobody here explains:
 *
 * - The navigator is **armed** when it goes into a replacement (`PACKAGE_REMOVED` with
 *   `EXTRA_REPLACING`) while it is the package this app last confirmed in the role. The
 *   remembered pick alone would not do: it outlives the Shortcuts switch being turned off, and
 *   an update must not undo «Штатные».
 * - It is **put back** when the same package comes back (`PACKAGE_ADDED` with `EXTRA_REPLACING`,
 *   then `PACKAGE_REPLACED`), by a write that lands only while the role still holds the stock map,
 *   so a choice made in the stock settings in the meantime is never overwritten. The added event
 *   tries first and the replaced one again, in case AutoVoice's reset had not landed yet.
 * - A removal that is not a replacement **disarms** it: an uninstalled navigator is AutoVoice's
 *   reset to make, and the right one.
 *
 * The state is the process's, so this repairs only an update that lands while Denza Apps is running.
 * Whether a stopped app hears these broadcasts at all is an open question in the same doc.
 */
internal class NavigationRoleRepair(private val stockPackageName: String) {

    enum class Event { REMOVED, ADDED, REPLACED }

    /** The navigator that held the role when its update began, until it is back or gone. */
    private var updating: String? = null

    /**
     * One package broadcast. [held] is the package this app last confirmed in the role, read before
     * anything this broadcast sets off could read the role again.
     *
     * Returns the package to write back into the role, or null when there is nothing to do.
     */
    @Synchronized
    fun on(event: Event, packageName: String, replacing: Boolean, held: String?): String? {
        when (event) {
            Event.REMOVED -> when {
                replacing && packageName == held && held != stockPackageName -> updating = packageName
                !replacing && packageName == updating -> updating = null
            }
            Event.ADDED -> if (replacing && packageName == updating) return packageName
            Event.REPLACED -> if (packageName == updating) {
                updating = null
                return packageName
            }
        }
        return null
    }

    /** The role holds [packageName] again, so the replaced event after an added one has nothing to do. */
    @Synchronized
    fun restored(packageName: String) {
        if (updating == packageName) updating = null
    }

    companion object {
        /** The broadcasts the repair reads, out of the ones the launcher catalogue watches. */
        fun eventOf(action: String?): Event? = when (action) {
            Intent.ACTION_PACKAGE_REMOVED -> Event.REMOVED
            Intent.ACTION_PACKAGE_ADDED -> Event.ADDED
            Intent.ACTION_PACKAGE_REPLACED -> Event.REPLACED
            else -> null
        }
    }
}
