package dev.denza.apps.feature.cloud

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Public installation identity, never a SIM identity or the shell control secret. */
internal data class CloudInstallMarker(val installId: String, val generation: Long, val custom: Boolean) {
    init {
        require(installId.matches(Regex("[0-9a-f]{32}")))
        require(generation > 0)
    }

    fun bytes(): ByteArray =
        "{\"protocol\":2,\"install_id\":\"$installId\",\"generation\":$generation,\"desired\":\"${if (custom) "custom" else "off"}\"}\n"
            .toByteArray(StandardCharsets.US_ASCII)

    /** Readers observe the old complete marker or the new complete marker, never a partial write. */
    fun publish(path: Path) {
        val parent = path.parent ?: error("Недоступны файлы приложения")
        Files.createDirectories(parent)
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Недоступны файлы приложения" }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Недоступны файлы приложения" }
            if (Files.size(path) <= 512 && Files.readAllBytes(path).contentEquals(bytes())) return
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".pending")
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) { "Недоступны файлы приложения" }
            Files.delete(temporary)
        }
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val data = ByteBuffer.wrap(bytes())
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Only this fixed temporary file belongs to the marker publisher.
            if (Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(temporary)
        }
    }
}
