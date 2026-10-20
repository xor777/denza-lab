package dev.denza.apps.feature.diagnostics.scan

import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lines of one recording; closed when «Запись» goes off or the section closes. */
internal interface StreamSink : AutoCloseable {
    fun write(lines: List<String>)
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
 */
internal class CsvStreamSinks(private val dir: File, private val zone: ZoneId) : StreamSinks {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    override fun open(atMillis: Long): StreamSink? = runCatching {
        dir.mkdirs()
        val file = File(dir, "stream-${stamp.format(Instant.ofEpochMilli(atMillis).atZone(zone))}.csv")
        CsvSink(file.bufferedWriter()).also { it.write(listOf(HEADER)) }
    }.getOrNull()

    private class CsvSink(private val out: Writer) : StreamSink {
        private var failed = false

        override fun write(lines: List<String>) {
            if (failed || lines.isEmpty()) return
            runCatching {
                lines.forEach { out.write(it); out.write("\n") }
                out.flush()
            }.onFailure { failed = true }
        }

        override fun close() {
            runCatching { out.close() }
        }
    }

    companion object {
        const val HEADER = "received_ns,address,pid,raw,value"
    }
}
