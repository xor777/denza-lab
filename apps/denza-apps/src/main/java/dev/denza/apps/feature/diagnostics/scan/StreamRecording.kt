package dev.denza.apps.feature.diagnostics.scan

import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lines of one recording; closed when «Запись» goes off or the section closes. */
internal interface StreamSink : AutoCloseable {
    /**
     * Appends [lines]; false once the recording has ended on its own - full, or the storage refused a
     * write - and nothing more goes in. The lines already in stay.
     */
    fun write(lines: List<String>): Boolean
    override fun close()
}

/** Opens a recording, or null when it cannot be written. */
internal fun interface StreamSinks {
    fun open(atMillis: Long): StreamSink?
}

/**
 * «Запись» (architecture, 6.1): `<dir>/stream-<time>.csv`, one line per value as it came - the car's
 * receive time in nanoseconds, the address, the PID, the raw data bytes and the value - so a drive
 * can be taken apart afterwards like `captures/vehicle-log/`. A gap is a line with no bytes and no
 * value. The fast car signals come in the same file with `autoservice` as their address.
 *
 * Each recording is a new file: a name already taken - two recordings in one second, a clock set back -
 * gets `-2`, `-3`, … and an existing file is never opened again. A recording ends by itself at
 * [maxBytes], and at the first write the storage refuses; [keep] recordings are kept, the oldest
 * going once a new one holds its first line. A recording closed before its first line leaves no
 * file and has deleted none.
 */
internal class CsvStreamSinks(
    private val dir: File,
    private val zone: ZoneId,
    private val maxBytes: Long = MAX_BYTES,
    private val keep: Int = KEEP,
) : StreamSinks {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    override fun open(atMillis: Long): StreamSink? = runCatching {
        dir.mkdirs()
        val base = "stream-${stamp.format(Instant.ofEpochMilli(atMillis).atZone(zone))}"
        val file = generateSequence(1) { it + 1 }.take(MAX_SUFFIX)
            .map { n -> File(dir, if (n == 1) "$base.csv" else "$base-$n.csv") }
            .firstOrNull { it.createNewFile() } ?: return@runCatching null
        CsvSink(file, FileOutputStream(file, true).bufferedWriter(), maxBytes) { prune(file) }.also { it.header() }
    }.getOrNull()

    /** The oldest recordings beyond [keep] go, [current] - the one that has just taken its first line - one of them. */
    private fun prune(current: File) {
        recordings(dir).filter { it != current }.dropLast((keep - 1).coerceAtLeast(0)).forEach { it.delete() }
    }

    /** One recording's file; [firstLine] runs once, after its first line is written. */
    private class CsvSink(
        private val file: File,
        private val out: Writer,
        private val maxBytes: Long,
        private val firstLine: () -> Unit,
    ) : StreamSink {
        private var ended = false
        private var bytes = 0L
        private var sampled = false

        fun header() {
            put(listOf(HEADER))
        }

        override fun write(lines: List<String>): Boolean {
            if (ended) return false
            if (lines.isEmpty()) return true
            if (!put(lines)) return false
            if (!sampled) {
                sampled = true
                runCatching(firstLine)
            }
            return true
        }

        private fun put(lines: List<String>): Boolean {
            val size = lines.sumOf { it.length + 1L }
            if (bytes + size > maxBytes) {
                ended = true
                return false
            }
            runCatching {
                lines.forEach { out.write(it); out.write("\n") }
                out.flush()
                bytes += size
            }.onFailure { ended = true }
            return !ended
        }

        override fun close() {
            runCatching { out.close() }
            // A recording that never took a line is no recording: nothing for the report to find.
            if (!sampled) runCatching { file.delete() }
        }
    }

    companion object {
        const val HEADER = "received_ns,address,pid,raw,value"
        /** A recording's ceiling: hours of the engine's stream at its usual rate. */
        const val MAX_BYTES = 32L * 1024 * 1024
        /** Recordings kept in the section's folder; the report copies the newest. */
        const val KEEP = 8
        private const val MAX_SUFFIX = 1_000

        /** The recordings in [dir], oldest first: by when they were last written, then by name. */
        fun recordings(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.name.startsWith("stream-") && f.name.endsWith(".csv") }.orEmpty()
                .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
    }
}
