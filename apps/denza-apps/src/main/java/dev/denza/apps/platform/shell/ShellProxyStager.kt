package dev.denza.apps.platform.shell

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The helpers this app runs as the shell user, each packed by the build into a jar of its own.
 *
 * [asset] is the name `PackShellProxy` gives the jar in the APK's assets (`ShellProxyJarAssetsTest`
 * holds the build script to these names); [fileName] is what its staged copies are called on the
 * car, `<fileName>-<sha256>.jar`. No [fileName] followed by `-` may begin another one, because every
 * copy that starts so and is not the current one is deleted.
 *
 * The names do not carry the application id. Two installs of this app side by side - an
 * `applicationIdSuffix`, which the build does not have - would each delete the other's copy at
 * every call, and each would then stage again.
 */
internal enum class ShellProxyJar(val asset: String, val fileName: String) {
    SPLIT_TASK("split-task-proxy.jar", "denza-split-proxy"),
    VEHICLE_SIGNAL("vehicle-signal-proxy.jar", "denza-vehicle-signal"),
}

/** The car will not take the jar: the asset is unreadable or empty, or the copy fails its hash. */
internal class ShellProxyRefused(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Puts one shell-UID helper where `app_process` can load it, and proves it is the right one.
 *
 * The shell user cannot read this app's data directory, so a helper goes to `/data/local/tmp`, where
 * every host probe in `tools/` puts its own; it is written through the shell the caller has open
 * anyway. The copy is named by the SHA-256 of its bytes and checked by it, and a build that changes
 * a helper therefore stages a new file. Until 2026-10-09 the split's copy was named by versionCode
 * and checked by size: the owner installs builds without raising the version, so a changed proxy of
 * the same length would have stayed on the car, running as shell.
 *
 * The jar travels as base64 in pieces of [CHUNK_BYTES], so that no command line is longer than
 * [MAX_COMMAND_LINE], and lands in a part file of this call's own. Only a part file whose hash is
 * the jar's is moved into place, by `mv`, in one step: what a name holds never changes, and a path
 * one caller verified is the same file whatever another is writing.
 *
 * Each call deletes the copies of the same helper under any other name - an earlier build's jar, a
 * part file of an earlier build - and so the directory holds one per helper. The sweep spares every
 * file that begins with the current name, which is what keeps two callers staging at once safe; it
 * also means that a part file of the current jar left by a link that dropped mid-write stays until
 * the jar changes. It is a few kilobytes, and nothing reads it.
 *
 * Failing throws: [ShellProxyRefused] when the car has answered and the answer is no, anything else
 * when the link did not carry the question. What a caller does then is its own policy: the split
 * loads the class from the APK instead ([ShellProxyClasspath]); the turn-signal listener stays off,
 * and checks the hash again at its next start.
 */
internal class ShellProxyStager(
    val helper: ShellProxyJar,
    jar: () -> ByteArray,
    private val log: (String) -> Unit = {},
    private val directory: String = DIRECTORY,
    private val nonce: () -> String = ::randomNonce,
) {
    private val artifact by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bytes = try {
            jar()
        } catch (error: Exception) {
            throw ShellProxyRefused("the packed ${helper.asset} cannot be read", error)
        }
        if (bytes.isEmpty()) throw ShellProxyRefused("the packed ${helper.asset} is empty")
        Artifact(bytes, sha256(bytes))
    }

    /** The staged jar's path, verified on the car by its SHA-256; written first if it is not there. */
    @Synchronized
    fun stage(shell: (String) -> String): String {
        val local = artifact
        val path = "$directory/${helper.fileName}-${local.sha256}.jar"
        if (remoteSha256(send(shell, sweepAndHashCommand(path))) == local.sha256) return path

        writeCommands(local, path, "$path.${nonce()}.part").forEach { command -> send(shell, command) }
        if (remoteSha256(send(shell, hashCommand(path))) != local.sha256) {
            throw ShellProxyRefused("${helper.asset} failed SHA-256 verification at $path")
        }
        log("${helper.fileName} staged at $path (${local.bytes.size} bytes)")
        return path
    }

    private fun send(shell: (String) -> String, command: String): String {
        check(command.length <= MAX_COMMAND_LINE) {
            "a staging command of ${command.length} characters is longer than $MAX_COMMAND_LINE"
        }
        return shell(command)
    }

    /**
     * The jar in pieces, the first creating the part file and the last publishing it: the part is
     * moved into place only when its own hash is the jar's, and removed in every case.
     */
    private fun writeCommands(local: Artifact, path: String, part: String): List<String> {
        val pieces = (local.bytes.indices step CHUNK_BYTES).map { from ->
            Base64.getEncoder().encodeToString(
                local.bytes.copyOfRange(from, minOf(from + CHUNK_BYTES, local.bytes.size)),
            )
        }
        return pieces.mapIndexed { index, piece ->
            buildString {
                if (index == 0) append("mkdir -p ${shellQuote(directory)} && ")
                append("printf '%s' ${shellQuote(piece)} | base64 -d ")
                append(if (index == 0) ">" else ">>")
                append(" ${shellQuote(part)}")
                if (index == pieces.lastIndex) {
                    append(
                        " && set -- \$(sha256sum ${shellQuote(part)} 2>/dev/null) && " +
                            "[ \"\$1\" = ${shellQuote(local.sha256)} ] && " +
                            "chmod 644 ${shellQuote(part)} && " +
                            "mv -f ${shellQuote(part)} ${shellQuote(path)}; " +
                            "rm -f ${shellQuote(part)}",
                    )
                }
            }
        }
    }

    /**
     * Every other copy of this helper goes, then the current one is hashed. One round trip, so a
     * caller that finds its jar in place pays exactly what it paid before the sweep existed.
     */
    private fun sweepAndHashCommand(path: String): String =
        "for f in ${shellQuote("$directory/${helper.fileName}-")}*; do " +
            "case \"\$f\" in ${shellQuote(path)}*) ;; *) rm -f \"\$f\" 2>/dev/null ;; esac; " +
            "done; " + hashCommand(path)

    // Silenced before it is attempted: the transport merges stderr into the answer.
    private fun hashCommand(path: String): String = "sha256sum ${shellQuote(path)} 2>/dev/null"

    private fun remoteSha256(output: String): String? =
        output.trim().substringBefore(' ').takeIf { it.matches(SHA_256) }

    private class Artifact(val bytes: ByteArray, val sha256: String)

    internal companion object {
        const val DIRECTORY = "/data/local/tmp"

        /**
         * The longest command line staging sends. The car's persistent shell is a terminal with
         * mksh's line editor on it (docs/adb-authorization-recovery.md, "The persistent shell is a
         * terminal"), and the longest line proven through it is the turn-signal listener's
         * 7,449-byte jar staged whole on 2026-09-04: about 10.2 KB of command and frame
         * (docs/vehicle-data-findings.md, "Product adapter and safety boundary"). 8 KiB stays under
         * it with the transport's frame added.
         */
        const val MAX_COMMAND_LINE = 8 * 1024

        /** 6,144 characters of base64 a piece, which leaves the paths and the hash room. */
        const val CHUNK_BYTES = 4_608

        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

        private val random = SecureRandom()

        private fun randomNonce(): String =
            ByteArray(8).also(random::nextBytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}

/**
 * Where a one-shot helper is loaded from: its staged jar, or the APK when the car will not take it.
 *
 * The APK is what the split always used before it had a jar - 62 MB of dex that ART opens and
 * verifies on every start, measured at 1.36 s each on this car - so a car that refuses the file
 * loses the speed and nothing else. A staged path is kept for the process until [forget]. Only
 * a refusal from the car ([ShellProxyRefused]) counts towards [MAX_REFUSALS], after which the APK
 * is the classpath for the rest of the process; a link that did not answer gives the APK for that
 * one call and asks again at the next, because a dropped link says nothing about the file.
 *
 * The kept path can go stale: nothing stops the file from being deleted while the process lives.
 * A command line therefore names the jar through [classpathAssignment], which falls back to the
 * APK for that command if the file is not there when it runs.
 */
internal class ShellProxyClasspath(
    private val stager: ShellProxyStager,
    val apkPath: String,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var resolved: String? = null

    @Volatile
    private var refusals = 0

    fun entry(shell: (String) -> String): String {
        resolved?.let { return it }
        return try {
            stager.stage(shell).also { staged -> resolved = staged }
        } catch (refused: ShellProxyRefused) {
            log(
                "thin ${stager.helper.fileName} refused, loading it from the apk: " +
                    (refused.message ?: refused),
            )
            refusals += 1
            if (refusals >= MAX_REFUSALS) resolved = apkPath
            apkPath
        } catch (error: Exception) {
            log(
                "thin ${stager.helper.fileName} not reached, loading it from the apk this time: " +
                    (error.message ?: error),
            )
            apkPath
        }
    }

    /**
     * Drops a staged path, so that the next [entry] checks the car again and stages anew if the
     * jar is gone. An APK the car settled on by its refusals stays.
     */
    fun forget() {
        if (resolved != apkPath) resolved = null
    }

    private companion object {
        /** After this many refusals the car has answered: the APK is the classpath. */
        const val MAX_REFUSALS = 3
    }
}

/**
 * The classpath of an `app_process` command line: [jar], or [apk] if [jar] is not readable when
 * the command runs.
 *
 * A staged path is kept for the life of the process, and the file can go meanwhile - a hand-cleaned
 * `/data/local/tmp`, an acceptance reset. The check is mksh's builtin `[`, so it costs no process,
 * and a vanished jar costs that one command the APK's slow start instead of a class that cannot be
 * loaded. When [jar] is the APK the line is the plain `CLASSPATH=<apk>` it always was.
 */
internal fun classpathAssignment(jar: String, apk: String): String =
    if (jar == apk) {
        "CLASSPATH=${shellQuote(apk)}"
    } else {
        "c=${shellQuote(jar)}; [ -r \"\$c\" ] || c=${shellQuote(apk)}; CLASSPATH=\"\$c\""
    }

/** Whether `app_process` said it could not load the helper's class at all, so nothing of it ran. */
internal fun helperNotLoaded(output: String): Boolean = "ClassNotFoundException" in output
