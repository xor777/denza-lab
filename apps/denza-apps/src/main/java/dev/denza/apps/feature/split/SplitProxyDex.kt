package dev.denza.apps.feature.split

import java.util.Base64

/** Where the shell-UID task proxy is loaded from, resolved on a live shell and cached after. */
internal fun interface SplitProxyClasspath {
    fun entry(shell: (String) -> String): String
}

/**
 * Stages [SplitTaskProxyMain] as a jar of its own so that `app_process` has almost nothing to load.
 *
 * The recipes have always run that one class as the shell user, and the only classpath available to
 * them was the application APK itself: 62 MB of dex that ART opens and verifies for every one-shot
 * call, measured at 1.36 s each on this car - most of the cost of every removal the product makes.
 * The class packed alone is a 3.7 KB jar, which the build ships as an asset.
 *
 * The shell user cannot read the application's own data directory, so the jar goes where every
 * other shell-UID helper of this project already goes (`/data/local/tmp`, the same place the host
 * probes in `tools/` push theirs), written through the shell that is open anyway. The name carries
 * the application version, so an update stages a fresh copy and the old ones are swept away with
 * it.
 *
 * Every step is best effort by construction: any failure at all resolves to the APK, which is
 * exactly what the product used before, so a car that will not take the file loses the speed and
 * nothing else.
 */
internal class SplitStagedProxyDex(
    private val apkPath: String,
    private val versionTag: String,
    private val jar: () -> ByteArray,
    private val log: (String) -> Unit = {},
) : SplitProxyClasspath {

    @Volatile
    private var resolved: String? = null

    @Volatile
    private var refusals = 0

    override fun entry(shell: (String) -> String): String {
        resolved?.let { return it }
        return runCatching { stage(shell) }
            .onSuccess { staged -> resolved = staged }
            .getOrElse { error ->
                log(
                    "thin split proxy unavailable, loading it from the apk: " +
                        (error.message ?: error),
                )
                // A flaky link on the first try must not pin the slow path for the whole process,
                // but neither may every removal keep paying for the attempt.
                refusals += 1
                if (refusals >= MAX_REFUSALS) resolved = apkPath
                apkPath
            }
    }

    private fun stage(shell: (String) -> String): String {
        val bytes = jar()
        check(bytes.isNotEmpty()) { "the packed proxy jar is empty" }
        val path = "$DIRECTORY/$PREFIX$versionTag.jar"
        if (sizeOf(shell, path) == bytes.size) return path
        val payload = Base64.getEncoder().encodeToString(bytes)
        shell(
            "mkdir -p $DIRECTORY && rm -f '$DIRECTORY/$PREFIX'*.jar && " +
                "printf '%s' '$payload' | base64 -d > '$path' && chmod 644 '$path'",
        )
        check(sizeOf(shell, path) == bytes.size) {
            "the packed proxy jar did not reach $path"
        }
        log("thin split proxy staged at $path (${bytes.size} bytes)")
        return path
    }

    // The redirection is silenced before it is attempted: the transport merges stderr into the
    // answer, and "cannot open" is not a size.
    private fun sizeOf(shell: (String) -> String, path: String): Int =
        shell("wc -c 2>/dev/null < '$path'").trim().toIntOrNull() ?: -1

    internal companion object {
        const val ASSET = "split-task-proxy.jar"

        /** After this many refusals the car has answered: the APK is the classpath. */
        private const val MAX_REFUSALS = 3

        private const val DIRECTORY = "/data/local/tmp"
        private const val PREFIX = "denza-split-proxy-"
    }
}
