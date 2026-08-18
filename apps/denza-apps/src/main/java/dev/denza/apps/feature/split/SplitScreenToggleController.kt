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

    /** Repairs persisted mismatches left by builds where the toggle changed only the icon. */
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
