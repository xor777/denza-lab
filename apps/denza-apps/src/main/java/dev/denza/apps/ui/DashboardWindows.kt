package dev.denza.apps.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.denza.apps.ui.dashboard.DenzaActions
import dev.denza.apps.ui.dashboard.TileId

/**
 * The windows a tile opens over the dashboard, as `DenzaAppsRoot` keeps them: a tile's panel
 * ([settingsFor]), the instruments' screen picker, and the three whole-sheet choosers.
 *
 * Saved with the screen ([Saver]), so the split's pane changes, which recreate the activity, leave
 * an open window open. That saving also carries them through the death of the process, and there
 * the startup gate decides. A new process starts behind the gate - the car's answer to the ADB key
 * unknown until the passive look - with the runtime not started, and each of these windows is a
 * window of its own, drawn above the gate's shield. Brought straight back, a chooser read the car
 * and ran its feature before the car was proven to trust the app: «Что показывать» marked the
 * instruments as chosen and closed on a choice that went nowhere, «Экран справа» could start an
 * install. So what is open and what is [shown] are two things: no chooser and no screen picker
 * shows while the gate is up, and each comes back as it was once the gate drops.
 *
 * A tile's panel is not held back. «Сервис», which the gate leaves the driver, opens one from its
 * rows on purpose, and a panel reads nothing of the car by opening; a chooser its button asks for
 * waits for the gate like any other.
 *
 * What a chooser does with an answer is here too, so the rule the root follows is one a test can
 * hold: a chooser closes exactly when the app says the choice was taken.
 */
@Stable
internal class DashboardWindows(
    settingsFor: TileId? = null,
    clusterPicker: Boolean = false,
    apps: Boolean = false,
    navigationApp: Boolean = false,
    fseApp: Boolean = false,
) {
    /** The tile whose panel is open. */
    var settingsFor: TileId? by mutableStateOf(settingsFor)

    /** «Приборный экран», opened by a tile waiting on the instruments' screen. */
    var clusterPicker: Boolean by mutableStateOf(clusterPicker)

    /** «Что транслировать» as a window of its own. */
    var apps: Boolean by mutableStateOf(apps)

    /** «Что показывать» as a window of its own. */
    var navigationApp: Boolean by mutableStateOf(navigationApp)

    /** «Экран справа»'s chooser. */
    var fseApp: Boolean by mutableStateOf(fseApp)

    /** What stands over the dashboard now: what is open, the choosers only once the gate is down. */
    fun shown(gateUp: Boolean): Shown = Shown(
        settingsFor = settingsFor,
        clusterPicker = clusterPicker && !gateUp,
        apps = apps && !gateUp,
        navigationApp = navigationApp && !gateUp,
        fseApp = fseApp && !gateUp,
    )

    /** One answer chosen on «Что показывать»: the window closes if the app took it, and only then. */
    fun chooseNavigationApp(packageName: String, actions: DenzaActions) {
        if (actions.onSelectNavigationApp(packageName)) navigationApp = false
    }

    /** One application pressed on «Экран справа»: the window closes once its install has started. */
    fun installFseApp(packageName: String, actions: DenzaActions) {
        if (actions.onInstallFseApp(packageName)) fseApp = false
    }

    /**
     * «Экран справа» opens with its list already read - on this thread, so it opens drawn - and not
     * at all while an install is under way.
     */
    fun openFseChooser(actions: DenzaActions) {
        if (actions.onLoadFseApps()) fseApp = true
    }

    /**
     * «Экран справа» stands, and [read] says whether this process has read its list. Opened here
     * it has; brought back with the screen after the process died it has not, and is read now -
     * once, and not again for a car that truly offers nothing. An install the new process finds
     * under way shuts it, as a press would have.
     */
    fun readFseAppsIfLost(read: Boolean, actions: DenzaActions) {
        if (!read && !actions.onLoadFseApps()) fseApp = false
    }

    /** The windows that stand at one moment; see [shown]. */
    data class Shown(
        val settingsFor: TileId?,
        val clusterPicker: Boolean,
        val apps: Boolean,
        val navigationApp: Boolean,
        val fseApp: Boolean,
    )

    companion object {
        /** By the tile's name, so a build whose tiles differ restores no panel rather than failing. */
        val Saver: Saver<DashboardWindows, Any> = listSaver(
            save = { windows ->
                listOf(
                    windows.settingsFor?.name.orEmpty(),
                    windows.clusterPicker,
                    windows.apps,
                    windows.navigationApp,
                    windows.fseApp,
                )
            },
            restore = { saved ->
                DashboardWindows(
                    settingsFor = TileId.entries.firstOrNull { it.name == saved[0] },
                    clusterPicker = saved[1] as Boolean,
                    apps = saved[2] as Boolean,
                    navigationApp = saved[3] as Boolean,
                    fseApp = saved[4] as Boolean,
                )
            },
        )
    }
}
