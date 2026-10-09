package dev.denza.apps.feature.split

/**
 * Where the shell-UID task proxy is loaded from, resolved on a live shell.
 *
 * In the product it is the jar [dev.denza.apps.platform.shell.ShellProxyStager] keeps on the car,
 * or the APK when the car will not take it ([dev.denza.apps.platform.shell.ShellProxyClasspath]);
 * the tests answer with a path of their own. A command names it through
 * [dev.denza.apps.platform.shell.classpathAssignment], which loads the APK for that command if the
 * jar is gone, and [forget] is called when the proxy's class could not be loaded at all.
 */
internal fun interface SplitProxyClasspath {
    fun entry(shell: (String) -> String): String

    /** The next [entry] asks the car again. */
    fun forget() {}
}
