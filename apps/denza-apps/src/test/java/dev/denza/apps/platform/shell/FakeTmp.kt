package dev.denza.apps.platform.shell

import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A fake `/data/local/tmp` that understands the commands [ShellProxyStager] sends - the sweep with
 * its hash, a piece of the write, the last piece with its publication, the hash - and nothing else.
 *
 * Each command acts on the files the way the shell would, one step at a time: the last piece goes
 * into the part file, the part is hashed, then [beforeRename] runs, then the rename. That gap is
 * where another stager can run in a test, which is exactly the interleaving two features staging at
 * once can produce on the car.
 */
internal class FakeTmp {
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Anything the shell would have printed as an error: a rename of a part someone deleted. */
    val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Runs between a publication's hash check and its rename. */
    @Volatile
    var beforeRename: (String) -> Unit = {}

    /** Turns the bytes a piece decoded into what lands in the part file: a full disk, a bad link. */
    @Volatile
    var damage: (ByteArray) -> ByteArray = { it }

    /** Refuses every write, as a read-only or full filesystem would. */
    @Volatile
    var refuseWrites = false

    fun shell(command: String): String {
        commands += command
        val output = StringBuilder()
        SWEEP.find(command)?.let { match ->
            val prefix = match.groupValues[1]
            val keep = match.groupValues[2]
            files.keys.filter { it.startsWith(prefix) && !it.startsWith(keep) }.forEach(files::remove)
        }
        PIECE.find(command)?.let { match ->
            val (payload, redirect, part) = match.destructured
            if (refuseWrites) return "sh: can't create $part: Read-only file system"
            val bytes = damage(Base64.getDecoder().decode(payload))
            val before = if (redirect == ">>") files[part] ?: ByteArray(0) else ByteArray(0)
            files[part] = before + bytes
            PUBLISH.find(command)?.let { publish ->
                val (expected, path) = publish.destructured
                val written = files[part]
                if (written != null && ShellProxyStager.sha256(written) == expected) {
                    beforeRename(part)
                    val moved = files.remove(part)
                    if (moved == null) {
                        errors += "mv: $part: No such file or directory"
                    } else {
                        files[path] = moved
                    }
                }
                files.remove(part)
            }
        }
        HASH.find(command)?.let { match ->
            val path = match.groupValues[1]
            files[path]?.let { bytes -> output.append("${ShellProxyStager.sha256(bytes)}  $path\n") }
        }
        return output.toString()
    }

    /** The files whose names start with [prefix], in name order. */
    fun named(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }.sorted()

    private companion object {
        val SWEEP = Regex("""^for f in '([^']*)'\*; do case "\${'$'}f" in '([^']*)'\*\) ;;""")
        val PIECE = Regex("""printf '%s' '([^']*)' \| base64 -d (>>?) '([^']*)'""")
        val PUBLISH = Regex(
            """&& set -- \${'$'}\(sha256sum '[^']*' 2>/dev/null\) && """ +
                """\[ "\${'$'}1" = '([0-9a-f]{64})' \] && chmod 644 '[^']*' && """ +
                """mv -f '[^']*' '([^']*)'; rm -f '[^']*'$""",
        )
        val HASH = Regex("""sha256sum '([^']*)' 2>/dev/null$""")
    }
}
