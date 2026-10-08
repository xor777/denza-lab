package dev.denza.apps.feature.split

/** Keeps the user-facing launcher entry and the split runtime under one toggle contract. */
internal object SplitScreenToggleController {
    fun setEnabled(
        enabled: Boolean,
        launcherVisible: () -> Boolean,
        setLauncherVisible: (Boolean) -> Unit,
        setRuntimeEnabled: (Boolean) -> Unit,
    ) {
        val previousLauncherVisible = launcherVisible()
        setLauncherVisible(enabled)
        try {
            setRuntimeEnabled(enabled)
        } catch (error: Throwable) {
            runCatching { setLauncherVisible(previousLauncherVisible) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * A press on the «Разделение» tile, or on «Разделить экран» in its panel (contract 1.2.8).
     *
     * The press ends with the function on and the split opening. While the toggle is off it is
     * turned on first, by [enable] - the toggle's own path, which moves the launcher icon, the runtime
     * and the firmware signals together - and only then is the split opened. An enable that did not
     * take opens nothing: the open would be refused by a runtime that is still off, and the tile
     * already shows that the switch did not move.
     */
    fun launch(launcherVisible: Boolean, enable: () -> Boolean, open: () -> Unit) {
        if (!launcherVisible && !enable()) return
        open()
    }

    /**
     * Brings the runtime in line with the launcher icon, which is the persisted toggle.
     *
     * A starting process makes this repair for mismatches left by builds where the toggle changed
     * only the icon, and the launcher entry makes it before it opens (1.2.8).
     */
    fun reconcile(
        launcherVisible: Boolean,
        runtimeEnabled: Boolean,
        setRuntimeEnabled: (Boolean) -> Unit,
    ) {
        if (runtimeEnabled != launcherVisible) {
            setRuntimeEnabled(launcherVisible)
        }
    }
}
